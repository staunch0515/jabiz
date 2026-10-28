package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.MutableClock;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuizBuks for Shopify, S3a (docs/quizbuks/shopify/S3-billing.md): a shop's plan is read from Shopify (stubbed here)
 * when a member opens the plan page, when Shopify reports a change of the subscription and by the daily job; a move
 * down keeps the higher plan until the end of the period it was paid for; what cannot be read leaves the plan as it
 * was; an uninstall puts the shop back on the free plan (read again after a new install) and {@code shop/redact}
 * erases its plan and use.
 */
class ShopifyPlanIT extends ShopifyItSupport {

    @Autowired
    JobRunner jobRunner;

    @Autowired
    @Qualifier("qbsPlanSyncJob")
    JobDefinition<?> planJob;

    @BeforeEach
    @AfterEach
    void forgetSubscriptions() {
        SUBSCRIPTIONS.clear();
    }

    void later(Duration duration) {
        MutableClock mutable = (MutableClock) clock;
        mutable.set(mutable.instant().plus(duration));
    }

    /** A connected shop; its admin. */
    Staff connected(String shop) {
        Staff admin = open(shop, "1");
        run("QBS_SHOP_CONNECT", admin.bearer(), Map.of("sessionToken", sessionToken(shop, "1")));
        return admin;
    }

    static String subscription(String name, String periodEnd, boolean test, int trialDays, String createdAt) {
        return "{\"id\":\"gid://shopify/AppSubscription/" + Math.abs(name.hashCode()) + "\",\"name\":\"" + name
            + "\",\"status\":\"ACTIVE\",\"test\":" + test + ",\"trialDays\":" + trialDays + ",\"createdAt\":\""
            + createdAt + "\",\"currentPeriodEnd\":" + (periodEnd == null ? "null" : "\"" + periodEnd + "\"") + "}";
    }

    Map<String, Object> plan(Staff staff, boolean refresh) {
        return run("QBS_PLAN_GET", staff.bearer(), Map.of("refresh", refresh));
    }

    List<StubHttpServer.Received> planReads(String shop) {
        return SHOPIFY.requests("/shops/" + shop + "/admin/api/2026-10/graphql.json").stream()
            .filter(r -> r.body().contains("currentAppInstallation")).toList();
    }

    @Test
    void aPaidPlanIsReadFromShopify() {
        String shop = shop("paid");
        Staff admin = connected(shop);
        Instant now = clock.instant();
        Instant end = now.plus(Duration.ofDays(20)).truncatedTo(ChronoUnit.SECONDS);
        Instant created = now.minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
        SUBSCRIPTIONS.put(shop, "[" + subscription("Growth", end.toString(), true, 14, created.toString()) + "]");

        Map<String, Object> plan = plan(admin, true);
        assertThat(plan).containsEntry("plan", "GROWTH").containsEntry("shopifyPlan", "Growth")
            .containsEntry("unknownPlan", false).containsEntry("test", true).containsEntry("used", 0)
            .containsEntry("limit", 3000).containsEntry("nextPlan", null)
            .containsEntry("trialEndsAt", created.plus(Duration.ofDays(14)).toString())
            .containsEntry("periodEnd", end.toString())
            .containsEntry("periodStart", end.minus(Duration.ofDays(30)).toString())
            .containsEntry("pricingUrl", "https://admin.shopify.com/store/" + shop.replace(".myshopify.com", "")
                + "/charges/quizbuks/pricing_plans");
        assertThat(query("SELECT plan, subscription_name, test FROM qbs_shop_plan WHERE shop_domain = ?", shop))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("plan", "GROWTH")
                .containsEntry("subscription_name", "Growth").containsEntry("test", true));

