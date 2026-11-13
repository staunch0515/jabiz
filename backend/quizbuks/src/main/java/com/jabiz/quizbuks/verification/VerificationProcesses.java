package com.jabiz.quizbuks.verification;

import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.mail.MailRecipient;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.ReviewCodes;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.mail.MailTokens;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.MfaRequirement;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A Sponsor's company verification (F-M3, docs/quizbuks/plans/impl/F-M.md §6):
 * <ul>
 *   <li>{@value #SUBMIT}: the Sponsor gives the company's name and domain. A public mail service's domain, or one
 *       verified for another Sponsor, is refused. When the account's verified address is on the domain (a subdomain
 *       counts) it goes to the operator's review at once; otherwise a one-time link goes to an address on the domain
 *       the Sponsor gives. Submitting again ends what stood: one in progress is withdrawn (its review too), one
 *       verified is superseded (the controller, 2026-11-10: a new name or domain loses the verification at once). The
 *       same submission again only sends the link again (the earlier links stop working).</li>
 *   <li>{@value #CONFIRM}: the Sponsor, signed in, opens the link: the token is the latest of their account, the
 *       address the submission's, not older than {@code qb.sponsor-verification.link-hours}; it goes to the review.</li>
 *   <li>{@value #APPROVAL_RESULT}: run for the approval events, as the system: verifies or sends back the submission
 *       whose own request was decided, still bound to it as stored. A domain verified for another Sponsor meanwhile is
 *       sent back. The top-up of the onboarding platform fee Kudos ({@code QB_FEE_CREDIT_CERTIFIED_TOP_UP}) follows in a
 *       transaction of its own, from the event {@value #DECIDED_EVENT} (the review of F-M3, L2: money failing never
 *       holds up the verification; the daily job {@code qb.fee-credit-certified-due} makes what it missed).</li>
 *   <li>{@value #REVOKE}: headquarters or a member of the Sponsor's operator withdraws a verification, with a reason
 *       the Sponsor reads; the platform fee Kudos given are kept.</li>
 * </ul>
 * Locks: the Sponsor's verification ({@code qb.sponsor-verification:<sponsor>}), then the domain's
 * ({@code qb.sponsor-verification-domain:<domain>}, an approval only), then the platform's approvals.
 */
public final class VerificationProcesses {

    public static final String SUBMIT = "QB_SPONSOR_VERIFICATION_SUBMIT";
    public static final String CONFIRM = "QB_SPONSOR_VERIFICATION_CONFIRM";
    public static final String APPROVAL_RESULT = "QB_SPONSOR_VERIFICATION_APPROVAL_RESULT";
    public static final String REVOKE = "QB_SPONSOR_VERIFICATION_REVOKE";
    /**
     * A verification decided or withdrawn, published in the transaction of {@value #APPROVAL_RESULT} or
     * {@value #REVOKE} (payload {@code verificationId}, {@code sponsorId}, {@code status}): the Sponsor's in-app notice.
     */
    public static final String DECIDED_EVENT = "qb.sponsor-verification.decided";

    /**
     * @param companyName    the company's registered name
     * @param domain         the company's domain (a leading {@code www.} is left out)
     * @param confirmAddress an address on the domain for the link; none when the account's address is on it
     */
    public record SubmitInput(@NotBlank @Size(max = QbVerifications.NAME_LENGTH) String companyName,
        @NotBlank @Size(max = QbVerifications.DOMAIN_LENGTH) String domain,
        @Size(max = QbVerifications.ADDRESS_LENGTH) String confirmAddress) {}

    /** @param token the link's token, as the page read it from the address */
    public record ConfirmInput(@Sensitive @NotBlank @Size(max = 128) String token) {

        @Override
        public String toString() {
            return "ConfirmInput[token=***]";
        }
    }

    /** @param reason why, as the Sponsor will read it */
    public record RevokeInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Size(max = QbVerifications.REASON_LENGTH) String reason) {}

    /** Input of {@value #APPROVAL_RESULT}, from an approval event of any subject. */
    public record ApprovalResultInput(@NotBlank String subject, @NotBlank String entityId, @NotBlank String status,
        String requestId, String preparerId, String contentHash, String reason) {}

    /**
     * @param status            the submission's status afterwards
     * @param method            {@code ACCOUNT} or {@code LINK}
     * @param approvalRequestId the review's request, once it is in review
     */
    public record VerificationOutput(String verificationId, String status, String method, String approvalRequestId) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String ID = "verificationId";
    static final String NEW_ID = "newVerificationId";
    static final String PARAMS = "params";
    static final String ONBOARDING = "onboarding";
    static final String STANDING = "standing";
    static final String TAKEN = "taken";
    static final String ASSIGNMENT = "assignment";
    static final String CALLER = "caller";
    static final String ROW = "row";
    static final String VALUES = "values";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String WITHDRAW_ID = "withdrawId";
    static final String SEND_LINK = "sendLink";
    static final String USED = "used";
    static final String FOUND = "found";
    static final String REQUEST = "request";
    static final String DECISIONS = "decisions";
    static final String VERIFIED_NOW = "verifiedNow";
    static final String DECIDED_NOW = "decidedNow";

    private VerificationProcesses() {
    }

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SubmitInput, VerificationOutput, ProcessContext> submit() {
        return ProcessDefinition.define(SUBMIT, 1, SubmitInput.class, VerificationOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Submits the Sponsor's company for verification by its mail domain: reviewed by their "
                        + "operator, after a link to an address on the domain unless the account's address is on it.")
                    .permissions(QbPermissions.SPONSOR_VERIFICATION)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = start(start, input, null);
                        ctx.put(NEW_ID, start.ids().assign(QbVerifications.VERIFICATION, new HashMap<>()));
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, VerificationOutput.class));
                pb.step("Load the link's validity", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.VERIFICATION_LINK_HOURS));
                pb.step("Lock the Sponsor's verification", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbVerifications.lockName(ctx.request().actorId())));
                CallerChecks.refuseBanned(pb);
                pb.step("Load the onboarding, the submissions, the domain, the operator and the account",
                    QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.Eq("userId", ctx.request().actorId())).limit(1).build(),
                            ONBOARDING),
                        QueryEntities.query(QbVerifications.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                                new QueryPredicate.In("status", new ArrayList<Object>(VerificationRules.STANDING)))))
                            .limit(10).build(), STANDING),
                        QueryEntities.query(QbVerifications.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.In("domain", domainOf(ctx)),
                                new QueryPredicate.Eq("status", QbVerifications.APPROVED)))).limit(1).build(), TAKEN),
                        QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> EntityQuery
                            .builder().where(new QueryPredicate.In("userId", List.of(ctx.request().actorId())))
                            .limit(1).build(), ASSIGNMENT),
                        QueryEntities.query(SecurityEntities.USER_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("userId", AccountEditing.uuids(ctx.request().actorId())))
                            .limit(1).build(), CALLER)));
                pb.compute("Check the submission", (metadata, ctx) -> checkSubmission(ctx));
                pb.step("Withdraw the review in progress", WithdrawApproval.<ProcessContext>when(
                    ctx -> ctx.contains(WITHDRAW_ID), VerificationApprovals.SUBJECT, ctx -> ctx.get(WITHDRAW_ID)));
                pb.step("Ask for the operator's review", RequireApproval.<ProcessContext>when(ctx -> ctx.contains(CASE),
                    VerificationApprovals.SUBJECT, ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL));
                pb.compute("Record the submission", (metadata, ctx) -> submitted(ctx));
                pb.step("Mail the link", SendMail.<ProcessContext>when(
                    ctx -> !ctx.hasViolations() && Boolean.TRUE.equals(ctx.get(SEND_LINK)), VerificationMails.CONFIRM,
                    ctx -> MailRecipient.user(ctx.request().actorId(), linkAddress(ctx), null),
                    Map.of("companyName", ctx -> String.valueOf(values(ctx).get("companyName")),
                        "domain", ctx -> String.valueOf(values(ctx).get("domain")),
                        "hours", ctx -> String.valueOf(linkHours(ctx)))));
            });
    }

    private static List<Object> domainOf(ProcessContext ctx) {
        List<Object> domains = new ArrayList<>();
        VerificationRules.domain(ctx.get(INPUT, SubmitInput.class).domain()).ifPresent(domains::add);
        return domains;
    }

    static void checkSubmission(ProcessContext ctx) {
        SubmitInput input = ctx.get(INPUT, SubmitInput.class);
        String sponsorId = ctx.request().actorId();
        EntityInstance onboarding = first(ctx, ONBOARDING);
        if (onboarding == null || !SponsorStatus.APPROVED.equals(onboarding.get("reviewStatus"))) {
            ctx.reject(new Violation(null, VerificationCodes.NOT_APPROVED, "Only an approved Sponsor verifies their "
                + "company", Map.of()));
            return;
        }
        Optional<String> normalized = VerificationRules.domain(input.domain());
        if (normalized.isEmpty()) {
            ctx.reject(new Violation("domain", VerificationCodes.DOMAIN_FORMAT, "Not a domain", Map.of()));
            return;
        }
        String domain = normalized.get();
        if (VerificationRules.publicMail(domain)) {
            ctx.reject(new Violation("domain", VerificationCodes.PUBLIC_DOMAIN, "A public mail service's domain "
                + "proves nothing about a company", Map.of("domain", domain)));
            return;
        }
        EntityInstance taken = first(ctx, TAKEN);
        if (taken != null && !sponsorId.equals(taken.get("sponsorId"))) {
            ctx.reject(new Violation("domain", VerificationCodes.DOMAIN_TAKEN, "The domain is verified for another "
                + "Sponsor", Map.of("domain", domain)));
            return;
        }
        String companyName = input.companyName().strip();
        EntityInstance user = first(ctx, CALLER);
        String accountAddress = user == null ? null : (String) user.get("email");
        boolean byAccount = SecurityEntities.emailVerified(user) && VerificationRules.addressOn(accountAddress, domain);
        String confirmAddress = null;
        if (!byAccount) {
            if (input.confirmAddress() == null || input.confirmAddress().isBlank()) {
                ctx.reject(new Violation("confirmAddress", VerificationCodes.ADDRESS_REQUIRED, "An address on the "
                    + "domain is needed for the link", Map.of("domain", domain)));
                return;
            }
            if (!VerificationRules.addressOn(input.confirmAddress(), domain)) {
                ctx.reject(new Violation("confirmAddress", VerificationCodes.ADDRESS_DOMAIN, "The address is not on "
                    + "the domain", Map.of("domain", domain)));
                return;
            }
            confirmAddress = VerificationRules.address(input.confirmAddress());
        }
        String method = byAccount ? VerificationRules.ACCOUNT : VerificationRules.LINK;

        for (EntityInstance standing : list(ctx, STANDING)) {
            if (QbVerifications.APPROVED.equals(standing.get("status"))
                && companyName.equals(standing.get("companyName")) && domain.equals(standing.get("domain"))) {
                ctx.reject(new Violation(null, VerificationCodes.STATE, "The company is verified already",
                    Map.of("status", QbVerifications.APPROVED)));
                return;
            }
        }
        // The same submission again, while its link is out: only the link again (the earlier ones stop working).
        for (EntityInstance standing : list(ctx, STANDING)) {
            if (QbVerifications.LINK_SENT.equals(standing.get("status")) && !byAccount
                && companyName.equals(standing.get("companyName")) && domain.equals(standing.get("domain"))
                && confirmAddress.equals(VerificationRules.address((String) standing.get("confirmAddress")))) {
                ctx.changes().update(QbVerifications.VERIFICATION, standing.id(), standing.version(),
                    Map.of("linkSentAt", ctx.opTime()));
                Map<String, Object> resent = new LinkedHashMap<>(standing.attributes());
                ctx.put(VALUES, resent);
                ctx.put(SEND_LINK, true);
                ctx.put(OUTPUT, new VerificationOutput(String.valueOf(standing.id()), QbVerifications.LINK_SENT,
                    method, null));
                return;
            }
        }
        // Whatever stood ends now: one in progress withdrawn (its review too), one verified superseded.
        for (EntityInstance standing : list(ctx, STANDING)) {
            String ended = VerificationRules.endedBy(standing.get("status"));
            if (ended == null) {
                continue;
            }
            if (QbVerifications.REVIEW.equals(standing.get("status"))) {
                ctx.put(WITHDRAW_ID, String.valueOf(standing.id()));
            }
            ctx.changes().update(QbVerifications.VERIFICATION, standing.id(), standing.version(),
                Map.of("status", ended, "endedAt", ctx.opTime()));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("verificationId", ctx.get(NEW_ID));
        values.put("sponsorId", sponsorId);
        values.put("companyName", companyName);
        values.put("domain", domain);
        values.put("method", method);
        values.put("confirmAddress", confirmAddress);
        values.put("submittedAt", ctx.opTime());
        ctx.put(VALUES, values);
        if (byAccount) {
            ctx.put(CASE, reviewCase(ctx, values));
        }
    }

    /** The review's case of a submission (as about to be stored, or as stored), in its operator's scope. */
    private static ApprovalCase reviewCase(ProcessContext ctx, Map<String, Object> values) {
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        if (assignment == null) {
            // F-S1: every Sponsor has an operator.
            throw new IllegalStateException("Sponsor " + values.get("sponsorId") + " has no operator");
        }
        Map<String, Object> content = new LinkedHashMap<>();
        for (String field : new String[] {"sponsorId", "companyName", "domain", "method"}) {
            content.put(field, String.valueOf(values.get(field)));
        }
        ctx.put(ASSIGNMENT + "Id", String.valueOf((Object) assignment.get("operatorId")));
        return ApprovalCase.of(String.valueOf(values.get("verificationId")), VerificationApprovals.facts(), content)
            .reference(values.get("companyName") + " (" + values.get("domain") + ")")
            .scope(QbOperators.SCOPE, String.valueOf((Object) assignment.get("operatorId")));
    }

    @SuppressWarnings("unchecked")
    static void submitted(ProcessContext ctx) {
        if (ctx.hasViolations() || ctx.contains(OUTPUT)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>((Map<String, Object>) ctx.get(VALUES));
        String method = (String) values.get("method");
        if (VerificationRules.LINK.equals(method)) {
            values.put("status", QbVerifications.LINK_SENT);
            values.put("linkSentAt", ctx.opTime());
            ctx.put(SEND_LINK, true);
        } else if (!inReview(ctx, values)) {
            return;
        }
        ctx.changes().insert(QbVerifications.VERIFICATION, values);
        ctx.put(OUTPUT, new VerificationOutput(String.valueOf(values.get("verificationId")),
            (String) values.get("status"), method, (String) values.get("approvalRequestId")));
    }

    /** Puts the review's request on the values; false (and a violation) when no review rule is in effect. */
    private static boolean inReview(ProcessContext ctx, Map<String, Object> values) {
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval == null || approval.status() != ApprovalOutcome.Status.PENDING) {
            // Every verification is reviewed: no rule (or an outcome without a review) is refused.
            ctx.reject(new Violation(null, ReviewCodes.REVIEW_NOT_CONFIGURED, "Company verifications need a review, "
                + "but no review rule is in effect", Map.of("rule", VerificationApprovals.RULE)));
            return false;
        }
        values.put("status", QbVerifications.REVIEW);
        values.put("approvalRequestId", approval.requestId());
        values.put("operatorId", ctx.get(ASSIGNMENT + "Id"));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(ProcessContext ctx) {
        return (Map<String, Object>) ctx.get(VALUES);
    }

    private static String linkAddress(ProcessContext ctx) {
        return (String) values(ctx).get("confirmAddress");
    }

    private static int linkHours(ProcessContext ctx) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        BigDecimal hours = params.get(QbParams.VERIFICATION_LINK_HOURS, BigDecimal.class);
        return Math.max(1, Math.min(hours.intValue(), (int) VerificationMails.MAX_VALIDITY.toHours()));
    }

    // ---- confirm ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ConfirmInput, VerificationOutput, ProcessContext> confirm() {
        return ProcessDefinition.define(CONFIRM, 1, ConfirmInput.class, VerificationOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Proves the company's mailbox with the link mailed to it; the verification goes to the "
                        + "operator's review.")
                    .permissions(QbPermissions.SPONSOR_VERIFICATION)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> start(start, input, null))
                    .outputMapper(ctx -> ctx.get(OUTPUT, VerificationOutput.class));
                pb.step("Load the link's validity", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.VERIFICATION_LINK_HOURS));
                pb.step("Lock the Sponsor's verification", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbVerifications.lockName(ctx.request().actorId())));
                CallerChecks.refuseBanned(pb);
                pb.step("Use the link", MailTokens.<ProcessContext>consume(VerificationMails.PURPOSE,
                    ctx -> ctx.get(INPUT, ConfirmInput.class).token(), USED));
                pb.step("Load the submission and the operator", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbVerifications.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                            new QueryPredicate.Eq("status", QbVerifications.LINK_SENT)))).limit(1).build(), ROW),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.In("userId", List.of(ctx.request().actorId())))
                        .limit(1).build(), ASSIGNMENT)));
                pb.compute("Check the link", (metadata, ctx) -> checkLink(ctx));
                pb.step("Ask for the operator's review", RequireApproval.<ProcessContext>when(ctx -> ctx.contains(CASE),
                    VerificationApprovals.SUBJECT, ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL));
                pb.compute("Record the confirmation", (metadata, ctx) -> confirmed(ctx));
            });
    }

    static void checkLink(ProcessContext ctx) {
        MailTokens.Consumed used = (MailTokens.Consumed) ctx.get(USED);
        EntityInstance row = first(ctx, ROW);
        Instant sent = row == null ? null : (Instant) row.get("linkSentAt");
        boolean good = used != null && row != null && ctx.request().actorId().equals(used.userId())
            && VerificationRules.address(used.address()) != null
            && VerificationRules.address(used.address()).equals(VerificationRules.address(
                (String) row.get("confirmAddress")))
            && sent != null && ctx.opTime().isBefore(sent.plus(Duration.ofHours(linkHours(ctx))));
        if (!good) {
            ctx.reject(new Violation("token", VerificationCodes.LINK_INVALID, "The link is used, expired, replaced "
                + "by a later one or not this account's", Map.of()));
            return;
        }
        ctx.put(CASE, reviewCase(ctx, new LinkedHashMap<>(row.attributes())));
    }

    static void confirmed(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance row = first(ctx, ROW);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("confirmedAt", ctx.opTime());
        if (!inReview(ctx, values)) {
            return;
        }
        ctx.changes().update(QbVerifications.VERIFICATION, row.id(), row.version(), values);
        ctx.put(OUTPUT, new VerificationOutput(String.valueOf(row.id()), QbVerifications.REVIEW,
            (String) row.get("method"), (String) values.get("approvalRequestId")));
    }

    // ---- approval result -------------------------------------------------------------------------------------------

    /**
     * Continues after a review is decided (subscribed to both approval events, run as the system; the permission is
     * granted to no role). Only the submission's own request counts, still bound to it as stored; anything else
     * changes nothing.
     */
    public static ProcessDefinition<ApprovalResultInput, VerificationOutput, ProcessContext> approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, VerificationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Verifies or sends back a company verification after its review is decided.")
                .permissions(QbPermissions.VERIFICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> start(start, input,
                    VerificationApprovals.SUBJECT.equals(input.subject()) ? input.entityId() : null))
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, VerificationOutput.class)
                    : new VerificationOutput(id(ctx), null, null, null))
                .step("Find the submission", QueryEntities.<ProcessContext>of(QbVerifications.DEFAULT_DATASET,
                    ctx -> byId(id(ctx)), FOUND))
                .step("Lock the Sponsor's verification", HoldLock.<ProcessContext>exclusive(
                    ctx -> first(ctx, FOUND) == null ? null : QbVerifications.lockName(first(ctx, FOUND).get("sponsorId"))))
                .step("Lock the domain", HoldLock.<ProcessContext>exclusive(
                    ctx -> first(ctx, FOUND) == null ? null : QbVerifications.domainLockName(first(ctx, FOUND).get("domain"))))
                .step("Read the submission, the domain's verification and the request", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbVerifications.DEFAULT_DATASET, ctx -> byId(id(ctx)), ROW),
                    QueryEntities.query(QbVerifications.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("domain", first(ctx, FOUND) == null ? new ArrayList<>()
                                : new ArrayList<>(List.of(String.valueOf((Object) first(ctx, FOUND).get("domain"))))),
                            new QueryPredicate.Eq("status", QbVerifications.APPROVED)))).limit(1).build(), TAKEN),
                    QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", foundRequest(ctx))).limit(1).build(), REQUEST),
                    QueryEntities.query(ApprovalEntities.DECISION_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", foundRequest(ctx))).limit(20).build(), DECISIONS)))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> boundRequest(ctx) && ApprovalEntities.APPROVED.equals(result(ctx).status()),
                    VerificationApprovals.SUBJECT, ctx -> storedCase(ctx).preparedBy(result(ctx).preparerId()),
                    APPROVAL))
                .compute("Verify or send back", (metadata, ctx) -> decided(ctx))
                .step("Tell the Sponsor", PublishEvent.<ProcessContext>when(
                    ctx -> !ctx.hasViolations() && ctx.contains(DECIDED_NOW), DECIDED_EVENT,
                    ctx -> eventPayload(first(ctx, ROW), (String) ctx.get(DECIDED_NOW)))));
    }

    private static ApprovalResultInput result(ProcessContext ctx) {
        return ctx.get(INPUT, ApprovalResultInput.class);
    }

    /** The event's request, when the submission found waits for it in review. */
    private static List<Object> foundRequest(ProcessContext ctx) {
        EntityInstance found = first(ctx, FOUND);
        String requestId = result(ctx).requestId();
        return found != null && QbVerifications.REVIEW.equals(found.get("status")) && requestId != null
            && requestId.equals(found.get("approvalRequestId")) ? AccountEditing.uuids(requestId) : new ArrayList<>();
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        return row != null && QbVerifications.REVIEW.equals(row.get("status")) && result(ctx).requestId() != null
            && result(ctx).requestId().equals(row.get("approvalRequestId"));
    }

    private static boolean storedRequest(ProcessContext ctx) {
        EntityInstance request = first(ctx, REQUEST);
        return ownRequest(ctx) && request != null && VerificationApprovals.SUBJECT.equals(request.get("subject"))
            && String.valueOf(first(ctx, ROW).id()).equals(request.get("entityId"));
    }

    private static boolean boundRequest(ProcessContext ctx) {
        return storedRequest(ctx) && VerificationApprovals.hash(storedCase(ctx).content())
            .equals(String.valueOf((Object) first(ctx, REQUEST).get("contentHash")).strip());
    }

    private static ApprovalCase storedCase(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        return ApprovalCase.of(row.id(), VerificationApprovals.facts(), VerificationApprovals.content(row));
    }

    static void decided(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        if (!storedRequest(ctx)) {
            unchanged(ctx, row);
            return;
        }
        ApprovalResultInput input = result(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("decidedAt", ctx.opTime());
        if (ApprovalEntities.APPROVED.equals(input.status()) && boundRequest(ctx)) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval == null || approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                unchanged(ctx, row);
                return;
            }
            EntityInstance taken = first(ctx, TAKEN);
            if (taken != null && !String.valueOf((Object) row.get("sponsorId")).equals(taken.get("sponsorId"))) {
                // Verified for another Sponsor while this one was in review: one domain, one Sponsor.
                values.put("status", QbVerifications.REJECTED);
                values.put("rejectReason", VerificationCodes.DOMAIN_TAKEN);
            } else {
                values.put("status", QbVerifications.APPROVED);
                values.put("verifiedAt", ctx.opTime());
                ctx.put(VERIFIED_NOW, true);
            }
        } else if (ApprovalEntities.REJECTED.equals(input.status())
            && ApprovalEntities.REJECTED.equals(first(ctx, REQUEST).get("status"))) {
            values.put("status", QbVerifications.REJECTED);
            values.put("rejectReason", rejectReason(ctx));
        } else {
            unchanged(ctx, row);
            return;
        }
        ctx.changes().update(QbVerifications.VERIFICATION, row.id(), row.version(), values);
        ctx.put(DECIDED_NOW, values.get("status"));
        ctx.put(OUTPUT, new VerificationOutput(String.valueOf(row.id()), (String) values.get("status"),
            (String) row.get("method"), (String) row.get("approvalRequestId")));
    }

    /** The reviewer's reason for the rejection, from their decision; none when they gave none. */
    private static String rejectReason(ProcessContext ctx) {
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

    // ---- revoke ----------------------------------------------------------------------------------------------------

    /**
     * {@value #REVOKE}: headquarters, or a member of the Sponsor's operator (another's Sponsor is not found), withdraws
     * the Sponsor's verification with a reason the Sponsor reads (the controller, 2026-11-10: always a second factor).
     * The platform fee Kudos given for it are kept.
     */
    public static ProcessDefinition<RevokeInput, VerificationOutput, ProcessContext> revoke() {
        return ProcessDefinition.define(REVOKE, 1, RevokeInput.class, VerificationOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Withdraws a Sponsor's company verification, with a reason they read; the platform fee "
                        + "Kudos given are kept.")
                    .permissions(QbPermissions.VERIFICATION_REVOKE)
                    .requiresMfa(MfaRequirement.ALWAYS)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> start(start, input, null))
                    .outputMapper(ctx -> ctx.get(OUTPUT, VerificationOutput.class));
                CallerChecks.refuseBanned(pb);
                // An operator's member is checked within the act (Q48e-1); headquarters passes.
                OperatorChecks.memberWhenScoped(pb);
                OperatorChecks.ownSponsor(pb, ctx -> ctx.get(INPUT, RevokeInput.class).sponsorId().strip());
                pb.step("Lock the Sponsor's verification", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbVerifications.lockName(ctx.get(INPUT, RevokeInput.class).sponsorId().strip())));
                pb.step("Load the verification", QueryEntities.<ProcessContext>of(QbVerifications.DEFAULT_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", ctx.get(INPUT, RevokeInput.class).sponsorId().strip()),
                        new QueryPredicate.Eq("status", QbVerifications.APPROVED)))).limit(1).build(), ROW));
                pb.compute("Withdraw", (metadata, ctx) -> {
                    EntityInstance row = first(ctx, ROW);
                    if (row == null) {
                        ctx.reject(new Violation("sponsorId", VerificationCodes.STATE, "The Sponsor's company is not "
                            + "verified", Map.of("status", "NONE")));
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", QbVerifications.REVOKED);
                    values.put("endedAt", ctx.opTime());
                    values.put("revokeReason", ctx.get(INPUT, RevokeInput.class).reason().strip());
                    values.put("revokedOperatorId", ctx.contains(OperatorChecks.OPERATOR_ID)
                        ? OperatorChecks.operatorId(ctx) : null);
                    ctx.changes().update(QbVerifications.VERIFICATION, row.id(), row.version(), values);
                    ctx.put(OUTPUT, new VerificationOutput(String.valueOf(row.id()), QbVerifications.REVOKED,
                        (String) row.get("method"), (String) row.get("approvalRequestId")));
                });
                pb.step("Tell the Sponsor", PublishEvent.<ProcessContext>when(ctx -> !ctx.hasViolations(),
                    DECIDED_EVENT, ctx -> eventPayload(first(ctx, ROW), QbVerifications.REVOKED)));
            });
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    static Map<String, Object> eventPayload(EntityInstance row, String status) {
        return Map.of("verificationId", String.valueOf(row.id()), "sponsorId",
            String.valueOf((Object) row.get("sponsorId")), "status", status);
    }

    static EntityQuery byId(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null && !id.toString().isBlank()) {
            ids.add(id.toString().strip());
        }
        return EntityQuery.builder().where(new QueryPredicate.In("verificationId", ids)).limit(1).build();
    }

    private static ProcessContext start(ProcessStart start, Object input, String id) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        if (id != null) {
            ctx.put(ID, id.strip());
        }
        return ctx;
    }

    static String id(ProcessContext ctx) {
        return ctx.contains(ID) ? ctx.get(ID, String.class) : null;
    }

    private static void unchanged(ProcessContext ctx, EntityInstance row) {
        if (row != null) {
            ctx.put(OUTPUT, new VerificationOutput(String.valueOf(row.id()), row.get("status"), row.get("method"),
                row.get("approvalRequestId")));
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
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
}
