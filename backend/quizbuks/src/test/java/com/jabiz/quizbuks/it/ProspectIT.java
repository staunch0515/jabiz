package com.jabiz.quizbuks.it;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.prospect.ProspectCodes;
import com.jabiz.quizbuks.prospect.ProspectProcesses;
import com.jabiz.quizbuks.prospect.QbProspects;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI prospecting (docs/quizbuks/plans/Q48-operators.md, Q48c): a day's search finds businesses, each is researched,
 * scored and drafted, a person approves the draft (as edited), and it leaves within working hours and the day's cap
 * through the owner's own mail server, with the trial quiz's link; blocked, unsubscribed and refusing businesses are
 * never written to; every call's tokens and searches are counted to the owner's month, whose cap stops new searches;
 * an operator sees and acts on its own prospects only. Claude is the stub, the owner's mail server the second
 * GreenMail (SMTPS with a login); the platform's server is the first and carries none of these mails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=quizbuks@example.com",
    "jabiz.mail.base-url=https://quizbuks.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3052"})
class ProspectIT extends AiItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3052, null,
        ServerSetup.PROTOCOL_SMTP));

    static final String OWN_USER = "prospect";
    static final String OWN_PASSWORD = "prospect-secret-1";
    static final int OWN_PORT = 3468;

    @RegisterExtension
    static final GreenMailExtension OWN = new GreenMailExtension(new ServerSetup(OWN_PORT, null,
        ServerSetup.PROTOCOL_SMTPS)).withConfiguration(GreenMailConfiguration.aConfig()
        .withUser("sales@partner.example", OWN_USER, OWN_PASSWORD));

    /** A Monday at 10:00 in Tokyo: within the working hours. */
    static final Instant MONDAY_TEN = Instant.parse("2026-11-02T01:00:00Z");

    @Autowired
    @Qualifier("qbProspectFindJobDefinition")
    JobDefinition<ProspectProcesses.DirectionRef> findJob;

    @Autowired
    @Qualifier("qbProspectResearchJobDefinition")
    JobDefinition<ProspectProcesses.ProspectRef> researchJob;

    @Autowired
    @Qualifier("qbProspectSendJobDefinition")
    JobDefinition<ProspectProcesses.ProspectRef> sendJob;

    @BeforeEach
    void prospectingOn() {
        clock.set(MONDAY_TEN);
        // The tests of the class share a schema: an earlier test's owner neither searches nor sends in this one.
        execute("UPDATE qb_prospect_setting SET enabled = false");
        if (query("SELECT 1 FROM sys_param_version WHERE param_key = ? AND param_value = 'true'",
            QbParams.PROSPECT_ENABLED).isEmpty()) {
            param(QbParams.PROSPECT_MODEL, "claude-opus-5-5");
            param(QbParams.PROSPECT_EFFORT, "medium");
            param(QbParams.PROSPECT_ENABLED, "true");
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** A user whose address is verified: a new version of the platform's user, as the verification writes. */
    private String verifiedUser(String name) {
        String userId = createUser(name);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        return userId;
    }

    /** Headquarters' prospecting person: a real user (the test mail of the server goes to their address). */
    private String hq(String userId) {
        return TestTokens.bearer(tokens, userId, QbPermissions.PROSPECT_USE, QbPermissions.ADMIN_PROSPECT);
    }

    private String hqAdmin() {
        return TestTokens.bearer(tokens, "q48c-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    /** A new operator with one lead; returns {operatorId, the lead's token}. */
    private List<String> newOperator(String name) {
        String operatorId = (String) run(OperatorProcesses.SAVE, hqAdmin(), body("name", name, "contactName",
            "Owner", "contactEmail", "ops@" + name.toLowerCase().replace(' ', '-') + ".example")).get("operatorId");
        String member = verifiedUser(unique("q48c-op"));
        run(OperatorProcesses.MEMBER_ADD, hqAdmin(), body("operatorId", operatorId, "userId", member, "lead", true));
        return List.of(operatorId, tokenOf(member, QbRoles.OPERATOR));
    }

    private static Map<String, Object> settings(int dailyCap, String cap, Long revision) {
        return body("enabled", true, "dailyCap", dailyCap, "minScore", 60, "monthlyCostCap", new BigDecimal(cap),
            "senderName", "QuizBuks Partners", "senderAddress", "1-2-3 Shibuya, Tokyo", "senderContact",
            "sales@partner.example", "baseRevision", revision);
    }

    private static Map<String, Object> server() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("host", "localhost");
        input.put("port", OWN_PORT);
        input.put("tls", "SMTPS");
        input.put("username", OWN_USER);
        input.put("password", OWN_PASSWORD);
        input.put("fromAddress", "sales@partner.example");
        input.put("fromName", "QuizBuks Partners");
        return input;
    }

    /** An owner ready to prospect: settings, a verified server and one active direction; returns the direction. */
    private String ready(String token, int dailyCap, String cap) {
        run(ProspectProcesses.SETTINGS_SAVE, token, settings(dailyCap, cap, null));
        assertThat(run(ProspectProcesses.MAIL_SERVER_SET, token, server())).containsEntry("verified", true);
        return (String) run(ProspectProcesses.DIRECTION_SAVE, token, body("name", "Tea shops", "brief",
            "Independent tea shops that sell online", "country", "jp", "language", "JA", "active", true,
            "perSearch", 5)).get("directionId");
    }

    private JobRunner.JobRun runJob(JobDefinition<?> job) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun run = jobRunner.execute(job, clock.instant());
        runJobs();
        return run;
    }

    /** A 200 answer of the Messages API that ran {@code searches} web searches. */
    static StubHttpServer.Answer webAnswer(String text, long in, long out, long searches) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "msg_stub");
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", "claude-opus-5-5");
        message.put("content", List.of(Map.of("type", "text", "text", text)));
        message.put("stop_reason", "end_turn");
        message.put("usage", Map.of("input_tokens", in, "output_tokens", out, "server_tool_use",
            Map.of("web_search_requests", searches)));
        return StubHttpServer.Answer.json(200, JSON.writeValueAsString(message));
    }

    static String found(String... domains) {
        List<Object> candidates = new ArrayList<>();
        for (String domain : domains) {
            candidates.add(Map.of("domain", domain, "url", "https://" + domain + "/", "company", "Shop " + domain));
        }
        return "I found these.\n" + JSON.writeValueAsString(Map.of("candidates", candidates));
    }

    static Map<String, Object> trial(String title) {
        List<Object> questions = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            questions.add(Map.of("stem", "Which tea is number " + i + "?", "options", List.of(
                Map.of("text", "Sencha " + i, "correct", true), Map.of("text", "Coffee", "correct", false),
                Map.of("text", "Cocoa", "correct", false), Map.of("text", "Juice", "correct", false))));
        }
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("title", title);
        trial.put("intro", "Five questions about our teas.");
        trial.put("questions", questions);
        return trial;
    }

    static String research(String email, int score, boolean refuses) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("company", "Green Leaf Tea");
        root.put("language", "ja");
        root.put("country", "JP");
        root.put("sellsOnline", true);
        root.put("shopify", true);
        root.put("publicEmail", email);
        root.put("refusesMarketing", refuses);
        root.put("score", score);
        root.put("reasons", List.of("Teas with a story", "A Shopify shop"));
        root.put("products", List.of(Map.of("name", "Sencha", "description", "A green tea from Shizuoka")));
        root.put("trial", trial("Know your sencha"));
        root.put("mailSubject", "A quiz about your sencha");
        root.put("mailBody", "Your Shizuoka sencha caught our eye.\nWe made a short quiz from your product pages.");
        return "Done.\n" + JSON.writeValueAsString(root);
    }

    private Map<String, Object> prospect(String domain) {
        return query("SELECT * FROM qb_prospect WHERE domain = ?", domain).getFirst();
    }

    private static List<MimeMessage> to(GreenMailExtension server, String address) {
        return Arrays.stream(server.getReceivedMessages()).filter(m -> {
            try {
                return m.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    // ---- tests -------------------------------------------------------------------------------------------------------

    @Test
    void aBusinessIsFoundResearchedApprovedAndWrittenToThroughTheOwnersServer() {
        String person = verifiedUser(unique("q48c-hq"));
        String token = hq(person);
        String directionId = ready(token, 5, "50");

        // The day's search: the direction's brief goes to Claude inside its tag, with the search tool.
        CLAUDE.script(MESSAGES, webAnswer(found("greenleaf.example", "amazon.co.jp", "teahouse.example"), 3000,
            900, 4));
        assertThat(runJob(findJob).items().succeeded()).isEqualTo(1);
        JsonNode search = sent().getLast();
        assertThat(search.get("tools").get(0).get("type").asString()).startsWith("web_search_");
        assertThat(search.get("tools").get(0).get("user_location").get("country").asString()).isEqualTo("JP");
        assertThat(search.get("messages").get(0).get("content").asString()).contains("Independent tea shops");
        assertThat(query("SELECT domain, status FROM qb_prospect WHERE owner = 'direct' ORDER BY domain"))
            .extracting(r -> r.get("domain") + ":" + r.get("status"))
            .containsExactly("greenleaf.example:FOUND", "teahouse.example:FOUND");
        assertThat(query("SELECT last_search_at FROM qb_prospect_direction WHERE direction_id = ?", directionId)
            .getFirst().get("last_search_at")).isNotNull();
        // Searched once a day: the next run the same day starts nothing.
        int calls = sent().size();
        runJob(findJob);
        assertThat(sent()).hasSize(calls);

        // The research: one business's site, read with fetches; its draft kept for a person.
        CLAUDE.script(MESSAGES, webAnswer(research("info@greenleaf.example", 85, false), 20_000, 3000, 1));
        CLAUDE.script(MESSAGES, webAnswer(research(null, 75, false), 15_000, 2000, 1));
        assertThat(runJob(researchJob).items().succeeded()).isEqualTo(2);
        Map<String, Object> drafted = prospect("greenleaf.example");
        assertThat(drafted).containsEntry("status", QbProspects.DRAFTED).containsEntry("public_email",
            "info@greenleaf.example").containsEntry("shopify", true);
        assertThat(String.valueOf(drafted.get("trial"))).contains("Know your sencha");
        // No published business address: never written to.
        assertThat(prospect("teahouse.example")).containsEntry("status", QbProspects.SKIPPED)
            .containsEntry("failure", "no-public-email");
        JsonNode read = sent().get(sent().size() - 2);
        assertThat(read.get("tools").toString()).contains("web_fetch_");
        assertThat(read.get("messages").get(0).get("content").asString()).contains("The site: https://");

        // The month's cost: every call's tokens and the four plus two searches at ten dollars a thousand.
        Map<String, Object> usage = query("SELECT * FROM qb_prospect_usage WHERE owner = 'direct'").getFirst();
        assertThat(((Number) usage.get("searches")).longValue()).isEqualTo(6);
        assertThat(((Number) usage.get("jobs")).longValue()).isEqualTo(3);
        assertThat(((Number) usage.get("input_tokens")).longValue()).isEqualTo(38_000);
        assertThat((BigDecimal) usage.get("cost_usd")).isGreaterThan(new BigDecimal("0.06"));

        // The person edits the draft; a link is refused, a trial with four questions too.
        String prospectId = String.valueOf(drafted.get("prospect_id"));
        long revision = ((Number) drafted.get("revision")).longValue();
        assertThat(codes(refused(ProspectProcesses.APPROVE, token, body("prospectId", prospectId, "mailSubject",
            "Hello", "mailBody", "See https://x.example", "baseRevision", revision), 422)))
            .containsExactly(ProspectCodes.MAIL_BODY);
        Map<String, Object> shortTrial = trial("Short");
        ((List<?>) shortTrial.get("questions")).remove(0);
        assertThat(codes(refused(ProspectProcesses.APPROVE, token, body("prospectId", prospectId, "mailSubject",
            "Hello", "mailBody", "Hello there", "trial", shortTrial, "baseRevision", revision), 422)))
            .containsExactly(ProspectCodes.TRIAL);
        assertThat(run(ProspectProcesses.APPROVE, token, body("prospectId", prospectId, "mailSubject",
            "A quiz made from your sencha", "mailBody", "Your sencha caught our eye.", "trial",
            trial("Sencha, edited"), "baseRevision", revision))).containsEntry("status", QbProspects.APPROVED);
        Map<String, Object> approved = prospect("greenleaf.example");
        String key = String.valueOf(approved.get("trial_key"));
        assertThat(key).hasSize(48);
        assertThat(approved.get("approved_by")).isEqualTo(person);

        // Before nine in Tokyo it waits; at ten it leaves through the owner's server, never the platform's.
        clock.set(Instant.parse("2026-11-02T23:30:00Z"));
        assertThat(runJob(sendJob).items().succeeded()).isEqualTo(1);
        assertThat(prospect("greenleaf.example")).containsEntry("status", QbProspects.APPROVED);
        clock.set(MONDAY_TEN.plus(Duration.ofDays(1)));
        assertThat(runJob(sendJob).items().succeeded()).isEqualTo(1);
        deliverEvents();
        assertThat(to(OWN, "info@greenleaf.example")).singleElement().satisfies(mail -> {
            assertThat(mail.getSubject()).isEqualTo("A quiz made from your sencha");
            assertThat(mail.getFrom()[0].toString()).contains("sales@partner.example");
            String text = GreenMailUtil.getBody(mail);
            assertThat(text).contains("Your sencha caught our eye.").contains("sponsor/try/" + key)
                .contains("1-2-3 Shibuya, Tokyo").contains("unsubscribe");
        });
        assertThat(to(MAIL, "info@greenleaf.example")).isEmpty();
        assertThat(prospect("greenleaf.example")).containsEntry("status", QbProspects.SENT);
        assertThat(query("SELECT sender_key FROM sys_mail_message WHERE address = ?", "info@greenleaf.example"))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("sender_key", "prospect:direct"));

        // The trial page reads the quiz that went out by its key, anonymously; another key finds nothing.
        Map<String, Object> page = publicQuery("qb.public.prospect-trial", "p.key=" + key).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(String.valueOf(page.get("items"))).contains("Sencha, edited").contains("Green Leaf Tea")
            .doesNotContain("info@greenleaf.example");
        Map<String, Object> none = publicQuery("qb.public.prospect-trial", "p.key=" + "0".repeat(48))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<?>) none.get("items")).isEmpty();

        // Headquarters' page shows the prospects, without the key.
        assertThat(items("qb.admin.prospects", token, Map.of("params", Map.of("owner", "direct"))))
            .hasSize(2).allSatisfy(row -> assertThat(row).doesNotContainKey("trialKey"));
    }

    @Test
    void blockedRefusingAndUnsubscribedBusinessesAreNeverWrittenTo() {
        List<String> operator = newOperator(unique("Block"));
        String owner = operator.get(0);
        String token = operator.get(1);
        ready(token, 5, "50");
        // A domain blocked by hand is neither kept from a search nor researched.
        assertThat(run(ProspectProcesses.BLOCK_ADD, token, body("kind", "DOMAIN", "value", "WWW.Blocked.example",
            "reason", "MANUAL"))).containsEntry("value", "blocked.example").containsEntry("changed", true);

        CLAUDE.script(MESSAGES, webAnswer(found("blocked.example", "refuses.example", "later.example"), 100, 100,
            1));
        runJob(findJob);
        assertThat(query("SELECT domain FROM qb_prospect WHERE owner = ? ORDER BY domain", owner))
            .extracting(r -> r.get("domain")).containsExactly("later.example", "refuses.example");
        // The blocked domain was sent to Claude as one to leave out.
        assertThat(sent().getLast().get("messages").get(0).get("content").asString()).contains("blocked.example");

        // A site that refuses promotional mail is skipped and its domain blocked for every owner.
        // Researched in the order found: refuses.example, then later.example.
        CLAUDE.script(MESSAGES, webAnswer(research("info@refuses.example", 90, true), 100, 100, 0));
        CLAUDE.script(MESSAGES, webAnswer(research("info@later.example", 90, false), 100, 100, 0));
        runJob(researchJob);
        assertThat(prospect("refuses.example")).containsEntry("status", QbProspects.SKIPPED)
            .containsEntry("failure", "refuses-marketing");
        assertThat(query("SELECT reason FROM qb_prospect_block WHERE value = 'refuses.example'")).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("reason", QbProspects.REFUSES_MARKETING));

        // Approved, then the address unsubscribed (the platform's address-level opt-out): skipped at the send, and
        // blocked for every owner.
        Map<String, Object> later = prospect("later.example");
        run(ProspectProcesses.APPROVE, token, body("prospectId", String.valueOf(later.get("prospect_id")),
            "mailSubject", "Hello", "mailBody", "Hello there", "baseRevision", later.get("revision")));
        execute("INSERT INTO sys_mail_address_optout (opt_out_id, address, template, opted_out_time, version) "
            + "VALUES (gen_random_uuid(), 'info@later.example', 'qb.prospect', now(), 0)");
        runJob(sendJob);
        deliverEvents();
        assertThat(to(OWN, "info@later.example")).isEmpty();
        assertThat(prospect("later.example")).containsEntry("status", QbProspects.SKIPPED)
            .containsEntry("failure", "blocked");
        assertThat(query("SELECT reason FROM qb_prospect_block WHERE value = 'info@later.example'"))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("reason", QbProspects.UNSUBSCRIBED));

        // Discarded with "block": its domain is blocked too, and it stays discarded.
        assertThat(run(ProspectProcesses.DISCARD, token, body("prospectId", String.valueOf(later.get("prospect_id")),
            "block", true))).containsEntry("status", QbProspects.DISCARDED);
        assertThat(query("SELECT reason FROM qb_prospect_block WHERE value = 'later.example'")).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("reason", QbProspects.DECLINED));
        // Another's business is not this operator's to block through a discard: 404.
        refused(ProspectProcesses.DISCARD, newOperator(unique("Other")).get(1), body("prospectId",
            String.valueOf(later.get("prospect_id")), "block", true), 404);
    }

    @Test
    void theMonthsCostCapStopsNewSearchesAndTheDailyCapTheSends() {
        List<String> operator = newOperator(unique("Cap"));
        String owner = operator.get(0);
        String token = operator.get(1);
        ready(token, 1, "0.02");

        CLAUDE.script(MESSAGES, webAnswer(found("one.example", "two.example"), 1000, 1000, 3));
        runJob(findJob);
        // Three searches: 0.03 dollars, over the cap of 0.02. The research waits, the next day's search too.
        assertThat(query("SELECT cost_usd FROM qb_prospect_usage WHERE owner = ?", owner).getFirst()
            .get("cost_usd")).satisfies(cost -> assertThat((BigDecimal) cost).isGreaterThan(new BigDecimal("0.02")));
        int calls = sent().size();
        runJob(researchJob);
        clock.set(MONDAY_TEN.plus(Duration.ofDays(1)));
        runJob(findJob);
        assertThat(sent()).hasSize(calls);
        assertThat(query("SELECT status FROM qb_prospect WHERE owner = ?", owner))
            .allSatisfy(r -> assertThat(r).containsEntry("status", QbProspects.FOUND));

        // A higher cap; the daily cap of one mail allows two researches a day, and one send.
        Map<String, Object> setting = query("SELECT revision FROM qb_prospect_setting WHERE owner = ?", owner)
            .getFirst();
        run(ProspectProcesses.SETTINGS_SAVE, token, settings(1, "50", ((Number) setting.get("revision"))
            .longValue()));
        CLAUDE.script(MESSAGES, webAnswer(research("hi@one.example", 90, false), 100, 100, 0));
        CLAUDE.script(MESSAGES, webAnswer(research("hi@two.example", 90, false), 100, 100, 0));
        runJob(researchJob);
        for (String domain : List.of("one.example", "two.example")) {
            Map<String, Object> row = prospect(domain);
            assertThat(row).containsEntry("status", QbProspects.DRAFTED);
            run(ProspectProcesses.APPROVE, token, body("prospectId", String.valueOf(row.get("prospect_id")),
                "mailSubject", "Hello", "mailBody", "Hello there", "baseRevision", row.get("revision")));
        }
        runJob(sendJob);
        assertThat(query("SELECT count(*) AS n FROM qb_prospect WHERE owner = ? AND status = 'SENT'", owner)
            .getFirst().get("n")).isEqualTo(1L);
        clock.set(MONDAY_TEN.plus(Duration.ofDays(2)));
        runJob(sendJob);
        assertThat(query("SELECT count(*) AS n FROM qb_prospect WHERE owner = ? AND status = 'SENT'", owner)
            .getFirst().get("n")).isEqualTo(2L);
    }

    @Test
    void anOperatorProspectsForItselfAndSeesNothingOfOthers() {
        List<String> created = newOperator(unique("Kansai"));
        String operatorId = created.get(0);
        String operator = created.get(1);
        ready(operator, 5, "50");
        assertThat(query("SELECT owner FROM qb_prospect_setting WHERE owner = ?", operatorId)).hasSize(1);
        // Its server is its own, under its own sender key.
        assertThat(query("SELECT 1 FROM qb_sponsor_mail_server WHERE sponsor_id = ?", "prospect:" + operatorId))
            .hasSize(1);

        CLAUDE.script(MESSAGES, webAnswer(found("kansai-tea.example"), 100, 100, 1));
        runJob(findJob);
        CLAUDE.script(MESSAGES, webAnswer(research("info@kansai-tea.example", 90, false), 100, 100, 0));
        runJob(researchJob);
        Map<String, Object> mine = query("SELECT * FROM qb_prospect WHERE owner = ?", operatorId).getFirst();
        assertThat(mine).containsEntry("status", QbProspects.DRAFTED);
        assertThat(items("qb.operator.prospects", operator, Map.of())).extracting(r -> r.get("domain"))
            .containsExactly("kansai-tea.example");

        // Another owner's prospect is not the operator's: 404, and not in its list.
        List<String> other = newOperator(unique("Other"));
        ready(other.get(1), 5, "50");
        execute("INSERT INTO qb_prospect (prospect_id, owner, direction_id, domain, website_url, status, found_at, "
            + "public_email, revision, version) SELECT gen_random_uuid()::text, owner, direction_id, "
            + "'theirs-only.example', 'https://theirs-only.example/', 'DRAFTED', now(), 'a@theirs-only.example', 1, 0 "
            + "FROM qb_prospect_direction WHERE owner = ? LIMIT 1", other.get(0));
        Map<String, Object> theirs = prospect("theirs-only.example");
        refused(ProspectProcesses.APPROVE, operator, body("prospectId", String.valueOf(theirs.get("prospect_id")),
            "mailSubject", "Hello", "mailBody", "Hello there", "baseRevision", 1), 404);
        assertThat(items("qb.operator.prospects", operator, Map.of())).extracting(r -> r.get("domain"))
            .doesNotContain("theirs-only.example");

        // Somebody who is neither an operator's member nor headquarters' prospects for nobody.
        String stranger = TestTokens.bearer(tokens, verifiedUser(unique("q48c-x")), QbPermissions.PROSPECT_USE);
        refused(ProspectProcesses.SETTINGS_SAVE, stranger, settings(5, "50", null), 422);
    }
}
