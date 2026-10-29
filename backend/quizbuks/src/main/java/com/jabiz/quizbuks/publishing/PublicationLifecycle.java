package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.reward.DigitalPrizeStatus;
import com.jabiz.quizbuks.reward.QbDigitalPrizes;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.content.QbContent;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.APPROVAL;
import static com.jabiz.quizbuks.publishing.PublicationEditing.CASE;
import static com.jabiz.quizbuks.publishing.PublicationEditing.FOUND;
import static com.jabiz.quizbuks.publishing.PublicationEditing.INPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUBLICATION;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUB_ID;
import static com.jabiz.quizbuks.publishing.PublicationEditing.TIERS;
import static com.jabiz.quizbuks.publishing.PublicationEditing.VERSIONS;
import static com.jabiz.quizbuks.publishing.PublicationEditing.first;
import static com.jabiz.quizbuks.publishing.PublicationEditing.instant;
import static com.jabiz.quizbuks.publishing.PublicationEditing.list;
import static com.jabiz.quizbuks.publishing.PublicationEditing.uuid;
import static com.jabiz.quizbuks.publishing.PublicationStatus.DRAFT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.ENDED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.FUNDS_SHORT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.LIVE;
import static com.jabiz.quizbuks.publishing.PublicationStatus.PAUSED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.PENDING_REVIEW;
import static com.jabiz.quizbuks.publishing.PublicationStatus.SCHEDULED;

/**
 * A publication's way from submission to its end (M-30, M-31, M-35, P-09, 5.3-2; docs/quizbuks/plans/
 * Q4-publishing.md, D-Q4-1, D-Q4-4, D-Q4-7):
 * <ul>
 *   <li>{@code QB_PUBLICATION_SUBMIT}: checks the draft (every problem at once), then asks for a review; goes on at
 *       once when the review is skipped by the controlled parameter or the same content was approved before;</li>
 *   <li>{@code QB_PUBLICATION_WITHDRAW}: takes a publication out of review, back to its draft;</li>
 *   <li>{@code QB_PUBLICATION_PAUSE} / {@code QB_PUBLICATION_RESUME}: stops new attempts at once; resumes to where
 *       the time says;</li>
 *   <li>{@code QB_PUBLICATION_APPROVAL_RESULT}: run for the approval events: an approval of the content under review
 *       puts the publication on, a rejection sends it back with the reviewer's reason;</li>
 *   <li>{@code QB_PUBLICATION_ADVANCE}, run for each item of the minute's job {@value #TICK_JOB}: the publications
 *       whose start or end has come move on, those missed while the system was down included.</li>
 * </ul>
 * Locks, always in this order (D-Q4-5): the sponsor's account ({@code qb.account:<sponsor>}, by the approval result
 * only: a ban and an approval take turns, Q10b review L1), the quiz's ({@code qb.quiz:<quizId>}, shared, by submission
 * only), then the publication's ({@code qb.pub:<publicationId>}); Q5 takes the wallet's after them.
 *
 * <p>A publication of a quiz one of whose publications an administrator took down (Q10b, review M-d) goes on only
 * after a person's review: its case says {@code autoApprove = false} and {@code takenDown = true} whatever the
 * parameter says, and when no review is asked for all the same (a rule exempts it, or an earlier approval of the same
 * content would be reused) the submission is refused ({@code QB_PUBLICATION_REVIEW_REQUIRED}).
 */
public final class PublicationLifecycle {

    public static final String SUBMIT = "QB_PUBLICATION_SUBMIT";
    public static final String WITHDRAW = "QB_PUBLICATION_WITHDRAW";
    public static final String PAUSE = "QB_PUBLICATION_PAUSE";
    public static final String RESUME = "QB_PUBLICATION_RESUME";
    public static final String APPROVAL_RESULT = "QB_PUBLICATION_APPROVAL_RESULT";
    public static final String ADVANCE = "QB_PUBLICATION_ADVANCE";

