package com.jabiz.runtime.erasure;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.integrity.IntegrityKey;
import com.jabiz.runtime.integrity.ErasureEvidence;
import com.jabiz.runtime.integrity.IntegrityStore;
import com.jabiz.runtime.retention.ArchiveEntities;
import com.jabiz.runtime.retention.DeletionGuard;
import com.jabiz.runtime.mail.MailEntities;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.protect.ProtectedChangeEntities;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.security.erasure.ErasedEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Overwrites what an erased user left in append-only records (docs/design/10-security.md section 17; decision D62
 * items 4 to 6), on the maintenance connection, in one transaction under the seal's lock. Each statement overwrites
 * the personal columns of the rows that belong to the user and records each row it changed in {@code sys_erasure_item}
 * with its digest before and after over the columns of the block that sealed it, so that the verification can tell a
 * purge from a tampering. Rows are never deleted from append-only tables; a statement skips rows already overwritten,
 * so running a purge again changes nothing.
 */
@Component
public class ErasurePurger {

    /** The processes whose input is the erasure itself (ids only): their records stay as they are. */
    private static final List<String> OWN_PROCESSES = List.of(AccountErasure.NAME, ErasurePurge.NAME);

    private final EntityDefinitionRegistry entities;
    private final PersonalDataErasers erasers;
    private final IntegrityStore integrity;
    private final DeletionGuard guard;
    private final IntegrityKey key;

    public ErasurePurger(EntityDefinitionRegistry entities, PersonalDataErasers erasers, IntegrityStore integrity,
        DeletionGuard guard, IntegrityKey key) {
        this.entities = entities;
        this.erasers = erasers;
        this.integrity = integrity;
        this.guard = guard;
        this.key = key;
    }

