package com.jabiz.quizbuks.identity;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;
import com.jabiz.runtime.security.SecurityEntities;

import java.util.List;

/**
 * The three entities of accounts (docs/quizbuks/02-design.md section 3.1, docs/quizbuks/plans/Q2-accounts.md): a
 * person's profile, a sponsor's onboarding and a user's ban. All are temporal (their versions are the history the
 * administrators and the audit read), without scheduled versions, and one per user: the platform's user id is the
 * immutable, unique {@code userId} (a temporal entity's own key is a generated UUID). A profile's and an onboarding's
 * is the caller's id as text, as Q4's {@code sponsorId}; a ban's refers to the platform's user. Only the processes of
 * {@link ProfileProcesses}, {@link SponsorProcesses} and {@link BanProcesses} write them.
 */
public final class QbAccounts {

    public static final String PROFILE = "QbProfile";
    public static final String SPONSOR = "QbSponsor";
    public static final String BAN = "QbUserBan";

    public static final String CURRENCY_DICTIONARY = "urn:jabiz:dict:quizbuks:currency";
    public static final String REVIEW_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:sponsor-review-status";

    /** The currencies a taker may prefer for showing amounts (U-04): a display hint only, Kudos are JPY. */
    public static final List<String> CURRENCIES = List.of("USD", "EUR", "GBP", "JPY", "CNY", "INR");

    /** Sponsors' avatars: uploaded by the sponsor, re-encoded without metadata. */
    public static final String AVATAR_POLICY = "qb.sponsor-avatar";

    /** The task a sponsor gets when their onboarding is sent back; closed by applying again. */
    public static final String RESUBMIT_TASK = "qb.sponsor.resubmit";
    public static final String RESUBMIT_TASK_TITLE = "task.qb.sponsor.resubmit";

    /** The default (administrators' read-only) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** One's own profile (takers and sponsors), read only. */
    public static final String MY_PROFILE_DATASET = "urn:jabiz:dataset:me:" + PROFILE;
    /** A sponsor's own onboarding, read only. */
    public static final String MY_SPONSOR_DATASET = "urn:jabiz:dataset:sponsor:" + SPONSOR;
    /** One's own sign-in records (M-02), read only: the platform's entity, limited to the caller. */
    public static final String MY_SIGN_INS_DATASET = "urn:jabiz:dataset:me:SecLoginRecord";

    /**
     * The name of a user's account lock ({@code HoldLock}): every process that writes a user's profile, onboarding or
     * ban takes it exclusively before reading them (D-Q2-10). It comes before every other lock: a ban holds it while
     * it pauses the sponsor's publications (their locks after it, Q10b), and the approval result of a publication
     * takes the sponsor's before the publication's (review L1). Null without a user.
     */
    public static String lockName(Object userId) {
        return userId == null ? null : "qb.account:" + userId;
    }

    /** The source key of a sponsor's open onboarding tasks ({@code CreateTask} / {@code CloseTasks}). */
    public static String taskSource(Object userId) {
        return "qb.sponsor:" + userId;
    }

