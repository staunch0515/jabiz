package com.jabiz.quizbuks.shopify;

import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The plan in force and the period a moment falls in (docs/quizbuks/shopify/S3-billing.md sections 2 and 3). */
class QbsBillingTest {

    static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    static EntityInstance row(String plan, Instant periodEnd, String kept, Instant keptFrom, Instant keptUntil) {
        Map<String, Object> values = new HashMap<>();
        values.put("plan", plan);
        values.put("periodEnd", periodEnd);
        values.put("keptPlan", kept);
        values.put("keptFrom", keptFrom);
        values.put("keptUntil", keptUntil);
        return new EntityInstance("s1", QbsBilling.PLAN, 1, null, values);
    }

    @Test
    void aShopWithoutAPaidPlanCountsByCalendarMonth() {
        assertThat(QbsBilling.plan(null, NOW)).isEqualTo(QbsBilling.FREE);
        QbsBilling.Period month = new QbsBilling.Period(Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(QbsBilling.period(null, NOW)).isEqualTo(month);
        assertThat(QbsBilling.period(row(QbsBilling.FREE, null, null, null, null), NOW)).isEqualTo(month);
        // A paid plan whose period end Shopify did not give (a trial) counts by month too.
        assertThat(QbsBilling.period(row(QbsBilling.STARTER, null, null, null, null), NOW)).isEqualTo(month);
    }

    @Test
    void aPaidPlanCountsTheThirtyDaysOfShopifysBillingThatHoldNow() {
        Instant end = NOW.plus(Duration.ofDays(5));
        EntityInstance growth = row(QbsBilling.GROWTH, end, null, null, null);
        assertThat(QbsBilling.period(growth, NOW)).isEqualTo(new QbsBilling.Period(end.minus(Duration.ofDays(30)), end));
        // Passed, not read again yet: the next 30 days, and the ones after.
        assertThat(QbsBilling.period(growth, end)).isEqualTo(new QbsBilling.Period(end, end.plus(Duration.ofDays(30))));
        assertThat(QbsBilling.period(growth, end.plus(Duration.ofDays(65))))
            .isEqualTo(new QbsBilling.Period(end.plus(Duration.ofDays(60)), end.plus(Duration.ofDays(90))));
        // An end further off (a trial, then the first period): the 30 days that hold now, not a longer period.
        Instant far = NOW.plus(Duration.ofDays(44));
        assertThat(QbsBilling.period(row(QbsBilling.GROWTH, far, null, null, null), NOW))
            .isEqualTo(new QbsBilling.Period(far.minus(Duration.ofDays(60)), far.minus(Duration.ofDays(30))));
    }

    @Test
    void aKeptPlanHoldsForThePeriodItWasCountedIn() {
        // Kept from a month counted by the calendar (a trial without Shopify's period end): the same period.
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant until = Instant.parse("2026-11-01T00:00:00Z");
        EntityInstance moved = row(QbsBilling.FREE, null, QbsBilling.PRO, from, until);
        assertThat(QbsBilling.plan(moved, NOW)).isEqualTo(QbsBilling.PRO);
        assertThat(QbsBilling.period(moved, NOW)).isEqualTo(new QbsBilling.Period(from, until));
        assertThat(QbsBilling.plan(moved, until)).isEqualTo(QbsBilling.FREE);
        assertThat(QbsBilling.period(moved, until)).isEqualTo(QbsBilling.month(until));
    }

    @Test
    void limitsAreTheParametersOrTheirDefaultsAndPlansRankInOrder() {
        assertThat(QbsBilling.limit(null, QbsBilling.FREE)).isEqualTo(50);
        assertThat(QbsBilling.limitParam(QbsBilling.GROWTH)).isEqualTo("qbs.plan.growth.monthly-attempts");
        assertThat(QbsBilling.above(QbsBilling.PRO, QbsBilling.STARTER)).isTrue();
        assertThat(QbsBilling.above(QbsBilling.FREE, QbsBilling.FREE)).isFalse();
        assertThat(QbsBilling.completed(null)).isZero();
        assertThat(QbsBilling.completed(new EntityInstance("u", QbsBilling.USAGE, 1, null,
            Map.of("completed", BigDecimal.valueOf(7))))).isEqualTo(7);
    }

    @Test
    void shopifysPlanNamesAndPlanPage() {
        ShopifyPlans plans = new ShopifyPlans("Starter", "Growth", "Pro", "quizbuks");
        assertThat(plans.check()).isEmpty();
        assertThat(plans.planOf(" growth ")).contains(QbsBilling.GROWTH);
        assertThat(plans.planOf("Enterprise")).isEmpty();
        assertThat(plans.pricingUrl("demo.myshopify.com"))
            .isEqualTo("https://admin.shopify.com/store/demo/charges/quizbuks/pricing_plans");
        assertThatThrownBy(() -> plans.pricingUrl("evil.example.com")).isInstanceOf(IllegalArgumentException.class);
        // Every mistake of the configuration at once, for the startup to report.
        assertThat(new ShopifyPlans("Starter", "starter", " ", "Quiz Buks").check())
            .extracting(problem -> problem.location()).containsExactly("quizbuks.shopify.plans.growth.name",
                "quizbuks.shopify.plans.pro.name", "quizbuks.shopify.app-handle");
    }
}