    /**
     * Purges one erasure in the maintenance transaction {@code engine} runs in; returns how many rows it overwrote.
     * Writes {@code sys_erasure_purge} last.
     */
    public Mono<Integer> purge(StorageEngine engine, UUID erasureId, UUID userId, long processSeqId, Instant time) {
        Run run = new Run(engine, erasureId, userId);
        // The connection must see the application's tables: a purge that finds nothing would pass for done.
        return engine.select("SELECT erasure_id FROM sys_erasure WHERE erasure_id = :id",
                Map.of("id", BoundValue.of(erasureId)))
            .next()
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("The maintenance connection does not see erasure "
                + erasureId + ": is its URL the application's database and schema?")))
            // The text of a row, hence its digest, depends on these settings: as the seal and the verification have them.
            .then(integrity.pin(engine))
            .then(Mono.defer(() -> addresses(run)))
            .then(Mono.defer(() -> entityIds(run)))
            .then(Mono.defer(() -> operations(run)))
            // From here on no seal reads a row between this transaction's reading and overwriting it (21 section 2.5);
            // the lookups above, which may scan, ran before.
            .then(engine.select(IntegrityStore.LOCK, Map.of()).then())
            .then(Mono.defer(() -> Flux.fromIterable(targets(run)).concatMap(target -> overwrite(run, target))
                .then()))
            .then(Mono.defer(() -> documents(run)))
            // What the purge overwrote, signed: only signed entries account for a changed sealed row.
            .then(Mono.defer(() -> integrity.erasureItems(engine, erasureId).collectList()))
            .flatMap(items -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("erasure_id", erasureId);
                row.put("purged_time", time);
                row.put("row_count", run.count);
                row.put("process_seq_id", processSeqId);
                row.put("signature", ErasureEvidence.sign(key, erasureId, run.count, items));
                row.put("key_id", key.id());
                return engine.insert("sys_erasure_purge", row).thenReturn(run.count);
            });
    }

    /**
     * One overwriting statement: the table, which rows, and what each column becomes (null: by its type). An
     * application's entity names, instead of the rows, its declarations ({@code declared}): the rows any of them
     * finds, each column overwritten only where a declaration naming it found the row ({@code conditions}, decision
     * D62 item 8). One statement for them all: each row is written once, and every condition reads the row as it was.
     */
    record Target(String table, String where, Map<String, BoundValue> params, Map<String, String> sets,
        List<Declared> declared, Map<String, String> conditions) {

        Target(String table, String where, Map<String, BoundValue> params, Map<String, String> sets) {
            this(table, where, params, sets, null, Map.of());
        }
    }

    /** One declaration of an entity: the column holding the user's id, and the columns it overwrites. */
    record Declared(String userColumn, Set<String> columns) {}

    /** What one purge learns as it goes. */
    private static final class Run {
        final StorageEngine engine;
        final UUID erasureId;
        final UUID userId;
        String[] addresses = new String[0];
        /** The user's names in any version and its addresses, in lower case: what a sign-in or a request names. */
        String[] names = new String[0];
        final Map<String, List<Object>> ids = new LinkedHashMap<>();
        /** The keys of the rows each declaration finds, by entity and user column (decision D62 item 8). */
        final Map<String, List<Object>> declaredIds = new LinkedHashMap<>();
        /** The operations whose input and output are the user's. */
        Long[] processes = new Long[0];
        int count;

        Run(StorageEngine engine, UUID erasureId, UUID userId) {
            this.engine = engine;
            this.erasureId = erasureId;
            this.userId = userId;
        }

        Map<String, BoundValue> user() {
            Map<String, BoundValue> params = new LinkedHashMap<>();
            params.put("user", BoundValue.of(userId));
            params.put("userText", BoundValue.of(userId.toString()));
            params.put("addresses", BoundValue.of(addresses));
            return params;
        }
    }

    /**
     * Every address the user verified, in any version, and its names; in lower case. An address the user only entered
     * (or that a provider link named) is left out: anyone can register with someone else's address and then delete the
     * account. So is an address or a name another user has, or had: their records are not the erased user's.
     */
    private Mono<Void> addresses(Run run) {
        Map<String, BoundValue> user = Map.of("user", BoundValue.of(run.userId));
        return run.engine.select("""
                SELECT array_agg(DISTINCT lower(a)) AS addresses FROM (
                    SELECT verified_email AS a FROM sec_user_version WHERE user_id = :user) AS x
                WHERE a IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sec_user_version o WHERE o.user_id <> :user
                    AND lower(a) IN (lower(o.email), lower(o.verified_email), lower(o.registered_email),
                        lower(o.user_name)))""", user)
            .next()
            .doOnNext(row -> run.addresses = Rows.strings(row.get("addresses")).toArray(String[]::new))
            .then(run.engine.select("""
                SELECT array_agg(DISTINCT lower(u.user_name)) AS names FROM sec_user_version u WHERE u.user_id = :user
                AND NOT EXISTS (SELECT 1 FROM sec_user_version o WHERE o.user_id <> :user
                    AND lower(u.user_name) IN (lower(o.user_name), lower(o.email)))""", user).next())
            .doOnNext(row -> {
                Set<String> names = new LinkedHashSet<>(Rows.strings(row.get("names")));
                names.addAll(List.of(run.addresses));
                run.names = names.toArray(String[]::new);
            })
            .then();
    }

    /** The keys of the user's rows, by entity: the user, its platform records, and the erasers' entities. */
    private Mono<Void> entityIds(Run run) {
        run.ids.put(SecurityEntities.USER, List.of(run.userId));
        // An entity may be declared under several user fields (decision D62 item 8): its rows are those of each.
        List<Map.Entry<EntityDefinition, String>> userColumns = new ArrayList<>();
        Map<String, String> declarations = new LinkedHashMap<>();
        for (String name : List.of(SecurityEntities.USER_IDENTITY, SecurityEntities.USER_MFA,
            SecurityEntities.LOGIN_RECORD, SecurityEntities.USER_ROLE, SecurityEntities.USER_MAIL_PREFERENCE,
            ProtectedChangeEntities.CHANGE)) {
            userColumns.add(Map.entry(entities.getOrThrow(name), "user_id"));
        }
        for (ErasedEntity erased : erasers.entities()) {
            EntityDefinition def = entities.getOrThrow(erased.entity());
            userColumns.add(Map.entry(def, def.physicalColumn(erased.userField())));
            declarations.put(declaration(def.name, def.physicalColumn(erased.userField())), erased.userField());
        }
        return Flux.fromIterable(userColumns)
            .concatMap(entry -> {
                EntityDefinition def = entry.getKey();
                String table = SqlIdentifiers.require(def.physicalTable);
                String key = SqlIdentifiers.require(def.primaryKeyColumn());
                return userMatch(run, table, entry.getValue()).flatMapMany(match -> run.engine.select(
                        "SELECT DISTINCT t." + key + " AS id FROM " + table + " t WHERE " + match.where(), match.params()))
                    .map(row -> row.get("id"))
                    .collectList()
                    .flatMap(found -> {
                        String declaration = declaration(def.name, entry.getValue());
                        String userField = declarations.get(declaration);
                        if (userField == null) {
                            run.ids.merge(def.name, List.copyOf(found), ErasurePurger::union);
                            return Mono.empty();
                        }
                        // An application's rows: also those ACCOUNT_ERASE deleted, known by their audit records.
                        return audited(run, def.name, userField).map(gone -> {
                            List<Object> ids = union(found.stream().map(id -> (Object) String.valueOf(id)).toList(),
                                gone);
                            run.ids.merge(def.name, ids, ErasurePurger::union);
                            run.declaredIds.put(declaration, ids);
                            return ids;
                        });
                    });
            })
            .then();
    }

    /**
     * The keys of an entity's rows whose user field held the user's id in an audit record (its insert, its deletion,
     * a change of the field): rows a plain table no longer has once ACCOUNT_ERASE deleted them, whose earlier audit
     * records and the deletion's own still hold their personal values.
     */
    private static Mono<List<Object>> audited(Run run, String entity, String userField) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("entityType", BoundValue.of(entity));
        params.put("userField", BoundValue.of(userField));
        params.put("userText", BoundValue.of(run.userId.toString()));
        return run.engine.select("SELECT DISTINCT t.entity_id::text AS id FROM sys_audit_record t"
                + " WHERE t.entity_type = :entityType AND :userText IN (t.changes::jsonb -> :userField ->> 0,"
                + " t.changes::jsonb -> :userField ->> 1)", params)
            .map(row -> (Object) Rows.string(row.get("id")))
            .collectList();
    }

    private static String declaration(String entity, String userColumn) {
        return entity + "\n" + userColumn;
    }

    private static <T> List<T> union(List<T> before, List<T> more) {
        Set<T> union = new LinkedHashSet<>(before);
        union.addAll(more);
        return List.copyOf(union);
    }

    /** A condition on the column that holds the user id: as a uuid where it is one (its index applies), else as text. */
    record Match(String where, Map<String, BoundValue> params) {}

    private static Mono<Match> userMatch(Run run, String table, String column) {
        String quoted = "t." + SqlIdentifiers.require(column);
        return columns(run.engine, table).map(columns -> {
            Column info = columns.get(column);
            if (info == null) {
                throw new IllegalStateException("Column " + column + " of " + table + " does not exist");
            }
            return "uuid".equals(info.type())
                ? new Match(quoted + " = :user", Map.of("user", BoundValue.of(run.userId)))
                : new Match(quoted + " = :userText", Map.of("userText", BoundValue.of(run.userId.toString())));
        });
    }

    /**
     * The operations the user ran, that wrote the user's rows, or that named the user - a sign-in or a request by name
     * or address, refused or not, writes no row of the user's (decision D62 item 4); the erasure's own excepted.
     * Collected before the seal's lock is taken: the search by name reads every operation.
     */
    private Mono<Void> operations(Run run) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("userText", BoundValue.of(run.userId.toString()));
        params.put("names", BoundValue.of(run.names));
        params.put("anonymous", BoundValue.of(RequestContext.ANONYMOUS_ACTOR));
        params.put("own", BoundValue.of(OWN_PROCESSES.toArray(String[]::new)));
        List<String> wrote = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, List<Object>> entry : run.ids.entrySet()) {
            UUID[] uuids = entry.getValue().stream().map(ErasurePurger::uuid).filter(java.util.Objects::nonNull)
                .toArray(UUID[]::new);
            if (uuids.length == 0) {
                continue;
            }
            params.put("type" + n, BoundValue.of(entry.getKey()));
            params.put("ids" + n, BoundValue.of(uuids));
            wrote.add("SELECT i.process_seq_id FROM op_process_item i WHERE i.entity_type = :type" + n
                + " AND i.entity_id = ANY(:ids" + n + ")");
            n++;
        }
        String named = run.names.length == 0 ? "" : " UNION SELECT p.process_seq_id FROM op_process p"
            + " WHERE p.actor_id = :anonymous AND (lower(p.input_summary->>'userName') = ANY(:names)"
            + " OR lower(p.input_summary->>'email') = ANY(:names))";
        String sql = "SELECT array_agg(c.process_seq_id) AS processes FROM (SELECT p.process_seq_id FROM op_process p"
            + " WHERE p.actor_id = :userText" + named
            + wrote.stream().map(select -> " UNION " + select).collect(Collectors.joining()) + ") AS c"
            + " WHERE NOT EXISTS (SELECT 1 FROM op_process o WHERE o.process_seq_id = c.process_seq_id"
            + " AND o.process_name = ANY(:own))";
        if (run.names.length == 0) {
            params.remove("names");
            params.remove("anonymous");
        }
        return run.engine.select(sql, params).next()
            .doOnNext(row -> run.processes = row.get("processes") instanceof Long[] ids ? ids
                : Rows.strings(row.get("processes")).stream().map(Long::valueOf).toArray(Long[]::new))
            .then();
    }

    /** What the purge overwrites: the platform's records of the user, the erasers' fields, the audit and operations. */
    private List<Target> targets(Run run) {
        List<Target> targets = new ArrayList<>();
        Map<String, BoundValue> user = run.user();
        Map<String, String> userName = new LinkedHashMap<>();
        userName.put("user_name", "'erased-' || t.user_id::text");
        for (String column : List.of("display_name", "email", "locale", "password_hash", "verified_email",
            "registered_email")) {
            userName.put(column, null);
        }
        targets.add(new Target("sec_user_version", "t.user_id = :user", user, userName));
        targets.add(new Target("sec_user_identity_version", "t.user_id = :user", user, sets("subject", "email")));
        targets.add(new Target("sec_user_mfa_version", "t.user_id = :user", user,
            sets("secret", "recovery_codes")));
        targets.add(new Target("sec_login_record_version", "t.user_id = :user", user,
            sets("user_name", "client_ip", "user_agent")));
        targets.add(new Target("sec_refresh_token", "t.user_id = :user", user, sets("client_ip", "user_agent")));
        targets.add(new Target("sec_protected_change_version", "t.user_id = :userText", user,
            sets("email", "subject", "password_hash")));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("address", null);
        params.put("params", "'" + ErasureValues.JSON_MARK + "'");
        // Mail of the user, and mail of nobody's to one of its addresses (another user's mail is theirs).
        String mail = "t.user_id = :user OR (t.user_id IS NULL AND lower(t.address) = ANY(:addresses))";
        targets.add(new Target("sys_mail_message", mail, user, params));
        targets.add(new Target("sys_mail_token", mail, user, sets("address")));
        targets.add(new Target("sys_notification", "t.recipient_id = :userText", user,
            sets("address", "subject", "body")));
        targets.add(new Target("sys_document_delivery", "lower(t.address) = ANY(:addresses)", user,
            sets("address", "subject", "body")));
        Map<String, String> input = new LinkedHashMap<>();
        input.put("input", "'" + ErasureValues.JSON_MARK + "'");
        targets.add(new Target("sys_external_job", "t.requested_by = :userText", user, input));
        targets.add(new Target("sys_external_job_outcome", "t.job_id IN (SELECT j.job_id FROM sys_external_job j"
            + " WHERE j.requested_by = :userText)", user, sets("result")));
        Map<String, List<ErasedEntity>> byEntity = new LinkedHashMap<>();
        for (ErasedEntity erased : erasers.entities()) {
            if (!erased.fields().isEmpty()) {
                byEntity.computeIfAbsent(erased.entity(), name -> new ArrayList<>()).add(erased);
            }
        }
        byEntity.forEach((entity, declared) -> {
            EntityDefinition def = entities.getOrThrow(entity);
            Map<String, String> fields = new LinkedHashMap<>();
            List<Declared> declarations = new ArrayList<>();
            for (ErasedEntity erased : declared) {
                Set<String> columns = new LinkedHashSet<>();
                erased.fields().forEach(field -> columns.add(def.physicalColumn(field)));
                columns.forEach(column -> fields.put(column, null));
                declarations.add(new Declared(def.physicalColumn(erased.userField()), columns));
            }
            targets.add(new Target(def.physicalTable, null, user, fields, List.copyOf(declarations), Map.of()));
        });
        targets.addAll(audit(run));
        targets.addAll(operationTargets(run));
        return targets;
    }

    /** The audit records of the user's rows: the values of the personal fields become the mask. */
    private List<Target> audit(Run run) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put(SecurityEntities.USER, List.of("userName", "displayName", "email", "locale", "verifiedEmail",
            "registeredEmail", "passwordHash"));
        fields.put(SecurityEntities.USER_IDENTITY, List.of("subject", "email"));
        fields.put(SecurityEntities.LOGIN_RECORD, List.of("userName", "clientIp", "userAgent"));
        fields.put(SecurityEntities.USER_MFA, List.of("secret", "recoveryCodes"));
        fields.put(ProtectedChangeEntities.CHANGE, List.of("email", "subject", "passwordHash"));
        List<Target> targets = new ArrayList<>();
        fields.forEach((entity, names) -> auditTarget(entity, run.ids.getOrDefault(entity, List.of()), names)
            .ifPresent(targets::add));
        // An application's rows, each masked with the fields of the declarations that found it (decision D62 item 8):
        // not another declaration's, which may be another person's. Rows of one set of fields share a statement, so
        // that each audit record is written once.
        Map<String, Map<Object, Set<String>>> byRow = new LinkedHashMap<>();
        for (ErasedEntity erased : erasers.entities()) {
            if (erased.fields().isEmpty()) {
                continue;
            }
            EntityDefinition def = entities.getOrThrow(erased.entity());
            Map<Object, Set<String>> rows = byRow.computeIfAbsent(def.name, name -> new LinkedHashMap<>());
            for (Object id : run.declaredIds.getOrDefault(declaration(def.name,
                def.physicalColumn(erased.userField())), List.of())) {
                rows.computeIfAbsent(String.valueOf(id), key -> new java.util.TreeSet<>()).addAll(erased.fields());
            }
        }
        byRow.forEach((entity, rows) -> {
            Map<Set<String>, List<Object>> byFields = new LinkedHashMap<>();
            rows.forEach((id, names) -> byFields.computeIfAbsent(names, key -> new ArrayList<>()).add(id));
            byFields.forEach((names, ids) -> auditTarget(entity, ids, List.copyOf(names)).ifPresent(targets::add));
        });
        // The opt-outs of the user's addresses are gone (ACCOUNT_ERASE deleted them); their audit records hold the
        // address. They are found by it: another user's addresses are not among them.
        Map<String, BoundValue> optOuts = new LinkedHashMap<>(run.user());
        optOuts.put("entityType", BoundValue.of(MailEntities.ADDRESS_OPT_OUT));
        optOuts.put("fields", BoundValue.of(new String[] {"address"}));
        targets.add(new Target("sys_audit_record", "t.entity_type = :entityType AND t.changed_fields && :fields"
            + " AND lower(COALESCE(t.changes::jsonb->'address'->>0, t.changes::jsonb->'address'->>1))"
            + " = ANY(:addresses)", optOuts, Map.of("changes", masked())));
        return targets;
    }

    /** The audit records of these rows of the entity that changed one of the fields: their values become the mask. */
    private static java.util.Optional<Target> auditTarget(String entity, List<Object> ids, List<String> names) {
        if (ids.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("entityType", BoundValue.of(entity));
        params.put("entityIds", BoundValue.of(ids.stream().map(String::valueOf).toArray(String[]::new)));
        params.put("fields", BoundValue.of(names.toArray(String[]::new)));
        return java.util.Optional.of(new Target("sys_audit_record", "t.entity_type = :entityType"
            + " AND t.entity_id = ANY(:entityIds) AND t.changed_fields && :fields", params,
            Map.of("changes", masked())));
    }

    /** Each named field's pair [before, after] keeps null where there was none and the mask where there was. */
    private static String masked() {
        return "(SELECT COALESCE(jsonb_object_agg(e.key, CASE WHEN e.key = ANY(:fields) THEN"
            + " jsonb_build_array(" + mask("e.value->0") + ", " + mask("e.value->1") + ") ELSE e.value END),"
            + " '{}'::jsonb) FROM jsonb_each(t.changes::jsonb) AS e)::text";
    }

    private static String mask(String value) {
        return "CASE WHEN " + value + " IS NULL OR jsonb_typeof(" + value + ") = 'null' THEN 'null'::jsonb"
            + " ELSE to_jsonb('" + ErasureValues.TEXT_MARK + "'::text) END";
    }

    /** The operations collected by {@link #operations}: their input and output are personal. */
    private static List<Target> operationTargets(Run run) {
        if (run.processes.length == 0) {
            return List.of();
        }
        Map<String, BoundValue> params = Map.of("processes", BoundValue.of(run.processes));
        return List.of(
            new Target("op_process", "t.process_seq_id = ANY(:processes)", params, sets("input_summary")),
            new Target("op_process_result", "t.process_seq_id = ANY(:processes)", params, sets("output")));
    }

    /**
     * The issued documents of the erasers' layouts whose subject is one of the user's rows, unless a retention period
     * or a legal hold keeps them (decision D62 item 5): their PDF, content, parameters and recipients.
     */
    private Mono<Void> documents(Run run) {
        EntityDefinition runs = entities.getOrThrow(ArchiveEntities.DOCUMENT_RUN);
        return Flux.fromIterable(erasers.entities())
            .filter(erased -> !erased.documents().isEmpty())
            .concatMap(erased -> {
                // The rows this declaration found: another declaration's layouts are not its (decision D62 item 8).
                EntityDefinition def = entities.getOrThrow(erased.entity());
                List<Object> ids = run.declaredIds.getOrDefault(declaration(def.name,
                    def.physicalColumn(erased.userField())), List.of());
                if (ids.isEmpty()) {
                    return Mono.empty();
                }
                return run.engine.select("SELECT * FROM sys_document_run WHERE layout_id = ANY(:layouts)"
                        + " AND subject_entity = :entity AND subject_id = ANY(:subjects)", Map.of(
                            "layouts", BoundValue.of(erased.documents().toArray(String[]::new)),
                            "entity", BoundValue.of(erased.entity()),
                            "subjects", BoundValue.of(ids.stream().map(String::valueOf).toArray(String[]::new))))
                    .concatMap(row -> guard.refusal(run.engine, runs, row.get("run_id"), attributes(runs, row))
                        .map(kept -> Set.<UUID>of())
                        .defaultIfEmpty(Set.of(Rows.uuid(row.get("run_id")))))
                    .collect(LinkedHashSet<UUID>::new, Set::addAll)
                    .flatMap(free -> {
                        if (free.isEmpty()) {
                            return Mono.empty();
                        }
                        Map<String, String> sets = new LinkedHashMap<>();
                        sets.put("pdf", null);
                        sets.put("content", null);
                        sets.put("params", "'" + ErasureValues.JSON_MARK + "'");
                        sets.put("recipients", null);
                        return overwrite(run, new Target("sys_document_run", "t.run_id = ANY(:runs)",
                            Map.of("runs", BoundValue.of(free.toArray(UUID[]::new))), sets));
                    });
            })
            .then();
    }

    /** Runs one target: overwrites its rows that still hold something and records each in {@code sys_erasure_item}. */
    private Mono<Void> overwrite(Run run, Target target) {
        String table = SqlIdentifiers.require(target.table());
        Mono<Target> resolved = target.declared() == null ? Mono.just(target)
            : Flux.fromIterable(target.declared())
                .concatMap(declared -> userMatch(run, table, declared.userColumn())
                    .map(match -> Map.entry(declared, match)))
                .collectList()
                .map(matches -> declaredTarget(table, target, matches));
        Mono<IntegrityStore.Table> meta = integrity.table(run.engine, table)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("The maintenance connection does not see table "
                + table)));
        return resolved.flatMap(match -> overwrite(run, match, meta));
    }

    /**
     * The statement of an entity's declarations: the rows any of them finds; a column only some declarations name is
     * overwritten where one of those found the row, and kept elsewhere.
     */
    static Target declaredTarget(String table, Target target, List<Map.Entry<Declared, Match>> matches) {
        Map<String, BoundValue> params = new LinkedHashMap<>(target.params());
        matches.forEach(match -> params.putAll(match.getValue().params()));
        String where = matches.stream().map(match -> "(" + match.getValue().where() + ")")
            .collect(Collectors.joining(" OR "));
        Map<String, String> conditions = new LinkedHashMap<>();
        for (String column : target.sets().keySet()) {
            List<String> finding = matches.stream().filter(match -> match.getKey().columns().contains(column))
                .map(match -> "(" + match.getValue().where() + ")").toList();
            if (finding.size() < matches.size()) {
                conditions.put(column, String.join(" OR ", finding));
            }
        }
        return new Target(table, where, params, target.sets(), null, Map.copyOf(conditions));
    }

    private Mono<Void> overwrite(Run run, Target target, Mono<IntegrityStore.Table> tableMeta) {
        String table = SqlIdentifiers.require(target.table());
        return Mono.zip(tableMeta, columns(run.engine, table))
            .flatMap(found -> {
                IntegrityStore.Table meta = found.getT1();
                Map<String, Column> columns = found.getT2();
                Map<String, String> sets = new LinkedHashMap<>();
                target.sets().forEach((column, expression) -> {
                    Column info = columns.get(column);
                    if (info == null) {
                        throw new IllegalStateException("Column " + column + " of " + table + " does not exist");
                    }
                    String value = expression != null ? expression
                        : ErasureValues.replacement(info.type(), info.nullable());
                    if (value == null) {
                        throw new IllegalStateException("Column " + column + " of " + table + " is required and of"
                            + " type " + info.type() + ": the purge has nothing to overwrite it with");
                    }
                    String condition = target.conditions().get(column);
                    String quoted = SqlIdentifiers.require(column);
                    sets.put(quoted, condition == null ? value
                        : "CASE WHEN " + condition + " THEN (" + value + ") ELSE t." + quoted + " END");
                });
                List<String> keys = meta.keyColumns().stream().map(SqlIdentifiers::require).toList();
                String changed = sets.entrySet().stream()
                    .map(set -> "t." + set.getKey() + " IS DISTINCT FROM (" + set.getValue() + ")")
                    .collect(Collectors.joining(" OR "));
                String sql = "WITH b AS (SELECT "
                    + keys.stream().map(key -> "t." + key + " AS k_" + key).collect(Collectors.joining(", "))
                    + ", k.row_key, s.cols, CASE WHEN s.cols IS NULL THEN NULL ELSE "
                    + IntegrityStore.digest("t", "s.cols") + " END AS before FROM " + table + " t"
                    + " CROSS JOIN LATERAL (SELECT " + meta.keyExpression("t") + " AS row_key) AS k"
                    + " LEFT JOIN LATERAL (SELECT st.columns AS cols FROM sys_integrity_item i"
                    + " JOIN sys_integrity_seal_table st ON st.seal_no = i.seal_no AND st.table_name = i.table_name"
                    + " WHERE i.table_name = :purgeTable AND i.row_key = k.row_key) AS s ON true"
                    + " WHERE (" + target.where() + ") AND (" + changed + ") FOR UPDATE OF t),"
                    + " u AS (UPDATE " + table + " t SET "
                    + sets.entrySet().stream().map(set -> set.getKey() + " = (" + set.getValue() + ")")
                        .collect(Collectors.joining(", "))
                    + " FROM b WHERE " + keys.stream().map(key -> "t." + key + " = b.k_" + key)
                        .collect(Collectors.joining(" AND "))
                    + " RETURNING b.row_key, b.before, CASE WHEN b.cols IS NULL THEN NULL ELSE "
                    + IntegrityStore.digest("t", "b.cols") + " END AS after)"
                    + " INSERT INTO sys_erasure_item (erasure_id, table_name, row_key, columns, digest_before,"
                    + " digest_after) SELECT :purgeErasure, :purgeTable, row_key, :purgeColumns, before, after FROM u"
                    + " RETURNING row_key";
                Map<String, BoundValue> params = new LinkedHashMap<>(target.params());
                params.put("purgeTable", BoundValue.of(table));
                params.put("purgeErasure", BoundValue.of(run.erasureId));
                params.put("purgeColumns", BoundValue.of(sets.keySet().toArray(String[]::new)));
                // A named parameter the statement does not use is an error of the driver: only those it uses.
                params.keySet().removeIf(name -> !java.util.regex.Pattern.compile("(?<!:):" + name + "\\b")
                    .matcher(sql).find());
                return run.engine.select(sql, params).count()
                    .doOnNext(rows -> run.count += rows.intValue())
                    .then();
            });
    }

    /** A column's type and whether it takes NULL. */
    record Column(String type, boolean nullable) {}

    private static Mono<Map<String, Column>> columns(StorageEngine engine, String table) {
        return engine.select("SELECT column_name::text AS name, data_type::text AS type, is_nullable::text AS nullable"
                + " FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = :table",
                Map.of("table", BoundValue.of(table)))
            .collectMap(row -> Rows.string(row.get("name")), row -> new Column(Rows.string(row.get("type")),
                "YES".equals(row.get("nullable"))));
    }

    /** A row of the table as the entity's attributes, for the retention and hold checks. */
    private static Map<String, Object> attributes(EntityDefinition def, Map<String, Object> row) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (FieldDefinition field : def.fields.values()) {
            if (row.containsKey(field.physicalColumn())) {
                attributes.put(field.name(), row.get(field.physicalColumn()));
            }
        }
        return attributes;
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) {
            return id;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    private static Map<String, String> sets(String... columns) {
        Map<String, String> sets = new LinkedHashMap<>();
        for (String column : columns) {
            sets.put(column, null);
        }
        return sets;
    }
}
