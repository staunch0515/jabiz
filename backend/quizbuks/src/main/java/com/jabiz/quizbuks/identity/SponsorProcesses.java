package com.jabiz.quizbuks.identity;

import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.mail.MailRecipient;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.ReviewCodes;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.identity.AccountEditing.INPUT;
import static com.jabiz.quizbuks.identity.AccountEditing.OUTPUT;
import static com.jabiz.quizbuks.identity.AccountEditing.PROFILES;
import static com.jabiz.quizbuks.identity.AccountEditing.SPONSORS;
import static com.jabiz.quizbuks.identity.AccountEditing.first;

/**
 * A sponsor's onboarding and profile (M-01, M-03, P-09; docs/quizbuks/plans/Q2-accounts.md, D-Q2-3):
 * <ul>
 *   <li>{@code QB_SPONSOR_APPLY}: the name, company, introduction and avatar, with the terms accepted, go to review;
 *       approved at once when the review switch is on or the same content was approved before. Applying again while
 *       pending replaces the request (the platform supersedes the old one); after a rejection it closes the
 *       "submit again" task;</li>
 *   <li>{@code QB_SPONSOR_PROFILE_SAVE}: an approved sponsor changes the same without review (Q2-4);</li>
 *   <li>{@code QB_SPONSOR_APPROVAL_RESULT}: run for the approval events, as the system: approves or rejects the
 *       onboarding whose own request was decided, mails the sponsor and, after a rejection, gives them a task.</li>
 * </ul>
 * The caller is the sponsor (the input names nobody); every write needs a verified address (D-Q2-1) and takes the
 * account's lock first (D-Q2-10).
 */
public final class SponsorProcesses {

    public static final String APPLY = "QB_SPONSOR_APPLY";
    public static final String PROFILE_SAVE = "QB_SPONSOR_PROFILE_SAVE";
    public static final String APPROVAL_RESULT = "QB_SPONSOR_APPROVAL_RESULT";

    static final String NEW_ID = "newOnboardingId";
    static final String PARAMS = "params";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String VALUES = "values";
    static final String APPROVED_NOW = "approvedNow";
    static final String REJECTED_NOW = "rejectedNow";
    static final String FIRST = "first";
    static final String FOUND = "found";
    static final String REQUEST = "request";
    static final String DECISIONS = "decisions";
    static final String KEY = "onboardingId";

    /**
     * @param displayName  the sponsor's name (their profile's)
     * @param intro        Markdown, one language
     * @param avatar       a file of the policy {@code qb.sponsor-avatar} the caller uploaded; none to have none
     * @param acceptTerms  must be true: the terms are accepted with every application
     * @param baseRevision the onboarding's revision the caller edited; when given and no longer current, 422
     */
    public record ApplyInput(@NotNull String displayName, @NotNull String company, String intro, UUID avatar,
        Boolean acceptTerms, Long baseRevision) {}

    public record ProfileInput(@NotNull String displayName, @NotNull String company, String intro, UUID avatar,
        Long baseRevision) {}

    /**
     * @param approval the approval outcome when the process asked for one ({@code PENDING}, {@code APPROVED},
     *                 {@code NOT_REQUIRED})
     */
    public record SponsorOutput(String userId, UUID onboardingId, String reviewStatus, long revision,
        String approval, String approvalRequestId) {}

    /**
     * Input of {@code QB_SPONSOR_APPROVAL_RESULT}, from an approval event of any subject.
     *
     * @param requestId the request decided: only the onboarding's own request (its {@code approvalRequestId}) counts
     */
    public record ApprovalResultInput(@NotBlank String subject, @NotBlank String entityId, @NotBlank String status,
        String requestId, String preparerId, String contentHash, String reason) {}

