package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.content.ai.SiteProfileProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drafting a profile package and a "get to know us" quiz from a website (F-C7, docs/quizbuks/plans/impl/F-C7.md) end to
 * end against the stub: the request and what is sent (the site's domains, no search, nothing of the sponsor), the draft
 * kept for the sponsor and read through its template, a personal e-mail address taken out, the failures with their
 * codes, the limits (switch, one at a time, a day, a month), the declaration, cancelling and the expiry.
 */
class SiteProfileIT extends AiItSupport {

    private static final String DRAFT = """
        {"failure": null,
         "package": {
           "basics": {"company": "Aini Inc.",
             "intro": {"ja": "ヘルメットの会社です。taro@aini.example まで。", "en": "A helmet company. Write taro@aini.example."},
             "contactName": "Taro Yamada"},
           "page": {"handle": "aini", "headline": {"ja": "毎日を安心して", "en": "Ride with confidence"},
             "website": "https://www.aini.example/"},
           "products": [{"name": {"ja": "通勤ヘルメット", "en": "City helmet"}, "price": 12800, "currency": "JPY",
              "shopUrl": "https://www.aini.example/shop/ah-100"}]},
         "sources": {"basics.company": "https://www.aini.example/about"},
         "notFound": ["page.cover"], "translated": ["en"],
         "materials": [{"title": "About us", "body": "We have made helmets since 1990.",
                        "url": "https://www.aini.example/about"}],
         "questions": [{"stem": "Since when?", "options": [{"text": "1990", "correct": true}, {"text": "2000",
            "correct": false}, {"text": "2010", "correct": false}, {"text": "2020", "correct": false}], "materials": [1]}]}
        """;

    @BeforeEach
    void switchedOn() {
        aiOn();
        if (query("SELECT 1 FROM sys_param_version WHERE param_key = ? AND param_value = 'true'",
            QbParams.SITE_PROFILE_ENABLED).isEmpty()) {
            param(QbParams.SITE_PROFILE_ENABLED, "true");
        }
        // A new day for each test: the day's requests start from none.
        advance(Duration.ofDays(1));
        answering = request -> {
            JsonNode body = JSON.readTree(request.body());
            return body.toString().contains("You read ONE company") && !request.path().endsWith("count_tokens")
                ? answer(body.get("model").asString(), "end_turn", DRAFT, 9000, 5000) : null;
        };
    }

    private Map<String, Object> ask(String token, String url, boolean rights) {
        return body("url", url, "language", "ja", "otherLanguages", List.of("en"), "rightsDeclared", rights,
            "aiNoticeVersionNo", noticeVersion());
    }

    @Test
    void aDraftIsKeptForTheSponsorAndNothingOfTheSponsorIsSent() {
        String token = sponsorFull(SPONSOR_A);
        Map<String, Object> requested = run(SiteProfileProcesses.REQUEST, token, ask(token, "https://www.aini.example", true));
        assertThat(requested).containsEntry("status", QbAi.QUEUED);
        Object aiJobId = requested.get("aiJobId");
        assertThat(job(aiJobId)).containsEntry("kind", QbAi.KIND_SITE_PROFILE).containsEntry("quiz_id", null);

        runJobs();
        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("status", QbAi.READY);
        assertThat(job.get("expires_at")).isNotNull();
        String stored = String.valueOf(job.get("candidates"));
        // The package without the contact name, a person's e-mail address taken out of the texts, where it came from,
        // what was not found, the article and the question.
        assertThat(stored).contains("Aini Inc.", "ヘルメットの会社です", "page.cover", "About us", "Since when?",
            "https://www.aini.example/about").doesNotContain("taro@aini.example", "contactName", "Taro Yamada");
        assertThat(((Number) job.get("candidate_count")).intValue()).isEqualTo(1);
        assertThat(((Number) usage(SPONSOR_A).get("jobs")).intValue()).isPositive();

        // What was sent: the web reader limited to the site's domains, no search, no sponsor.
        JsonNode sent = sent().getLast();
        assertThat(sent.get("tools")).hasSize(1);
        JsonNode fetch = sent.get("tools").get(0);
        assertThat(fetch.get("name").asString()).isEqualTo("web_fetch");
        assertThat(fetch.get("allowed_domains")).extracting(JsonNode::asString)
            .containsExactly("www.aini.example", "aini.example");
        assertThat(fetch.get("max_uses").asInt()).isEqualTo(10);
        String everything = sent.toString();
        assertThat(everything).doesNotContain(SPONSOR_A).doesNotContain("rightsDeclared");
        assertThat(everything).contains("https://www.aini.example/");
        StubHttpServer.Received received = CLAUDE.requests(MESSAGES).getLast();
        assertThat(received.header("x-api-key")).isEqualTo("test-only-quizbuks-claude-key");

        // The sponsor reads it through the template; another sponsor sees nothing of it.
        List<Map<String, Object>> mine = items("qb.sponsor.site-profile", token, Map.of());
        assertThat(mine).anySatisfy(row -> assertThat(row).containsEntry("aiJobId", String.valueOf(aiJobId))
            .containsEntry("status", QbAi.READY));
        assertThat(items("qb.sponsor.site-profile", sponsorFull(SPONSOR_B), Map.of()))
            .noneMatch(row -> String.valueOf(aiJobId).equals(String.valueOf(row.get("aiJobId"))));
        // The operation's summary holds the address asked for and nothing of the sponsor.
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name = ? ORDER BY "
            + "process_seq_id DESC LIMIT 1", SiteProfileProcesses.REQUEST).getFirst().get("s").toString())
            .contains("aini.example").doesNotContain(SPONSOR_A);
    }

    @Test
    void theRequestIsRefusedWithEveryProblem() {
        String token = sponsorFull(SPONSOR_B);
        int before = query("SELECT 1 FROM qb_ai_job WHERE kind = 'SITE_PROFILE' AND sponsor_id = ?", SPONSOR_B).size();
        var refused = call(SiteProfileProcesses.REQUEST, token, ask(token, "http://user:pw@localhost:8080/", false))
            .expectBody(MAP).returnResult();
        assertThat(refused.getStatus().value()).isEqualTo(422);
        assertThat(codes(refused.getResponseBody())).contains(AiCodes.SITE_RIGHTS_REQUIRED, AiCodes.SITE_URL_INVALID);
        // Nothing asked for.
        Map<String, Object> empty = ask(token, "https://www.aini.example/", true);
        empty.put("profile", false);
        empty.put("products", false);
        empty.put("quiz", false);
        var nothing = call(SiteProfileProcesses.REQUEST, token, empty).expectBody(MAP).returnResult();
        assertThat(codes(nothing.getResponseBody())).contains(AiCodes.SITE_REQUEST_INVALID);
        assertThat(query("SELECT 1 FROM qb_ai_job WHERE kind = 'SITE_PROFILE' AND sponsor_id = ?", SPONSOR_B))
            .hasSize(before);
    }

    @Test
    void aSwitchedOffDeploymentRefusesTheRequest() {
        param(QbParams.SITE_PROFILE_ENABLED, "false");
        try {
            String token = sponsorFull(SPONSOR_B);
            var refused = call(SiteProfileProcesses.REQUEST, token, ask(token, "https://www.aini.example/", true))
                .expectBody(MAP).returnResult();
            assertThat(refused.getStatus().value()).isEqualTo(422);
            assertThat(codes(refused.getResponseBody())).contains(AiCodes.UNAVAILABLE);
        } finally {
            param(QbParams.SITE_PROFILE_ENABLED, "true");
        }
    }

    @Test
    void oneAtATimeAndAFewADayAndAMonth() {
        String token = sponsorFull(SPONSOR_A);
        setParam(QbParams.SITE_PROFILE_DAILY, "2");
        run(SiteProfileProcesses.REQUEST, token, ask(token, "https://www.aini.example/", true));
        var busy = call(SiteProfileProcesses.REQUEST, token, ask(token, "https://www.aini.example/", true))
            .expectBody(MAP).returnResult();
        assertThat(codes(busy.getResponseBody())).contains(AiCodes.SITE_BUSY);
        runJobs();
        run(SiteProfileProcesses.REQUEST, token, ask(token, "https://www.aini.example/", true));
        runJobs();
        // The third of the day.
        var third = call(SiteProfileProcesses.REQUEST, token, ask(token, "https://www.aini.example/", true))
            .expectBody(MAP).returnResult();
        assertThat(codes(third.getResponseBody())).contains(AiCodes.SITE_DAILY_LIMIT);
        setParam(QbParams.SITE_PROFILE_DAILY, "3");
    }

    @Test
    void aSiteThatCannotBeReadFailsWithItsCode() {
        answering = request -> JSON.readTree(request.body()).toString().contains("You read ONE company")
            ? answer(JSON.readTree(request.body()).get("model").asString(), "end_turn", "{\"failure\": \"ROBOTS\"}", 4000,
                100) : null;
        String token = sponsorFull(SPONSOR_B);
        Object aiJobId = run(SiteProfileProcesses.REQUEST, token, ask(token, "https://robots.example", true)).get("aiJobId");
        runJobs();
        assertThat(job(aiJobId)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code", AiCodes.SITE_ROBOTS);
        assertThat(((Number) job(aiJobId).get("input_tokens")).longValue()).isEqualTo(4000);
    }

    @Test
    void aRequestCanBeCancelledAndADraftExpires() {
        String token = sponsorFull(SPONSOR_A);
        Object cancelled = run(SiteProfileProcesses.REQUEST, token, ask(token, "https://www.aini.example/", true))
            .get("aiJobId");
        assertThat(run(AiProcesses.CANCEL, token, body("aiJobId", cancelled))).containsEntry("status", QbAi.CANCELLED);
        runJobs();
        assertThat(job(cancelled)).containsEntry("status", QbAi.CANCELLED);

        Object ready = run(SiteProfileProcesses.REQUEST, token, ask(token, "https://www.aini.example/", true))
            .get("aiJobId");
        runJobs();
        assertThat(job(ready)).containsEntry("status", QbAi.READY);
        advance(Duration.ofDays(30));
        runExpiry();
        assertThat(job(ready)).containsEntry("status", QbAi.EXPIRED).containsEntry("candidates", null);
    }
}
