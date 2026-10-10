package com.jabiz.runtime.entity;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalSpec;
import com.jabiz.entity.UniqueConstraint;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Startup check that every physicalColumn declared in an EntityDefinition exists in its
 * physicalTable, that temporal tables and the operation tables have their constraints, indexes and append-only
 * guard (docs/design/04-temporal-append-only.md section 8), and that every declared unique constraint is backed by a unique index of the same name
 * over exactly its columns (docs/design/02-metamodel.md section 6). It prevents silent drift between the Java metamodel and the actual schema
 * (which other systems or manual DDL may change): without it, drift only surfaces when a
 * request happens to touch the affected field.
 *
 * The check runs, with the other {@link PlatformCheck}s, after all singletons are created and before the web
 * server starts accepting traffic. It blocks on the reactive client, which is acceptable here because
 * it executes on the startup thread and never on the request path. All inconsistencies
 * are collected and reported together.
 *
 * Disable with {@code jabiz.metamodel.consistency-check.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "jabiz.metamodel.consistency-check", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class MetaModelConsistencyChecker implements PlatformCheck {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(30);

    private final DatabaseClient db;
    private final EntityDefinitionRegistry registry;

    public MetaModelConsistencyChecker(DatabaseClient db, EntityDefinitionRegistry registry) {
        this.db = db;
        this.registry = registry;
    }

    /** Operation tables, append-only like every temporal table (decision D4). */
    static final List<String> OPERATION_TABLES = List.of("op_process", "op_process_item", "op_process_result",
        "entity_registry");

    public static final String CATEGORY = "METAMODEL";

    @Override
    public List<CheckProblem> check() {
        List<String> problems = Flux.fromIterable(OPERATION_TABLES)
            .concatMap(table -> checkAppendOnlyGuard("Operation table " + table, table))
            .concatWith(Flux.fromIterable(registry.all()).concatMap(this::checkEntity))
            .collectList()
            .block(CHECK_TIMEOUT);

        return problems == null ? List.of() : problems.stream().map(MetaModelConsistencyChecker::problem).toList();
    }

    /** Marks a finding that slows the application down without breaking it. */
    private static final String WARNING = "warning: ";

    private static CheckProblem problem(String text) {
        if (!text.startsWith(WARNING)) {
            return CheckProblem.error(CATEGORY, text);
        }
        CheckProblem located = CheckProblem.error(CATEGORY, text.substring(WARNING.length()));
        return CheckProblem.warning(CATEGORY, located.location(), located.message());
    }

    private Flux<String> checkEntity(EntityDefinition def) {
        return fetchColumns(def.physicalTable).flatMapMany(actual -> {
            if (actual.isEmpty()) {
                return Flux.just("Entity " + def.name + ": table " + def.physicalTable
                                 + " was not found or has no columns");
            }
            Flux<String> columns = Flux.fromIterable(def.fields.values())
                .filter(field -> !actual.contains(field.physicalColumn().toLowerCase(Locale.ROOT)))
                .map(field -> "Entity " + def.name + ": field " + field.name()
                              + " -> missing column " + field.physicalColumn()
                              + " in table " + def.physicalTable);
            if (def.temporal) {
                // Uniqueness of temporal entities is enforced by locks, not indexes (decision D6).
                Flux<String> rowId = actual.contains(def.temporalSpec.rowIdColumn().toLowerCase(Locale.ROOT))
                    ? Flux.empty()
                    : Flux.just("Entity " + def.name + ": missing row id column " + def.temporalSpec.rowIdColumn()
                                + " in table " + def.physicalTable);
                return columns.concatWith(rowId).concatWith(checkTemporalTable(def));
            }
            return columns.concatWith(Flux.fromIterable(def.uniqueConstraints)
                .concatMap(unique -> checkUniqueIndex(def, unique)));
        });
    }

    /**
     * What a temporal table needs (docs/design/04-temporal-append-only.md section 2.1): the unique version number
     * per entity, the index of the current-version query, the index of the operation, the foreign keys to
     * {@code op_process} and {@code entity_registry}, and the append-only guard.
     */
    private Flux<String> checkTemporalTable(EntityDefinition def) {
        String label = "Entity " + def.name + " (temporal)";
        String id = def.primaryKeyColumn().toLowerCase(Locale.ROOT);
        String version = def.systemColumn(TemporalSpec.VERSION_NO).toLowerCase(Locale.ROOT);
        String effective = def.systemColumn(TemporalSpec.EFFECT_START_TIME).toLowerCase(Locale.ROOT);
        String process = def.systemColumn(TemporalSpec.PROCESS_SEQ_ID).toLowerCase(Locale.ROOT);

        Flux<String> indexes = fetchIndexes(def.physicalTable).collectList().flatMapMany(found -> {
            List<String> problems = new ArrayList<>();
            if (found.stream().noneMatch(ix -> ix.unique() && ix.full() && ix.columns().size() == 2
                && Set.copyOf(ix.columns()).equals(Set.of(id, version)))) {
                problems.add(label + " -> no unique index on (" + id + ", " + version + ")");
            }
            if (found.stream().noneMatch(ix -> ix.full() && ix.startsWith(id, false)
                && ix.columns().size() >= 3 && ix.columns().get(1).equals(effective) && ix.descending(1)
                && ix.columns().get(2).equals(version) && ix.descending(2))) {
                problems.add(label + " -> no index on (" + id + ", " + effective + " DESC, " + version + " DESC)");
            }
            if (found.stream().noneMatch(ix -> ix.startsWith(process, false))) {
                problems.add(label + " -> no index on (" + process + ")");
            }
            // One version per instance (decision D29): the key alone is unique.
            if (def.temporalSpec.writeOnce() && found.stream().noneMatch(ix -> ix.unique() && ix.full()
                && ix.columns().equals(List.of(id)))) {
                problems.add(label + " -> write-once, but no unique index on (" + id + ") alone");
            }
            // Uniqueness checks find their candidates through an index on the constraint's fields (decision D29).
            for (UniqueConstraint unique : def.uniqueConstraints) {
                List<String> columns = unique.fields().stream()
                    .map(field -> def.physicalColumn(field).toLowerCase(Locale.ROOT)).toList();
                // Regardless of case (decision D36): an expression index on lower(column), which may leave out nulls.
                if (unique.ignoreCase() ? found.stream().noneMatch(ix -> ix.lowerOf(columns))
                    : found.stream().noneMatch(ix -> ix.full() && ix.columns().size() >= columns.size()
                    && Set.copyOf(ix.columns().subList(0, columns.size())).equals(Set.copyOf(columns)))) {
                    problems.add(WARNING + label + " -> unique constraint " + unique.name() + " has no index starting "
                        + "with " + columns + ": checking it reads every version of the table");
                }
            }
            return Flux.fromIterable(problems);
        });
        Flux<String> foreignKeys = fetchForeignKeys(def.physicalTable).collectList().flatMapMany(found -> {
            List<String> problems = new ArrayList<>();
            if (!found.contains(process + "->op_process")) {
                problems.add(label + " -> no foreign key (" + process + ") to op_process");
            }
            if (!found.contains(id + "->entity_registry")) {
                problems.add(label + " -> no foreign key (" + id + ") to entity_registry");
            }
            return Flux.fromIterable(problems);
        });
        return indexes.concatWith(foreignKeys).concatWith(checkAppendOnlyGuard(label, def.physicalTable));
    }

    /**
     * The table must have enabled triggers of {@code jabiz_reject_mutation()} rejecting row updates and deletions
     * and truncation (decision D5).
     */
    private Flux<String> checkAppendOnlyGuard(String label, String table) {
        return db.sql("""
                select t.tgtype as tgtype
                from pg_trigger t
                join pg_proc p on p.oid = t.tgfoid
                where t.tgrelid = to_regclass(:tableName) and not t.tgisinternal and t.tgenabled <> 'D'
                  and p.proname = 'jabiz_reject_mutation'
                """)
            .bind("tableName", table)
            .map((row, meta) -> ((Number) row.get("tgtype")).intValue())
            .all()
            .collectList()
            .flatMapMany(types -> {
                boolean update = false;
                boolean delete = false;
                boolean truncate = false;
                for (int type : types) {
                    boolean row = (type & 1) != 0;
                    boolean before = (type & 2) != 0;
                    if (row && before) {
                        delete |= (type & 8) != 0;
                        update |= (type & 16) != 0;
                    }
                    if (!row && before) {
                        truncate |= (type & 32) != 0;
                    }
                }
                List<String> problems = new ArrayList<>();
                if (!update || !delete) {
                    problems.add(label + " -> table " + table + " lacks the row trigger BEFORE UPDATE OR DELETE "
                                 + "executing jabiz_reject_mutation() (SELECT jabiz_protect_append_only(...))");
                }
                if (!truncate) {
                    problems.add(label + " -> table " + table + " lacks the statement trigger BEFORE TRUNCATE "
                                 + "executing jabiz_reject_mutation()");
                }
                return Flux.fromIterable(problems);
            });
    }

    /**
     * @param definition the index definition ({@code pg_get_indexdef}) in lower case without blanks, parentheses and
     *                   {@code ::text} casts: {@code lower((email)::text)} reads {@code loweremail}
     */
    private record IndexInfo(List<String> columns, List<Integer> options, boolean unique, boolean full,
        String definition) {
        boolean startsWith(String column, boolean descending) {
            return !columns.isEmpty() && columns.getFirst().equals(column) && descending(0) == descending;
        }

        boolean descending(int position) {
            return position < options.size() && (options.get(position) & 1) != 0;
        }

        /** Whether the index is on {@code lower(column)} of every one of the columns (decision D36). */
        boolean lowerOf(List<String> columns) {
            return !columns.isEmpty() && columns.stream().allMatch(column -> definition.contains("lower" + column));
        }
    }

    /** Indexes of the table with their key columns in order and per-column options (bit 1: DESC). */
    private Flux<IndexInfo> fetchIndexes(String table) {
        return db.sql("""
                select array_to_string(array(
                           select a.attname from unnest(i.indkey) with ordinality k(attnum, ord)
                           left join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k.attnum
                           order by k.ord), ',') as cols,
                       array_to_string(i.indoption::int2[], ',') as opts,
                       i.indisunique as is_unique,
                       (i.indpred is null and i.indexprs is null) as is_full,
                       pg_get_indexdef(i.indexrelid) as definition
                from pg_index i
                where i.indrelid = to_regclass(:tableName)
                """)
            .bind("tableName", table)
            .map((row, meta) -> new IndexInfo(
                split(row.get("cols", String.class)).stream().map(c -> c.toLowerCase(Locale.ROOT)).toList(),
                split(row.get("opts", String.class)).stream().map(Integer::valueOf).toList(),
                Boolean.TRUE.equals(row.get("is_unique", Boolean.class)),
                Boolean.TRUE.equals(row.get("is_full", Boolean.class)),
                normalizedDefinition(row.get("definition", String.class))))
            .all();
    }

    private static String normalizedDefinition(String definition) {
        return definition == null ? "" : definition.toLowerCase(Locale.ROOT).replace("::text", "")
            .replaceAll("[\\s()\"]", "");
    }

    /** Single-column foreign keys of the table as {@code column->referenced table}. */
    private Flux<String> fetchForeignKeys(String table) {
        return db.sql("""
                select a.attname as column_name, rt.relname as target
                from pg_constraint c
                join pg_class rt on rt.oid = c.confrelid
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                where c.contype = 'f' and c.conrelid = to_regclass(:tableName) and cardinality(c.conkey) = 1
                """)
            .bind("tableName", table)
            .map((row, meta) -> row.get("column_name", String.class).toLowerCase(Locale.ROOT) + "->"
                                + row.get("target", String.class).toLowerCase(Locale.ROOT))
            .all();
    }

    private static List<String> split(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(text.split(","));
    }

    private Mono<String> checkUniqueIndex(EntityDefinition def, UniqueConstraint unique) {
        Set<String> expected = new HashSet<>();
        unique.fields().forEach(f -> expected.add(def.physicalColumn(f).toLowerCase(Locale.ROOT)));
        String label = "Entity " + def.name + ": unique constraint " + unique.name();
        if (unique.ignoreCase()) {
            // A unique index of the constraint's name on lower(column) of each field (decision D36).
            List<String> columns = unique.fields().stream()
                .map(f -> def.physicalColumn(f).toLowerCase(Locale.ROOT)).toList();
            String named = "createuniqueindex" + unique.name().toLowerCase(Locale.ROOT) + "on";
            return fetchIndexes(def.physicalTable)
                .filter(ix -> ix.unique() && ix.definition().startsWith(named) && ix.lowerOf(columns))
                .hasElements()
                .flatMap(present -> present ? Mono.<String>empty() : Mono.just(label + " -> no unique index named "
                    + unique.name() + " on lower(" + String.join("), lower(", columns) + ") of table "
                    + def.physicalTable));
        }
        return fetchUniqueIndexColumns(def.physicalTable, unique.name().toLowerCase(Locale.ROOT))
            .flatMap(actual -> {
                if (actual.isEmpty()) {
                    return Mono.just(label + " -> no unique index named " + unique.name()
                                     + " on table " + def.physicalTable);
                }
                if (!actual.equals(expected)) {
                    return Mono.just(label + " -> index covers " + actual + " instead of " + expected);
                }
                return Mono.empty();
            });
    }

    /** Columns of a plain (non-partial, non-expression) unique index of the table; empty if there is none. */
    private Mono<Set<String>> fetchUniqueIndexColumns(String qualifiedTable, String indexName) {
        int dot = qualifiedTable.indexOf('.');
        String schemaCondition = dot < 0 ? "current_schema()" : ":schemaName";
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                select a.attname as column_name
                from pg_index i
                join pg_class ic on ic.oid = i.indexrelid
                join pg_class t on t.oid = i.indrelid
                join pg_namespace n on n.oid = t.relnamespace
                join pg_attribute a on a.attrelid = t.oid and a.attnum = any(i.indkey)
                where i.indisunique and i.indpred is null and not (0 = any(i.indkey))
                  and ic.relname = :indexName and t.relname = :tableName and n.nspname = """ + schemaCondition)
            .bind("indexName", indexName)
            .bind("tableName", (dot < 0 ? qualifiedTable : qualifiedTable.substring(dot + 1)).toLowerCase(Locale.ROOT));
        if (dot >= 0) {
            spec = spec.bind("schemaName", qualifiedTable.substring(0, dot).toLowerCase(Locale.ROOT));
        }
        return spec
            .map((row, meta) -> row.get("column_name", String.class))
            .all()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(HashSet::new, Set::add);
    }

    private Mono<Set<String>> fetchColumns(String qualifiedTable) {
        int dot = qualifiedTable.indexOf('.');
        DatabaseClient.GenericExecuteSpec spec;
        if (dot < 0) {
            spec = db.sql("""
                    select column_name
                    from information_schema.columns
                    where table_schema = current_schema()
                      and table_name = :tableName
                    """)
                .bind("tableName", qualifiedTable.toLowerCase(Locale.ROOT));
        } else {
            spec = db.sql("""
                    select column_name
                    from information_schema.columns
                    where table_schema = :schemaName
                      and table_name = :tableName
                    """)
                .bind("schemaName", qualifiedTable.substring(0, dot).toLowerCase(Locale.ROOT))
                .bind("tableName", qualifiedTable.substring(dot + 1).toLowerCase(Locale.ROOT));
        }
        return spec
            .map((row, meta) -> row.get("column_name", String.class))
            .all()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(HashSet::new, Set::add);
    }
}