    // ---- apply -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ApplyInput, SponsorOutput, ProcessContext> apply(AccountLinks links) {
        return ProcessDefinition.define(APPLY, 1, ApplyInput.class, SponsorOutput.class, ProcessContext.class, pb -> {
            pb.description("Applies for sponsor onboarding: name, company, introduction and avatar, with the terms "
                    + "accepted; reviewed unless reviews are switched off.")
                .permissions(QbPermissions.SPONSOR_ME)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = AccountEditing.start(start, input, start.request().actorId());
                    // The key of the onboarding if this is the first application: its review needs it beforehand.
                    ctx.put(NEW_ID, start.ids().assign(QbAccounts.SPONSOR, new HashMap<>()));
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, SponsorOutput.class));
            AccountEditing.lock(pb);
            AccountEditing.loadOfUser(pb, "Load the onboarding", QbAccounts.SPONSOR, SPONSORS);
            AccountEditing.loadOfUser(pb, "Load the profile", QbAccounts.PROFILE, PROFILES);
            AccountEditing.loadFile(pb, ctx -> ctx.get(INPUT, ApplyInput.class).avatar());
            pb.step("Load the review switch", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.REVIEW_SPONSOR_AUTO));
            AccountEditing.loadUser(pb, AccountEditing::userId);
            pb.compute("Check the application", (metadata, ctx) -> checkApplication(ctx));
            pb.step("Ask for a review", RequireApproval.<ProcessContext>when(ctx -> ctx.contains(CASE),
                SponsorApprovals.SUBJECT, ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL));
            pb.compute("Apply", (metadata, ctx) -> applied(ctx));
            pb.step("Close the request to submit again", CloseTasks.<ProcessContext>done(
                ctx -> QbAccounts.taskSource(AccountEditing.userId(ctx))));
            approvedMail(pb, links);
        });
    }

    private static boolean autoApprove(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(PARAMS, ParamValues.class).get(QbParams.REVIEW_SPONSOR_AUTO,
            Boolean.class));
    }

    static void checkApplication(ProcessContext ctx) {
        ApplyInput input = ctx.get(INPUT, ApplyInput.class);
        EntityInstance sponsor = first(ctx, SPONSORS);
        if (sponsor != null && SponsorStatus.APPROVED.equals(sponsor.get("reviewStatus"))) {
            ctx.reject(new Violation(null, AccountCodes.SPONSOR_ALREADY_APPROVED, "The onboarding is approved "
                + "already; change the profile instead", Map.of()));
            return;
        }
        if (!Boolean.TRUE.equals(input.acceptTerms())) {
            ctx.reject(new Violation("acceptTerms", AccountCodes.SPONSOR_TERMS_REQUIRED, "Accept the terms to "
                + "apply", Map.of()));
        }
        AccountEditing.current(ctx, sponsor, input.baseRevision(), AccountCodes.SPONSOR_CHANGED);
        AccountEditing.requireOwnFile(ctx, AccountEditing.userId(ctx), "avatar", input.avatar(),
            sponsor == null ? null : sponsor.get("avatar"));
        if (ctx.hasViolations()) {
            return;
        }
        Object id = sponsor == null ? ctx.get(NEW_ID) : sponsor.id();
        Instant terms = ctx.opTime();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("company", input.company());
        values.put("intro", blankToNull(input.intro()));
        values.put("avatar", input.avatar());
        values.put("termsAcceptedAt", terms);
        values.put("submittedAt", ctx.opTime());
        values.put("rejectReason", null);
        ctx.put(VALUES, values);
        ctx.put(CASE, ApprovalCase.of(id, SponsorApprovals.facts(autoApprove(ctx)), SponsorApprovals.content(
            input.displayName(), input.company(), blankToNull(input.intro()), input.avatar(), terms))
            .reference(reference(input.company())));
    }

    @SuppressWarnings("unchecked")
    static void applied(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        ApplyInput input = ctx.get(INPUT, ApplyInput.class);
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        Map<String, Object> values = new LinkedHashMap<>((Map<String, Object>) ctx.get(VALUES));
        switch (approval.status()) {
            case PENDING -> {
                values.put("reviewStatus", SponsorStatus.PENDING);
                values.put("approvalRequestId", approval.requestId());
            }
            case NOT_REQUIRED -> {
                // No rule asked for a review. Unless the switch says so, the rule is missing or off: refuse rather
                // than let a sponsor in unreviewed (platform gap 9 of the plan).
                if (!autoApprove(ctx)) {
                    ctx.reject(new Violation(null, ReviewCodes.REVIEW_NOT_CONFIGURED, "Sponsors need a review, but "
                        + "no review rule is in effect", Map.of("rule", SponsorApprovals.RULE)));
                    return;
                }
                approve(ctx, values, null);
            }
            case APPROVED -> approve(ctx, values, approval.requestId());
            default -> throw new IllegalStateException("Unknown approval outcome " + approval.status());
        }
        String userId = AccountEditing.userId(ctx);
        AccountEditing.writeName(ctx, first(ctx, PROFILES), userId, input.displayName());
        write(ctx, first(ctx, SPONSORS), values, approval.status().name());
    }

    private static void approve(ProcessContext ctx, Map<String, Object> values, String requestId) {
        values.put("reviewStatus", SponsorStatus.APPROVED);
        values.put("approvalRequestId", requestId);
        values.put("approvedAt", ctx.opTime());
        ctx.put(APPROVED_NOW, true);
    }

    /** Writes the onboarding's next version (a new one with the key allocated beforehand); puts the output. */
    private static void write(ProcessContext ctx, EntityInstance sponsor, Map<String, Object> values, String approval) {
        String userId = AccountEditing.userId(ctx);
        long revision = sponsor == null ? 1 : AccountEditing.number(sponsor.get("revision")) + 1;
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("revision", BigDecimal.valueOf(revision));
        next.put("editedAt", ctx.opTime());
        UUID id;
        if (sponsor == null && SponsorStatus.APPROVED.equals(next.get("reviewStatus"))) {
            // A first application approved at once: its lifecycle starts pending (see QbAccounts.SPONSOR_ENTITY).
            Map<String, Object> pending = new LinkedHashMap<>(next);
            pending.put(KEY, ctx.get(NEW_ID));
            pending.put("userId", userId);
            pending.put("reviewStatus", SponsorStatus.PENDING);
            pending.put("approvalRequestId", null);
            pending.put("approvedAt", null);
            id = AccountEditing.uuid(ctx.changes().insert(QbAccounts.SPONSOR, pending));
            revision++;
            Map<String, Object> approved = new LinkedHashMap<>();
            approved.put("reviewStatus", SponsorStatus.APPROVED);
            approved.put("approvalRequestId", next.get("approvalRequestId"));
            approved.put("approvedAt", next.get("approvedAt"));
            approved.put("revision", BigDecimal.valueOf(revision));
            ctx.changes().update(QbAccounts.SPONSOR, id, 1, approved);
        } else if (sponsor == null) {
            next.put(KEY, ctx.get(NEW_ID));
            next.put("userId", userId);
            id = AccountEditing.uuid(ctx.changes().insert(QbAccounts.SPONSOR, next));
        } else {
            // The platform refuses a move of state the onboarding's lifecycle does not declare.
            id = AccountEditing.uuid(sponsor.id());
            ctx.changes().update(QbAccounts.SPONSOR, sponsor.id(), sponsor.version(), next);
        }
        String status = next.containsKey("reviewStatus") ? (String) next.get("reviewStatus")
            : sponsor.get("reviewStatus");
        String requestId = next.containsKey("approvalRequestId") ? (String) next.get("approvalRequestId")
            : sponsor == null ? null : sponsor.get("approvalRequestId");
        ctx.put(OUTPUT, new SponsorOutput(userId, id, status, revision, approval, requestId));
    }

    private static <I, O> void approvedMail(com.jabiz.process.ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        AccountLinks links) {
        pb.step("Tell the sponsor they are approved", SendMail.<ProcessContext>when(ctx -> !ctx.hasViolations()
                && Boolean.TRUE.equals(ctx.get(APPROVED_NOW)) && AccountEditing.hasAddress(ctx),
            AccountMails.SPONSOR_APPROVED, ctx -> MailRecipient.user(AccountEditing.userId(ctx)),
            Map.of("company", SponsorProcesses::company, "link", ctx -> links.sponsor())));
    }

    /** The company of the onboarding the process wrote or found. */
    @SuppressWarnings("unchecked")
    private static String company(ProcessContext ctx) {
        if (ctx.contains(VALUES)) {
            return (String) ((Map<String, Object>) ctx.get(VALUES)).get("company");
        }
        EntityInstance found = first(ctx, FOUND);
        return found == null ? "" : found.get("company");
    }

    // ---- profile ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ProfileInput, SponsorOutput, ProcessContext> profileSave() {
        return ProcessDefinition.define(PROFILE_SAVE, 1, ProfileInput.class, SponsorOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Changes an approved sponsor's name, company, introduction and avatar, without review.")
                    .permissions(QbPermissions.SPONSOR_ME)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> AccountEditing.start(start, input, start.request().actorId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, SponsorOutput.class));
                AccountEditing.lock(pb);
                AccountEditing.loadOfUser(pb, "Load the onboarding", QbAccounts.SPONSOR, SPONSORS);
                AccountEditing.loadOfUser(pb, "Load the profile", QbAccounts.PROFILE, PROFILES);
                AccountEditing.loadFile(pb, ctx -> ctx.get(INPUT, ProfileInput.class).avatar());
                pb.compute("Save the sponsor profile", (metadata, ctx) -> saveProfile(ctx));
            });
    }

    static void saveProfile(ProcessContext ctx) {
        ProfileInput input = ctx.get(INPUT, ProfileInput.class);
        EntityInstance sponsor = first(ctx, SPONSORS);
        if (sponsor == null || !SponsorStatus.APPROVED.equals(sponsor.get("reviewStatus"))) {
            ctx.reject(new Violation(null, AccountCodes.SPONSOR_NOT_APPROVED, "The onboarding is not approved: "
                + "apply instead", Map.of("status", sponsor == null ? "NONE"
                : String.valueOf((Object) sponsor.get("reviewStatus")))));
            return;
        }
        if (!AccountEditing.current(ctx, sponsor, input.baseRevision(), AccountCodes.SPONSOR_CHANGED)) {
            return;
        }
        String userId = AccountEditing.userId(ctx);
        AccountEditing.requireOwnFile(ctx, userId, "avatar", input.avatar(), sponsor.get("avatar"));
        if (ctx.hasViolations()) {
            return;
        }
        AccountEditing.writeName(ctx, first(ctx, PROFILES), userId, input.displayName());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("company", input.company());
        values.put("intro", blankToNull(input.intro()));
        values.put("avatar", input.avatar());
        Map<String, Object> changed = AccountEditing.differences(sponsor, values);
        if (changed.isEmpty()) {
            ctx.put(OUTPUT, new SponsorOutput(userId, AccountEditing.uuid(sponsor.id()), sponsor.get("reviewStatus"),
                AccountEditing.number(sponsor.get("revision")), null, sponsor.get("approvalRequestId")));
            return;
        }
        write(ctx, sponsor, changed, null);
    }

    // ---- approval result -------------------------------------------------------------------------------------------

    /**
     * Continues after a review is decided (subscribed to both approval events, run as the system; the permission is
     * granted to no role). Only the onboarding's own request counts: the event must name the request the onboarding,
     * still pending, waits for, and the stored request must be bound to the onboarding's present content. An approval
     * goes through only when {@link RequireApproval} finds that request approved; a rejection only when the stored
     * request is rejected, and the reason is the reviewer's decision's, not the event's. Anything else (another
     * subject, a stale event, a forged call) changes nothing.
     */
    public static ProcessDefinition<ApprovalResultInput, SponsorOutput, ProcessContext> approvalResult(
        AccountLinks links) {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, SponsorOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Approves or rejects a sponsor's onboarding after its review is decided.")
                    .permissions(QbPermissions.SPONSOR_SYSTEM)
                    .internal()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = AccountEditing.start(start, input, null);
                        ctx.put(KEY, SponsorApprovals.SUBJECT.equals(input.subject())
                            ? AccountEditing.uuidOrNull(input.entityId()) : null);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, SponsorOutput.class)
                        : new SponsorOutput(null, ctx.get(KEY, UUID.class), null, 0, null, null));
                // The account's lock comes before reading the account, and only the onboarding names the user: read
                // it, lock, read it again. Events of other subjects find nothing and lock nothing.
                pb.step("Find the onboarding", QueryEntities.<ProcessContext>of(
                    QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> byKey(ctx.get(KEY)), FIRST));
                pb.step("Lock the account", HoldLock.<ProcessContext>exclusive(ctx -> {
                    EntityInstance found = first(ctx, FIRST);
                    return found == null ? null : QbAccounts.lockName(found.get("userId"));
                }));
                pb.step("Load the onboarding", QueryEntities.<ProcessContext>of(
                    QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> byKey(ctx.get(KEY)), FOUND));
                pb.step("Load the profile", QueryEntities.<ProcessContext>of(
                    QbAccounts.defaultDataset(QbAccounts.PROFILE), ctx -> AccountEditing.ofUser(owner(ctx)), PROFILES));
                pb.step("Load the request", QueryEntities.<ProcessContext>of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        ownRequest(ctx) ? AccountEditing.uuids(input(ctx).requestId()) : new ArrayList<>()))
                        .limit(1).build(), REQUEST));
                pb.step("Load the decisions", QueryEntities.<ProcessContext>of(ApprovalEntities.DECISION_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        boundRequest(ctx) ? AccountEditing.uuids(input(ctx).requestId()) : new ArrayList<>()))
                        .limit(Integer.MAX_VALUE).build(), DECISIONS));
                pb.step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> boundRequest(ctx) && ApprovalEntities.APPROVED.equals(input(ctx).status()),
                    SponsorApprovals.SUBJECT, ctx -> storedCase(ctx).preparedBy(input(ctx).preparerId()), APPROVAL));
                AccountEditing.loadUser(pb, SponsorProcesses::owner);
                pb.compute("Approve or reject", (metadata, ctx) -> approvalResult(ctx));
                pb.step("Ask the sponsor to submit again", CreateTask.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(REJECTED_NOW)), ctx -> TaskSpec.forUser(QbAccounts.RESUBMIT_TASK,
                        QbAccounts.RESUBMIT_TASK_TITLE, Map.of("company", company(ctx)), owner(ctx))
                        .about(QbAccounts.SPONSOR, first(ctx, FOUND).id())
                        .source(QbAccounts.taskSource(owner(ctx)))));
                approvedMail(pb, links);
                pb.step("Tell the sponsor they are sent back", SendMail.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(REJECTED_NOW)) && AccountEditing.hasAddress(ctx),
                    AccountMails.SPONSOR_REJECTED, ctx -> MailRecipient.user(owner(ctx)),
                    Map.of("company", SponsorProcesses::company, "reason", SponsorProcesses::reason,
                        "link", ctx -> links.sponsor())));
            });
    }

    private static EntityQuery byKey(Object key) {
        return EntityQuery.builder().where(new QueryPredicate.In(KEY, AccountEditing.uuids(key))).limit(1).build();
    }

    private static ApprovalResultInput input(ProcessContext ctx) {
        return ctx.get(INPUT, ApprovalResultInput.class);
    }

    /** The user of the onboarding found; null when none was. */
    private static String owner(ProcessContext ctx) {
        EntityInstance found = first(ctx, FOUND);
        return found == null ? null : found.get("userId");
    }

    /** Whether the event is about the request the onboarding, still pending, waits for. */
    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance found = first(ctx, FOUND);
        return found != null && SponsorStatus.PENDING.equals(found.get("reviewStatus"))
            && input(ctx).requestId() != null && input(ctx).requestId().equals(found.get("approvalRequestId"));
    }

    /**
     * Whether the stored request (not the event) is the onboarding's own: of this subject and onboarding, bound to its
     * present content. A name changed in the profile meanwhile unbinds it: the sponsor then applies again.
     */
    private static boolean boundRequest(ProcessContext ctx) {
        EntityInstance request = first(ctx, REQUEST);
        if (!ownRequest(ctx) || request == null) {
            return false;
        }
        EntityInstance sponsor = first(ctx, FOUND);
        String hash = SponsorApprovals.hash(storedCase(ctx).content());
        return SponsorApprovals.SUBJECT.equals(request.get("subject"))
            && String.valueOf(sponsor.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }

    /** The case of the onboarding as stored: its present content. */
    private static ApprovalCase storedCase(ProcessContext ctx) {
        EntityInstance sponsor = first(ctx, FOUND);
        EntityInstance profile = first(ctx, PROFILES);
        return ApprovalCase.of(sponsor.id(), SponsorApprovals.facts(false), SponsorApprovals.content(
            profile == null ? null : profile.get("displayName"), sponsor.get("company"), sponsor.get("intro"),
            AccountEditing.uuid(sponsor.get("avatar")), AccountEditing.instant(sponsor.get("termsAcceptedAt"))))
            .reference(reference(sponsor.get("company")));
    }

    /** The reviewer's reason for the rejection, from their decision. */
    private static String reason(ProcessContext ctx) {
        return AccountEditing.list(ctx, DECISIONS).stream()
            .filter(decision -> ApprovalProcesses.REJECT.equals(decision.get("decision")))
            .map(decision -> (String) decision.get("reason")).filter(reason -> reason != null && !reason.isBlank())
            .findFirst().orElse("");
    }

    static void approvalResult(ProcessContext ctx) {
        EntityInstance sponsor = first(ctx, FOUND);
        if (!boundRequest(ctx)) {
            unchanged(ctx, sponsor);
            return;
        }
        ApprovalResultInput input = input(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval == null || approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                unchanged(ctx, sponsor);
                return;
            }
            approve(ctx, values, approval.requestId());
            values.put("rejectReason", null);
        } else if (ApprovalEntities.REJECTED.equals(input.status())
            && ApprovalEntities.REJECTED.equals(first(ctx, REQUEST).get("status"))) {
            values.put("reviewStatus", SponsorStatus.REJECTED);
            String reason = reason(ctx);
            values.put("rejectReason", reason.isEmpty() ? null : reason);
            values.put("rejectedAt", ctx.opTime());
            ctx.put(REJECTED_NOW, true);
        } else {
            unchanged(ctx, sponsor);
            return;
        }
        // From here on the process is about the onboarding's user: the output, the task and the mail are theirs.
        ctx.put(AccountEditing.USER_ID, owner(ctx));
        write(ctx, sponsor, values, null);
    }

    private static void unchanged(ProcessContext ctx, EntityInstance sponsor) {
        if (sponsor != null) {
            ctx.put(OUTPUT, new SponsorOutput(sponsor.get("userId"), AccountEditing.uuid(sponsor.id()),
                sponsor.get("reviewStatus"), AccountEditing.number(sponsor.get("revision")), null,
                sponsor.get("approvalRequestId")));
        }
    }

    /** The input of {@code QB_SPONSOR_APPROVAL_RESULT} from an approval event. */
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

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** How reviewers know the case in their task: the company (at most 100 characters, as the platform's). */
    private static String reference(String company) {
        return company == null || company.isBlank() ? null : company;
    }

    private SponsorProcesses() {}
}
