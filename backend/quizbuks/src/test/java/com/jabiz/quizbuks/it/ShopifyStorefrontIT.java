package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.MutableClock;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.security.AppProxySignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuizBuks for Shopify, S2a (docs/quizbuks/shopify/S2-storefront.md): a shop's staff publish a quiz campaign on the
 * engine; the refill job keeps its pool of discount codes on Shopify (stubbed here); the storefront's block, through the
 * signed app proxy, starts and submits an attempt per visitor or customer, and a pass gets the next code while the day's
 * limit lasts; an uninstall pauses the campaign, and Shopify's privacy requests report and erase a customer's data.
 */
class ShopifyStorefrontIT extends ShopifyItSupport {

    @Autowired
    JobRunner jobRunner;

    @Autowired
    @Qualifier("qbsDiscountRefillJob")
    JobDefinition<?> refillJob;

    @BeforeEach
    void clearRefillLock() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
    }

    void later(Duration duration) {
        MutableClock mutable = (MutableClock) clock;
        mutable.set(mutable.instant().plus(duration));
    }

    // ---- staff --------------------------------------------------------------------------------------------------------

    static Map<String, Object> question(String stem, int correct) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            options.add(Map.of("text", stem + " option " + i, "correct", i == correct));
        }
        return Map.of("stem", stem, "options", options);
    }

    static Map<String, Object> draft(String name) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", name);
        draft.put("video", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        draft.put("timeLimitSec", 120);
        draft.put("passScore", 2);
        draft.put("percent", 15);
        draft.put("questions", List.of(question("First", 2), question("Second", 3)));
        return draft;
    }

    /** A connected shop with a live campaign; its admin. */
    record LiveShop(String shop, Staff admin, String campaignId, String publicationId) {}

    LiveShop liveShop(String prefix) {
        String shop = shop(prefix);
        Staff admin = open(shop, "1");
        run("QBS_SHOP_CONNECT", admin.bearer(), Map.of("sessionToken", sessionToken(shop, "1")));
        Map<String, Object> saved = run("QBS_CAMPAIGN_SAVE", admin.bearer(), draft("Spring quiz"));
        Map<String, Object> live = run("QBS_CAMPAIGN_PUBLISH", admin.bearer(),
            Map.of("campaignId", saved.get("campaignId")));
        assertThat(live).containsEntry("status", "LIVE");
        return new LiveShop(shop, admin, (String) saved.get("campaignId"), (String) live.get("publicationId"));
    }

    /** A run of the refill job, ten minutes after the last (a job runs once per scheduled time). */
    void refill() {
        later(Duration.ofMinutes(10));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.run(refillJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
    }

    // ---- the storefront -----------------------------------------------------------------------------------------------

    /** A request as Shopify's app proxy forwards it: the shop, the customer (if signed in), the visitor key, signed. */
    String signed(String shop, String customer, String visitor) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("visitor", List.of(visitor));
        query.put("shop", List.of(shop));
        query.put("logged_in_customer_id", List.of(customer == null ? "" : customer));
        query.put("path_prefix", List.of("/apps/quizbuks"));
        query.put("timestamp", List.of(String.valueOf(clock.instant().getEpochSecond())));
        query.put("signature", List.of(AppProxySignature.sign(query, SECRET)));
        List<String> pairs = new ArrayList<>();
        query.forEach((name, values) -> values.forEach(value -> pairs.add(URLEncoder.encode(name,
            StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8))));
        return String.join("&", pairs);
    }

    WebTestClient.ResponseSpec storefront(String process, String shop, String customer, String visitor,
        Map<String, Object> body) {
        URI uri = URI.create("/api/proxy/shopify/" + process + "?" + signed(shop, customer, visitor));
        return body == null ? client.get().uri(uri).exchange()
            : client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    Map<String, Object> ok(WebTestClient.ResponseSpec response) {
        var result = response.expectBody(MAP).returnResult();
        assertThat(result.getStatus().value()).as(String.valueOf(result.getResponseBody())).isEqualTo(200);
        return result.getResponseBody();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> answersAllRight(Map<String, Object> started, Map<Integer, String> rightText) {
        List<Map<String, Object>> answers = new ArrayList<>();
        for (Map<String, Object> question : (List<Map<String, Object>>) started.get("questions")) {
            int no = (Integer) question.get("no");
            Map<String, Object> right = ((List<Map<String, Object>>) question.get("options")).stream()
                .filter(option -> option.get("text").equals(rightText.get(no))).findFirst().orElseThrow();
            answers.add(Map.of("questionNo", no, "selected", List.of(right.get("no"))));
        }
        return answers;
    }

    static final int DISCOUNT_BATCH = 250;

    static final Map<Integer, String> RIGHT = Map.of(1, "First option 2", 2, "Second option 3");
    static final Map<Integer, String> WRONG = Map.of(1, "First option 1", 2, "Second option 1");

    /** A visitor (or customer) answers: started, then submitted after a person's pace. */
    Map<String, Object> play(LiveShop live, String customer, String visitor, Map<Integer, String> chosen) {
        Map<String, Object> started = ok(storefront("QBS_SF_START", live.shop(), customer, visitor,
            Map.of("publicationId", live.publicationId(), "visitor", visitor)));
        later(Duration.ofSeconds(10));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("publicationId", live.publicationId());
        body.put("visitor", visitor);
        body.put("attemptId", started.get("attemptId"));
        body.put("answers", answersAllRight(started, chosen));
        return ok(storefront("QBS_SF_SUBMIT", live.shop(), customer, visitor, body));
    }

    static String visitor() {
        return UUID.randomUUID().toString();
    }

    // ---- tests --------------------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void aPassingVisitorGetsACodeOfThePoolAndOnlyOnce() {
        LiveShop live = liveShop("pass");
        // No codes yet: the block says none today.
        assertThat(ok(storefront("QBS_SF_CAMPAIGN", live.shop(), null, visitor(), null)))
            .containsEntry("live", true).containsEntry("codesToday", false);
        refill();
        // One discount of the campaign's percentage, its 249 other codes added in one bulk creation.
        String graphql = "/shops/" + live.shop() + "/admin/api/2026-10/graphql.json";
        List<StubHttpServer.Received> calls = SHOPIFY.requests(graphql);
        Map<String, Object> created = JSON.readValue(calls.stream().filter(r -> r.body().contains(
            "discountCodeBasicCreate")).findFirst().orElseThrow().body(), Map.class);
        Map<String, Object> input = (Map<String, Object>) ((Map<String, Object>) created.get("variables")).get("input");
        assertThat(input).containsEntry("usageLimit", 1).containsEntry("appliesOncePerCustomer", true);
        assertThat((Map<String, Object>) input.get("customerGets")).containsEntry("value", Map.of("percentage", 0.15));
        assertThat(query("SELECT status, code_count FROM qbs_discount_batch WHERE shop_domain = ?", live.shop()))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", "CREATING"));
        refill();
        assertThat(query("SELECT status FROM qbs_discount_batch WHERE shop_domain = ?", live.shop()))
            .extracting(row -> row.get("status")).containsExactly("READY");

        Map<String, Object> campaign = ok(storefront("QBS_SF_CAMPAIGN", live.shop(), null, visitor(), null));
        assertThat(campaign).containsEntry("live", true).containsEntry("codesToday", true)
            .containsEntry("publicationId", live.publicationId()).containsEntry("questionCount", 2)
            .containsEntry("percent", 15).containsEntry("video", "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ");

        String visitor = visitor();
        Map<String, Object> passed = play(live, null, visitor, RIGHT);
        assertThat(passed).containsEntry("passed", true).containsEntry("outcome", "ISSUED").containsEntry("score", 2)
            .containsEntry("percent", 15);
        String code = (String) passed.get("discountCode");
        assertThat(code).matches("QB-[2-9A-HJ-NP-Z]{10}");
        assertThat(passed.get("expiresAt")).isNotNull();
        // Kept sealed; recorded as issued to the attempt; masked in the records.
        assertThat(query("SELECT sealed_code, hint, status, attempt_id FROM qbs_discount_code WHERE status = 'ISSUED'"
            + " AND shop_domain = ?", live.shop())).singleElement().satisfies(row -> {
                assertThat(String.valueOf(row.get("sealed_code"))).doesNotContain(code);
                assertThat(row).containsEntry("hint", "…" + code.substring(code.length() - 4))
                    .containsEntry("attempt_id", passed.get("attemptId"));
            });
        assertThat(query("SELECT * FROM op_process WHERE process_name = 'QBS_SF_SUBMIT'"))
            .allSatisfy(row -> assertThat(String.valueOf(row)).doesNotContain(code));

        // Asked again for the same attempt: the same code; a second start of the same visitor is refused.
        Map<String, Object> again = ok(storefront("QBS_SF_SUBMIT", live.shop(), null, visitor, Map.of(
            "publicationId", live.publicationId(), "visitor", visitor, "attemptId", passed.get("attemptId"))));
        assertThat(again).containsEntry("outcome", "AGAIN").containsEntry("discountCode", code);
        assertThat(ruleCode(storefront("QBS_SF_START", live.shop(), null, visitor, Map.of("publicationId",
            live.publicationId(), "visitor", visitor)).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("QB_ATTEMPT_ALREADY_TAKEN");
        // Another visitor cannot ask for that attempt's code.
        String other = visitor();
        storefront("QBS_SF_SUBMIT", live.shop(), null, other, Map.of("publicationId", live.publicationId(),
            "visitor", other, "attemptId", passed.get("attemptId"))).expectStatus().isNotFound();
    }

    @Test
    void aFailOrATooFastSubmissionGetsNoCode() {
        LiveShop live = liveShop("fail");
        refill();
        refill();
        assertThat(play(live, null, visitor(), WRONG)).containsEntry("passed", false)
            .containsEntry("outcome", "NOT_PASSED").containsEntry("discountCode", null);
        // Sooner than a person reads: refused, the attempt stays open.
        String visitor = visitor();
        Map<String, Object> started = ok(storefront("QBS_SF_START", live.shop(), null, visitor,
            Map.of("publicationId", live.publicationId(), "visitor", visitor)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("publicationId", live.publicationId());
        body.put("visitor", visitor);
        body.put("attemptId", started.get("attemptId"));
        body.put("answers", answersAllRight(started, RIGHT));
        assertThat(ruleCode(storefront("QBS_SF_SUBMIT", live.shop(), null, visitor, body).expectStatus()
            .isEqualTo(422).expectBody(MAP).returnResult().getResponseBody())).isEqualTo("QB_ATTEMPT_TOO_FAST");
        assertThat(query("SELECT 1 FROM qbs_discount_code WHERE status = 'ISSUED' AND shop_domain = ?", live.shop()))
            .isEmpty();
    }

    @Test
    void theDaysLimitStopsTheCodes() {
        LiveShop live = liveShop("cap");
        refill();
        refill();
        // As if the day's limit were reached already.
        execute("UPDATE qbs_discount_code SET status = 'ISSUED', issued_at = ? WHERE shop_domain = ? AND seq <= 100",
            java.sql.Timestamp.from(clock.instant()), live.shop());
        assertThat(ok(storefront("QBS_SF_CAMPAIGN", live.shop(), null, visitor(), null)))
            .containsEntry("codesToday", false);
        assertThat(play(live, null, visitor(), RIGHT)).containsEntry("passed", true)
            .containsEntry("outcome", "DAY_FULL").containsEntry("discountCode", null);
        // The next day (UTC), codes again.
        later(Duration.ofDays(1));
        assertThat(play(live, null, visitor(), RIGHT)).containsEntry("outcome", "ISSUED");
    }

    @Test
    void aSignedInCustomerIsOnePersonWhateverTheirVisitorKey() {
        LiveShop live = liveShop("customer");
        refill();
        refill();
        assertThat(play(live, "7001", visitor(), RIGHT)).containsEntry("outcome", "ISSUED");
        // Another browser of the same customer: the same person, who answered already.
        String other = visitor();
        assertThat(ruleCode(storefront("QBS_SF_START", live.shop(), "7001", other, Map.of("publicationId",
            live.publicationId(), "visitor", other)).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("QB_ATTEMPT_ALREADY_TAKEN");
        // The engine knows them by their row's key only.
        String taker = "qbs:" + query("SELECT taker_id FROM qbs_taker WHERE shop_domain = ? AND customer_id = '7001'",
            live.shop()).getFirst().get("taker_id");
        assertThat(query("SELECT 1 FROM qb_attempt WHERE user_id = ?", taker)).hasSize(1);
        assertThat(query("SELECT 1 FROM qb_attempt WHERE user_id LIKE '%7001%'")).isEmpty();
    }

    @Test
    void anotherShopsCampaignOrAnUnsignedRequestRunsNothing() {
        LiveShop live = liveShop("apart");
        LiveShop other = liveShop("other");
        String visitor = visitor();
        // The publication of another shop, through this shop's proxy: not found.
        storefront("QBS_SF_START", live.shop(), null, visitor, Map.of("publicationId", other.publicationId(),
            "visitor", visitor)).expectStatus().isNotFound();
        // A visitor without a key.
        assertThat(ruleCode(storefront("QBS_SF_START", live.shop(), null, visitor, Map.of("publicationId",
            live.publicationId())).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("QBS_VISITOR_REQUIRED");
        // Unsigned: 401, before anything.
        client.get().uri("/api/proxy/shopify/QBS_SF_CAMPAIGN?shop=" + live.shop()).exchange()
            .expectStatus().isUnauthorized();
        // The process API runs none of them.
        refused("QBS_SF_CAMPAIGN", live.admin().bearer(), Map.of(), 403);
        // An App taker cannot reach a shop's publication either (its result would show the correct options).
        String taker = com.jabiz.runtime.test.TestTokens.bearer(tokens, UUID.randomUUID().toString(),
            com.jabiz.quizbuks.QbPermissions.PLAY);
        for (String process : List.of("QB_VIEW", "QB_ATTEMPT_START")) {
            client.post().uri("/api/processes/" + process + "/latest").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", taker)
                .bodyValue(Map.of("publicationId", live.publicationId())).exchange().expectStatus()
                .value(status -> assertThat(status).as(process).isIn(403, 404));
        }
        assertThat(query("SELECT 1 FROM qb_attempt WHERE publication_id = ?::uuid", live.publicationId())).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStaffManageOneRunningCampaign() {
        LiveShop live = liveShop("staff");
        later(Duration.ofSeconds(1));
        Map<String, Object> second = run("QBS_CAMPAIGN_SAVE", live.admin().bearer(), draft("Summer quiz"));
        assertThat(ruleCode(refused("QBS_CAMPAIGN_PUBLISH", live.admin().bearer(), Map.of("campaignId",
            second.get("campaignId")), 422))).isEqualTo("QBS_CAMPAIGN_RUNNING");
        // A published campaign is not edited.
        Map<String, Object> edit = new LinkedHashMap<>(draft("Spring quiz 2"));
        edit.put("campaignId", live.campaignId());
        assertThat(ruleCode(refused("QBS_CAMPAIGN_SAVE", live.admin().bearer(), edit, 422)))
            .isEqualTo("QBS_CAMPAIGN_NOT_DRAFT");
        // Paused: the storefront shows nothing; resumed: back.
        run("QBS_CAMPAIGN_MOVE", live.admin().bearer(), Map.of("campaignId", live.campaignId(), "to", "PAUSED"));
        assertThat(ok(storefront("QBS_SF_CAMPAIGN", live.shop(), null, visitor(), null))).containsEntry("live", false);
        assertThat(query("SELECT status FROM qb_publication_card WHERE publication_id = ?::uuid", live.publicationId()))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", "PAUSED"));
        run("QBS_CAMPAIGN_MOVE", live.admin().bearer(), Map.of("campaignId", live.campaignId(), "to", "LIVE"));
        assertThat(ok(storefront("QBS_SF_CAMPAIGN", live.shop(), null, visitor(), null))).containsEntry("live", true);
        // Ended: another may go live.
        run("QBS_CAMPAIGN_MOVE", live.admin().bearer(), Map.of("campaignId", live.campaignId(), "to", "ENDED"));
        assertThat(run("QBS_CAMPAIGN_PUBLISH", live.admin().bearer(), Map.of("campaignId",
            second.get("campaignId")))).containsEntry("status", "LIVE");
        Map<String, Object> list = run("QBS_CAMPAIGN_LIST", live.admin().bearer(), Map.of());
        assertThat((List<Map<String, Object>>) list.get("campaigns")).extracting(c -> c.get("status"))
            .containsExactly("LIVE", "ENDED");
        // A member of another shop sees none of this shop's.
        Staff stranger = open(shop("stranger"), "1");
        refused("QBS_CAMPAIGN_MOVE", stranger.bearer(), Map.of("campaignId", second.get("campaignId"), "to",
            "PAUSED"), 404);
        // The engine's sponsor views do not list a shop's publications.
        assertThat(query("SELECT publisher_kind, reward_method FROM qb_publication_card WHERE publication_id = ?::uuid",
            live.publicationId())).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("publisher_kind", "SHOPIFY_SHOP").containsEntry("reward_method", "CHANNEL"));
    }

    @Test
    void theRefillRetiresAndDeletesBatches() {
        LiveShop live = liveShop("refill");
        refill();
        refill();
        // Seven days and a minute later the batch retires (its codes would last less than seven days), and a new one
        // is made since none can be handed out.
        later(Duration.ofDays(7).plusMinutes(1));
        refill();
        assertThat(query("SELECT status FROM qbs_discount_batch WHERE shop_domain = ? ORDER BY created_at",
            live.shop())).extracting(row -> row.get("status")).containsExactly("RETIRED", "CREATING");
        refill();
        // After its end, the first batch's discount is deleted from the shop, and the batch and its codes here.
        later(Duration.ofDays(7));
        refill();
        // (The second batch, seven days younger, has seven days or less left by now too: retired, and a third made.)
        assertThat(query("SELECT status FROM qbs_discount_batch WHERE shop_domain = ? ORDER BY created_at",
            live.shop())).extracting(row -> row.get("status")).containsExactly("RETIRED", "CREATING");
        assertThat(SHOPIFY.requests("/shops/" + live.shop() + "/admin/api/2026-10/graphql.json"))
            .anySatisfy(r -> assertThat(r.body()).contains("discountCodeDelete"));
        assertThat(query("SELECT 1 FROM qbs_discount_code WHERE shop_domain = ?", live.shop()))
            .hasSize(2 * DISCOUNT_BATCH);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUninstallPausesTheCampaignAndPrivacyRequestsReportAndErase() {
        LiveShop live = liveShop("privacy");
        refill();
        refill();
        assertThat(play(live, "8123", visitor(), RIGHT)).containsEntry("outcome", "ISSUED");

        Map<String, Object> request = new LinkedHashMap<>(privacy(live.shop()));
        request.put("customer", Map.of("id", 8123, "email", "someone@example.com"));
        deliver("customers/data_request", live.shop(), request);
        Map<String, Object> reported = query("SELECT outcome, customer_id, report FROM qbs_compliance_request"
            + " WHERE shop_domain = ? AND topic = 'customers/data_request'", live.shop()).getFirst();
        assertThat(reported).containsEntry("outcome", "REPORTED").containsEntry("customer_id", "8123");
        Map<String, Object> report = JSON.readValue(String.valueOf(reported.get("report")), Map.class);
        assertThat((List<?>) report.get("attempts")).hasSize(1);
        assertThat((List<?>) report.get("discountCodes")).hasSize(1);

        deliver("customers/redact", live.shop(), request);
        assertThat(query("SELECT outcome FROM qbs_compliance_request WHERE shop_domain = ? AND topic ="
            + " 'customers/redact'", live.shop())).extracting(row -> row.get("outcome")).containsExactly("REDACTED");
        assertThat(query("SELECT 1 FROM qbs_taker WHERE shop_domain = ?", live.shop())).isEmpty();
        // What the data request recorded of them is gone too, and the erasure's record names nobody.
        assertThat(query("SELECT topic, customer_id, report FROM qbs_compliance_request WHERE shop_domain = ?",
            live.shop())).singleElement().satisfies(row -> assertThat(row).containsEntry("topic", "customers/redact")
                .containsEntry("customer_id", null).containsEntry("report", null));
        assertThat(query("SELECT taker FROM qbs_discount_code WHERE status = 'ISSUED' AND shop_domain = ?",
            live.shop())).extracting(row -> row.get("taker")).containsOnlyNulls();
        // Nothing left of them: another data request finds nothing.
        Map<String, Object> second = new LinkedHashMap<>(request);
        second.put("data_request", Map.of("id", 2));
        deliver("customers/data_request", live.shop(), second);
        assertThat(query("SELECT outcome FROM qbs_compliance_request WHERE shop_domain = ? AND topic ="
            + " 'customers/data_request' ORDER BY handled_at", live.shop())).extracting(row -> row.get("outcome"))
            .contains("NO_DATA");

        deliver("app/uninstalled", live.shop(), uninstalled(live.shop()));
        assertThat(query("SELECT status FROM qbs_campaign WHERE campaign_id = ?", live.campaignId()))
            .extracting(row -> row.get("status")).containsExactly("PAUSED");
        assertThat(query("SELECT status FROM qb_publication_card WHERE publication_id = ?::uuid", live.publicationId()))
            .extracting(row -> row.get("status")).containsExactly("PAUSED");
        assertThat(ok(storefront("QBS_SF_CAMPAIGN", live.shop(), null, visitor(), null))).containsEntry("live", false);
        // Shopify deleted the app's discounts: no code of the batches is handed out any more.
        assertThat(query("SELECT DISTINCT status FROM qbs_discount_batch WHERE shop_domain = ?", live.shop()))
            .extracting(row -> row.get("status")).containsExactly("RETIRED");

        deliver("shop/redact", live.shop(), privacy(live.shop()));
        for (String table : List.of("qbs_campaign", "qbs_discount_batch", "qbs_discount_code", "qbs_taker")) {
            assertThat(query("SELECT 1 FROM " + table + " WHERE shop_domain = ?", live.shop())).as(table).isEmpty();
        }
        assertThat(query("SELECT status FROM qb_publication_card WHERE publication_id = ?::uuid", live.publicationId()))
            .extracting(row -> row.get("status")).containsExactly("ENDED");
    }

    @Test
    void aNewCampaignHandsOutItsOwnDiscountOnly() {
        LiveShop live = liveShop("switch");
        refill();
        refill();
        // Ended with most of its codes left; the next campaign is 30 % off. (Signed in again: the clock moved on.)
        live = new LiveShop(live.shop(), open(live.shop(), "1"), live.campaignId(), live.publicationId());
        run("QBS_CAMPAIGN_MOVE", live.admin().bearer(), Map.of("campaignId", live.campaignId(), "to", "ENDED"));
        Map<String, Object> draft = new LinkedHashMap<>(draft("Autumn quiz"));
        draft.put("percent", 30);
        later(Duration.ofSeconds(1));
        Map<String, Object> saved = run("QBS_CAMPAIGN_SAVE", live.admin().bearer(), draft);
        Map<String, Object> next = run("QBS_CAMPAIGN_PUBLISH", live.admin().bearer(), Map.of("campaignId",
            saved.get("campaignId")));
        LiveShop autumn = new LiveShop(live.shop(), live.admin(), (String) saved.get("campaignId"),
            (String) next.get("publicationId"));
        // None of the old campaign's codes: none until its own batch is made.
        assertThat(ok(storefront("QBS_SF_CAMPAIGN", live.shop(), null, visitor(), null)))
            .containsEntry("codesToday", false).containsEntry("percent", 30);
        assertThat(play(autumn, null, visitor(), RIGHT)).containsEntry("outcome", "NONE_LEFT");
        refill();
        refill();
        Map<String, Object> passed = play(autumn, null, visitor(), RIGHT);
        assertThat(passed).containsEntry("outcome", "ISSUED").containsEntry("percent", 30);
        assertThat(query("SELECT b.percent FROM qbs_discount_code c JOIN qbs_discount_batch b ON b.batch_id ="
            + " c.batch_id WHERE c.attempt_id = ?", passed.get("attemptId"))).singleElement()
            .satisfies(row -> assertThat(new java.math.BigDecimal(String.valueOf(row.get("percent"))))
                .isEqualByComparingTo("30"));
    }

    @Test
    void aSubmissionWithoutAVisitorKeyIsRefused() {
        LiveShop live = liveShop("nokey");
        refill();
        refill();
        String visitor = visitor();
        Map<String, Object> passed = play(live, null, visitor, RIGHT);
        // Asked again for the attempt's code, but without the key: refused as such, not a failure.
        assertThat(ruleCode(storefront("QBS_SF_SUBMIT", live.shop(), null, visitor, Map.of("publicationId",
            live.publicationId(), "attemptId", passed.get("attemptId"))).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody())).isEqualTo("QBS_VISITOR_REQUIRED");
    }

    @Test
    void aBulkCreationShopifyNeverFinishesIsGivenUp() {
        LiveShop live = liveShop("stuck");
        refill();
        String graphql = "/shops/" + live.shop() + "/admin/api/2026-10/graphql.json";
        SHOPIFY.script(graphql, StubHttpServer.Answer.json(200, "{\"data\":{\"discountRedeemCodeBulkCreation\":"
            + "{\"done\":false,\"failedCount\":0,\"codes\":{\"nodes\":[]}}}}"));
        refill();
        assertThat(query("SELECT status FROM qbs_discount_batch WHERE shop_domain = ?", live.shop()))
            .extracting(row -> row.get("status")).containsExactly("CREATING");
        // An hour on, still not done: given up, and a new batch made in its place.
        later(Duration.ofHours(1));
        refill();
        assertThat(query("SELECT status FROM qbs_discount_batch WHERE shop_domain = ? ORDER BY created_at",
            live.shop())).extracting(row -> row.get("status")).containsExactly("RETIRED", "CREATING");
    }

    @Test
    void aFailureAfterARefreshKeepsTheNewTokens() {
        LiveShop live = liveShop("refreshed");
        // The access token ends within minutes: the refill refreshes it, then Shopify fails the discount.
        execute("UPDATE qbs_shop_token SET access_expires_at = ? WHERE shop_id = (SELECT shop_id FROM qbs_shop"
            + " WHERE shop_domain = ?)", java.sql.Timestamp.from(clock.instant().plusSeconds(60)), live.shop());
        SHOPIFY.script("/shops/" + live.shop() + "/admin/api/2026-10/graphql.json",
            StubHttpServer.Answer.json(503, "{}"));
        refill();
        assertThat(SHOPIFY.requests("/shops/" + live.shop() + "/admin/oauth/access_token")).hasSize(2);
        // Kept: the refreshed pair (Shopify made the old one useless); no batch yet, the next run makes it.
        Instant ends = ((java.sql.Timestamp) query("SELECT t.access_expires_at FROM qbs_shop_token t JOIN qbs_shop s"
            + " ON s.shop_id = t.shop_id WHERE s.shop_domain = ?", live.shop()).getFirst().get("access_expires_at"))
            .toInstant();
        assertThat(ends).isAfter(clock.instant().plus(Duration.ofMinutes(30)));
        assertThat(query("SELECT 1 FROM qbs_discount_batch WHERE shop_domain = ?", live.shop())).isEmpty();
        refill();
        assertThat(query("SELECT status FROM qbs_discount_batch WHERE shop_domain = ?", live.shop()))
            .extracting(row -> row.get("status")).containsExactly("CREATING");
        assertThat(shopRows(live.shop()).getFirst()).containsEntry("connection", "CONNECTED");
    }
}
