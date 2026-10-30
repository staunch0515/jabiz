package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizCompleteness;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.APPROVAL;
import static com.jabiz.quizbuks.publishing.PublicationEditing.CARD;
import static com.jabiz.quizbuks.publishing.PublicationEditing.CASE;
import static com.jabiz.quizbuks.publishing.PublicationEditing.INPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUB_ID;
import static com.jabiz.quizbuks.publishing.PublicationEditing.SCHEDULE;
import static com.jabiz.quizbuks.publishing.PublicationEditing.TIERS;
import static com.jabiz.quizbuks.publishing.PublicationEditing.VERSIONS;
import static com.jabiz.quizbuks.publishing.PublicationEditing.first;
import static com.jabiz.quizbuks.publishing.PublicationEditing.ids;
import static com.jabiz.quizbuks.publishing.PublicationEditing.list;
import static com.jabiz.quizbuks.publishing.PublicationEditing.number;
import static com.jabiz.quizbuks.publishing.PublicationEditing.publication;
import static com.jabiz.quizbuks.publishing.PublicationStatus.FUNDS_SHORT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.LIVE;
import static com.jabiz.quizbuks.publishing.PublicationStatus.PAUSED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.SCHEDULED;

/**
 * Switching a publication that went on to another version of its quiz (docs/quizbuks/plans/Q51-publication-versions.md;
 * the product owner, 2026-10-29). A publication has one version in effect: its card, page and new attempts use it. A
 * switch is reviewed as the publication with the other version and the same policy, while the publication keeps
 * running on its version; one switch is in review at a time. Approved, the card takes the new version's title, cover,
 * video and number of questions; attempts keep the version they started with (their results never change), and a
 * taker who answered does not answer again, as it is the same publication. Rules (D-Q51-1 to 6):
 * <ul>
 *   <li>only the version changes, to another of the same quiz with the same full score (the tiers stay right); the
 *       policy (times, tiers, budget, reward) changes only by copying;</li>
 *   <li>only a publication scheduled, live, short of funds or paused, not one an administrator took down;</li>
 *   <li>the review is the publications' ({@code qb.publication.review}; the switch {@code qb.review.publication.auto}
 *       lets it through at once); approved before (the same content), it is made at once.</li>
 * </ul>
 * Lock order: {@code qb.quiz:<quiz>} (shared) → {@code qb.pub:<p>} → {@code qb.pub-start:<p>} (making a switch changes
 * what a start reads) → the approval's case lock {@code jabiz.approval:qb.publication:<p>}.
 */
public final class PublicationVersions {

    public static final String SWITCH = "QB_PUBLICATION_SWITCH_VERSION";
    public static final String SWITCH_WITHDRAW = "QB_PUBLICATION_SWITCH_WITHDRAW";

    /** The states in which a publication runs on a version and may switch to another. */
    static final Set<String> RUNNING = Set.of(SCHEDULED, LIVE, FUNDS_SHORT, PAUSED);

    /**
     * @param versionId    the version to switch to: another of the publication's quiz
     * @param baseRevision the publication's revision the caller saw (none: the last writer wins)
     */
    public record SwitchInput(@NotNull UUID publicationId, @NotNull UUID versionId, Long baseRevision) {}

    /** @param baseRevision the publication's revision the caller saw (none: the last writer wins) */
    public record WithdrawInput(@NotNull UUID publicationId, Long baseRevision) {}

    static final String FIRST = "first";
    static final String PARAMS = "params";
    static final String TAKEN_DOWN = "takenDown";
    /** The version a switch in review asks for, and its quiz (the approval result). */
    static final String PENDING_VERSIONS = "pendingVersions";
    static final String PENDING_QUIZZES = "pendingQuizzes";

    private PublicationVersions() {}

    // ---- ask for a switch ------------------------------------------------------------------------------------------

