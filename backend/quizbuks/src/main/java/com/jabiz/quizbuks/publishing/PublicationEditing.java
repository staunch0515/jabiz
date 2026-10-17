package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.QuizSnapshots;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * What the publication processes share (docs/quizbuks/plans/Q4-publishing.md, 流程 and D-Q4-5): the publication's
 * lock {@code qb.pub:<publicationId>}, taken before the publication is read; the publication read through the
 * sponsor's dataset (another sponsor's is 404 without saying whether it exists); its tiers, its version (read from
 * the default dataset: versions have no sponsor dataset) and the version's quiz (through the sponsor's dataset: not
 * the caller's, or removed, means it cannot be used); the revision check; and moving the publication to a state.
 */
final class PublicationEditing {

    static final String INPUT = "input";
    static final String PUB_ID = "publicationId";
    static final String PUBLICATION = "publication";
    static final String FOUND = "found";
    static final String TIERS = "tiers";
    static final String VERSIONS = "versions";
    static final String QUIZZES = "quizzes";
    static final String CASE = "case";
    static final String SCHEDULE = "schedule";
    static final String APPROVAL = "approval";
    static final String OUTPUT = "output";
    static final String SPONSOR_ROWS = "sponsorRows";
    static final String BAN_ROWS = "banRows";
    static final String CARD = "card";
    static final String SPONSOR_PROFILE = "sponsorProfile";

    /**
     * The family of the publication's lock {@code qb.pub:<publicationId>}: also the admission family of the processes
     * that queue for it before their transaction (platform decision D45), so that the queue's key and the lock's name
     * are the same text.
     */
    static final String LOCK_FAMILY = "qb.pub";

    static String lockName(Object publicationId) {
        return publicationId == null ? null : LOCK_FAMILY + ":" + publicationId;
    }

    /**
     * The family of the publication's start lock {@code qb.pub-start:<publicationId>} (Q10a4,
     * docs/quizbuks/plans/Q10-lock-split.md): held by a start around the starts' counters, and after the publication's
     * lock by the processes that change the state of a publication that went on. A family of its own, not
     * {@code qb.pub:start:…}: the platform takes a lock whose name begins with {@code <family>:} for one of that
     * family, so a start queuing as {@code qb.pub} would queue behind the completions again.
     */
    static final String START_LOCK_FAMILY = "qb.pub-start";

    static String startLockName(Object publicationId) {
        return publicationId == null ? null : START_LOCK_FAMILY + ":" + publicationId;
    }