    /**
     * The minute's per-item job that moves publications on: {@code QB_PUBLICATION_ADVANCE} for each publication
     * {@link #DUE_TEMPLATE} selects, in a transaction of its own (Q5b; platform decision D42).
     */
    public static final String TICK_JOB = "qb.publication-tick";

    /** The publications whose start or end has come by a time, from the schedule ({@code nextDueAt}). */
    public static final String DUE_TEMPLATE = "qb.system.publications-due";

    public record PublicationRef(@NotNull UUID publicationId) {}

    /**
     * Input of {@code QB_PUBLICATION_APPROVAL_RESULT}, from an approval event of any subject.
     *
     * @param requestId the request decided: only the publication's own request (its {@code approvalRequestId}) counts
     */
    public record ApprovalResultInput(@NotBlank String subject, @NotBlank String entityId, @NotBlank String status,
        String requestId, String preparerId, String contentHash, String reason) {}

    static final String FIRST = "first";
    /** Q33: the available codes of a pooled publication, loaded at submission. */
    static final String POOL = "pool";
    /** Whether an administrator took down another publication of the submitted one's quiz (Q10b, M-d). */
    static final String TAKEN_DOWN = "takenDown";
    static final String PARAMS = "params";
    static final String REQUEST = "request";

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublicationProcesses.PublicationRef, PublicationOutput, ProcessContext> submit() {
        return ProcessDefinition.define(SUBMIT, 1, PublicationProcesses.PublicationRef.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Submits a draft publication for review; it goes on at once when no review is needed.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .actsOn(QbPublications.PUBLICATION, "publicationId", a -> a.whenField("status", DRAFT))
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                // A contract action: the sponsors' terms and the privacy policy in effect are accepted (Q7b, Q7-2).
                .steps(ConsentChecks.sponsor())
                // The quiz's lock comes first (D-Q4-5), and only the publication tells which quiz: read it, lock
                // both, read it again. Only a save under the publication's lock changes its quiz.
                .step("Find the publication's quiz", LoadEntity.by(
                    QbPublications.sponsorDataset(QbPublications.PUBLICATION), PUB_ID, FIRST))
                .step("Lock the quiz", HoldLock.<ProcessContext>shared(
                    ctx -> QbContent.lockName(ctx.get(FIRST, EntityInstance.class).get("quizId"))))
                .steps(PublicationEditing::lockAndLoad)
                .steps(PublicationEditing::loadSponsor)
                .steps(PublicationEditing::loadSponsorMethods)
                .steps(b -> PublicationEditing.loadSponsorPublications(b, PublicationEditing::publication))
                .steps(b -> PublicationEditing.loadSponsorProfile(b, PublicationEditing::publication))
                .step("Load the review switch", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.REVIEW_PUBLICATION_AUTO))
                .steps(PublicationEditing::loadTiers)
                .steps(b -> PublicationEditing.loadVersion(b,
                    ctx -> PublicationEditing.publication(ctx).get("versionId"), true))
                // Q33: a digital prize from a pool promises each winner a code: the pool is counted (no statement
                // for the other publications).
                .step("Load the pool", QueryEntities.<ProcessContext>of(
                    QbDigitalPrizes.defaultDataset(QbDigitalPrizes.DIGITAL_CODE), ctx -> {
                        EntityInstance publication = PublicationEditing.publication(ctx);
                        boolean pooled = publication != null
                            && PublicationEditing.draftOf(publication).pooled();
                        // One AND (the builder's where replaces): not pooled matches nothing, no statement.
                        return EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.In("publicationId", pooled ? List.of(publication.id()) : List.of()),
                                new QueryPredicate.Eq("status", DigitalPrizeStatus.CODE_AVAILABLE))))
                            .limit(PublishingLimits.MAX_POOL_WINNERS).build();
                    }, POOL))
                .compute("Check the publication", (metadata, ctx) -> check(ctx))
                .step("Ask for a review", RequireApproval.<ProcessContext>when(ctx -> ctx.contains(CASE),
                    PublicationApprovals.SUBJECT, ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL))
                .compute("Submit", (metadata, ctx) -> submitted(ctx)));
    }

    private static boolean autoApprove(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(PARAMS, ParamValues.class).get(QbParams.REVIEW_PUBLICATION_AUTO,
            Boolean.class));
    }

    static void check(ProcessContext ctx) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        if (!Objects.equals(String.valueOf((Object) ctx.get(FIRST, EntityInstance.class).get("quizId")),
            String.valueOf((Object) publication.get("quizId")))) {
            throw new ConcurrentUpdateException(QbPublications.PUBLICATION + " " + publication.id()
                + " changed its quiz meanwhile");
        }
        PublicationProcesses.PublicationRef input = ctx.get(INPUT, PublicationProcesses.PublicationRef.class);
        if (!PublicationEditing.draft(ctx, publication)
            || !PublicationEditing.current(ctx, publication, input.baseRevision())) {
            return;
        }
        Object versionId = publication.get("versionId");
        EntityInstance version = versionId == null ? null : PublicationEditing.requireVersion(ctx, versionId);
        if (ctx.hasViolations()) {
            return;
        }
        PublicationReadiness.check(PublicationEditing.draftOf(publication),
            PublicationEditing.tiersOf(list(ctx, TIERS)), PublicationEditing.snapshot(version), ctx.opTime(),
            PublicationEditing.maxRewardPerAttempt(ctx)).forEach(ctx::reject);
        PublicationReadiness.pool(PublicationEditing.draftOf(publication), list(ctx, POOL).size())
            .forEach(ctx::reject);
        PublicationEditing.sponsorProblems(ctx).forEach(ctx::reject);
        PublicationEditing.methodProblems(ctx, PublicationEditing.methodOf(publication.attributes()))
            .forEach(ctx::reject);
        if (!ctx.hasViolations()) {
            boolean takenDown = !PublicationEditing.takenDownOfQuiz(ctx, publication).isEmpty();
            ctx.put(TAKEN_DOWN, takenDown);
            ctx.put(CASE, PublicationEditing.approvalCase(publication, list(ctx, TIERS), version, autoApprove(ctx),
                takenDown));
        }
    }

    static void submitted(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        EntityInstance publication = PublicationEditing.publication(ctx);
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval.status() != ApprovalOutcome.Status.PENDING && Boolean.TRUE.equals(ctx.get(TAKEN_DOWN))) {
            // Review M-d: after a takedown of the quiz only a person's review lets a publication of it go on.
            ctx.reject(new Violation(null, PublishingCodes.REVIEW_REQUIRED, "An administrator took down a "
                + "publication of this quiz: this one goes on only after a review by a person, which none asks for",
                Map.of("rule", PublicationApprovals.RULE)));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("submittedAt", ctx.opTime());
        switch (approval.status()) {
            case PENDING -> {
                values.put("status", PENDING_REVIEW);
                values.put("approvalRequestId", approval.requestId());
                values.put("rejectReason", null);
                values.put("rejectCode", null);
            }
            case NOT_REQUIRED -> {
                // No rule asked for a review. Unless the switch says so, that means the rule is missing or off:
                // refuse rather than let it through unreviewed (platform gap 5 of the plan).
                if (!autoApprove(ctx)) {
                    ctx.reject(new Violation(null, PublishingCodes.REVIEW_NOT_CONFIGURED, "Publications need a "
                        + "review, but no review rule is in effect", Map.of("rule", PublicationApprovals.RULE)));
                    return;
                }
                values.putAll(PublicationEditing.goingOn(ctx, publication, null));
            }
            case APPROVED -> values.putAll(PublicationEditing.goingOn(ctx, publication, approval.requestId()));
            default -> throw new IllegalStateException("Unknown approval outcome " + approval.status());
        }
        PublicationEditing.write(ctx, publication, values);
    }

    // ---- withdraw, pause, resume -----------------------------------------------------------------------------------

    public static ProcessDefinition<PublicationRef, PublicationOutput, ProcessContext> withdraw() {
        return ProcessDefinition.define(WITHDRAW, 1, PublicationRef.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Takes a publication out of review, back to its draft.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbPublications.PUBLICATION, "publicationId", a -> a.whenField("status", PENDING_REVIEW))
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(PublicationEditing::lockAndLoad)
                .step("Withdraw the review", WithdrawApproval.<ProcessContext>of(PublicationApprovals.SUBJECT,
                    ctx -> PublicationEditing.publication(ctx).id()))
                .compute("Back to the draft", (metadata, ctx) -> {
                    EntityInstance publication = PublicationEditing.publication(ctx);
                    if (PublicationEditing.in(ctx, publication, DRAFT, PENDING_REVIEW)) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("status", DRAFT);
                        values.put("approvalRequestId", null);
                        PublicationEditing.write(ctx, publication, values);
                    }
                }));
    }

    public static ProcessDefinition<PublicationRef, PublicationOutput, ProcessContext> pause() {
        return ProcessDefinition.define(PAUSE, 1, PublicationRef.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Pauses a scheduled, live or underfunded publication: it takes no new attempts until "
                    + "resumed.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbPublications.PUBLICATION, "publicationId",
                    a -> a.whenField("status", SCHEDULED, LIVE, FUNDS_SHORT))
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                // The start lock too (Q10a4): no start gets in once the pause commits.
                .steps(PublicationEditing::lockStatesAndLoad)
                .compute("Pause", (metadata, ctx) -> {
                    EntityInstance publication = PublicationEditing.publication(ctx);
                    // An underfunded one too (Q5, D-Q5-5): resuming it goes by the time, and the next reward its
                    // sponsor cannot pay makes it underfunded again.
                    if (PublicationEditing.in(ctx, publication, PAUSED, SCHEDULED, LIVE, FUNDS_SHORT)) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("status", PAUSED);
                        values.put("pausedAt", ctx.opTime());
                        values.put("pausedBy", PublicationStatus.BY_SPONSOR);
                        PublicationEditing.write(ctx, publication, values);
                    }
                }));
    }

    public static ProcessDefinition<PublicationRef, PublicationOutput, ProcessContext> resume() {
        return ProcessDefinition.define(RESUME, 1, PublicationRef.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Resumes a paused publication: scheduled before its start, live within its time, "
                    + "ended after its end.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbPublications.PUBLICATION, "publicationId", a -> a.whenField("status", PAUSED))
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(PublicationEditing::lockStatesAndLoad)
                .compute("Resume", (metadata, ctx) -> {
                    EntityInstance publication = PublicationEditing.publication(ctx);
                    // What an administrator paused only an administrator resumes (Q10b, M2).
                    if (PAUSED.equals(publication.get("status"))
                        && PublicationStatus.BY_ADMIN.equals(publication.get("pausedBy"))) {
                        ctx.reject(new Violation("publicationId", PublishingCodes.ADMIN_PAUSED, "An administrator "
                            + "paused this publication; only an administrator can resume it", Map.of()));
                        return;
                    }
                    resumed(ctx, publication, null);
                }));
    }

    /**
     * Resumes a paused publication to where its times say ({@link PublicationRules#resumeTo}); an administrator's
     * resumption records the reason ({@code adminReason} not null).
     */
    static void resumed(ProcessContext ctx, EntityInstance publication, String adminReason) {
        String to = PublicationRules.resumeTo(instant(publication.get("startAt")),
            instant(publication.get("endAt")), ctx.opTime());
        if (PublicationEditing.in(ctx, publication, to, PAUSED)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", to);
            values.put("pausedAt", null);
            values.put("pausedBy", null);
            if (adminReason != null) {
                values.put("adminReason", adminReason);
                // Q48a: the takedown is over, whoever made it.
                values.put("takedownOperatorId", null);
            }
            ended(ctx, values, to);
            PublicationEditing.write(ctx, publication, values);
        }
    }

    /** A publication that ends with its time: when, and why. */
    static void ended(ProcessContext ctx, Map<String, Object> values, String to) {
        if (ENDED.equals(to)) {
            values.put("endedAt", ctx.opTime());
            values.put("endReason", PublicationStatus.EXPIRED);
        }
    }

    // ---- approval result -------------------------------------------------------------------------------------------

    /**
     * Continues after a review is decided (subscribed to both approval events, run as the system; the permission is
     * granted to no role). Only the publication's own request counts: the event must name the request the publication
     * waits for. An approval goes through only when {@link RequireApproval} finds that same request approved for the
     * publication's content; a rejection only when the stored request is rejected, for that content. Anything else
     * (another subject, a stale event, a forged call) changes nothing.
     */
    public static ProcessDefinition<ApprovalResultInput, PublicationOutput, ProcessContext> approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Moves a publication on after its review is decided.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> PublicationEditing.start(start, input,
                    PublicationApprovals.SUBJECT.equals(input.subject()) ? uuidOrNull(input.entityId()) : null))
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, PublicationOutput.class)
                    : new PublicationOutput(ctx.get(PUB_ID, UUID.class), null, 0, null, null))
                // Events of other subjects lock and find nothing. The sponsor's account first (review L1): a ban
                // reads the sponsor's publications under it, so a ban and an approval take turns, and the approval
                // sees the ban or the ban the publication on. The sponsor never changes: read before the locks.
                .steps(PublicationEditing::lockSponsorAccount)
                .step("Lock the publication", HoldLock.<ProcessContext>exclusive(
                    ctx -> PublicationEditing.lockName(ctx.get(PUB_ID))))
                .step("Find the publication", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.PUBLICATION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("publicationId", PublicationEditing.ids(ctx.get(PUB_ID))))
                        .limit(1).build(), FOUND))
                .steps(PublicationEditing::loadTiers)
                .steps(b -> PublicationEditing.loadVersion(b, ctx -> {
                    EntityInstance found = first(ctx, FOUND);
                    return found == null ? null : found.get("versionId");
                }, false))
                .steps(PublicationEditing::loadSchedule)
                .steps(b -> PublicationEditing.loadSponsorProfile(b, ctx -> first(ctx, FOUND)))
                .steps(b -> PublicationEditing.loadSponsorBan(b, ctx -> first(ctx, FOUND)))
                .step("Load the request", QueryEntities.<ProcessContext>of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        ownRequest(ctx) ? PublicationEditing.ids(input(ctx).requestId()) : new ArrayList<>()))
                        .limit(1).build(), REQUEST))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(input(ctx).status()),
                    PublicationApprovals.SUBJECT, ctx -> PublicationEditing.approvalCase(first(ctx, FOUND),
                        list(ctx, TIERS), first(ctx, VERSIONS), false).preparedBy(input(ctx).preparerId()),
                    APPROVAL))
                .compute("Move on", (metadata, ctx) -> approvalResult(ctx))
                .steps(PublicationNotices::announce));
    }

    /** Whether the event is about the request the publication, still under review, waits for. */
    private static boolean ownRequest(ProcessContext ctx) {
        return underReview(ctx) && input(ctx).requestId() != null
            && input(ctx).requestId().equals(first(ctx, FOUND).get("approvalRequestId"));
    }

    private static UUID uuidOrNull(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApprovalResultInput input(ProcessContext ctx) {
        return ctx.get(INPUT, ApprovalResultInput.class);
    }

    private static boolean underReview(ProcessContext ctx) {
        EntityInstance found = first(ctx, FOUND);
        return found != null && PENDING_REVIEW.equals(found.get("status"));
    }

    static void approvalResult(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance publication = first(ctx, FOUND);
        ApprovalResultInput input = input(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                PublicationEditing.unchanged(ctx, publication);
                return;
            }
            if (PublicationEditing.sponsorBanned(ctx)) {
                // Banned while it was reviewed (Q10b, M2): a banned sponsor's publication does not go on.
                values.put("status", DRAFT);
                values.put("rejectCode", com.jabiz.quizbuks.identity.AccountCodes.USER_BANNED);
                values.put("rejectReason", null);
            } else if (ctx.opTime().isBefore(instant(publication.get("endAt")))) {
                values.putAll(PublicationEditing.goingOn(ctx, publication, approval.requestId()));
            } else {
                // Its time ran out while it was reviewed (question Q4-5): back to the draft, with the reason.
                values.put("status", DRAFT);
                values.put("rejectCode", PublishingCodes.EXPIRED_IN_REVIEW);
                values.put("rejectReason", null);
            }
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, publication)) {
            values.put("status", DRAFT);
            values.put("rejectReason", input.reason());
            values.put("rejectCode", null);
        } else {
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        PublicationEditing.write(ctx, publication, values);
    }

    /** Whether the stored request (not the event) says that the publication's present content was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance publication) {
        EntityInstance request = first(ctx, REQUEST);
        if (request == null) {
            return false;
        }
        String hash = PublicationApprovals.hash(PublicationEditing.approvalCase(publication, list(ctx, TIERS),
            first(ctx, VERSIONS), false).content());
        return ApprovalEntities.REJECTED.equals(request.get("status"))
            && PublicationApprovals.SUBJECT.equals(request.get("subject"))
            && String.valueOf(publication.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }

    // ---- advance (the items of the minute's job) -------------------------------------------------------------------

    /**
     * {@code QB_PUBLICATION_ADVANCE}: under the publication's lock, moves it as its times say
     * ({@link PublicationRules#next}); one changed meanwhile (paused, say) moves as its new state says, or not at all.
     */
    public static ProcessDefinition<PublicationRef, PublicationOutput, ProcessContext> advance() {
        return ProcessDefinition.define(ADVANCE, 1, PublicationRef.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Moves one publication on as its start and end times say.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .step("Lock the publication", HoldLock.<ProcessContext>exclusive(
                    ctx -> PublicationEditing.lockName(ctx.get(PUB_ID))))
                // It changes the state of a publication that went on: the start lock too (Q10a4, LS-4).
                .steps(PublicationEditing::lockStarts)
                .step("Load the publication", LoadEntity.by(
                    QbPublications.defaultDataset(QbPublications.PUBLICATION), PUB_ID, PUBLICATION))
                .steps(PublicationEditing::loadSchedule)
                // Only a publication still in review has a pending request: one past its end loses it. Only then is
                // the approval's case lock (jabiz.approval:<subject>:<publication>) taken: the lock order puts it
                // after the publication's pair, and every other run of the tick takes no lock after the start lock.
                .step("Withdraw a review that came too late", WithdrawApproval.<ProcessContext>when(
                    PublicationLifecycle::expiredInReview, PublicationApprovals.SUBJECT,
                    ctx -> PublicationEditing.publication(ctx).id()))
                .compute("Move on", (metadata, ctx) -> advance(ctx))
                .steps(PublicationNotices::announce));
    }

    private static boolean expiredInReview(ProcessContext ctx) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        return PENDING_REVIEW.equals(publication.get("status")) && DRAFT.equals(PublicationRules.next(
            PENDING_REVIEW, instant(publication.get("startAt")), instant(publication.get("endAt")), ctx.opTime()));
    }

    static void advance(ProcessContext ctx) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        String status = publication.get("status");
        String to = PublicationRules.next(status, instant(publication.get("startAt")),
            instant(publication.get("endAt")), ctx.opTime());
        if (to.equals(status)) {
            // A due time that no longer fits the state is put right, so the tick does not find it again.
            PublicationEditing.schedule(ctx, publication, status);
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", to);
        if (DRAFT.equals(to)) {
            // Still in review at its end (R3): nothing left to approve.
            values.put("rejectCode", PublishingCodes.EXPIRED_IN_REVIEW);
            values.put("rejectReason", null);
        }
        ended(ctx, values, to);
        PublicationEditing.write(ctx, publication, values);
    }

    /** The input of {@code QB_PUBLICATION_APPROVAL_RESULT} from an approval event. */
    static ApprovalResultInput resultInput(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        return new ApprovalResultInput(text(payload, "subject"), text(payload, "entityId"), text(payload, "status"),
            text(payload, "requestId"), text(payload, "preparerId"), text(payload, "contentHash"), text(payload, "reason"));
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private PublicationLifecycle() {}
}
