package com.jabiz.quizbuks.page;

import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.ReviewCodes;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.file.FileEntities;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.publicread.FileAccess;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A sponsor's page (docs/quizbuks/plans/Q60-sponsor-page.md, Q60a):
 * <ul>
 *   <li>{@value #SAVE}: the sponsor writes it (a first save makes the draft). A published page changes at once; a page
 *       in review goes back to its draft (the review is withdrawn), since the reviewer would approve other text;</li>
 *   <li>{@value #SUBMIT}: a draft, a page sent back or one taken down goes to review (the onboarding's switch skips it,
 *       never after a takedown); only an approved sponsor's;</li>
 *   <li>{@value #APPROVAL_RESULT}: run for the approval events, as the system: publishes or sends back the page whose
 *       own request was decided, still bound to the page as stored;</li>
 *   <li>{@value #UNPUBLISH}: the sponsor takes it back to the draft (a pending review is withdrawn);</li>
 *   <li>{@value #TAKEDOWN} / {@value #OPERATOR_TAKEDOWN}: headquarters, or the sponsor's operator, takes a published
 *       page down with a reason the sponsor reads.</li>
 * </ul>
 * Every write takes the sponsor's account lock first (as the onboarding and the ban do), then a handle's.
 */
public final class SponsorPageProcesses {

    public static final String SAVE = "QB_SPONSOR_PAGE_SAVE";
    public static final String SUBMIT = "QB_SPONSOR_PAGE_SUBMIT";
    public static final String APPROVAL_RESULT = "QB_SPONSOR_PAGE_APPROVAL_RESULT";
    public static final String UNPUBLISH = "QB_SPONSOR_PAGE_UNPUBLISH";
    public static final String TAKEDOWN = "QB_SPONSOR_PAGE_TAKEDOWN";
    public static final String OPERATOR_TAKEDOWN = "QB_OPERATOR_SPONSOR_PAGE_TAKEDOWN";

    /**
     * No logo and no story (Q65b): the page shows the onboarding's avatar and introduction, which the sponsor edits in
     * the basic profile; a caller still sending them is not heard (unknown input properties are ignored).
     *
     * @param handle       the name in the page's link ({@code /s/<handle>}); case does not matter
     * @param language     the page's one language
     * @param cover        a file of the policy {@code qb.sponsor-page-image} the caller uploaded; none to have none
     * @param website      an https link
     * @param baseRevision the revision the caller edited; when given and no longer current, 422
     */
    public record SaveInput(@NotBlank @Size(max = QbSponsorPages.HANDLE_LENGTH) String handle, @NotBlank String language, @NotBlank String headline,
        UUID cover, String website, Long baseRevision) {}

    /** @param baseRevision the revision the caller submits; when given and no longer current, 422 */
    public record SubmitInput(Long baseRevision) {}

    public record UnpublishInput(Long baseRevision) {}

    /** @param reason why, as the sponsor will read it */
    public record TakedownInput(@NotBlank String userId,
        @NotBlank @Size(max = QbSponsorPages.REASON_LENGTH) String reason) {}

    /**
     * @param approval the approval outcome when the process asked for one ({@code PENDING}, {@code APPROVED},
     *                 {@code NOT_REQUIRED})
     */
    public record PageOutput(String userId, String handle, String status, long revision, String approval,
        String approvalRequestId) {}

    /**
     * Input of {@value #APPROVAL_RESULT}, from an approval event of any subject.
     *
     * @param requestId the request decided: only the page's own (its {@code approvalRequestId}) counts
     */
    public record ApprovalResultInput(@NotBlank String subject, @NotBlank String entityId, @NotBlank String status,
        String requestId, String preparerId, String contentHash, String reason) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String USER_ID = "pageUserId";
    static final String PAGE = "page";
    static final String HOLDER = "handleHolder";
    static final String FILES = "files";
    static final String SPONSOR = "sponsor";
    static final String ASSIGNMENT = "assignment";
    static final String PARAMS = "params";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String WITHDRAW = "withdraw";
    static final String RELEASED = "released";
    static final String FIRST = "first";
    static final String REQUEST = "request";
    static final String DECISIONS = "decisions";
    static final String OFFLINE = "offlineImages";

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, PageOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, PageOutput.class, ProcessContext.class, pb -> {
            pb.description("Saves the sponsor's page: its link name, language, headline, cover and website. "
                    + "A published page changes at once.")
                .permissions(QbPermissions.SPONSOR_ME)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, start.request().actorId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PageOutput.class));
            lockAccount(pb);
            CallerChecks.refuseBanned(pb);
            pb.step("Lock the handle", HoldLock.<ProcessContext>exclusive(
                ctx -> QbSponsorPages.handleLockName(handle(ctx))));
            pb.step("Load the page, the handle's holder and the cover", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbSponsorPages.DEFAULT_DATASET, ctx -> ofUser(userId(ctx)), PAGE),
                QueryEntities.query(QbSponsorPages.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("handle", List.of(handle(ctx)))).limit(1).build(), HOLDER),
                QueryEntities.query(FileEntities.DATASET, ctx -> {
                    UUID cover = ctx.get(INPUT, SaveInput.class).cover();
                    List<Object> ids = cover == null ? List.of() : List.of(cover);
                    return EntityQuery.builder().where(new QueryPredicate.In(FileEntities.FILE_ID, ids)).limit(1)
                        .build();
                }, FILES)));
            pb.compute("Write the page", (metadata, ctx) -> saved(ctx));
            pb.step("Withdraw the review", WithdrawApproval.<ProcessContext>when(
                ctx -> Boolean.TRUE.equals(ctx.get(WITHDRAW)), SponsorPageApprovals.SUBJECT, SponsorPageProcesses::userId));
            releaseImages(pb);
        });
    }

    static void saved(ProcessContext ctx) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        String userId = userId(ctx);
        EntityInstance page = first(ctx, PAGE);
        EntityInstance holder = first(ctx, HOLDER);
        if (holder != null && !userId.equals(holder.get("userId"))) {
            ctx.reject(new Violation("handle", SponsorPageCodes.HANDLE_TAKEN, "The handle " + handle(ctx)
                + " is another sponsor's", Map.of("handle", handle(ctx))));
        }
        current(ctx, page, input.baseRevision());
        requireOwnFile(ctx, "cover", input.cover(), page);
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("handle", handle(ctx));
        values.put("language", input.language());
        values.put("headline", input.headline());
        values.put("cover", input.cover());
        values.put("website", blankToNull(input.website()));
        if (page == null) {
            values.put("userId", userId);
            values.put("status", QbSponsorPages.DRAFT);
            values.put("reviewRequired", false);
        } else {
            if (QbSponsorPages.PENDING.equals(page.get("status"))) {
                // The reviewer would approve other text than the page now holds: back to the draft.
                values.put("status", QbSponsorPages.DRAFT);
                values.put("approvalRequestId", null);
                ctx.put(WITHDRAW, true);
            }
            Object stored = page.get("cover");
            ctx.put(RELEASED, stored == null || same(stored, input.cover()) ? List.of()
                : List.of(UUID.fromString(String.valueOf(stored))));
        }
        write(ctx, page, values, null);
    }

    /**
     * Releases the cover the save replaced or removed (root CLAUDE.md, files): the reference is saved first, then the
     * platform deletes the file, which it refuses while anything still refers to it.
     */
    private static void releaseImages(ProcessDefinitionBuilder<SaveInput, PageOutput, ProcessContext> pb) {
        pb.step("Save", SaveChanges.now());
        pb.step("Release the replaced cover", CallProcess.<ProcessContext>when(
            ctx -> !ctx.hasViolations() && !released(ctx).isEmpty(), FileProcesses.DELETE, 1,
            ctx -> new FileProcesses.DeleteInput(released(ctx).getFirst()), null));
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> released(ProcessContext ctx) {
        return ctx.contains(RELEASED) ? (List<UUID>) ctx.get(RELEASED) : List.of();
    }

    /** Refuses an image newly put on the page that the sponsor did not upload (422 {@code QB_FILE_NOT_OWN}). */
    private static void requireOwnFile(ProcessContext ctx, String field, UUID given, EntityInstance page) {
        if (given == null || page != null && same(page.get("cover"), given)) {
            return;
        }
        for (EntityInstance file : list(ctx, FILES)) {
            if (same(file.get(FileEntities.FILE_ID), given)
                && !userId(ctx).equals(file.get(FileEntities.UPLOADED_BY))) {
                ctx.reject(new Violation(field, AccountCodes.FILE_NOT_OWN, "File " + given + " was not uploaded by "
                    + "you", Map.of("fileId", given.toString())));
            }
        }
    }

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SubmitInput, PageOutput, ProcessContext> submit() {
        return ProcessDefinition.define(SUBMIT, 1, SubmitInput.class, PageOutput.class, ProcessContext.class, pb -> {
            pb.description("Submits the sponsor's page for review; published at once when reviews are switched off "
                    + "(never after a takedown).")
                .permissions(QbPermissions.SPONSOR_ME)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, start.request().actorId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PageOutput.class));
            lockAccount(pb);
            CallerChecks.refuseBanned(pb);
            pb.step("Load the page, the onboarding and the operator", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbSponsorPages.DEFAULT_DATASET, ctx -> ofUser(userId(ctx)), PAGE),
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> ofUser(userId(ctx)),
                    SPONSOR),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> ofUser(userId(ctx)),
                    ASSIGNMENT)));
            pb.step("Load the review switch", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.REVIEW_SPONSOR_AUTO));
            pb.compute("Check the page", (metadata, ctx) -> checkSubmit(ctx));
            pb.step("Ask for a review", RequireApproval.<ProcessContext>when(ctx -> ctx.contains(CASE),
                SponsorPageApprovals.SUBJECT, ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL));
            pb.compute("Submit", (metadata, ctx) -> submitted(ctx));
        });
    }

    private static boolean autoApprove(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(PARAMS, ParamValues.class).get(QbParams.REVIEW_SPONSOR_AUTO,
            Boolean.class));
    }

    static void checkSubmit(ProcessContext ctx) {
        EntityInstance page = requirePage(ctx);
        EntityInstance sponsor = first(ctx, SPONSOR);
        if (sponsor == null || !SponsorStatus.APPROVED.equals(sponsor.get("reviewStatus"))) {
            ctx.reject(new Violation(null, SponsorPageCodes.SPONSOR_NOT_APPROVED, "Only an approved sponsor "
                + "publishes a page", Map.of()));
        }
        in(ctx, page, QbSponsorPages.DRAFT, QbSponsorPages.REJECTED, QbSponsorPages.TAKEN_DOWN);
        current(ctx, page, ctx.get(INPUT, SubmitInput.class).baseRevision());
        if (ctx.hasViolations()) {
            return;
        }
        ApprovalCase review = ApprovalCase.of(page.id(),
            SponsorPageApprovals.facts(autoApprove(ctx) && !underTakedown(page)),
            SponsorPageApprovals.content(page)).reference(page.get("handle"));
        // The review is the operator's who looks after the sponsor (Q59c-2); headquarters' for a direct sponsor.
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        ctx.put(CASE, assignment == null ? review
            : review.scope(QbOperators.SCOPE, String.valueOf((Object) assignment.get("operatorId"))));
    }

    static void submitted(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        EntityInstance page = first(ctx, PAGE);
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("submittedAt", ctx.opTime());
        if (approval.status() != ApprovalOutcome.Status.PENDING
            && underTakedown(page)) {
            ctx.reject(new Violation(null, SponsorPageCodes.REVIEW_REQUIRED, "The page was taken down: it goes back "
                + "only after a review by a person, which none asks for", Map.of("rule", SponsorPageApprovals.RULE)));
            return;
        }
        switch (approval.status()) {
            case PENDING -> {
                values.put("status", QbSponsorPages.PENDING);
                values.put("approvalRequestId", approval.requestId());
            }
            case NOT_REQUIRED -> {
                // No rule asked for a review. Unless the switch says so, the rule is missing or off: refuse rather
                // than publish unreviewed.
                if (!autoApprove(ctx)) {
                    ctx.reject(new Violation(null, ReviewCodes.REVIEW_NOT_CONFIGURED, "Sponsor pages need a review, "
                        + "but no review rule is in effect", Map.of("rule", SponsorPageApprovals.RULE)));
                    return;
                }
                publish(ctx, values, null);
            }
            case APPROVED -> publish(ctx, values, approval.requestId());
            default -> throw new IllegalStateException("Unknown approval outcome " + approval.status());
        }
        write(ctx, page, values, approval.status().name());
    }

    /** Whether the page was taken down and not published since: it goes back only through a person's review. */
    static boolean underTakedown(EntityInstance page) {
        return Boolean.TRUE.equals(page.get("reviewRequired"));
    }

    private static void publish(ProcessContext ctx, Map<String, Object> values, String requestId) {
        values.put("status", QbSponsorPages.PUBLISHED);
        values.put("approvalRequestId", requestId);
        values.put("publishedAt", ctx.opTime());
        values.put("rejectReason", null);
        values.put("reviewRequired", false);
    }

    // ---- approval result -------------------------------------------------------------------------------------------

    /**
     * Continues after a review is decided (subscribed to both approval events, run as the system; the permission is
     * granted to no role). Only the page's own request counts: the event must name the request the page, still
     * pending, waits for, and the stored request must be bound to the page as stored. An approval goes through only
     * when {@link RequireApproval} finds that request approved; a rejection only when the stored request is rejected,
     * with the reviewer's reason. Anything else changes nothing.
     */
    public static ProcessDefinition<ApprovalResultInput, PageOutput, ProcessContext> approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, PageOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Publishes or sends back a sponsor's page after its review is decided.")
                    .permissions(QbPermissions.SPONSOR_SYSTEM)
                    .internal()
                    .contextFactory((start, input) -> start(start, input,
                        SponsorPageApprovals.SUBJECT.equals(input.subject()) ? input.entityId() : null))
                    .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, PageOutput.class)
                        : new PageOutput(userId(ctx), null, null, 0, null, null));
                // Events of other subjects name no page: they find nothing and lock nothing.
                pb.step("Find the page", QueryEntities.<ProcessContext>of(QbSponsorPages.DEFAULT_DATASET,
                    ctx -> ofUser(userId(ctx)), FIRST));
                pb.step("Lock the account", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FIRST) == null
                    ? null : QbAccounts.lockName(userId(ctx))));
                pb.step("Load the page", QueryEntities.<ProcessContext>of(QbSponsorPages.DEFAULT_DATASET,
                    ctx -> ofUser(userId(ctx)), PAGE));
                pb.step("Load the request", QueryEntities.<ProcessContext>of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        ownRequest(ctx) ? AccountEditing.uuids(result(ctx).requestId()) : new ArrayList<>()))
                        .limit(1).build(), REQUEST));
                pb.step("Load the decisions", QueryEntities.<ProcessContext>of(ApprovalEntities.DECISION_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        storedRequest(ctx) ? AccountEditing.uuids(result(ctx).requestId()) : new ArrayList<>()))
                        .limit(Integer.MAX_VALUE).build(), DECISIONS));
                pb.step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> boundRequest(ctx) && ApprovalEntities.APPROVED.equals(result(ctx).status()),
                    SponsorPageApprovals.SUBJECT, ctx -> storedCase(ctx).preparedBy(result(ctx).preparerId()),
                    APPROVAL));
                pb.compute("Publish or send back", (metadata, ctx) -> approvalResult(ctx));
            });
    }

    private static ApprovalResultInput result(ProcessContext ctx) {
        return ctx.get(INPUT, ApprovalResultInput.class);
    }

    /** Whether the event is about the request the page, still pending, waits for. */
    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance page = first(ctx, PAGE);
        return page != null && QbSponsorPages.PENDING.equals(page.get("status"))
            && result(ctx).requestId() != null && result(ctx).requestId().equals(page.get("approvalRequestId"));
    }

    /** Whether the stored request (not the event) is the page's own: of this subject and page. */
    private static boolean storedRequest(ProcessContext ctx) {
        EntityInstance request = first(ctx, REQUEST);
        return ownRequest(ctx) && request != null && SponsorPageApprovals.SUBJECT.equals(request.get("subject"))
            && String.valueOf(first(ctx, PAGE).id()).equals(request.get("entityId"));
    }

    /** Whether the stored request is bound to the page as stored, which an approval needs. */
    private static boolean boundRequest(ProcessContext ctx) {
        return storedRequest(ctx) && SponsorPageApprovals.hash(storedCase(ctx).content())
            .equals(String.valueOf((Object) first(ctx, REQUEST).get("contentHash")).strip());
    }

    private static ApprovalCase storedCase(ProcessContext ctx) {
        EntityInstance page = first(ctx, PAGE);
        return ApprovalCase.of(page.id(), SponsorPageApprovals.facts(false), SponsorPageApprovals.content(page))
            .reference(page.get("handle"));
    }

    static void approvalResult(ProcessContext ctx) {
        EntityInstance page = first(ctx, PAGE);
        if (!storedRequest(ctx)) {
            unchanged(ctx, page);
            return;
        }
        ApprovalResultInput input = result(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        if (ApprovalEntities.APPROVED.equals(input.status()) && boundRequest(ctx)) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval == null || approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                unchanged(ctx, page);
                return;
            }
            publish(ctx, values, approval.requestId());
        } else if (ApprovalEntities.REJECTED.equals(input.status())
            && ApprovalEntities.REJECTED.equals(first(ctx, REQUEST).get("status"))) {
            values.put("status", QbSponsorPages.REJECTED);
            values.put("rejectReason", reason(ctx));
        } else {
            unchanged(ctx, page);
            return;
        }
        write(ctx, page, values, null);
    }

    /** The reviewer's reason for the rejection, from their decision; none when they gave none. */
    private static String reason(ProcessContext ctx) {
        return list(ctx, DECISIONS).stream()
            .filter(decision -> ApprovalProcesses.REJECT.equals(decision.get("decision")))
            .map(decision -> (String) decision.get("reason")).filter(reason -> reason != null && !reason.isBlank())
            .findFirst().orElse(null);
    }

    /** The input of {@value #APPROVAL_RESULT} from an approval event. */
    static ApprovalResultInput resultInput(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        return new ApprovalResultInput(text(payload, "subject"), text(payload, "entityId"), text(payload, "status"),
            text(payload, "requestId"), text(payload, "preparerId"), text(payload, "contentHash"),
            text(payload, "reason"));
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    // ---- unpublish -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<UnpublishInput, PageOutput, ProcessContext> unpublish() {
        return ProcessDefinition.define(UNPUBLISH, 1, UnpublishInput.class, PageOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Takes the sponsor's page back to its draft: no longer shown, or no longer in review.")
                    .permissions(QbPermissions.SPONSOR_ME)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> start(start, input, start.request().actorId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, PageOutput.class));
                lockAccount(pb);
                pb.step("Load the page", QueryEntities.<ProcessContext>of(QbSponsorPages.DEFAULT_DATASET,
                    ctx -> ofUser(userId(ctx)), PAGE));
                pb.compute("Back to the draft", (metadata, ctx) -> {
                    EntityInstance page = requirePage(ctx);
                    in(ctx, page, QbSponsorPages.PUBLISHED, QbSponsorPages.PENDING);
                    current(ctx, page, ctx.get(INPUT, UnpublishInput.class).baseRevision());
                    if (ctx.hasViolations()) {
                        return;
                    }
                    ctx.put(WITHDRAW, QbSponsorPages.PENDING.equals(page.get("status")));
                    offline(ctx, page);
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", QbSponsorPages.DRAFT);
                    values.put("approvalRequestId", null);
                    write(ctx, page, values, null);
                });
                pb.step("Withdraw the review", WithdrawApproval.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(WITHDRAW)), SponsorPageApprovals.SUBJECT,
                    SponsorPageProcesses::userId));
                stopServingImages(pb);
            });
    }

    // ---- takedown --------------------------------------------------------------------------------------------------

    /** {@value #TAKEDOWN}: headquarters takes a published page down. */
    public static ProcessDefinition<TakedownInput, PageOutput, ProcessContext> takedown() {
        return takedown(TAKEDOWN, false);
    }

    /** {@value #OPERATOR_TAKEDOWN}: an operator's member takes down a page of a sponsor the operator looks after. */
    public static ProcessDefinition<TakedownInput, PageOutput, ProcessContext> operatorTakedown() {
        return takedown(OPERATOR_TAKEDOWN, true);
    }

    /**
     * @param operator whether an operator's member runs it ({@code qb.operator.use}, the membership checked and held
     *                 first: lock order {@code qb.operator-member} before the account's, as the publications' acts)
     */
    private static ProcessDefinition<TakedownInput, PageOutput, ProcessContext> takedown(String name,
        boolean operator) {
        return ProcessDefinition.define(name, 1, TakedownInput.class, PageOutput.class, ProcessContext.class, pb -> {
            pb.description(operator ? "Takes down the published page of a sponsor the operator looks after."
                    : "Takes down a sponsor's published page; it comes back only after a new review.")
                .permissions(operator ? QbPermissions.OPERATOR_USE : QbPermissions.ADMIN_CONTENT_WRITE)
                .requiresMfa(MfaRequirement.ALWAYS)
                .requiresVerifiedEmail()
                .actsOn(QbSponsorPages.PAGE, "userId", a -> a.whenField("status", QbSponsorPages.PUBLISHED))
                .contextFactory((start, input) -> start(start, input, input.userId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PageOutput.class));
            if (operator) {
                OperatorChecks.member(pb);
            }
            lockAccount(pb);
            pb.step("Load the page", QueryEntities.<ProcessContext>of(QbSponsorPages.DEFAULT_DATASET,
                ctx -> ofUser(userId(ctx)), PAGE));
            if (operator) {
                // Q59c-2: only a page of a sponsor the caller's operator looks after; any other is not found.
                OperatorChecks.ownSponsor(pb, SponsorPageProcesses::userId);
            }
            pb.compute("Take down", (metadata, ctx) -> {
                EntityInstance page = requirePage(ctx);
                if (!in(ctx, page, QbSponsorPages.PUBLISHED)) {
                    return;
                }
                offline(ctx, page);
                write(ctx, page, takenDown(ctx, ctx.get(INPUT, TakedownInput.class).reason()), null);
            });
            stopServingImages(pb);
        });
    }

    static final String BANNED_PAGE = "bannedSponsorPage";
    static final String BANNED_PAGE_WITHDRAW = "bannedSponsorPageWithdraw";

    /**
     * Adds to a ban the steps that take the banned sponsor's page down (published or in review) in the ban's
     * transaction, under the account's lock the ban holds. The sponsor reads a fixed reason ("account suspended"), not
     * the ban's; after the unban the page comes back only through a new review.
     *
     * @param banned whether the ban went through, decided after its own steps
     */
    public static <I, O> void takeDownOnBan(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        java.util.function.Function<ProcessContext, String> userId,
        java.util.function.Predicate<ProcessContext> banned, String reason) {
        pb.step("Load the sponsor's page", QueryEntities.<ProcessContext>of(QbSponsorPages.DEFAULT_DATASET,
            ctx -> ofUser(userId.apply(ctx)), BANNED_PAGE));
        pb.compute("Take down the sponsor's page", (metadata, ctx) -> {
            EntityInstance page = first(ctx, BANNED_PAGE);
            if (page == null || !banned.test(ctx) || !List.of(QbSponsorPages.PUBLISHED, QbSponsorPages.PENDING)
                .contains(String.valueOf((Object) page.get("status")))) {
                return;
            }
            ctx.put(BANNED_PAGE_WITHDRAW, QbSponsorPages.PENDING.equals(page.get("status")));
            offline(ctx, page);
            stamped(ctx, page, takenDown(ctx, reason));
        });
        pb.step("Withdraw the page's review", WithdrawApproval.<ProcessContext>when(
            ctx -> Boolean.TRUE.equals(ctx.get(BANNED_PAGE_WITHDRAW)), SponsorPageApprovals.SUBJECT,
            ctx -> first(ctx, BANNED_PAGE).id()));
        stopServingImages(pb);
    }

    /**
     * Notes the cover of a published page going offline: it stops being served publicly once committed. The logo is
     * the onboarding's avatar (Q65b), public as an approved sponsor's, not as the page's.
     */
    private static void offline(ProcessContext ctx, EntityInstance page) {
        if (!QbSponsorPages.PUBLISHED.equals(page.get("status"))) {
            return;
        }
        Object cover = page.get("cover");
        ctx.put(OFFLINE, cover == null ? List.of() : List.of(UUID.fromString(String.valueOf(cover))));
    }

    /**
     * Ends the platform's remembered public decisions of the images {@link #offline} noted, after the commit, rather
     * than leaving them served for the decisions' lifetime (platform docs/design/15-public-access.md section 4).
     */
    @SuppressWarnings("unchecked")
    private static <I, O> void stopServingImages(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.afterCommit("Stop serving the page's images publicly", FileAccess.<ProcessContext>invalidate(
            ctx -> ctx.contains(OFFLINE) ? (List<UUID>) ctx.get(OFFLINE) : List.of()));
    }

    /** The values of a page taken down now, with the reason the sponsor reads. */
    static Map<String, Object> takenDown(ProcessContext ctx, String reason) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbSponsorPages.TAKEN_DOWN);
        values.put("takenDownAt", ctx.opTime());
        values.put("takedownReason", reason);
        values.put("approvalRequestId", null);
        values.put("reviewRequired", true);
        return values;
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static ProcessContext start(ProcessStart start, Object input, String userId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        if (userId != null) {
            ctx.put(USER_ID, userId);
        }
        return ctx;
    }

    static String userId(ProcessContext ctx) {
        return ctx.contains(USER_ID) ? ctx.get(USER_ID, String.class) : null;
    }

    private static String handle(ProcessContext ctx) {
        return QbSponsorPages.handle(ctx.get(INPUT, SaveInput.class).handle());
    }

    private static <I, O> void lockAccount(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the account", HoldLock.<ProcessContext>exclusive(
            ctx -> QbAccounts.lockName(userId(ctx))));
    }

    /** The page of a user, one at most (none for no user). */
    public static EntityQuery ofUser(String userId) {
        List<Object> ids = new ArrayList<>();
        if (userId != null) {
            ids.add(userId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("userId", ids)).limit(1).build();
    }

    private static EntityInstance requirePage(ProcessContext ctx) {
        EntityInstance page = first(ctx, PAGE);
        if (page == null) {
            throw new EntityNotFoundException(QbSponsorPages.PAGE + " " + userId(ctx));
        }
        return page;
    }

    /** Whether the page is in one of the states; otherwise 422 {@code QB_SPONSOR_PAGE_STATE}. */
    private static boolean in(ProcessContext ctx, EntityInstance page, String... states) {
        String status = page.get("status");
        if (List.of(states).contains(status)) {
            return true;
        }
        ctx.reject(new Violation(null, SponsorPageCodes.STATE, "The page is " + status + ", not one of "
            + List.of(states), Map.of("status", status)));
        return false;
    }

    private static void current(ProcessContext ctx, EntityInstance page, Long baseRevision) {
        AccountEditing.current(ctx, page, baseRevision, SponsorPageCodes.CHANGED);
    }

    /** Writes the page (a new one, or its next revision) and puts the output. */
    static void write(ProcessContext ctx, EntityInstance page, Map<String, Object> values, String approval) {
        Map<String, Object> next = stamped(ctx, page, values);
        long revision = ((BigDecimal) next.get("revision")).longValue();
        String status = next.containsKey("status") ? (String) next.get("status") : page.get("status");
        String handle = next.containsKey("handle") ? (String) next.get("handle") : page.get("handle");
        String requestId = next.containsKey("approvalRequestId") ? (String) next.get("approvalRequestId")
            : page == null ? null : page.get("approvalRequestId");
        ctx.put(OUTPUT, new PageOutput(userId(ctx), handle, status, revision, approval, requestId));
    }

    /** Writes the page (a new one, or its next revision); returns the values written. */
    private static Map<String, Object> stamped(ProcessContext ctx, EntityInstance page, Map<String, Object> values) {
        long revision = page == null ? 1 : AccountEditing.number(page.get("revision")) + 1;
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("revision", BigDecimal.valueOf(revision));
        next.put("editedAt", ctx.opTime());
        if (page == null) {
            ctx.changes().insert(QbSponsorPages.PAGE, next);
        } else {
            ctx.changes().update(QbSponsorPages.PAGE, page.id(), page.version(), next);
        }
        return next;
    }

    private static void unchanged(ProcessContext ctx, EntityInstance page) {
        if (page != null) {
            ctx.put(OUTPUT, new PageOutput(userId(ctx), page.get("handle"), page.get("status"),
                AccountEditing.number(page.get("revision")), null, page.get("approvalRequestId")));
        }
    }

    private static boolean same(Object stored, Object given) {
        return stored != null && given != null && String.valueOf(stored).equals(String.valueOf(given));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private SponsorPageProcesses() {
    }
}
