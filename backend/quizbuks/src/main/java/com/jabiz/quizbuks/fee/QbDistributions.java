package com.jabiz.quizbuks.fee;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * The distribution fee (F-M6, docs/quizbuks/plans/impl/F-M.md §9): a price per start of a publication's quiz, frozen
 * at its submission from the operator's price ({@value #PRICE}) and the publication's highest tier, charged a month at a
 * time ({@value #SETTLEMENT}) from the deposit its submission set aside (a {@code QbPrepayment} of the purpose
 * {@code DISTRIBUTION}).
 *
 * <p>{@value #PRICE} is an ordinary table, one row an operator, written only by {@code QB_OPERATOR_DISTRIBUTION_PRICE_SET}
 * (the operator's lead) and {@code QB_DISTRIBUTION_PRICE_SET} (the headquarters); no row: the operator's sponsors pay no
 * distribution fee. {@value #SETTLEMENT} is written once ({@code writeOnce}): one row a publication and month, one
 * {@code final} row a publication, both kept by unique indexes; written only by {@code QB_DISTRIBUTION_SETTLE}.
 */
public final class QbDistributions {

    public static final String PRICE = "QbDistributionPrice";
    public static final String SETTLEMENT = "QbDistributionSettlement";
    public static final String SPONSOR_SETTLEMENTS = "urn:jabiz:dataset:sponsor:" + SETTLEMENT;

    public static final int REASON_LENGTH = 500;

    private QbDistributions() {}

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /**
     * An operator's distribution price is saved in turns. Its own lock, not {@code qb.operator:}: the lead's process
     * holds the member lock first, which the lock order puts after the operator's.
     */
    public static String priceLockName(String operatorId) {
        return "qb.distribution-price:" + operatorId;
    }

    public static final EntityDefinition PRICE_ENTITY = EntityDefinition.define(PRICE, eb -> {
        eb.physicalTable("qb_distribution_price");
        eb.primaryKey("priceId");
        eb.field("priceId", f -> f.physicalColumn("price_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:distribution-price"));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true).processOnly()
            .asText(36));
        // Yen per start for each Kudos of the publication's highest tier (not a percentage).
        eb.field("rate", f -> f.physicalColumn("rate").required(true).processOnly().asNumeric(19, 4));
        eb.field("unitFloor", f -> f.physicalColumn("unit_floor").required(true).processOnly().asNumeric(19, 4));
        eb.field("unitCeiling", f -> f.physicalColumn("unit_ceiling").required(true).processOnly().asNumeric(19, 4));
        // Set aside for a publication with no cap on its winners.
        eb.field("minimumDeposit", f -> f.physicalColumn("minimum_deposit").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("reason", f -> f.physicalColumn("reason").required(true).processOnly().asText(REASON_LENGTH, true));
        eb.field("updatedAt", f -> f.physicalColumn("updated_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("updatedBy", f -> f.physicalColumn("updated_by").required(true).processOnly().asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("operatorId", "rate", "unitFloor", "unitCeiling", "minimumDeposit", "reason", "updatedAt",
                "updatedBy")
            .filters("operatorId")
            .sorts("operatorId", "updatedAt")
            .defaultSort("operatorId", true));
    });

    public static final EntityDefinition SETTLEMENT_ENTITY = EntityDefinition.define(SETTLEMENT, eb -> {
        eb.physicalTable("qb_distribution_settlement_version");
        eb.primaryKey("settlementId");
        eb.field("settlementId", f -> f.physicalColumn("settlement_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:distribution-settlement"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true).asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        // F-M11: the operator the fee belongs to, frozen at the submission.
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).asText(36));
        // The month whose starts it counts (YYYY-MM, in the fiscal year's zone); a final row may count past it.
        eb.field("period", f -> f.physicalColumn("period").immutable(true).required(true).asText(7));
        eb.field("fromStarts", f -> f.physicalColumn("from_starts").immutable(true).required(true).asNumeric(19, 0));
        eb.field("toStarts", f -> f.physicalColumn("to_starts").immutable(true).required(true).asNumeric(19, 0));
        eb.field("starts", f -> f.physicalColumn("starts").immutable(true).required(true).asNumeric(19, 0));
        eb.field("unit", f -> f.physicalColumn("unit").immutable(true).required(true).asNumeric(19, 4));
        eb.field("atFloor", f -> f.physicalColumn("at_floor").immutable(true).required(true).asBool());
        // What every start up to this row comes to, rounded up to the yen (before tax).
        eb.field("cumulativeAmount", f -> f.physicalColumn("cumulative_amount").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("tax", f -> f.physicalColumn("tax").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("total", f -> f.physicalColumn("total").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("final", f -> f.physicalColumn("final").immutable(true).required(true).asBool());
        // The fee charged (none when the amount is 0).
        eb.field("chargeId", f -> f.physicalColumn("charge_id").immutable(true).asText(36));
        eb.field("settledAt", f -> f.physicalColumn("settled_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("publicationId", "sponsorId", "operatorId", "period", "starts", "unit", "atFloor", "amount",
                "tax", "total", "final", "settledAt")
            .filters("publicationId", "sponsorId", "operatorId", "period", "final", "atFloor", "settledAt")
            .sorts("period", "settledAt")
            .defaultSort("settledAt", false));
    });
}
