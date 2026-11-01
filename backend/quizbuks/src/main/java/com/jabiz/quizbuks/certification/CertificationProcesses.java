package com.jabiz.quizbuks.certification;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.Certifications.Certification;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The certifications' processes (Q69, docs/quizbuks/plans/Q69-certifications.md):
 * <ul>
 *   <li>headquarters marks the official publishers ({@value #OFFICIAL_SET});</li>
 *   <li>an operator, or headquarters for the direct sponsors, makes a publication of its official publisher a round
 *       of a certification and closes it ({@value #ROUND_ADD}, {@value #ROUND_CLOSE});</li>
 *   <li>an operator or headquarters grants or revokes a certification by hand, with a reason ({@value #GRANT},
 *       {@value #REVOKE}; headquarters spot-checks);</li>
 *   <li>a taker records their passes and learns what they hold ({@value #CLAIM}).</li>
 * </ul>
 * None of them touches the answering path: a pass is read from the finished attempt when it is needed.
 */
public final class CertificationProcesses {

    public static final String OFFICIAL_SET = "QB_OFFICIAL_PUBLISHER_SET";
    public static final String ROUND_ADD = "QB_CERTIFICATION_ROUND_ADD";
    public static final String ROUND_CLOSE = "QB_CERTIFICATION_ROUND_CLOSE";
    public static final String GRANT = "QB_CERTIFICATION_GRANT";
    public static final String REVOKE = "QB_CERTIFICATION_REVOKE";
    public static final String CLAIM = "QB_CERTIFICATION_CLAIM";
    public static final String OVERVIEW = "QB_CERTIFICATION_OVERVIEW";

    /** Most rounds and records one person's certification reads; more is an error rather than a silent cut. */
    static final int MAX_ROWS = 500;

    public record OfficialInput(@NotBlank @Size(max = 128) String userId, @Size(max = 128) String operatorId,
        boolean active) {}

    public record OfficialOutput(String userId, String owner, boolean active) {}

    public record RoundInput(@NotNull UUID publicationId, @NotBlank @Size(max = 40) String certification) {}

    public record RoundRef(@NotNull UUID roundId) {}

    public record RoundOutput(String roundId, String publicationId, String certification, int certificationVersion,
        Instant closedAt) {}

    public record GrantInput(@NotBlank @Size(max = 128) String userId, @NotBlank @Size(max = 40) String certification,
        @NotBlank @Size(max = 500) String reason) {}

    public record GrantOutput(String recordId, String kind, Instant validUntil) {}

    public record ClaimInput(@NotBlank @Size(max = 40) String certification) {}

    /**
     * What the caller holds: until when (none: not held) and, for the page's link, the publication of the newest open
     * round.
     */
    public record ClaimOutput(String certification, Instant heldUntil, String roundPublicationId) {}

    public record OverviewInput() {}

    /** One round as the overview shows it; {@code holders}: the passes recorded on it. */
    public record RoundRow(String roundId, String publicationId, String publicationName, String certification,
        int certificationVersion, Instant addedAt, Instant closedAt, int holders) {}

    /** One record made by hand (a grant or a revocation), with its reason: what headquarters spot-checks. */
    public record HandRow(String recordId, String userId, String certification, String kind, String reason,
        String actor, String owner, Instant at, Instant validUntil) {}

    /** The caller's (operator's, or headquarters') rounds and records by hand, newest first. */
    public record Overview(String owner, List<RoundRow> rounds, List<HandRow> byHand) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String SPONSOR = "sponsor";
    static final String OPERATOR = "operator";
    static final String OFFICIAL = "official";
    static final String CARD = "card";
    static final String ROUNDS = "rounds";
    static final String ROUND = "round";
    static final String RECORDS = "records";
    static final String ATTEMPTS = "attempts";

    // ---- headquarters: the official publishers ----------------------------------------------------------------------

    public static ProcessDefinition<OfficialInput, OfficialOutput, ProcessContext> officialSet() {
        return ProcessDefinition.define(OFFICIAL_SET, 1, OfficialInput.class, OfficialOutput.class,
            ProcessContext.class, pb -> pb
                .description("Headquarters marks an approved sponsor account as the official publisher of an operator "
                    + "(or of headquarters): its publications may be certification rounds.")
                .permissions(QbPermissions.CERTIFICATION_ADMIN)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, OfficialOutput.class))
                .step("Lock the account", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbCertifications.lockName(input(ctx, OfficialInput.class).userId())))
                .step("Load the sponsor", QueryEntities.<ProcessContext>of(QbAccounts.defaultDataset(QbAccounts.SPONSOR),
                    ctx -> eq("userId", input(ctx, OfficialInput.class).userId(), 1), SPONSOR))
                .step("Load the operator", QueryEntities.<ProcessContext>of(
                    QbOperators.defaultDataset(QbOperators.OPERATOR),
                    ctx -> eq("operatorId", operatorOf(input(ctx, OfficialInput.class)), 1), OPERATOR))
                .step("Load the official row", QueryEntities.<ProcessContext>of(
                    QbCertifications.defaultDataset(QbCertifications.OFFICIAL),
                    ctx -> eq("userId", input(ctx, OfficialInput.class).userId(), 1), OFFICIAL))
                .compute("Mark them", (metadata, ctx) -> {
                    OfficialInput input = input(ctx, OfficialInput.class);
                    EntityInstance sponsor = first(ctx, SPONSOR);
                    if (sponsor == null || !SponsorStatus.APPROVED.equals(sponsor.get("reviewStatus"))) {
                        ctx.reject(new Violation("userId", CertificationCodes.NOT_APPROVED_SPONSOR,
                            "Only an approved sponsor can be an official publisher", Map.of()));
                        return;
                    }
                    String owner = input.operatorId() == null || input.operatorId().isBlank() ? QbCertifications.DIRECT
                        : input.operatorId().strip();
                    if (!QbCertifications.DIRECT.equals(owner) && first(ctx, OPERATOR) == null) {
                        ctx.reject(new Violation("operatorId", CertificationCodes.NO_SUCH_OPERATOR,
                            "No such operator", Map.of()));
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("owner", owner);
                    values.put("active", input.active());
                    values.put("setBy", ctx.request().actorId());
                    values.put("setAt", ctx.opTime());
                    EntityInstance existing = first(ctx, OFFICIAL);
                    if (existing == null) {
                        values.put("userId", input.userId());
                        ctx.changes().insert(QbCertifications.OFFICIAL, values);
                    } else {
                        ctx.changes().update(QbCertifications.OFFICIAL, existing.id(), existing.version(), values);
                    }
                    ctx.put(OUTPUT, new OfficialOutput(input.userId(), owner, input.active()));
                }));
    }

    // ---- an operator or headquarters: the rounds ---------------------------------------------------------------------

    public static ProcessDefinition<RoundInput, RoundOutput, ProcessContext> roundAdd() {
        return ProcessDefinition.define(ROUND_ADD, 1, RoundInput.class, RoundOutput.class, ProcessContext.class, pb -> {
            pb.description("Makes a publication of the caller's official publisher a round of a certification: "
                    + "passing it lets a taker hold the certification.")
                .permissions(QbPermissions.CERTIFICATION_MANAGE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, RoundOutput.class));
            OperatorChecks.memberWhenScoped(pb);
            pb.step("Load the publication's card", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.CARD),
                    ctx -> eq("publicationId", input(ctx, RoundInput.class).publicationId(), 1), CARD))
                .step("Load the official publishers", QueryEntities.<ProcessContext>of(
                    QbCertifications.defaultDataset(QbCertifications.OFFICIAL),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("active", true)).limit(MAX_ROWS).build(),
                    OFFICIAL))
                .step("Load the round", QueryEntities.<ProcessContext>of(
                    QbCertifications.defaultDataset(QbCertifications.ROUND),
                    ctx -> eq("publicationId", input(ctx, RoundInput.class).publicationId().toString(), 1), ROUND))
                .compute("Add the round", (metadata, ctx) -> {
                    RoundInput input = input(ctx, RoundInput.class);
                    Certification certification = certification(input.certification());
                    EntityInstance card = first(ctx, CARD);
                    String owner = owner(ctx);
                    if (card == null || !PublisherKind.SPONSOR.equals(card.get(PublisherKind.KIND_FIELD))) {
                        throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + input.publicationId());
                    }
                    Object publisher = card.get(PublisherKind.ID_FIELD);
                    boolean official = list(ctx, OFFICIAL).stream().anyMatch(o -> o.get("userId").equals(publisher)
                        && owner.equals(o.get("owner")));
                    if (!official) {
                        ctx.reject(new Violation("publicationId", CertificationCodes.NOT_OFFICIAL,
                            "Only a publication of the caller's official publisher can be a round", Map.of()));
                        return;
                    }
                    EntityInstance existing = first(ctx, ROUND);
                    if (existing != null) {
                        ctx.reject(new Violation("publicationId", CertificationCodes.ALREADY_A_ROUND,
                            "The publication is a round already", Map.of()));
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("certification", certification.key());
                    values.put("certificationVersion", certification.version());
                    values.put("publicationId", input.publicationId().toString());
                    values.put("owner", owner);
                    values.put("addedBy", ctx.request().actorId());
                    values.put("addedAt", ctx.opTime());
                    Object id = ctx.changes().insert(QbCertifications.ROUND, values);
                    ctx.put(OUTPUT, new RoundOutput(String.valueOf(id), input.publicationId().toString(),
                        certification.key(), certification.version(), null));
                });
        });
    }

    public static ProcessDefinition<RoundRef, RoundOutput, ProcessContext> roundClose() {
        return ProcessDefinition.define(ROUND_CLOSE, 1, RoundRef.class, RoundOutput.class, ProcessContext.class, pb -> {
            pb.description("Closes a round: passes submitted from now on no longer count (those before still do).")
                .permissions(QbPermissions.CERTIFICATION_MANAGE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, RoundOutput.class));
            OperatorChecks.memberWhenScoped(pb);
            pb.step("Load the round", QueryEntities.<ProcessContext>of(
                    QbCertifications.defaultDataset(QbCertifications.ROUND),
                    ctx -> eq("roundId", input(ctx, RoundRef.class).roundId().toString(), 1), ROUND))
                .compute("Close it", (metadata, ctx) -> {
                    EntityInstance round = first(ctx, ROUND);
                    // Another operator's round is not found, as one that does not exist.
                    if (round == null || !owner(ctx).equals(round.get("owner"))) {
                        throw new EntityNotFoundException(QbCertifications.ROUND + " " + input(ctx, RoundRef.class)
                            .roundId());
                    }
                    Instant closed = instant(round.get("closedAt"));
                    if (closed == null) {
                        closed = ctx.opTime();
                        ctx.changes().update(QbCertifications.ROUND, round.id(), round.version(),
                            Map.of("closedAt", closed));
                    }
                    ctx.put(OUTPUT, new RoundOutput(String.valueOf(round.id()), round.get("publicationId"),
                        round.get("certification"), number(round.get("certificationVersion")), closed));
                });
        });
    }

    // ---- an operator or headquarters: by hand ------------------------------------------------------------------------

    public static ProcessDefinition<GrantInput, GrantOutput, ProcessContext> grant() {
        return byHand(GRANT, CertificationHolding.GRANTED, "Grants a certification by hand, with a reason: it counts "
            + "as a pass for the certification's validity.");
    }

    public static ProcessDefinition<GrantInput, GrantOutput, ProcessContext> revoke() {
        return byHand(REVOKE, CertificationHolding.REVOKED, "Revokes a certification, with a reason: every pass and "
            + "grant before it stops counting; a later pass counts again.");
    }

    private static ProcessDefinition<GrantInput, GrantOutput, ProcessContext> byHand(String name, String kind,
        String description) {
        return ProcessDefinition.define(name, 1, GrantInput.class, GrantOutput.class, ProcessContext.class, pb -> {
            pb.description(description)
                .permissions(QbPermissions.CERTIFICATION_MANAGE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, GrantOutput.class));
            OperatorChecks.memberWhenScoped(pb);
            pb.step("Lock the person's certifications", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbCertifications.lockName(input(ctx, GrantInput.class).userId().strip())))
                .compute("Record it", (metadata, ctx) -> {
                    GrantInput input = input(ctx, GrantInput.class);
                    Certification certification = certification(input.certification());
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("userId", input.userId().strip());
                    values.put("certification", certification.key());
                    values.put("certificationVersion", certification.version());
                    values.put("kind", kind);
                    values.put("reason", input.reason().strip());
                    values.put("actor", ctx.request().actorId());
                    values.put("owner", owner(ctx));
                    values.put("at", ctx.opTime());
                    Instant until = CertificationHolding.GRANTED.equals(kind)
                        ? ctx.opTime().plus(certification.validity()) : null;
                    values.put("validUntil", until);
                    Object id = ctx.changes().insert(QbCertifications.RECORD, values);
                    ctx.put(OUTPUT, new GrantOutput(String.valueOf(id), kind, until));
                });
        });
    }

    // ---- the taker ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ClaimInput, ClaimOutput, ProcessContext> claim() {
        return ProcessDefinition.define(CLAIM, 1, ClaimInput.class, ClaimOutput.class, ProcessContext.class, pb -> pb
            .description("Records the caller's passes of a certification's rounds and says until when they hold it, "
                + "with the newest open round to answer when they do not.")
            .permissions(QbPermissions.ME)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ClaimOutput.class))
            .step("Lock the caller's certifications", HoldLock.<ProcessContext>exclusive(
                ctx -> QbCertifications.lockName(ctx.request().actorId())))
            .step("Load the rounds", QueryEntities.<ProcessContext>of(
                QbCertifications.defaultDataset(QbCertifications.ROUND),
                ctx -> rounds(certification(input(ctx, ClaimInput.class).certification())), ROUNDS))
            .step("Load the caller's records", QueryEntities.<ProcessContext>of(
                QbCertifications.defaultDataset(QbCertifications.RECORD),
                ctx -> records(ctx.request().actorId(), input(ctx, ClaimInput.class).certification()), RECORDS))
            .step("Load the caller's attempts on them", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(com.jabiz.quizbuks.play.QbPlay.ATTEMPT), ctx -> attempts(ctx), ATTEMPTS))
            .compute("Record the passes", (metadata, ctx) -> {
                Certification certification = certification(input(ctx, ClaimInput.class).certification());
                List<EntityInstance> rounds = list(ctx, ROUNDS);
                List<CertificationHolding.Entry> entries = new ArrayList<>(list(ctx, RECORDS).stream()
                    .map(CertificationProcesses::entry).toList());
                for (Map<String, Object> pass : unrecordedPasses(certification, rounds, list(ctx, RECORDS),
                    list(ctx, ATTEMPTS), ctx.request().actorId())) {
                    ctx.changes().insert(QbCertifications.RECORD, pass);
                    entries.add(passEntry(pass));
                }
                Instant until = CertificationHolding.heldUntil(entries, certification, ctx.opTime()).orElse(null);
                String open = rounds.stream().filter(r -> r.get("closedAt") == null
                        && number(r.get("certificationVersion")) == certification.version())
                    .max((a, b) -> instant(a.get("addedAt")).compareTo(instant(b.get("addedAt"))))
                    .map(r -> String.valueOf((Object) r.get("publicationId"))).orElse(null);
                ctx.put(OUTPUT, new ClaimOutput(certification.key(), until, open));
            }));
    }

    // ---- an operator or headquarters: what it runs --------------------------------------------------------------------

    public static ProcessDefinition<OverviewInput, Overview, ProcessContext> overview() {
        return ProcessDefinition.define(OVERVIEW, 1, OverviewInput.class, Overview.class, ProcessContext.class, pb -> {
            pb.description("The caller's certification rounds with how many passes were recorded on each, and the "
                    + "grants and revocations made by hand with their reasons (read only).")
                .permissions(QbPermissions.CERTIFICATION_MANAGE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, Overview.class));
            OperatorChecks.memberWhenScoped(pb);
            pb.step("Load the rounds", QueryEntities.<ProcessContext>of(
                    QbCertifications.defaultDataset(QbCertifications.ROUND),
                    ctx -> eq("owner", owner(ctx), MAX_ROWS), ROUNDS))
                .step("Load the records", QueryEntities.<ProcessContext>of(
                    QbCertifications.defaultDataset(QbCertifications.RECORD),
                    ctx -> eq("owner", owner(ctx), MAX_ROWS), RECORDS))
                .step("Load the rounds' cards", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.CARD), ctx -> {
                        List<Object> ids = list(ctx, ROUNDS).stream()
                            .map(r -> (Object) UUID.fromString(String.valueOf((Object) r.get("publicationId")))).toList();
                        return EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                            ids.isEmpty() ? List.of(new UUID(0, 0)) : ids)).limit(MAX_ROWS).build();
                    }, CARD))
                .compute("Describe them", (metadata, ctx) -> {
                    Map<String, String> names = list(ctx, CARD).stream().collect(Collectors.toMap(
                        c -> String.valueOf((Object) c.get("publicationId")), c -> String.valueOf((Object) c.get("name")),
                        (a, b) -> a));
                    List<EntityInstance> records = list(ctx, RECORDS);
                    List<RoundRow> rounds = list(ctx, ROUNDS).stream()
                        .sorted((a, b) -> instant(b.get("addedAt")).compareTo(instant(a.get("addedAt"))))
                        .map(r -> {
                            String publication = String.valueOf((Object) r.get("publicationId"));
                            Set<String> attempts = holdersOf(records, r);
                            return new RoundRow(String.valueOf(r.id()), publication, names.get(publication),
                                r.get("certification"), number(r.get("certificationVersion")),
                                instant(r.get("addedAt")), instant(r.get("closedAt")), attempts.size());
                        }).toList();
                    List<HandRow> byHand = records.stream()
                        .filter(r -> !CertificationHolding.PASSED.equals(r.get("kind")))
                        .sorted((a, b) -> instant(b.get("at")).compareTo(instant(a.get("at"))))
                        .map(r -> new HandRow(String.valueOf(r.id()), r.get("userId"), r.get("certification"),
                            r.get("kind"), r.get("reason"), r.get("actor"), r.get("owner"), instant(r.get("at")),
                            instant(r.get("validUntil"))))
                        .toList();
                    ctx.put(OUTPUT, new Overview(owner(ctx), rounds, byHand));
                });
        });
    }

    /** The people whose passes were recorded on a round. */
    private static Set<String> holdersOf(List<EntityInstance> records, EntityInstance round) {
        String id = String.valueOf(round.id());
        return records.stream().filter(r -> CertificationHolding.PASSED.equals(r.get("kind")))
            .filter(r -> id.equals(r.get("roundId")))
            .map(r -> (String) r.get("userId")).collect(Collectors.toSet());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** The caller's operator, or headquarters' owner when the caller acts for headquarters. */
    static String owner(ProcessContext ctx) {
        String operator = OperatorChecks.operatorId(ctx);
        return operator == null ? QbCertifications.DIRECT : operator;
    }

    static Certification certification(String key) {
        return Certifications.of(key).orElseThrow(() -> new EntityNotFoundException("Certification " + key));
    }

    static EntityQuery rounds(Certification certification) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("certification", certification.key()))
            .limit(MAX_ROWS).build();
    }

    static EntityQuery records(Object userId, String certification) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("userId", userId),
            new QueryPredicate.Eq("certification", certification)))).limit(MAX_ROWS).build();
    }

    private static EntityQuery attempts(ProcessContext ctx) {
        List<Object> publications = list(ctx, ROUNDS).stream()
            .map(r -> (Object) UUID.fromString(String.valueOf((Object) r.get("publicationId")))).toList();
        // No round: an id nobody has, so that the step reads nothing.
        List<Object> ids = publications.isEmpty() ? List.of(new UUID(0, 0)) : publications;
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("userId", ctx.request().actorId()),
            new QueryPredicate.In("publicationId", ids)))).limit(MAX_ROWS).build();
    }

    /**
     * The caller's passes of the certification's rounds that no record holds yet, as the records to insert: a passed,
     * submitted attempt on a round, before the round closed.
     */
    static List<Map<String, Object>> unrecordedPasses(Certification certification, List<EntityInstance> rounds,
        List<EntityInstance> records, List<EntityInstance> attempts, Object userId) {
        Map<String, EntityInstance> byPublication = rounds.stream()
            .collect(Collectors.toMap(r -> String.valueOf((Object) r.get("publicationId")), r -> r, (a, b) -> a));
        Set<String> recorded = records.stream().map(r -> (String) r.get("attemptId"))
            .filter(id -> id != null).collect(Collectors.toSet());
        List<Map<String, Object>> passes = new ArrayList<>();
        for (EntityInstance attempt : attempts) {
            String attemptId = String.valueOf(attempt.id());
            EntityInstance round = byPublication.get(String.valueOf((Object) attempt.get("publicationId")));
            Instant submitted = instant(attempt.get("submittedAt"));
            if (round == null || recorded.contains(attemptId) || submitted == null
                || !Boolean.TRUE.equals(attempt.get("passed"))) {
                continue;
            }
            Instant closed = instant(round.get("closedAt"));
            if (closed != null && !submitted.isBefore(closed)) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", userId);
            values.put("certification", certification.key());
            values.put("certificationVersion", number(round.get("certificationVersion")));
            values.put("kind", CertificationHolding.PASSED);
            values.put("attemptId", attemptId);
            values.put("roundId", String.valueOf(round.id()));
            values.put("actor", userId);
            values.put("owner", round.get("owner"));
            values.put("at", submitted);
            values.put("validUntil", submitted.plus(certification.validity()));
            passes.add(values);
        }
        return passes;
    }

    static CertificationHolding.Entry passEntry(Map<String, Object> pass) {
        return new CertificationHolding.Entry(CertificationHolding.PASSED, (Integer) pass.get("certificationVersion"),
            (Instant) pass.get("at"), (Instant) pass.get("validUntil"));
    }

    static CertificationHolding.Entry entry(EntityInstance record) {
        return new CertificationHolding.Entry(record.get("kind"), number(record.get("certificationVersion")),
            instant(record.get("at")), instant(record.get("validUntil")));
    }

    private static EntityQuery eq(String field, Object value, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.Eq(field, value == null ? "" : value)).limit(limit)
            .build();
    }

    private static String operatorOf(OfficialInput input) {
        return input.operatorId() == null || input.operatorId().isBlank() ? "" : input.operatorId().strip();
    }

    static <T> T input(ProcessContext ctx, Class<T> type) {
        return ctx.get(INPUT, type);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.time.OffsetDateTime time) {
            return time.toInstant();
        }
        return Instant.parse(value.toString());
    }

    static int number(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private CertificationProcesses() {}
}
