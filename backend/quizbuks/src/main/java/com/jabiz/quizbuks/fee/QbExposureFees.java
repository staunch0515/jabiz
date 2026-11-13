package com.jabiz.quizbuks.fee;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * The exposure fee (F-M8, docs/quizbuks/plans/impl/F-M8.md): a price per exposure of a shared video that is not the
 * publisher's own, frozen at the publication's submission from the operator's price ({@value #PRICE}), charged a month
 * at a time ({@value #SETTLEMENT}) for the exposures counted (F-S13) up to the number bought, from the deposit the
 * submission set aside (a {@code QbPrepayment} of the purpose {@code EXPOSURE}).
 *
 * <p>{@value #PRICE} is an ordinary table, one row an operator, written only by
 * {@code QB_OPERATOR_EXPOSURE_PRICE_SET} (the operator's lead) and {@code QB_EXPOSURE_PRICE_SET} (the headquarters); no
 * row: the operator's sponsors pay no exposure fee and buy no number. {@value #SETTLEMENT} is written once
 * ({@code writeOnce}): one row a publication and month, one {@code final} row a publication, both kept by unique
 * indexes. {@value #NOTICE} is the one reminder for each number bought that the publication is running out.
 */
public final class QbExposureFees {

    public static final String PRICE = "QbExposurePrice";
    public static final String SETTLEMENT = "QbExposureSettlement";
    public static final String NOTICE = "QbExposureNotice";
    public static final String SPONSOR_SETTLEMENTS = "urn:jabiz:dataset:sponsor:" + SETTLEMENT;

    public static final int REASON_LENGTH = 500;

    private QbExposureFees() {}

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /**
     * An operator's exposure price is saved in turns. Its own lock, not {@code qb.operator:}: the lead's process holds
     * the member lock first, which the lock order puts after the operator's.
     */
    public static String priceLockName(String operatorId) {
        return "qb.exposure-price:" + operatorId;
    }

    public static final EntityDefinition PRICE_ENTITY = EntityDefinition.define(PRICE, eb -> {
        eb.physicalTable("qb_exposure_price");
        eb.primaryKey("priceId");
        eb.field("priceId", f -> f.physicalColumn("price_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:exposure-price"));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true).processOnly()
            .asText(36));
        // Yen per exposure, more than 0 (no price at all is how an operator charges nothing).
        eb.field("unit", f -> f.physicalColumn("unit").required(true).processOnly().asNumeric(19, 4));
        eb.field("reason", f -> f.physicalColumn("reason").required(true).processOnly().asText(REASON_LENGTH, true));
        eb.field("updatedAt", f -> f.physicalColumn("updated_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("updatedBy", f -> f.physicalColumn("updated_by").required(true).processOnly().asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("operatorId", "unit", "reason", "updatedAt", "updatedBy")
            .filters("operatorId")
            .sorts("operatorId", "updatedAt")
            .defaultSort("operatorId", true));
    });

    public static final EntityDefinition SETTLEMENT_ENTITY = EntityDefinition.define(SETTLEMENT, eb -> {
        eb.physicalTable("qb_exposure_settlement_version");
        eb.primaryKey("settlementId");
        eb.field("settlementId", f -> f.physicalColumn("settlement_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:exposure-settlement"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true).asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        // F-M11: the operator the fee belongs to, frozen at the submission.
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).asText(36));
        // The month whose exposures it counts (YYYY-MM, in the fiscal year's zone); a final row may count past it.
        eb.field("period", f -> f.physicalColumn("period").immutable(true).required(true).asText(7));
        eb.field("fromCount", f -> f.physicalColumn("from_count").immutable(true).required(true).asNumeric(19, 0));
        eb.field("toCount", f -> f.physicalColumn("to_count").immutable(true).required(true).asNumeric(19, 0));
        eb.field("counted", f -> f.physicalColumn("counted").immutable(true).required(true).asNumeric(19, 0));
        eb.field("unit", f -> f.physicalColumn("unit").immutable(true).required(true).asNumeric(19, 4));
        // What every exposure up to this row comes to, rounded up to the yen (before tax).
        eb.field("cumulativeAmount", f -> f.physicalColumn("cumulative_amount").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("tax", f -> f.physicalColumn("tax").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("total", f -> f.physicalColumn("total").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("final", f -> f.physicalColumn("final").immutable(true).required(true).asBool());
        // The fee charged (none when the amount and the tax are 0).
        eb.field("chargeId", f -> f.physicalColumn("charge_id").immutable(true).asText(36));
        eb.field("settledAt", f -> f.physicalColumn("settled_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("publicationId", "sponsorId", "operatorId", "period", "counted", "unit", "amount", "tax", "total",
                "final", "settledAt")
            .filters("publicationId", "sponsorId", "operatorId", "period", "final", "settledAt")
            .sorts("period", "settledAt")
            .defaultSort("settledAt", false));
    });

    public static final EntityDefinition NOTICE_ENTITY = EntityDefinition.define(NOTICE, eb -> {
        eb.physicalTable("qb_exposure_notice");
        eb.primaryKey("noticeId");
        eb.field("noticeId", f -> f.physicalColumn("notice_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:exposure-notice"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true).processOnly()
            .asText(36));
        // The number bought when the reminder was sent: the next one waits for a different number.
        eb.field("notifiedFor", f -> f.physicalColumn("notified_for").required(true).processOnly().asNumeric(9, 0));
        // The exposures counted when it was last looked at: looked at again only when more were counted.
        eb.field("checkedUsed", f -> f.physicalColumn("checked_used").required(true).processOnly().asNumeric(12, 0));
        eb.field("notifiedAt", f -> f.physicalColumn("notified_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
    });
}