        // Read again within the hour only when asked to.
        SHOPIFY.reset();
        plan(admin, false);
        assertThat(planReads(shop)).isEmpty();
        plan(admin, true);
        assertThat(planReads(shop)).hasSize(1);
        later(Duration.ofMinutes(61));
        // (Signed in again: the clock moved past the access token.)
        plan(open(shop, "1"), false);
        assertThat(planReads(shop)).hasSize(2);
    }

    @Test
    void noSubscriptionOrOneOfAnotherNameIsTheFreePlan() {
        String shop = shop("free");
        Staff admin = connected(shop);
        Map<String, Object> plan = plan(admin, true);
        var month = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        assertThat(plan).containsEntry("plan", "FREE").containsEntry("shopifyPlan", null).containsEntry("limit", 50)
            .containsEntry("unknownPlan", false)
            .containsEntry("periodStart", month.atStartOfDay().toInstant(ZoneOffset.UTC).toString())
            .containsEntry("periodEnd", month.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString());

        SUBSCRIPTIONS.put(shop, "[" + subscription("Enterprise", null, false, 0, clock.instant().toString()) + "]");
        assertThat(plan(admin, true)).containsEntry("plan", "FREE").containsEntry("shopifyPlan", "Enterprise")
            .containsEntry("unknownPlan", true);
    }

    @Test
    void aChangeOfTheSubscriptionIsReadWhenShopifyReportsIt() {
        String shop = shop("hook");
        Staff admin = connected(shop);
        plan(admin, true);
        String end = clock.instant().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS).toString();
        SUBSCRIPTIONS.put(shop, "[" + subscription("Starter", end, false, 0, clock.instant().toString()) + "]");
        // The body names the subscription, not the shop's address: the header says which shop.
        deliver("app_subscriptions/update", shop, Map.of("app_subscription", Map.of(
            "admin_graphql_api_id", "gid://shopify/AppSubscription/1", "name", "Starter", "status", "ACTIVE")));
        assertThat(query("SELECT plan FROM qbs_shop_plan WHERE shop_domain = ?", shop)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("plan", "STARTER"));
        // A header naming no shop: nothing read.
        SHOPIFY.reset();
        deliver("app_subscriptions/update", "not a shop", Map.of("app_subscription", Map.of("name", "Pro")));
        assertThat(SHOPIFY.requests()).isEmpty();
    }

    @Test
    void aMoveDownKeepsTheHigherPlanUntilThePaidPeriodEnds() {
        String shop = shop("down");
        Staff admin = connected(shop);
        Instant end = clock.instant().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
        SUBSCRIPTIONS.put(shop, "[" + subscription("Pro", end.toString(), false, 0, clock.instant().toString()) + "]");
        assertThat(plan(admin, true)).containsEntry("plan", "PRO").containsEntry("limit", 15000);

        SUBSCRIPTIONS.remove(shop);
        assertThat(plan(admin, true)).containsEntry("plan", "PRO").containsEntry("nextPlan", "FREE")
            .containsEntry("planUntil", end.toString()).containsEntry("periodEnd", end.toString())
            .containsEntry("limit", 15000);
        // Read again meanwhile: still kept.
        assertThat(plan(admin, true)).containsEntry("plan", "PRO").containsEntry("planUntil", end.toString());

        later(Duration.ofDays(11));
        assertThat(plan(open(shop, "1"), false)).containsEntry("plan", "FREE").containsEntry("nextPlan", null)
            .containsEntry("limit", 50);
    }

    @Test
    void whatCannotBeReadLeavesThePlanAsItWas() {
        String shop = shop("unread");
        Staff admin = connected(shop);
        String end = clock.instant().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS).toString();
        SUBSCRIPTIONS.put(shop, "[" + subscription("Growth", end, false, 0, clock.instant().toString()) + "]");
        plan(admin, true);
        SHOPIFY.script("/shops/" + shop + "/admin/api/2026-10/graphql.json", StubHttpServer.Answer.json(502, "{}"));
        assertThat(plan(admin, true)).containsEntry("plan", "GROWTH").containsEntry("connection", "CONNECTED");
        // Shopify no longer takes the token: the shop connects again, the plan is kept.
        SHOPIFY.script("/shops/" + shop + "/admin/api/2026-10/graphql.json", StubHttpServer.Answer.json(401, "{}"));
        assertThat(plan(admin, true)).containsEntry("plan", "GROWTH").containsEntry("connection", "RECONNECT");
    }

    @Test
    void theDailyJobReadsEveryConnectedShop() {
        String shop = shop("job");
        connected(shop);
        String unconnected = shop("job-none");
        open(unconnected, "1");
        SUBSCRIPTIONS.put(shop, "[" + subscription("Starter", null, false, 0, clock.instant().toString()) + "]");
        later(Duration.ofDays(1));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.run(planJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT plan FROM qbs_shop_plan WHERE shop_domain = ?", shop)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("plan", "STARTER"));
        assertThat(query("SELECT 1 FROM qbs_shop_plan WHERE shop_domain = ?", unconnected)).isEmpty();
    }

    @Test
    void anUninstallIsTheFreePlanAndShopRedactErasesThePlanAndUse() {
        String shop = shop("gone");
        Staff admin = connected(shop);
        SUBSCRIPTIONS.put(shop, "[" + subscription("Growth", null, false, 0, clock.instant().toString()) + "]");
        plan(admin, true);
        execute("INSERT INTO qbs_usage (usage_id, shop_domain, period_start, period_end, completed, version)"
            + " VALUES (?, ?, ?, ?, 3, 1)", java.util.UUID.randomUUID().toString(), shop,
            java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant().plusSeconds(60)));

        deliver("app/uninstalled", shop, uninstalled(shop));
        // Shopify cancelled the subscription: no plan until it is read again; the use is kept.
        assertThat(query("SELECT 1 FROM qbs_shop_plan WHERE shop_domain = ?", shop)).isEmpty();
        assertThat(query("SELECT 1 FROM qbs_usage WHERE shop_domain = ?", shop)).hasSize(1);

        deliver("shop/redact", shop, privacy(shop));
        assertThat(query("SELECT 1 FROM qbs_shop_plan WHERE shop_domain = ?", shop)).isEmpty();
        assertThat(query("SELECT 1 FROM qbs_usage WHERE shop_domain = ?", shop)).isEmpty();
    }

    @Test
    void aNewInstallReadsThePlanAgainAtOnce() {
        String shop = shop("again");
        Staff admin = connected(shop);
        SUBSCRIPTIONS.put(shop, "[" + subscription("Growth", null, false, 0, clock.instant().toString()) + "]");
        assertThat(plan(admin, true)).containsEntry("plan", "GROWTH");
        deliver("app/uninstalled", shop, uninstalled(shop));
        // Installed again within the hour, the new subscription chosen: the page reads it without being asked.
        SUBSCRIPTIONS.put(shop, "[" + subscription("Starter", null, false, 0, clock.instant().toString()) + "]");
        Staff back = open(shop, "1", issuedLater());
        run("QBS_SHOP_CONNECT", back.bearer(), Map.of("sessionToken", sessionToken(shop, "1", issuedLater())));
        assertThat(plan(back, false)).containsEntry("plan", "STARTER");
    }

    @Test
    void onlyAMemberOfAnInstalledShopSeesItsPlan() {
        String shop = shop("member");
        connected(shop);
        Staff member = open(shop, "2");
        assertThat(plan(member, false)).containsEntry("plan", "FREE");
        deliver("app/uninstalled", shop, uninstalled(shop));
        assertThat(ruleCode(refused("QBS_PLAN_GET", member.bearer(), Map.of("refresh", false), 422)))
            .isEqualTo("QBS_SHOP_UNINSTALLED");
    }
}
