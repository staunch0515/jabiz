package com.jabiz.quizbuks.verification;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.identity.AccountLimits;

import java.util.List;

/**
 * A Sponsor's company verification by the company's mail domain (F-M3, docs/quizbuks/plans/impl/F-M.md §6; Q105 §3.5,
 * D-Q105-11): the company's name and its domain, proven either by the account's own address on the domain or by a
 * one-time link mailed to an address on it, then reviewed by another member of the Sponsor's operator (the platform's
 * approval, subject {@value VerificationApprovals#SUBJECT}). A Sponsor is verified while they have a row
 * {@value #APPROVED} ({@link VerificationRules#verified}); one domain is verified for one Sponsor only.
 *
 * <p>One submission is one row, kept with its outcome: an ordinary table written by {@link VerificationProcesses} only
 * ({@code processOnlyWrites}), every change through the platform's write path and its audit. The address the link went
 * to is the Sponsor's to see (and the reviewer's); it is masked in the audit and cleared when the account is erased.
 */
public final class QbVerifications {

    public static final String VERIFICATION = "QbSponsorVerification";
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + VERIFICATION;
    /** The Sponsor's own submissions, read only. */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + VERIFICATION;
    /** The verified companies anybody may read: the Sponsor, the domain and when (the public page's mark). */
    public static final String PUBLIC_DATASET = "urn:jabiz:dataset:public:" + VERIFICATION;
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:sponsor-verification-status";

    /** The link went to an address on the domain; waiting for it to be opened. */
    public static final String LINK_SENT = "LINK_SENT";
    /** The domain is proven; waiting for the operator's review. */
    public static final String REVIEW = "REVIEW";
    /** Verified: the public page says so. */
    public static final String APPROVED = "APPROVED";
    /** The review sent it back (with the reason); the Sponsor corrects it and submits again. */
    public static final String REJECTED = "REJECTED";
    /** A submission in progress the Sponsor replaced by another. */
    public static final String WITHDRAWN = "WITHDRAWN";
    /** A verification the Sponsor replaced by another name or domain: it ends at once (the controller, 2026-11-10). */
    public static final String SUPERSEDED = "SUPERSEDED";
    /** Withdrawn by headquarters or an operator, with a reason; the platform fee Kudos given are kept. */
    public static final String REVOKED = "REVOKED";
    public static final List<String> STATUSES = List.of(LINK_SENT, REVIEW, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED,
        REVOKED);
    /** A submission in progress (at most one per Sponsor, with or beside none verified). */
    public static final List<String> OPEN = List.of(LINK_SENT, REVIEW);

    public static final int NAME_LENGTH = 200;
    public static final int DOMAIN_LENGTH = 253;
    public static final int ADDRESS_LENGTH = 320;
    public static final int REASON_LENGTH = 500;
    public static final int ID_LENGTH = 36;

    /** A Sponsor's verification: its submissions, the review's result and a withdrawal one after another. */
    public static String lockName(Object sponsorId) {
        return sponsorId == null ? null : "qb.sponsor-verification:" + sponsorId;
    }

    /**
     * A domain's verification: an approval checks under it that no other Sponsor holds the domain (after the
     * Sponsor's lock, before the wallet's).
     */
    public static String domainLockName(Object domain) {
        return domain == null ? null : "qb.sponsor-verification-domain:" + domain;
    }

    public static final EntityDefinition ENTITY = EntityDefinition.define(VERIFICATION, eb -> {
        eb.physicalTable("qb_sponsor_verification");
        eb.primaryKey("verificationId");
        eb.field("verificationId", f -> f.physicalColumn("verification_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor-verification"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        // The company's registered name and its domain (normalized, VerificationRules.domain), as submitted.
        eb.field("companyName", f -> f.physicalColumn("company_name").immutable(true).required(true)
            .asText(NAME_LENGTH));
        eb.field("domain", f -> f.physicalColumn("domain").immutable(true).required(true).asText(DOMAIN_LENGTH));
        // How the domain is proven: ACCOUNT (the account's own verified address is on it) or LINK (a mailed link).
        eb.field("method", f -> f.physicalColumn("method").immutable(true).required(true).asText(16));
        // The address the link went to (LINK only): masked in the audit, cleared when the account is erased.
        eb.field("confirmAddress", f -> f.physicalColumn("confirm_address").processOnly().auditMasked()
            .asText(ADDRESS_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("linkSentAt", f -> f.physicalColumn("link_sent_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("confirmedAt", f -> f.physicalColumn("confirmed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(64));
        // The operator that looked after the Sponsor when it went to review (the review's scope).
        eb.field("operatorId", f -> f.physicalColumn("operator_id").processOnly().asText(ID_LENGTH));
        eb.field("submittedAt", f -> f.physicalColumn("submitted_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("decidedAt", f -> f.physicalColumn("decided_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("verifiedAt", f -> f.physicalColumn("verified_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").processOnly().asText(REASON_LENGTH, true));
        eb.field("endedAt", f -> f.physicalColumn("ended_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revokeReason", f -> f.physicalColumn("revoke_reason").processOnly().asText(REASON_LENGTH, true));
        // The operator whose member withdrew it; none: headquarters (who exactly, the audit says).
        eb.field("revokedOperatorId", f -> f.physicalColumn("revoked_operator_id").processOnly().asText(ID_LENGTH));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("companyName");
        eb.listView("default", lv -> lv
            .columns("companyName", "domain", "sponsorId", "method", "status", "submittedAt", "verifiedAt", "endedAt")
            .filters("sponsorId", "status", "domain", "operatorId")
            .sorts("submittedAt", "verifiedAt", "decidedAt")
            .defaultSort("submittedAt", false));
    });

    private QbVerifications() {
    }
}