    /**
     * Takes the publication's start lock, after its lock and before the reads (platform decision D46 rule 5): for a
     * process that changes the state of a publication that went on, so that no start gets in after the change commits
     * and every start before it is counted. Lock order: {@code qb.pub:<p>} → {@code qb.pub-start:<p>}; nothing is
     * locked after it but a later publication's locks (design section 4.3).
     */
    static <I, O> void lockStarts(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the publication's starts", HoldLock.<ProcessContext>exclusive(
            ctx -> startLockName(ctx.get(PUB_ID))));
    }

    static ProcessContext start(ProcessStart start, Object input, UUID publicationId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(PUB_ID, publicationId);
        return ctx;
    }

    /** Takes the publication's lock and loads it through the sponsor's dataset: 404 unless it is the caller's. */
    static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        lockAndLoad(pb, false);
    }

    /**
     * As {@link #lockAndLoad(ProcessDefinitionBuilder)}, for a process that changes the state of a publication that
     * went on (pause, resume): the start lock too, after the publication's ({@link #lockStarts}).
     */
    static <I, O> void lockStatesAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        lockAndLoad(pb, true);
    }

    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb, boolean starts) {
        pb.step("Lock the publication", HoldLock.<ProcessContext>exclusive(ctx -> lockName(ctx.get(PUB_ID))));
        if (starts) {
            lockStarts(pb);
        }
        pb.step("Load the publication", LoadEntity.by(QbPublications.sponsorDataset(QbPublications.PUBLICATION),
            PUB_ID, PUBLICATION));
        loadSchedule(pb);
    }

    /**
     * Loads the schedule row and the card of the publication named by {@link #PUB_ID}, if it has them: {@link #write}
     * keeps both with the state.
     */
    static <I, O> void loadSchedule(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the schedule", QueryEntities.<ProcessContext>of(
            QbPublications.defaultDataset(QbPublications.SCHEDULE), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("publicationId", ids(ctx.get(PUB_ID)))).limit(1).build(), SCHEDULE))
            .step("Load the card", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("publicationId", ids(ctx.get(PUB_ID)))).limit(1).build(), CARD));
    }

    /**
     * Loads the profile of the publication's sponsor (none when they have none), whose country the card keeps
     * ({@link #goingOn}). By the immutable user, so the query reads only that user's versions.
     */
    static <I, O> void loadSponsorProfile(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, EntityInstance> publication) {
        pb.step("Load the sponsor's profile", QueryEntities.<ProcessContext>of(
            QbAccounts.defaultDataset(QbAccounts.PROFILE), ctx -> {
                EntityInstance found = publication.apply(ctx);
                return EntityQuery.builder().where(new QueryPredicate.In("userId",
                    ids(found == null ? null : found.get("sponsorId")))).limit(1).build();
            }, SPONSOR_PROFILE));
    }

    /**
     * Loads the caller's onboarding and ban (D-Q2-6), for {@link #sponsorProblems}. Read only, without the account's
     * lock: an approval is never taken back, and a ban that comes meanwhile is caught by the next action.
     */
    static <I, O> void loadSponsor(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the sponsor's onboarding", QueryEntities.<ProcessContext>of(
                QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("userId", ctx.request().actorId())).limit(1).build(), SPONSOR_ROWS))
            // A ban refers to a platform user (a UUID); any other caller id has none.
            .step("Load the sponsor's ban", QueryEntities.<ProcessContext>of(
                QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId", userIds(ctx.request().actorId()))).limit(1).build(),
                BAN_ROWS));
    }

    private static List<Object> userIds(String actorId) {
        List<Object> ids = new ArrayList<>();
        try {
            UUID id = UUID.fromString(actorId);
            if (id.toString().equalsIgnoreCase(actorId)) {
                ids.add(id);
            }
        } catch (IllegalArgumentException e) {
            // Not a platform user.
        }
        return ids;
    }

    /** Why the caller may not publish as a sponsor ({@link SponsorChecks}); none when they may. */
    static List<Violation> sponsorProblems(ProcessContext ctx) {
        EntityInstance sponsor = first(ctx, SPONSOR_ROWS);
        EntityInstance ban = first(ctx, BAN_ROWS);
        return SponsorChecks.check(sponsor == null ? null : sponsor.attributes(), ban == null ? null
            : ban.attributes());
    }

    /** Loads the tiers of the publication named by {@link #PUB_ID} (none without one). */
    static <I, O> void loadTiers(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the tiers", QueryEntities.<ProcessContext>of(QbPublications.defaultDataset(QbPublications.TIER),
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId", ids(ctx.get(PUB_ID))))
                .limit(Integer.MAX_VALUE).build(), TIERS));
    }

    /** Loads the version {@code versionId} names (none when it names none) and, for a sponsor, its quiz. */
    static <I, O> void loadVersion(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> versionId, boolean withQuiz) {
        pb.step("Load the version", QueryEntities.<ProcessContext>of(QbContent.defaultDataset(QbContent.VERSION),
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("versionId", ids(versionId.apply(ctx))))
                .limit(1).build(), VERSIONS));
        if (withQuiz) {
            pb.step("Load the version's quiz", QueryEntities.<ProcessContext>of(
                QbContent.sponsorDataset(QbContent.QUIZ), ctx -> {
                    EntityInstance version = first(ctx, VERSIONS);
                    return EntityQuery.builder().where(new QueryPredicate.In("quizId",
                        ids(version == null ? null : version.get("quizId")))).limit(1).build();
                }, QUIZZES));
        }
    }

    static List<Object> ids(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            ids.add(id);
        }
        return ids;
    }

    /**
     * The version loaded for the caller: 404 when there is none, or it is another sponsor's (without saying which);
     * 422 {@code QB_PUBLICATION_QUIZ_REMOVED} when its quiz was removed (its files may be gone). Null after a refusal.
     */
    static EntityInstance requireVersion(ProcessContext ctx, Object versionId) {
        EntityInstance version = first(ctx, VERSIONS);
        if (version == null || !ctx.request().actorId().equals(version.get("ownerId"))) {
            throw new EntityNotFoundException(QbContent.VERSION + " " + versionId + " not found");
        }
        if (first(ctx, QUIZZES) == null) {
            ctx.reject(new Violation("versionId", PublishingCodes.QUIZ_REMOVED, "The quiz of this version was "
                + "removed", Map.of("versionId", String.valueOf(versionId))));
            return null;
        }
        return version;
    }

    /**
     * Whether the caller saw the publication as it is: a given {@code baseRevision} other than the current one is
     * 422 {@code QB_PUBLICATION_CHANGED}; none means the last writer wins.
     */
    static boolean current(ProcessContext ctx, EntityInstance publication, Long baseRevision) {
        long revision = number(publication.get("revision"));
        if (baseRevision != null && baseRevision != revision) {
            ctx.reject(new Violation("baseRevision", PublishingCodes.CHANGED, "The publication was changed "
                + "meanwhile: revision " + revision + ", not " + baseRevision,
                Map.of("revision", revision, "baseRevision", baseRevision)));
            return false;
        }
        return true;
    }

    /** Only a draft may be edited or deleted (D-Q4-2): 422 {@code QB_PUBLICATION_NOT_DRAFT} otherwise. */
    static boolean draft(ProcessContext ctx, EntityInstance publication) {
        if (!PublicationStatus.DRAFT.equals(publication.get("status"))) {
            ctx.reject(new Violation("publicationId", PublishingCodes.NOT_DRAFT, "The publication is "
                + publication.get("status") + ", no longer a draft",
                Map.of("status", String.valueOf((Object) publication.get("status")))));
            return false;
        }
        return true;
    }

    /**
     * Whether the publication is in one of {@code from}; otherwise the move to {@code to} is refused as the platform
     * refuses an undeclared one (422 {@code ILLEGAL_TRANSITION}). The platform does not see a move to the state the
     * publication is already in, nor a move the lifecycle allows but this action does not make.
     */
    static boolean in(ProcessContext ctx, EntityInstance publication, String to, String... from) {
        String status = publication.get("status");
        if (!List.of(from).contains(status)) {
            ctx.reject(illegal(status, to));
            return false;
        }
        return true;
    }

    private static Violation illegal(String from, String to) {
        return new Violation("status", PlatformErrorCodes.ILLEGAL_TRANSITION, "Illegal transition from [" + from
            + "] to [" + to + "] on " + QbPublications.PUBLICATION, Map.of("from", String.valueOf(from), "to", to));
    }

    /**
     * Writes the publication's next version: {@code values} with the next revision and the time. Puts the output.
     * A change of state must be one the lifecycle declares (checked here too, so a move to the same state is refused).
     */
    static void write(ProcessContext ctx, EntityInstance publication, Map<String, Object> values) {
        Object status = values.get("status");
        if (status != null && !QbPublications.PUBLICATION_ENTITY.allowsTransition(publication.get("status"),
            status.toString())) {
            ctx.reject(illegal(publication.get("status"), status.toString()));
            return;
        }
        long revision = number(publication.get("revision")) + 1;
        Map<String, Object> next = new LinkedHashMap<>(values);
        if (PublicationStatus.DRAFT.equals(status)) {
            // Back to its draft, the publication has no review any more: its next submission makes a new one.
            next.put("approvalRequestId", null);
        }
        next.put("revision", BigDecimal.valueOf(revision));
        next.put("editedAt", ctx.opTime());
        ctx.changes().update(QbPublications.PUBLICATION, publication.id(), publication.version(), next);
        if (status != null) {
            schedule(ctx, publication, status.toString());
            card(ctx, status.toString());
        }
        ApprovalOutputs approval = ApprovalOutputs.of(ctx);
        ctx.put(OUTPUT, new PublicationOutput(uuid(publication.id()),
            status == null ? publication.get("status") : status.toString(), revision, approval.status(),
            next.containsKey("approvalRequestId") ? (String) next.get("approvalRequestId")
                : publication.get("approvalRequestId")));
    }

    /**
     * Keeps the publication's schedule row with its state (under the publication's lock, read by
     * {@link #loadSchedule}): none for a draft; for any other state the version and the time the tick must next look
     * at it (none once it ended).
     */
    static void schedule(ProcessContext ctx, EntityInstance publication, String status) {
        EntityInstance row = first(ctx, SCHEDULE);
        if (PublicationStatus.DRAFT.equals(status)) {
            if (row != null) {
                ctx.changes().delete(QbPublications.SCHEDULE, row.id(), row.version());
            }
            return;
        }
        Instant due = PublicationRules.nextDue(status, instant(publication.get("startAt")),
            instant(publication.get("endAt")));
        if (row == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("publicationId", publication.id());
            values.put("quizId", publication.get("quizId"));
            values.put("versionId", publication.get("versionId"));
            values.put("nextDueAt", due);
            ctx.changes().insert(QbPublications.SCHEDULE, values);
        } else if (!same(row.get("nextDueAt"), due)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("nextDueAt", due);
            ctx.changes().update(QbPublications.SCHEDULE, row.id(), row.version(), values);
        }
    }

    /** Keeps the card's state with the publication's (under its lock, read by {@link #loadSchedule}). */
    static void card(ProcessContext ctx, String status) {
        EntityInstance row = first(ctx, CARD);
        if (row != null && !status.equals(row.get("status"))) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", status);
            if (PublicationStatus.ENDED.equals(status)) {
                values.put("endedAt", ctx.opTime());
            }
            ctx.changes().update(QbPublications.CARD, row.id(), row.version(), values);
        }
    }

    /** The output when nothing was written. */
    static void unchanged(ProcessContext ctx, EntityInstance publication) {
        ApprovalOutputs approval = ApprovalOutputs.of(ctx);
        ctx.put(OUTPUT, new PublicationOutput(uuid(publication.id()), publication.get("status"),
            number(publication.get("revision")), approval.status(), publication.get("approvalRequestId")));
    }

    /** The approval outcome of the process, if it asked. */
    private record ApprovalOutputs(String status) {
        static ApprovalOutputs of(ProcessContext ctx) {
            Object outcome = ctx.get(APPROVAL);
            return new ApprovalOutputs(outcome instanceof com.jabiz.runtime.approval.ApprovalOutcome o
                ? o.status().name() : null);
        }
    }

    /**
     * The values of a publication that goes on (D-Q4-4) at the operation time: scheduled or live by its times, the
     * time of the approval, and the reasons of an earlier refusal cleared. Inserts both sides of its counters, all
     * zero (Q10a4: in the same transaction, so a publication on always has both), and its card (Q5, D-Q5-6) from the
     * publication, its version ({@link #VERSIONS}), tiers ({@link #TIERS}) and sponsor's profile
     * ({@link #SPONSOR_PROFILE}).
     */
    static Map<String, Object> goingOn(ProcessContext ctx, EntityInstance publication, String requestId) {
        Map<String, Object> values = new LinkedHashMap<>();
        String status = PublicationRules.resumeTo(instant(publication.get("startAt")),
            instant(publication.get("endAt")), ctx.opTime());
        values.put("status", status);
        values.put("approvedAt", ctx.opTime());
        values.put("approvalRequestId", requestId);
        values.put("rejectReason", null);
        values.put("rejectCode", null);
        ctx.changes().insert(QbPublications.COUNTER, zeros(publication, "completions", "passes", "winners",
            "released", "spent"));
        ctx.changes().insert(QbPublications.START_COUNTER, zeros(publication, "starts", "reservedTotal"));
        ctx.changes().insert(QbPublications.CARD, cardOf(ctx, publication, status));
        return values;
    }

    /** A counters row of the publication with {@code counts} at 0. */
    private static Map<String, Object> zeros(EntityInstance publication, String... counts) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("publicationId", publication.id());
        for (String count : counts) {
            values.put(count, BigDecimal.ZERO);
        }
        return values;
    }

    /** The card of a publication going on in {@code status}. */
    private static Map<String, Object> cardOf(ProcessContext ctx, EntityInstance publication, String status) {
        EntityInstance version = first(ctx, VERSIONS);
        if (version == null) {
            // Submission requires a version, and it is written once: it is always there.
            throw new IllegalStateException(QbPublications.PUBLICATION + " " + publication.id() + " has no version");
        }
        QuizSnapshot snapshot = snapshot(version);
        EntityInstance profile = first(ctx, SPONSOR_PROFILE);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("publicationId", publication.id());
        card.put("sponsorId", publication.get("sponsorId"));
        card.put("status", status);
        for (String field : List.of("regions", "startAt", "endAt", "name", "versionId", "maxReward", "fullScore",
            "passScore", "timeLimitSec", "budgetCap", "maxWinners")) {
            card.put(field, publication.get(field));
        }
        card.put("quizTitle", version.get("title"));
        card.put("questionCount", version.get("questionCount"));
        card.put("cover", snapshot.cover() == null ? null : snapshot.cover().toString());
        card.put("tiers", CardTiers.toJson(ordered(list(ctx, TIERS)).stream().map(row -> new CardTiers.Tier(
            String.valueOf(row.id()), integer(row.get("fromScore")), integer(row.get("toScore")),
            money(row.get("reward")))).toList()));
        card.put("sponsorCountry", profile == null ? null : profile.get("countryCode"));
        card.put("wentOnAt", ctx.opTime());
        return card;
    }

    /** The publication's policy as the readiness check and the approval see it. */
    static PublicationReadiness.Draft draftOf(Map<String, Object> values) {
        return new PublicationReadiness.Draft(uuid(values.get("versionId")), (String) values.get("name"),
            (String) values.get("regions"), instant(values.get("startAt")), instant(values.get("endAt")),
            integer(values.get("timeLimitSec")), integer(values.get("passScore")), money(values.get("budgetCap")),
            integer(values.get("maxWinners")));
    }

    static PublicationReadiness.Draft draftOf(EntityInstance publication) {
        return draftOf(publication.attributes());
    }

    /** The tiers in their order. */
    static List<EntityInstance> ordered(List<EntityInstance> tiers) {
        return tiers.stream().sorted(Comparator.<EntityInstance>comparingLong(row -> number(row.get("seq")))
            .thenComparing(row -> String.valueOf(row.id()))).toList();
    }

    static List<TierCoverage.Tier> tiersOf(List<EntityInstance> rows) {
        return ordered(rows).stream().map(row -> new TierCoverage.Tier(integer(row.get("fromScore")),
            integer(row.get("toScore")), money(row.get("reward")))).toList();
    }

    /** The snapshot of a version; null without one. */
    static QuizSnapshot snapshot(EntityInstance version) {
        return version == null ? null : QuizSnapshots.fromJson(version.get("content"));
    }

    /**
     * The approval case of a publication (D-Q4-4): its facts, with {@code autoApprove} as the parameter says; bound
     * to the version's content and the whole policy; known to people by its name.
     */
    static ApprovalCase approvalCase(EntityInstance publication, List<EntityInstance> tiers, EntityInstance version,
        boolean autoApprove) {
        List<TierCoverage.Tier> ranges = tiersOf(tiers);
        Map<String, Object> facts = PublicationApprovals.facts(autoApprove, money(publication.get("maxReward")),
            money(publication.get("maxSpend")), publication.get("sponsorId"), publication.get("regions"));
        Map<String, Object> content = PublicationApprovals.content(draftOf(publication),
            version == null ? null : version.get("contentHash"), ranges);
        return ApprovalCase.of(publication.id(), facts, content).reference(publication.get("name"));
    }

    /** Problems as the caller reads them: field, code, the message in their language, parameters. */
    static List<SaveOutput.Problem> problems(List<Violation> violations, MessageCatalog messages, ProcessContext ctx) {
        return violations.stream().map(v -> new SaveOutput.Problem(v.field(), v.ruleCode(),
            messages.message(v, ctx.request().locale()), v.params())).toList();
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

    static EntityInstance publication(ProcessContext ctx) {
        return ctx.get(PUBLICATION, EntityInstance.class);
    }

    /** The values of {@code values} that differ from {@code row} (numbers by value, times by instant). */
    static Map<String, Object> differences(EntityInstance row, Map<String, Object> values) {
        Map<String, Object> changed = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (!same(row.get(field), value)) {
                changed.put(field, value);
            }
        });
        return changed;
    }

    static boolean same(Object stored, Object value) {
        if (stored == null || value == null) {
            return stored == value;
        }
        if (stored instanceof BigDecimal a && value instanceof Number b) {
            return a.compareTo(new BigDecimal(b.toString())) == 0;
        }
        if (stored instanceof UUID || value instanceof UUID) {
            return stored.toString().equals(value.toString());
        }
        if (stored instanceof java.time.OffsetDateTime || stored instanceof Instant) {
            return Objects.equals(instant(stored), instant(value));
        }
        return Objects.equals(stored, value);
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    static long number(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValueExact();
    }

    static Integer integer(Object value) {
        return value == null ? null : new BigDecimal(value.toString()).intValueExact();
    }

    static BigDecimal money(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    static BigDecimal decimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    static Instant instant(Object value) {
        return QbPublications.instant(value);
    }

    private PublicationEditing() {}
}