    public static final EntityDefinition PROFILE_ENTITY = EntityDefinition.define(PROFILE, eb -> {
        eb.physicalTable("qb_profile_version");
        eb.primaryKey("profileId");
        eb.field("profileId", f -> f.physicalColumn("profile_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:profile"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("displayName", f -> f.physicalColumn("display_name").required(true)
            .asText(AccountLimits.DISPLAY_NAME_LENGTH).apply(Rules.notBlank(AccountCodes.NAME_BLANK)));
        // A sponsor's profile carries only the name: the country and currency are the takers' (U-03, U-04).
        eb.field("countryCode", f -> f.physicalColumn("country_code").asText(2)
            .apply(Rules.pattern(AccountCodes.COUNTRY_FORMAT, "[A-Z]{2}")));
        eb.field("city", f -> f.physicalColumn("city").asText(AccountLimits.CITY_LENGTH));
        eb.field("preferredCurrency", f -> f.physicalColumn("preferred_currency")
            .asCode(CURRENCY_DICTIONARY, CURRENCIES.toArray(String[]::new)));
        eb.field("createdAt", f -> f.physicalColumn("created_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.unique("uk_qb_profile_user", "userId");
        eb.display("displayName");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("userId", "displayName", "countryCode", "city", "preferredCurrency", "createdAt", "editedAt")
            .filters("userId", "displayName", "countryCode", "preferredCurrency", "createdAt")
            .sorts("createdAt", "displayName", "editedAt")
            .defaultSort("createdAt", false));
    });

    public static final EntityDefinition SPONSOR_ENTITY = EntityDefinition.define(SPONSOR, eb -> {
        eb.physicalTable("qb_sponsor_version");
        eb.primaryKey("onboardingId");
        eb.field("onboardingId", f -> f.physicalColumn("onboarding_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        // The sponsor's name as applied for and approved (M-01, M-03). The profile carries a name too (the taker's,
        // shown in the app); this one is the onboarding's own, so renaming the profile never unbinds a review.
        eb.field("contactName", f -> f.physicalColumn("contact_name").required(true)
            .asText(AccountLimits.DISPLAY_NAME_LENGTH).apply(Rules.notBlank(AccountCodes.NAME_BLANK)));
        eb.field("company", f -> f.physicalColumn("company").required(true).asText(AccountLimits.COMPANY_LENGTH)
            .apply(Rules.notBlank(AccountCodes.COMPANY_BLANK)));
        // Markdown in one language, rendered by the frontends only, without raw HTML.
        eb.field("intro", f -> f.physicalColumn("intro").asText(AccountLimits.INTRO_LENGTH, true));
        eb.field("avatar", f -> f.physicalColumn("avatar").kind(FileKind.of(AVATAR_POLICY)));
        eb.field("reviewStatus", f -> f.physicalColumn("review_status").required(true).processOnly()
            .asCode(REVIEW_STATUS_DICTIONARY, SponsorStatus.ALL.toArray(String[]::new)));
        eb.field("termsAcceptedAt", f -> f.physicalColumn("terms_accepted_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // The version of the sponsors' terms accepted with the latest application (Q7b); none before Q7b or while no
        // version was in effect. The consents themselves are QbConsent rows.
        eb.field("termsVersionNo", f -> f.physicalColumn("terms_version_no").processOnly().asNumeric(6, 0));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(64));
        eb.field("submittedAt", f -> f.physicalColumn("submitted_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvedAt", f -> f.physicalColumn("approved_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").processOnly()
            .asText(AccountLimits.REASON_LENGTH, true));
        eb.field("rejectedAt", f -> f.physicalColumn("rejected_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // D-Q2-3. Applying makes it pending, or approved at once when no review is needed (the switch, or the same
        // content approved before); a rejected one applies again. Approved is final, so it cannot be a first state
        // (the platform wants a way out of every initial state): a first application approved at once is written
        // pending, then approved, in the same operation.
        eb.stateTransitions("reviewStatus", st -> st
            .initial(SponsorStatus.PENDING)
            .from(SponsorStatus.PENDING).to(SponsorStatus.APPROVED, SponsorStatus.REJECTED)
            .from(SponsorStatus.REJECTED).to(SponsorStatus.PENDING, SponsorStatus.APPROVED));
        eb.unique("uk_qb_sponsor_user", "userId");
        eb.display("company");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("userId", "contactName", "company", "reviewStatus", "submittedAt", "approvedAt", "rejectReason",
                "editedAt")
            .filters("userId", "company", "reviewStatus", "submittedAt", "approvedAt")
            .sorts("submittedAt", "approvedAt", "company", "editedAt")
            .defaultSort("submittedAt", false));
    });

    public static final EntityDefinition BAN_ENTITY = EntityDefinition.define(BAN, eb -> {
        eb.physicalTable("qb_user_ban_version");
        eb.primaryKey("banId");
        eb.field("banId", f -> f.physicalColumn("ban_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:user-ban"));
        // A platform user (only they sign in), as a reference: the sign-in guard's read binds the user id as a UUID,
        // which a text field refuses (platform gap 15 of the plan).
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asReference(SecurityEntities.USER));
        eb.field("banned", f -> f.physicalColumn("banned").required(true).processOnly().asBool());
        eb.field("reason", f -> f.physicalColumn("reason").processOnly().asText(AccountLimits.REASON_LENGTH, true));
        eb.field("bannedAt", f -> f.physicalColumn("banned_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("bannedBy", f -> f.physicalColumn("banned_by").processOnly().asText(AccountLimits.USER_ID_LENGTH));
        eb.field("liftedAt", f -> f.physicalColumn("lifted_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("liftedBy", f -> f.physicalColumn("lifted_by").processOnly().asText(AccountLimits.USER_ID_LENGTH));
        eb.field("liftNote", f -> f.physicalColumn("lift_note").processOnly()
            .asText(AccountLimits.REASON_LENGTH, true));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.unique("uk_qb_user_ban_user", "userId");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("userId", "banned", "reason", "bannedAt", "bannedBy", "liftedAt", "liftedBy", "liftNote")
            .filters("userId", "banned", "bannedAt", "liftedAt")
            .sorts("bannedAt", "liftedAt")
            .defaultSort("bannedAt", false));
    });

    /**
     * The approval subject of onboarding (D-Q2-3): the rule {@link SponsorApprovals#RULE} asks for a review unless
     * {@code autoApprove}. A case's id is the {@code onboardingId}.
     */
    public static ApprovalSubject approvalSubject() {
        return ApprovalSubject.define(SponsorApprovals.SUBJECT, s -> s.entity(SPONSOR).bool("autoApprove"));
    }

    private QbAccounts() {}
}
