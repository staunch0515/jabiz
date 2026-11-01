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
import com.jabiz.quizbuks.content.QuizProcesses;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.prospect.QbProspects;
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
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
    static final String OLD_AVATAR = "oldAvatar";
    static final String ASSIGNMENT = "assignment";
    static final String LANGUAGE_OPERATOR = "languageOperator";
    static final String TRIAL = "trial";

    /**
     * @param displayName  the sponsor's name (their profile's)
     * @param intro        Markdown, one language
     * @param avatar       a file of the policy {@code qb.sponsor-avatar} the caller uploaded; none to have none
     * @param acceptTerms  must be true: the terms are accepted with every application
     * @param baseRevision the onboarding's revision the caller edited; when given and no longer current, 422
     * @param termsVersionNo   the version of the sponsors' terms the page showed; while a version is in effect it must
     *                         be that one (Q7b, D-Q7-5)
     * @param privacyVersionNo the version of the privacy policy the page showed, likewise
     */
    /**
     * @param trialKey the prospecting trial that brought the sponsor (Q48e-1, the sign-up link's {@code ?src=}), if the
     *                 console kept one: a first application goes to the operator that prospected the business
     */
    public record ApplyInput(@NotNull String displayName, @NotNull String company, String intro, UUID avatar,
        Boolean acceptTerms, Long baseRevision, Integer termsVersionNo, Integer privacyVersionNo,
        @Size(max = 64) String trialKey, Map<String, Map<String, String>> introTranslations,
        List<String> introConfirmed) {

        /** An application with its introduction in one language (before Q73c). */
        public ApplyInput(String displayName, String company, String intro, UUID avatar, Boolean acceptTerms,
            Long baseRevision, Integer termsVersionNo, Integer privacyVersionNo, String trialKey) {
            this(displayName, company, intro, avatar, acceptTerms, baseRevision, termsVersionNo, privacyVersionNo,
                trialKey, null, null);
        }

        /** The versions the page showed, by kind of text. */
        Map<String, Integer> shown() {
            Map<String, Integer> shown = new HashMap<>();
            shown.put(QbLegal.TERMS_SPONSOR, termsVersionNo);
            shown.put(QbLegal.PRIVACY, privacyVersionNo);
            return shown;
        }
    }

    public record ProfileInput(@NotNull String displayName, @NotNull String company, String intro, UUID avatar,
        Long baseRevision, Map<String, Map<String, String>> introTranslations, List<String> introConfirmed) {

        public ProfileInput(String displayName, String company, String intro, UUID avatar, Long baseRevision) {
            this(displayName, company, intro, avatar, baseRevision, null, null);
        }
    }

    /** The introduction's field among its translations (Q73c). */
    public static final String INTRO = "intro";

    /**
     * Q73c: the introduction's translations after a save (optional; one out of date refused with the save's other
     * problems), as stored; null for none.
     */
    static String introTranslations(ProcessContext ctx, EntityInstance sponsor, String intro,
        Map<String, Map<String, String>> given, List<String> confirmed) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(INTRO, intro);
        Map<String, Map<String, com.jabiz.quizbuks.publishing.PublicationLanguages.Text>> stored =
            com.jabiz.quizbuks.publishing.PublicationLanguages.parse(sponsor == null ? null
                : sponsor.get("introTranslations"));
        List<String> languages = new java.util.ArrayList<>(given == null ? stored.keySet() : given.keySet());
        var merged = com.jabiz.quizbuks.content.TextTranslations.merge(given, stored, sources, languages, confirmed);
        com.jabiz.quizbuks.content.TextTranslations.problems(languages, languages, given == null ? null
            : given, merged, sources, Map.of(INTRO, AccountLimits.INTRO_LENGTH), false).forEach(ctx::reject);
        return com.jabiz.quizbuks.publishing.PublicationLanguages.toJson(merged);
    }


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

    public static ProcessDefinition<ApplyInput, SponsorOutput, ProcessContext> apply() {
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
            // Q59c: the assignment after the account (nothing takes them the other way round).
            pb.step("Lock the sponsor's assignment", HoldLock.<ProcessContext>exclusive(
                ctx -> QbOperators.assignmentLockName(AccountEditing.userId(ctx))));
            CallerChecks.refuseBanned(pb);
            AccountEditing.loadOfUser(pb, "Load the onboarding", QbAccounts.SPONSOR, SPONSORS);
            AccountEditing.loadOfUser(pb, "Load the profile", QbAccounts.PROFILE, PROFILES);
            pb.step("Load the assignment and the operator of the language", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId", List.of(AccountEditing.userId(ctx)))).limit(1).build(),
                    ASSIGNMENT),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("language", List.of(QuizProcesses.languageOf(ctx))),
                        new QueryPredicate.In("status", List.of(QbOperators.ACTIVE))))).limit(1).build(),
                    LANGUAGE_OPERATOR)));
            // Q48e-1: the trial that brought the sponsor, read again under its lock (after the assignment's, as the
            // claim takes them), for a first application only.
            pb.step("Find the trial that brought them", QueryEntities.<ProcessContext>of(
                    QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> trialQuery(ctx, null), TRIAL))
                .step("Lock the trial", HoldLock.<ProcessContext>exclusive(
                    ctx -> first(ctx, TRIAL) == null ? null : QbProspects.prospectLockName(first(ctx, TRIAL).id())))
                .step("Read the trial again", QueryEntities.<ProcessContext>of(
                    QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> trialQuery(ctx, first(ctx, TRIAL)), TRIAL));
            AccountEditing.loadFile(pb, ctx -> ctx.get(INPUT, ApplyInput.class).avatar());
            pb.step("Load the review switch", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.REVIEW_SPONSOR_AUTO));
            AccountEditing.loadUser(pb, AccountEditing::userId);
            ConsentChecks.load(pb, "Load the terms in effect", QbLegal.SPONSOR_KINDS, AccountEditing::userId);
            pb.compute("Check the application", (metadata, ctx) -> checkApplication(ctx));
            pb.step("Ask for a review", RequireApproval.<ProcessContext>when(ctx -> ctx.contains(CASE),
                SponsorApprovals.SUBJECT, ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL));
            pb.compute("Apply", (metadata, ctx) -> applied(ctx));
            pb.step("Close the request to submit again", CloseTasks.<ProcessContext>done(
                ctx -> QbAccounts.taskSource(AccountEditing.userId(ctx))));
            releaseOldAvatar(pb);
            approvedMail(pb);
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
        // Applying accepts the sponsors' terms and the privacy policy in effect (Q7b): the versions the page showed must
        // be those, and are recorded; the application is the server's check of consent for onboarding (Q7-2).
        List<ConsentChecks.State> consents = Boolean.TRUE.equals(input.acceptTerms())
            ? ConsentChecks.accepted(ctx, input.shown()) : List.of();
        AccountEditing.current(ctx, sponsor, input.baseRevision(), AccountCodes.SPONSOR_CHANGED);
        AccountEditing.requireOwnFile(ctx, AccountEditing.userId(ctx), "avatar", input.avatar(),
            sponsor == null ? null : sponsor.get("avatar"));
        String introTranslations = introTranslations(ctx, sponsor, blankToNull(input.intro()),
            input.introTranslations(), input.introConfirmed());
        if (ctx.hasViolations()) {
            return;
        }
        Object id = sponsor == null ? ctx.get(NEW_ID) : sponsor.id();
        // The time of the first acceptance stays (Q2 review 6) and the version accepted is part of the content (Q7b):
        // the same application under the same terms is the same content, so it finds the same request (pending)
        // rather than replacing it.
        Instant accepted = sponsor == null ? null : AccountEditing.instant(sponsor.get("termsAcceptedAt"));
        Instant terms = accepted != null ? accepted : ctx.opTime();
        ConsentChecks.record(ctx, AccountEditing.userId(ctx), consents, QbLegal.SOURCE_ONBOARDING);
        ConsentChecks.State sponsorTerms = ConsentChecks.states(ctx).get(QbLegal.TERMS_SPONSOR);
        Integer termsVersionNo = sponsorTerms == null ? null : sponsorTerms.currentVersionNo();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("contactName", input.displayName());
        values.put("company", input.company());
        values.put("intro", blankToNull(input.intro()));
        values.put("introTranslations", introTranslations);
        values.put("avatar", input.avatar());
        values.put("termsAcceptedAt", terms);
        values.put("termsVersionNo", termsVersionNo == null ? null : BigDecimal.valueOf(termsVersionNo));
        values.put("submittedAt", ctx.opTime());
        values.put("rejectReason", null);
        values.put("rejectedAt", null);
        ctx.put(VALUES, values);
        oldAvatar(ctx, sponsor, input.avatar());
        ApprovalCase review = ApprovalCase.of(id, SponsorApprovals.facts(autoApprove(ctx)), SponsorApprovals.content(
            input.displayName(), input.company(), blankToNull(input.intro()), input.avatar(), terms, termsVersionNo,
            introTranslations))
            .reference(reference(input.company()));
        // Q59c-2: the review is the operator's who looks after the sponsor (the one of the language for a first
        // application, which this process assigns them to); headquarters' for a direct sponsor.
        String operator = operatorOf(ctx);
        ctx.put(CASE, operator == null ? review : review.scope(QbOperators.SCOPE, operator));
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
        // The profile shows the name too; the last writer wins there (the onboarding keeps the approved one).
        String userId = AccountEditing.userId(ctx);
        AccountEditing.writeName(ctx, first(ctx, PROFILES), userId, input.displayName());
        write(ctx, first(ctx, SPONSORS), values, approval.status().name());
        assignByLanguage(ctx, userId);
    }

    /**
     * The trial named by the input, for a first application of a sponsor of no operator: none otherwise. Read again by
     * its id once locked ({@code found}).
     */
    static EntityQuery trialQuery(ProcessContext ctx, EntityInstance found) {
        String key = ctx.get(INPUT, ApplyInput.class).trialKey();
        List<Object> values = new java.util.ArrayList<>();
        boolean first = first(ctx, SPONSORS) == null && first(ctx, ASSIGNMENT) == null;
        if (found != null) {
            values.add(String.valueOf(found.id()));
        } else if (first && key != null && !key.isBlank()) {
            values.add(key.strip());
        }
        return EntityQuery.builder().where(new QueryPredicate.In(found == null ? "trialKey" : "prospectId", values))
            .limit(1).build();
    }

    /**
     * Q48e-1: the trial a first application goes by: one that went out, of an operator (not headquarters'), that no
     * other sponsor claimed. The key is random and mailed to the business only, so it is the evidence, as for
     * {@code QB_PROSPECT_CLAIM}.
     */
    static EntityInstance trial(ProcessContext ctx) {
        EntityInstance trial = first(ctx, TRIAL);
        if (trial == null || !QbProspects.SENT.equals(trial.get("status"))
            || QbProspects.DIRECT.equals(trial.get("owner"))) {
            return null;
        }
        Object converted = trial.get("convertedUserId");
        return converted == null || AccountEditing.userId(ctx).equals(String.valueOf(converted)) ? trial : null;
    }

    /** The operator the sponsor is (or this first application makes them) looked after by; null: headquarters. */
    static String operatorOf(ProcessContext ctx) {
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        if (assignment != null) {
            return String.valueOf((Object) assignment.get("operatorId"));
        }
        if (first(ctx, SPONSORS) != null) {
            return null;
        }
        // Q48e-1: the operator that prospected the business comes before the one of the language.
        EntityInstance trial = trial(ctx);
        if (trial != null) {
            return String.valueOf((Object) trial.get("owner"));
        }
        EntityInstance operator = first(ctx, LANGUAGE_OPERATOR);
        return operator != null ? String.valueOf(operator.id()) : null;
    }

    /**
     * Q59c: a sponsor's first application goes to the active operator of the language they applied in, unless they
     * have an operator already; none of the language: headquarters'. A prospecting claim made afterwards replaces it.
     */
    static void assignByLanguage(ProcessContext ctx, String userId) {
        if (first(ctx, SPONSORS) != null || first(ctx, ASSIGNMENT) != null) {
            return;
        }
        EntityInstance trial = trial(ctx);
        if (trial != null) {
            assignToProspecting(ctx, userId, trial);
            return;
        }
        EntityInstance operator = first(ctx, LANGUAGE_OPERATOR);
        if (operator == null) {
            return;
        }
        String language = String.valueOf((Object) operator.get("language"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userId", userId);
        values.put("operatorId", String.valueOf(operator.id()));
        values.put("assignedAt", ctx.opTime());
        values.put("assignedBy", QbOperators.BY_LANGUAGE);
        values.put("reason", "Sign-up language: " + language);
        ctx.changes().insert(QbOperators.ASSIGNMENT, values);
    }

    /**
     * Q48e-1: a first application that came by an operator's trial goes to that operator, as {@code QB_PROSPECT_CLAIM}
     * would assign it afterwards (the same values), and the trial is marked converted by this sponsor.
     */
    static void assignToProspecting(ProcessContext ctx, String userId, EntityInstance trial) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userId", userId);
        values.put("operatorId", String.valueOf((Object) trial.get("owner")));
        values.put("assignedAt", ctx.opTime());
        values.put("assignedBy", "prospect:" + trial.id());
        values.put("reason", "AI prospecting: " + trial.get("domain"));
        ctx.changes().insert(QbOperators.ASSIGNMENT, values);
        if (trial.get("convertedUserId") == null) {
            ctx.changes().update(QbProspects.PROSPECT, trial.id(), trial.version(), Map.of("convertedUserId", userId,
                "convertedAt", ctx.opTime(), "revision",
                BigDecimal.valueOf(AccountEditing.number(trial.get("revision")) + 1)));
        }
    }

    /** Notes the avatar the onboarding had, when this write replaces or removes it. */
    private static void oldAvatar(ProcessContext ctx, EntityInstance sponsor, UUID given) {
        Object stored = sponsor == null ? null : sponsor.get("avatar");
        if (stored != null && !AccountEditing.same(stored, given)) {
            ctx.put(OLD_AVATAR, AccountEditing.uuid(stored));
        }
    }

    /**
     * Releases a replaced avatar (review S3; root CLAUDE.md, files): the reference is cleared and saved first, then the
     * platform deletes the file, which it refuses while anything still refers to it.
     */
    private static <I, O> void releaseOldAvatar(com.jabiz.process.ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Save", SaveChanges.now())
            .step("Release the old avatar", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && ctx.contains(OLD_AVATAR), FileProcesses.DELETE, 1,
                ctx -> new FileProcesses.DeleteInput(ctx.get(OLD_AVATAR, UUID.class)), null));
    }

    private static void approve(ProcessContext ctx, Map<String, Object> values, String requestId) {
        values.put("reviewStatus", SponsorStatus.APPROVED);
        values.put("approvalRequestId", requestId);
        values.put("approvedAt", ctx.opTime());
        values.put("rejectReason", null);
        values.put("rejectedAt", null);
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

    private static <I, O> void approvedMail(com.jabiz.process.ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Tell the sponsor they are approved", SendMail.<ProcessContext>when(ctx -> !ctx.hasViolations()
                && Boolean.TRUE.equals(ctx.get(APPROVED_NOW)) && AccountEditing.hasAddress(ctx),
            AccountMails.SPONSOR_APPROVED, ctx -> MailRecipient.user(AccountEditing.userId(ctx)),
            Map.of("company", SponsorProcesses::company)));
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
                CallerChecks.refuseBanned(pb);
                AccountEditing.loadOfUser(pb, "Load the onboarding", QbAccounts.SPONSOR, SPONSORS);
                AccountEditing.loadOfUser(pb, "Load the profile", QbAccounts.PROFILE, PROFILES);
                AccountEditing.loadFile(pb, ctx -> ctx.get(INPUT, ProfileInput.class).avatar());
                pb.compute("Save the sponsor profile", (metadata, ctx) -> saveProfile(ctx));
                releaseOldAvatar(pb);
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
        String introTranslations = introTranslations(ctx, sponsor, blankToNull(input.intro()),
            input.introTranslations(), input.introConfirmed());
        if (ctx.hasViolations()) {
            return;
        }
        AccountEditing.writeName(ctx, first(ctx, PROFILES), userId, input.displayName());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("contactName", input.displayName());
        values.put("company", input.company());
        values.put("intro", blankToNull(input.intro()));
        values.put("introTranslations", introTranslations);
        values.put("avatar", input.avatar());
        oldAvatar(ctx, sponsor, input.avatar());
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
    public static ProcessDefinition<ApprovalResultInput, SponsorOutput, ProcessContext> approvalResult() {
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
                pb.step("Load the request", QueryEntities.<ProcessContext>of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        ownRequest(ctx) ? AccountEditing.uuids(input(ctx).requestId()) : new ArrayList<>()))
                        .limit(1).build(), REQUEST));
                pb.step("Load the decisions", QueryEntities.<ProcessContext>of(ApprovalEntities.DECISION_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        storedRequest(ctx) ? AccountEditing.uuids(input(ctx).requestId()) : new ArrayList<>()))
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
                approvedMail(pb);
                pb.step("Tell the sponsor they are sent back", SendMail.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(REJECTED_NOW)) && AccountEditing.hasAddress(ctx),
                    AccountMails.SPONSOR_REJECTED, ctx -> MailRecipient.user(owner(ctx)),
                    Map.of("company", SponsorProcesses::company, "reason", SponsorProcesses::reason)));
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

    /** Whether the stored request (not the event) is the onboarding's own: of this subject and onboarding. */
    private static boolean storedRequest(ProcessContext ctx) {
        EntityInstance request = first(ctx, REQUEST);
        if (!ownRequest(ctx) || request == null) {
            return false;
        }
        return SponsorApprovals.SUBJECT.equals(request.get("subject"))
            && String.valueOf(first(ctx, FOUND).id()).equals(request.get("entityId"));
    }

    /**
     * Whether the stored request is the onboarding's own and bound to its present content, which only applying again
     * changes while it is pending (the content is the onboarding's alone, review 1). An approval needs it.
     */
    private static boolean boundRequest(ProcessContext ctx) {
        return storedRequest(ctx) && SponsorApprovals.hash(storedCase(ctx).content())
            .equals(String.valueOf((Object) first(ctx, REQUEST).get("contentHash")).strip());
    }

    /** The case of the onboarding as stored: its present content. */
    private static ApprovalCase storedCase(ProcessContext ctx) {
        EntityInstance sponsor = first(ctx, FOUND);
        return ApprovalCase.of(sponsor.id(), SponsorApprovals.facts(false), SponsorApprovals.content(
            sponsor.get("contactName"), sponsor.get("company"), sponsor.get("intro"),
            AccountEditing.uuid(sponsor.get("avatar")), AccountEditing.instant(sponsor.get("termsAcceptedAt")),
            termsVersionNo(sponsor.get("termsVersionNo")), sponsor.get("introTranslations")))
            .reference(reference(sponsor.get("company")));
    }

    /** The stored version of the terms, as the content holds it (a number, or none). */
    static Integer termsVersionNo(Object stored) {
        return stored == null ? null : (int) AccountEditing.number(stored);
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
        if (!storedRequest(ctx)) {
            unchanged(ctx, sponsor);
            return;
        }
        ApprovalResultInput input = input(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        if (ApprovalEntities.APPROVED.equals(input.status()) && boundRequest(ctx)) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval == null || approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                unchanged(ctx, sponsor);
                return;
            }
            approve(ctx, values, approval.requestId());
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
