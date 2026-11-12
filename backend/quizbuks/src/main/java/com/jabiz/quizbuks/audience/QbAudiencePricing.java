package com.jabiz.quizbuks.audience;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * The audience rules as a managed product line, and their prices (Q47c, docs/quizbuks/plans/Q47c-audience-billing.md):
 * <ul>
 *   <li>{@code QbAudienceRule}: one row per kind of criterion of {@link AudienceCriteria} (the code gives the kinds:
 *       how they match and how takers answer them): whether sponsors may use it now, whether it is charged for, and
 *       its place in the list. Headquarters manage them; a kind without a row is open and charged for;</li>
 *   <li>{@code QbAudiencePriceList}: an operator's price list (its id the owner) with the least a publication pays
 *       when it uses a charged rule (F-M5: headquarters' own {@code direct} list is no longer read or written, the
 *       requester 2026-11-09; its rows stay until a later clean-up);</li>
 *   <li>{@code QbAudiencePrice}: a rule's daily price in an owner's list (JPY).</li>
 * </ul>
 * Ordinary tables written by {@link AudiencePricingProcesses} only; their history is in the audit records.
 */
public final class QbAudiencePricing {

    public static final String RULE = "QbAudienceRule";
    public static final String PRICE_LIST = "QbAudiencePriceList";
    public static final String PRICE = "QbAudiencePrice";

    public static final int REASON_LENGTH = 500;
    /** The highest daily price or least fee one may set, in JPY. */
    public static final long MAX_PRICE = 9_999_999L;

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** An owner's price list is saved in turns. */
    static String priceLockName(String owner) {
        return "qb.audience-prices:" + owner;
    }

    /** The rules are saved in turns. */
    static final String RULES_LOCK = "qb.audience-rules";

    public static final EntityDefinition RULE_ENTITY = EntityDefinition.define(RULE, eb -> {
        eb.physicalTable("qb_audience_rule");
        eb.primaryKey("ruleKey");
        eb.field("ruleKey", f -> f.physicalColumn("rule_key").immutable(true).required(true).processOnly()
            .asText(32));
        eb.field("enabled", f -> f.physicalColumn("enabled").required(true).processOnly().asBool());
        eb.field("billable", f -> f.physicalColumn("billable").required(true).processOnly().asBool());
        eb.field("sortOrder", f -> f.physicalColumn("sort_order").required(true).processOnly().asNumeric(9, 0));
        eb.field("updatedAt", f -> f.physicalColumn("updated_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("updatedBy", f -> f.physicalColumn("updated_by").required(true).processOnly().asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("ruleKey", "enabled", "billable", "sortOrder", "updatedAt", "updatedBy")
            .sorts("sortOrder", "ruleKey")
            .defaultSort("sortOrder", true));
    });

    public static final EntityDefinition PRICE_LIST_ENTITY = EntityDefinition.define(PRICE_LIST, eb -> {
        eb.physicalTable("qb_audience_price_list");
        eb.primaryKey("priceListId");
        eb.field("priceListId", f -> f.physicalColumn("price_list_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:audience-price-list"));
        eb.field("owner", f -> f.physicalColumn("owner").immutable(true).required(true).processOnly().asText(64));
        eb.field("minimumFee", f -> f.physicalColumn("minimum_fee").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("reason", f -> f.physicalColumn("reason").required(true).processOnly()
            .asText(REASON_LENGTH, true));
        eb.field("updatedAt", f -> f.physicalColumn("updated_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("updatedBy", f -> f.physicalColumn("updated_by").required(true).processOnly().asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("owner", "minimumFee", "reason", "updatedAt", "updatedBy")
            .filters("owner")
            .sorts("owner", "updatedAt")
            .defaultSort("owner", true));
    });

    public static final EntityDefinition PRICE_ENTITY = EntityDefinition.define(PRICE, eb -> {
        eb.physicalTable("qb_audience_price");
        eb.primaryKey("priceId");
        eb.field("priceId", f -> f.physicalColumn("price_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:audience-price"));
        eb.field("priceListId", f -> f.physicalColumn("price_list_id").immutable(true).required(true)
            .processOnly().asText(36));
        eb.field("ruleKey", f -> f.physicalColumn("rule_key").immutable(true).required(true).processOnly()
            .asText(32));
        eb.field("dailyPrice", f -> f.physicalColumn("daily_price").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("priceListId", "ruleKey", "dailyPrice")
            .filters("priceListId", "ruleKey")
            .sorts("ruleKey")
            .defaultSort("ruleKey", true));
    });

    private QbAudiencePricing() {}
}
