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

    static String lockName(Object publicationId) {
        return publicationId == null ? null : "qb.pub:" + publicationId;
    }

    static ProcessContext start(ProcessStart start, Object input, UUID publicationId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(PUB_ID, publicationId);
        return ctx;
    }

    /** Takes the publication's lock and loads it through the sponsor's dataset: 404 unless it is the caller's. */
    static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the publication", HoldLock.<ProcessContext>exclusive(ctx -> lockName(ctx.get(PUB_ID))))
            .step("Load the publication", LoadEntity.by(QbPublications.sponsorDataset(QbPublications.PUBLICATION),
                PUB_ID, PUBLICATION));
        loadSchedule(pb);
    }

    /** Loads the schedule row of the publication named by {@link #PUB_ID}, if it has one. */
    static <I, O> void loadSchedule(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the schedule", QueryEntities.<ProcessContext>of(
            QbPublications.defaultDataset(QbPublications.SCHEDULE), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("publicationId", ids(ctx.get(PUB_ID)))).limit(1).build(), SCHEDULE));
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
     * time of the approval, and the reasons of an earlier refusal cleared. Inserts its counters, all zero.
     */
    static Map<String, Object> goingOn(ProcessContext ctx, EntityInstance publication, String requestId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", PublicationRules.resumeTo(instant(publication.get("startAt")),
            instant(publication.get("endAt")), ctx.opTime()));
        values.put("approvedAt", ctx.opTime());
        values.put("approvalRequestId", requestId);
        values.put("rejectReason", null);
        values.put("rejectCode", null);
        Map<String, Object> counter = new LinkedHashMap<>();
        counter.put("publicationId", publication.id());
        for (String count : List.of("starts", "completions", "passes", "winners", "reserved", "spent")) {
            counter.put(count, BigDecimal.ZERO);
        }
        ctx.changes().insert(QbPublications.COUNTER, counter);
        return values;
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
