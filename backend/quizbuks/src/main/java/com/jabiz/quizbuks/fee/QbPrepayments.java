package com.jabiz.quizbuks.fee;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * Prepayments (F-M5, docs/quizbuks/plans/impl/F-M.md §8): what a publication's submission set aside of the sponsor's
 * account that pays the platform's fees, for the fees it will be charged while it runs (account 2180, its lines
 * carrying the sponsor, the publication and the purpose). A prepayment is not a fee: what is not charged of it goes
 * back where it came from when the publication goes back to its draft (withdrawn, rejected, its review expired) or a
 * day after it ends.
 *
 * <p>{@value #PREPAYMENT} is an ordinary table (its remaining amount and status change), written only by the
 * prepayment processes ({@code fee.Prepayments}) under the sponsor's wallet lock. A prepaid sponsor's prepayment
 * ({@value #PREPAID}) holds money: its parts are {@code QbFeePayment} rows aimed at it, taken from the fee credit and
 * cash lots in the account's one order; a fee charged from it is paid by parts naming it ({@code prepaymentId}); what is
 * released is a part of its own with the amount negated. A postpaid sponsor's ({@value #POSTPAID}) holds no money: it
 * only counts against their credit limit until its fee is charged or it is released.
 */
public final class QbPrepayments {

    public static final String PREPAYMENT = "QbPrepayment";
    public static final String SPONSOR_PREPAYMENTS = "urn:jabiz:dataset:sponsor:" + PREPAYMENT;

    public static final String PURPOSE_DICTIONARY = "urn:jabiz:dict:quizbuks:prepayment-purpose";
    public static final String MODE_DICTIONARY = "urn:jabiz:dict:quizbuks:prepayment-mode";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:prepayment-status";

    /** What a prepayment is for: the ledger dimension {@value #PURPOSE} of 2180 (F-M6, F-M8, F-M7, F-M9). */
    public static final String DISTRIBUTION = "DISTRIBUTION";
    public static final String EXPOSURE = "EXPOSURE";
    public static final String AUDIENCE = "AUDIENCE";
    public static final String AI = "AI";
    public static final List<String> PURPOSES = List.of(DISTRIBUTION, EXPOSURE, AUDIENCE, AI);

    /** Whether it holds money (a prepaid sponsor's) or only counts against the credit limit (a postpaid one's). */
    public static final String PREPAID = FeeRules.PREPAID;
    public static final String POSTPAID = FeeRules.POSTPAID;
    public static final List<String> MODES = List.of(PREPAID, POSTPAID);

    public static final String OPEN = "OPEN";
    public static final String RELEASED = "RELEASED";
    public static final List<String> STATUSES = List.of(OPEN, RELEASED);

    /** Why what was left of it went back. */
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String REJECTED = "REJECTED";
    public static final String REVIEW_EXPIRED = "REVIEW_EXPIRED";
    public static final String ENDED = "ENDED";
    public static final List<String> RELEASE_REASONS = List.of(WITHDRAWN, REJECTED, REVIEW_EXPIRED, ENDED);

    /** The ledger dimension (position 3) that carries the purpose on 2180's lines. */
    public static final String PURPOSE = "purpose";

    public static final int DETAIL_LENGTH = 4000;
    public static final String REFUND_OUTCOME_DICTIONARY = "urn:jabiz:dict:quizbuks:audience-refund-outcome";

    private QbPrepayments() {}

    public static final EntityDefinition PREPAYMENT_ENTITY = EntityDefinition.define(PREPAYMENT, eb -> {
        eb.physicalTable("qb_prepayment");
        eb.primaryKey("prepaymentId");
        eb.field("prepaymentId", f -> f.physicalColumn("prepayment_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:prepayment"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        // The publication it was set aside for (none: an AI credit, F-M9).
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).processOnly().asText(36));
        eb.field("purpose", f -> f.physicalColumn("purpose").immutable(true).required(true).processOnly()
            .asCode(PURPOSE_DICTIONARY, PURPOSES.toArray(String[]::new)));
        eb.field("mode", f -> f.physicalColumn("mode").immutable(true).required(true).processOnly()
            .asCode(MODE_DICTIONARY, MODES.toArray(String[]::new)));
        // What was set aside (the fee and its tax), and what is left of it. F-M8: the exposure fee's deposit is raised
        // by each top-up (QB_PREPAYMENT_TOP_UP, the only writer), so the amount is not immutable; the others never change.
        eb.field("amount", f -> f.physicalColumn("amount").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("remaining", f -> f.physicalColumn("remaining").required(true).processOnly().asMonetary("JPY", 0));
        // The fee before tax it stands for, charged from it when the publication goes on (F-M7's audience fee).
        eb.field("chargeAmount", f -> f.physicalColumn("charge_amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        // F-M11: the operator its fees belong to, frozen at the submission.
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).processOnly().asText(36));
        // What the amount was worked out from (the audience fee's quote), as JSON, for the pages and the checks. As it was
        // at the submission: an exposure top-up (F-M8) raises the amount but does not rewrite this.
        eb.field("detail", f -> f.physicalColumn("detail").immutable(true).processOnly().asText(DETAIL_LENGTH, true));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("frozenAt", f -> f.physicalColumn("frozen_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("chargedAt", f -> f.physicalColumn("charged_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("releasedAt", f -> f.physicalColumn("released_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("releaseReason", f -> f.physicalColumn("release_reason").processOnly().asText(20));
        // F-M7: the audience fee's refund of unused days, decided once (the refund job takes those not decided).
        eb.field("refundDecidedAt", f -> f.physicalColumn("refund_decided_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("refundOutcome", f -> f.physicalColumn("refund_outcome").processOnly()
            .asCode(REFUND_OUTCOME_DICTIONARY, AudienceRefunds.OUTCOMES.toArray(String[]::new)));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "publicationId", "purpose", "mode", "amount", "remaining", "status", "frozenAt")
            .filters("sponsorId", "publicationId", "purpose", "mode", "status", "frozenAt", "releasedAt")
            .sorts("frozenAt")
            .defaultSort("frozenAt", false));
    });
}