    public static ProcessDefinition<SwitchInput, PublicationOutput, ProcessContext> switchVersion() {
        return ProcessDefinition.define(SWITCH, 1, SwitchInput.class, PublicationOutput.class, ProcessContext.class,
            pb -> pb
                .description("Asks to switch a publication that went on to another version of its quiz; it is reviewed "
                    + "while the publication runs on its version.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .actsOn(QbPublications.PUBLICATION, "publicationId",
                    a -> a.whenField("status", SCHEDULED, LIVE, FUNDS_SHORT, PAUSED))
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(CallerChecks::refuseBanned)
                // A contract action, as submitting (Q7b).
                .steps(ConsentChecks.sponsor())
                // The quiz's lock first (D-Q4-5), then the publication's pair: making the switch changes what a start
                // reads, so the start lock too, before the approval's (the lock order above).
                .step("Find the publication's quiz", LoadEntity.by(
                    QbPublications.sponsorDataset(QbPublications.PUBLICATION), PUB_ID, FIRST))
                .step("Lock the quiz", HoldLock.<ProcessContext>shared(
                    ctx -> QbContent.lockName(ctx.get(FIRST, EntityInstance.class).get("quizId"))))
                .steps(PublicationEditing::lockStatesAndLoad)
                .steps(b -> PublicationEditing.loadSponsorPublications(b, PublicationEditing::publication))
                .step("Load the review switch", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.REVIEW_PUBLICATION_AUTO))
                .steps(PublicationEditing::loadTiers)
                .steps(b -> PublicationEditing.loadVersion(b, ctx -> input(ctx).versionId(), true))
                .compute("Check the switch", (metadata, ctx) -> check(ctx))
                .step("Ask for a review", RequireApproval.<ProcessContext>when(ctx -> ctx.contains(CASE),
                    PublicationApprovals.SUBJECT, ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL))
                .compute("Switch or wait for the review", (metadata, ctx) -> requested(ctx)));
    }

    private static SwitchInput input(ProcessContext ctx) {
        return ctx.get(INPUT, SwitchInput.class);
    }

    private static boolean autoApprove(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(PARAMS, ParamValues.class).get(QbParams.REVIEW_PUBLICATION_AUTO,
            Boolean.class));
    }

    static void check(ProcessContext ctx) {
        EntityInstance publication = publication(ctx);
        if (!Objects.equals(String.valueOf((Object) ctx.get(FIRST, EntityInstance.class).get("quizId")),
            String.valueOf((Object) publication.get("quizId")))) {
            throw new ConcurrentUpdateException(QbPublications.PUBLICATION + " " + publication.id()
                + " changed its quiz meanwhile");
        }
        if (!PublicationEditing.current(ctx, publication, input(ctx).baseRevision())) {
            return;
        }
        if (!RUNNING.contains(String.valueOf((Object) publication.get("status")))) {
            ctx.reject(new Violation("publicationId", PublishingCodes.SWITCH_NOT_RUNNING, "The publication is "
                + publication.get("status") + ": only one that went on and has not ended switches its version",
                Map.of("status", String.valueOf((Object) publication.get("status")))));
            return;
        }
        if (PublicationAdmin.takenDown(publication)) {
            ctx.reject(new Violation("publicationId", PublishingCodes.SWITCH_TAKEN_DOWN, "An administrator took the "
                + "publication down"));
            return;
        }
        if (publication.get("pendingRequestId") != null) {
            ctx.reject(new Violation("publicationId", PublishingCodes.SWITCH_PENDING, "A switch to version "
                + publication.get("pendingVersionLabel") + " is in review",
                Map.of("label", String.valueOf((Object) publication.get("pendingVersionLabel")))));
            return;
        }
        Object versionId = input(ctx).versionId();
        EntityInstance version = PublicationEditing.requireVersion(ctx, versionId);
        if (version == null) {
            return;
        }
        if (!String.valueOf((Object) version.get("quizId")).equals(String.valueOf((Object) publication.get("quizId")))) {
            ctx.reject(new Violation("versionId", PublishingCodes.SWITCH_OTHER_QUIZ, "Version " + versionId
                + " is of another quiz"));
            return;
        }
        if (String.valueOf(version.id()).equals(String.valueOf((Object) publication.get("versionId")))) {
            ctx.reject(new Violation("versionId", PublishingCodes.SWITCH_SAME_VERSION, "The publication uses version "
                + version.get("label") + " already"));
            return;
        }
        long fullScore = number(publication.get("fullScore"));
        long newFullScore = number(version.get("fullScore"));
        if (fullScore != newFullScore) {
            ctx.reject(new Violation("versionId", PublishingCodes.SWITCH_FULL_SCORE, "Version " + version.get("label")
                + " scores up to " + newFullScore + ", the publication's tiers up to " + fullScore,
                Map.of("fullScore", fullScore, "newFullScore", newFullScore)));
            return;
        }
        // A version is checked when it is saved; checked again here as at submission, in case the rules became
        // stricter since (defence, as PublicationReadiness does).
        QuizSnapshot snapshot = PublicationEditing.snapshot(version);
        QuizCompleteness.check(snapshot).forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        boolean takenDown = !PublicationEditing.takenDownOfQuiz(ctx, publication).isEmpty();
        ctx.put(TAKEN_DOWN, takenDown);
        ctx.put(CASE, PublicationEditing.approvalCase(publication, version.id(), list(ctx, TIERS), version,
            autoApprove(ctx), takenDown));
    }

    static void requested(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        EntityInstance publication = publication(ctx);
        EntityInstance version = first(ctx, VERSIONS);
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval.status() != ApprovalOutcome.Status.PENDING && Boolean.TRUE.equals(ctx.get(TAKEN_DOWN))) {
            // Review M-d, as at submission: after a takedown of the quiz only a person's review lets a version on.
            ctx.reject(new Violation(null, PublishingCodes.REVIEW_REQUIRED, "An administrator took down a "
                + "publication of this quiz: a switch is made only after a review by a person, which none asks for",
                Map.of("rule", PublicationApprovals.RULE)));
            return;
        }
        switch (approval.status()) {
            case PENDING -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("pendingVersionId", version.id());
                values.put("pendingVersionLabel", version.get("label"));
                values.put("pendingRequestId", approval.requestId());
                values.put("pendingSince", ctx.opTime());
                values.put("switchRejectReason", null);
                values.put("switchRejectCode", null);
                PublicationEditing.write(ctx, publication, values);
            }
            case NOT_REQUIRED -> {
                // No rule asked for a review: unless the switch says so, the rule is missing or off (gap 5).
                if (!autoApprove(ctx)) {
                    ctx.reject(new Violation(null, PublishingCodes.REVIEW_NOT_CONFIGURED, "Publications need a "
                        + "review, but no review rule is in effect", Map.of("rule", PublicationApprovals.RULE)));
                    return;
                }
                make(ctx, publication, version, null);
            }
            case APPROVED -> make(ctx, publication, version, approval.requestId());
            default -> throw new IllegalStateException("Unknown approval outcome " + approval.status());
        }
    }

    /**
     * Makes the switch to {@code version} (under the publication's pair of locks): the publication's version and label,
     * its card (title, cover, video and number of questions; the full score and kind are the same), its schedule
     * row's version, and a past version for the one it leaves; the switch in review is cleared.
     *
     * @param requestId the approval of the switch (none when the review switch let it through)
     */
    static void make(ProcessContext ctx, EntityInstance publication, EntityInstance version, String requestId) {
        QuizSnapshot snapshot = PublicationEditing.snapshot(version);
        EntityInstance card = first(ctx, CARD);
        if (card != null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("versionId", version.id());
            values.put("quizTitle", version.get("title"));
            values.put("language", version.get("language"));
            values.put("questionCount", version.get("questionCount"));
            values.put("cover", snapshot.cover() == null ? null : snapshot.cover().toString());
            values.put("video", snapshot.materials().stream().filter(QuizSnapshot.Material::watchToAnswer).findFirst()
                .flatMap(material -> com.jabiz.quizbuks.content.VideoEmbeds.player(material.url())).orElse(null));
            ctx.changes().update(QbPublications.CARD, card.id(), card.version(), values);
        }
        EntityInstance schedule = first(ctx, SCHEDULE);
        if (schedule != null) {
            ctx.changes().update(QbPublications.SCHEDULE, schedule.id(), schedule.version(),
                Map.of("versionId", version.id()));
        }
        Map<String, Object> past = new LinkedHashMap<>();
        past.put("publicationId", publication.id());
        past.put("quizId", publication.get("quizId"));
        past.put("versionId", publication.get("versionId"));
        past.put("replacedAt", ctx.opTime());
        ctx.changes().insert(QbPublications.PAST_VERSION, past);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("versionId", version.id());
        values.put("versionLabel", version.get("label"));
        values.put("approvalRequestId", requestId);
        values.putAll(cleared());
        PublicationEditing.write(ctx, publication, values);
    }

    /** The fields of a switch in review, cleared. */
    static Map<String, Object> cleared() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("pendingVersionId", null);
        values.put("pendingVersionLabel", null);
        values.put("pendingRequestId", null);
        values.put("pendingSince", null);
        values.put("switchRejectReason", null);
        values.put("switchRejectCode", null);
        return values;
    }

    // ---- withdraw a switch -----------------------------------------------------------------------------------------

    public static ProcessDefinition<WithdrawInput, PublicationOutput, ProcessContext> withdraw() {
        return ProcessDefinition.define(SWITCH_WITHDRAW, 1, WithdrawInput.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Withdraws a switch of version still in review; the publication keeps its version.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .actsOn(QbPublications.PUBLICATION, "publicationId")
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(CallerChecks::refuseBanned)
                .steps(PublicationEditing::lockAndLoad)
                .compute("Check", (metadata, ctx) -> {
                    EntityInstance publication = publication(ctx);
                    if (!PublicationEditing.current(ctx, publication, ctx.get(INPUT, WithdrawInput.class)
                        .baseRevision())) {
                        return;
                    }
                    if (publication.get("pendingRequestId") == null) {
                        ctx.reject(new Violation("publicationId", PublishingCodes.SWITCH_NOT_PENDING, "No switch of "
                            + "version is in review"));
                    }
                })
                .step("Withdraw the review", WithdrawApproval.<ProcessContext>when(ctx -> !ctx.hasViolations(),
                    PublicationApprovals.SUBJECT, ctx -> publication(ctx).id()))
                .compute("Withdraw", (metadata, ctx) -> {
                    if (!ctx.hasViolations()) {
                        PublicationEditing.write(ctx, publication(ctx), cleared());
                    }
                }));
    }

    // ---- the review's result (QB_PUBLICATION_APPROVAL_RESULT) ------------------------------------------------------

    /** Loads the version a switch in review asks for and its quiz (no statement without one). */
    static <I, O> void loadPending(com.jabiz.process.ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        String found) {
        pb.step("Load the version asked for", QueryEntities.<ProcessContext>of(QbContent.defaultDataset(
                QbContent.VERSION), ctx -> {
                EntityInstance publication = first(ctx, found);
                return EntityQuery.builder().where(new QueryPredicate.In("versionId",
                    ids(publication == null ? null : publication.get("pendingVersionId")))).limit(1).build();
            }, PENDING_VERSIONS))
            .step("Load its quiz", QueryEntities.<ProcessContext>of(QbContent.defaultDataset(QbContent.QUIZ),
                ctx -> {
                    EntityInstance version = first(ctx, PENDING_VERSIONS);
                    return EntityQuery.builder().where(new QueryPredicate.In("quizId",
                        ids(version == null ? null : version.get("quizId")))).limit(1).build();
                }, PENDING_QUIZZES));
    }

    /** Whether the event is about the switch the publication has in review. */
    static boolean ownSwitch(EntityInstance publication, String requestId) {
        return publication != null && requestId != null && requestId.equals(publication.get("pendingRequestId"));
    }

    /** The approval case of the switch in review: the publication with the version it asks for. */
    static ApprovalCase switchCase(ProcessContext ctx, EntityInstance publication) {
        EntityInstance version = first(ctx, PENDING_VERSIONS);
        return PublicationEditing.approvalCase(publication, publication.get("pendingVersionId"), list(ctx, TIERS),
            version, false, false);
    }

    /**
     * After the review of a switch: approved for the switch's content, it is made while the publication still runs and
     * may (not ended, not taken down, its sponsor not banned, the quiz not removed); otherwise the switch is dropped
     * with the reason. Rejected for that content, it is dropped with the reviewer's reason.
     */
    static void result(ProcessContext ctx, EntityInstance publication, PublicationLifecycle.ApprovalResultInput input,
        EntityInstance request) {
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval.status() != ApprovalOutcome.Status.APPROVED || !input.requestId().equals(approval.requestId())) {
                PublicationEditing.unchanged(ctx, publication);
                return;
            }
            String dropped = !RUNNING.contains(String.valueOf((Object) publication.get("status")))
                ? PublishingCodes.SWITCH_ENDED
                : PublicationAdmin.takenDown(publication) ? PublishingCodes.SWITCH_TAKEN_DOWN
                : PublicationEditing.sponsorBanned(ctx) ? AccountCodes.USER_BANNED
                : quizRemoved(ctx) ? PublishingCodes.QUIZ_REMOVED : null;
            if (dropped != null) {
                Map<String, Object> values = cleared();
                values.put("switchRejectCode", dropped);
                PublicationEditing.write(ctx, publication, values);
                return;
            }
            make(ctx, publication, first(ctx, PENDING_VERSIONS), approval.requestId());
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, publication, request)) {
            Map<String, Object> values = cleared();
            values.put("switchRejectReason", input.reason());
            PublicationEditing.write(ctx, publication, values);
        } else {
            PublicationEditing.unchanged(ctx, publication);
        }
    }

    private static boolean quizRemoved(ProcessContext ctx) {
        EntityInstance quiz = first(ctx, PENDING_QUIZZES);
        return quiz == null || Boolean.TRUE.equals(quiz.get("removed"));
    }

    /** Whether the stored request (not the event) says that the switch's content was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance publication, EntityInstance request) {
        if (request == null) {
            return false;
        }
        String hash = PublicationApprovals.hash(switchCase(ctx, publication).content());
        return ApprovalEntities.REJECTED.equals(request.get("status"))
            && PublicationApprovals.SUBJECT.equals(request.get("subject"))
            && String.valueOf(publication.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }
}
