package com.jabiz.quizbuks.shopify;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.param.ParamValues;
import com.jabiz.runtime.EntityInstance;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A shop's plan and what it used of it (docs/quizbuks/shopify/S3-billing.md). Shopify sells the plans (Shopify App
 * Pricing: its own plan page, trials and charges); QuizBuks reads which one a shop is on, counts the attempts its
 * storefront completes in each billing period and stops new ones at the plan's limit. Ordinary tables written by the
 * module's processes only; deleted with the shop's other rows on {@code shop/redact}.
 *
 * <ul>
 *   <li>{@value #PLAN}: one row per shop, what Shopify last said of its subscription. A shop without a row, or without
 *       an active subscription, is on {@value #FREE}. After a move to a lower plan the higher one is kept until the end
 *       of the period it was paid for ({@code keptPlan} for the period {@code keptFrom} to {@code keptUntil}).</li>
 *   <li>{@value #USAGE}: the attempts a shop's storefront completed in one period, one row per shop and period.</li>
 * </ul>
 */
public final class QbsBilling {

    private QbsBilling() {}

    public static final String PLAN = "QbsShopPlan";
    public static final String USAGE = "QbsUsage";

    public static final String PLAN_DICTIONARY = "urn:jabiz:dict:quizbuks:shopify-plan";

    /** The plans, lowest first: their codes here, matched to Shopify's plan names by configuration. */
    public static final String FREE = "FREE";
    public static final String STARTER = "STARTER";
    public static final String GROWTH = "GROWTH";
    public static final String PRO = "PRO";
    public static final List<String> PLANS = List.of(FREE, STARTER, GROWTH, PRO);

    /** The attempts a plan completes a period until its parameter ({@link #limitParam}) is created. */
    public static final Map<String, Integer> DEFAULT_LIMITS = Map.of(FREE, 50, STARTER, 500, GROWTH, 3_000,
        PRO, 15_000);

    /** Shopify bills a plan every 30 days; a period without a subscription is a calendar month (UTC). */
    public static final Duration PAID_PERIOD = Duration.ofDays(30);

    /** The status of a subscription that counts ({@code AppSubscriptionStatus}). */
    public static final String ACTIVE = "ACTIVE";

    public static final int GID_LENGTH = QbsStorefront.GID_LENGTH;
    public static final int NAME_LENGTH = 255;
    public static final int STATUS_LENGTH = 20;

    /**
     * The business parameter of the attempts a plan completes a period: {@code qbs.plan.<plan>.monthly-attempts},
     * controlled (four eyes), not created by the setup ({@link #DEFAULT_LIMITS} until it is).
     */
    public static String limitParam(String plan) {
        return "qbs.plan." + plan.toLowerCase(Locale.ROOT) + ".monthly-attempts";
    }

    public static final List<String> LIMIT_PARAMS = PLANS.stream().map(QbsBilling::limitParam).toList();

    public static final EntityDefinition PLAN_ENTITY = EntityDefinition.define(PLAN, eb -> {
        eb.physicalTable("qbs_shop_plan");
        eb.primaryKey("shopId");
        eb.field("shopId", f -> f.physicalColumn("shop_id").immutable(true).required(true)
            .asText(QbsShops.ID_LENGTH));
        eb.field("shopDomain", f -> f.physicalColumn("shop_domain").immutable(true).required(true)
            .asText(Shopify.SHOP_DOMAIN_LENGTH));
        eb.field("plan", f -> f.physicalColumn("plan").required(true).processOnly()
            .asCode(PLAN_DICTIONARY, PLANS.toArray(String[]::new)));
        eb.field("subscriptionId", f -> f.physicalColumn("subscription_id").processOnly().asText(GID_LENGTH));
        eb.field("subscriptionName", f -> f.physicalColumn("subscription_name").processOnly().asText(NAME_LENGTH));
        eb.field("test", f -> f.physicalColumn("test").required(true).processOnly().asBool());
        eb.field("trialEndsAt", f -> f.physicalColumn("trial_ends_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("periodEnd", f -> f.physicalColumn("period_end").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("keptPlan", f -> f.physicalColumn("kept_plan").processOnly()
            .asCode(PLAN_DICTIONARY, PLANS.toArray(String[]::new)));
        eb.field("keptFrom", f -> f.physicalColumn("kept_from").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("keptUntil", f -> f.physicalColumn("kept_until").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("checkedAt", f -> f.physicalColumn("checked_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qbs_shop_plan_domain", "shopDomain");
    });

    public static final EntityDefinition USAGE_ENTITY = EntityDefinition.define(USAGE, eb -> {
        eb.physicalTable("qbs_usage");
        eb.primaryKey("usageId");
        eb.field("usageId", f -> f.physicalColumn("usage_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:shopify-usage"));
        eb.field("shopDomain", f -> f.physicalColumn("shop_domain").immutable(true).required(true)
            .asText(Shopify.SHOP_DOMAIN_LENGTH));
        eb.field("periodStart", f -> f.physicalColumn("period_start").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("periodEnd", f -> f.physicalColumn("period_end").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("completed", f -> f.physicalColumn("completed").required(true).processOnly().asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qbs_usage_period", "shopDomain", "periodStart");
    });

    /** A billing period: from {@code start}, up to (not including) {@code end}. */
    public record Period(Instant start, Instant end) {}

    /** The plan a shop's row gives now: the one kept after a move down while its period lasts, else its own. */
    public static String plan(EntityInstance row, Instant now) {
        if (row == null) {
            return FREE;
        }
        String kept = row.get("keptPlan");
        Instant until = instant(row.get("keptUntil"));
        if (kept != null && until != null && now.isBefore(until)) {
            return kept;
        }
        return row.get("plan");
    }

    /**
     * The period {@code now} falls in: while a higher plan is kept, the one it was paid for (as it was counted then);
     * on a paid plan, the 30 days of Shopify's billing that hold {@code now}, counted from its period end (moved by
     * 30 days at a time while Shopify is not read again, either way: an end further off, as after a trial, does not
     * make a longer period); otherwise the calendar month (UTC).
     */
    public static Period period(EntityInstance row, Instant now) {
        if (row != null) {
            Instant from = instant(row.get("keptFrom"));
            Instant until = instant(row.get("keptUntil"));
            if (row.get("keptPlan") != null && from != null && until != null && now.isBefore(until)) {
                return new Period(from, until);
            }
            Instant end = instant(row.get("periodEnd"));
            if (!FREE.equals(row.get("plan")) && end != null) {
                long millis = PAID_PERIOD.toMillis();
                // The whole periods from now to the end, rounded so that the period's end is after now.
                long periods = Math.floorDiv(Duration.between(now, end).toMillis() - 1, millis);
                end = end.minus(PAID_PERIOD.multipliedBy(periods));
                return new Period(end.minus(PAID_PERIOD), end);
            }
        }
        return month(now);
    }

    /** The calendar month (UTC) {@code now} falls in. */
    public static Period month(Instant now) {
        LocalDate first = now.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return new Period(first.atStartOfDay().toInstant(ZoneOffset.UTC),
            first.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    /** The attempts {@code plan} completes a period: its parameter as in effect, its default before it is created. */
    public static long limit(ParamValues params, String plan) {
        long fallback = DEFAULT_LIMITS.get(plan);
        return params == null ? fallback : params.find(limitParam(plan), BigDecimal.class)
            .map(BigDecimal::longValue).orElse(fallback);
    }

    /** Whether {@code plan} is above {@code other}. */
    public static boolean above(String plan, String other) {
        return PLANS.indexOf(plan) > PLANS.indexOf(other);
    }

    /** The attempts a usage row counted (none without one). */
    public static long completed(EntityInstance usage) {
        return usage == null ? 0 : CampaignProcesses.number(usage.get("completed"));
    }

    static Instant instant(Object value) {
        return value == null ? null : CampaignProcesses.instant(value);
    }
}
