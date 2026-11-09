package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.help.HelpProcesses;
import com.jabiz.quizbuks.help.HelpUpkeep;
import com.jabiz.quizbuks.help.HelpUsageGauge;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-C10's tests (docs/quizbuks/plans/impl/F-C10.md): real PostgreSQL and the Claude stub of the AI tests, which answers
 * a help question with an answer holding the site's links and others' ({@link #ANSWER}) unless a test scripts its own.
 * The help is switched on once per class ({@link #helpOn()}).
 */
abstract class HelpItSupport extends AiItSupport {

    /** What the stub answers a help question: the site's link, a link of another site, and addresses in the text. */
    static final String ANSWER_TEXT = "Open your wallet and choose Withdraw. More: https://evil.example/steal and "
        + "[the FAQ](https://evil.example/faq), or the help page /help/wallet.";
    static final String ANSWER = JSON.writeValueAsString(Map.of("answer", ANSWER_TEXT, "links",
        List.of("/help/wallet", "https://evil.example/p", "//evil.example/x", "javascript:alert(1)"),
        "quiz_request", false));

    @DynamicPropertySource
    static void helpLimits(DynamicPropertyRegistry registry) {
        // The tests ask many times a minute; the limits themselves are the platform's (and configuration).
        for (String process : List.of(HelpProcesses.ASK, HelpProcesses.ESCALATE, HelpProcesses.RATE,
            HelpProcesses.REPLY)) {
            registry.add("jabiz.process.rate-limits[" + process + "].per-user", () -> "10000");
            registry.add("jabiz.process.rate-limits[" + process + "].per-address", () -> "10000");
        }
    }

    @Autowired
    @Qualifier("qbHelpRetentionJob")
    JobDefinition<HelpUpkeep.ItemInput> retentionJob;

    @Autowired
    @Qualifier("qbHelpAutoCloseJob")
    JobDefinition<HelpUpkeep.ItemInput> autoCloseJob;

    @Autowired
    @Qualifier("qbHelpRemindJob")
    JobDefinition<HelpUpkeep.ItemInput> remindJob;

    @Autowired
    @Qualifier("qbHelpUsageJob")
    JobDefinition<HelpUpkeep.UsageCheckInput> usageJob;

    @Autowired
    HelpUsageGauge gauge;

    @BeforeEach
    void helpStub() {
        answering = request -> {
            if (!"POST".equals(request.method()) || !MESSAGES.equals(request.path())) {
                return null;
            }
            JsonNode body = JSON.readTree(request.body());
            return userText(body).contains("<help_question>")
                ? answer(body.get("model").asString(), "end_turn", ANSWER, 300, 100) : null;
        };
    }

    /** Switches the help on (four eyes), once per class. */
    void helpOn() {
        if (query("SELECT 1 FROM sys_param_version WHERE param_key = ? AND param_value = 'true'",
            QbParams.HELP_ENABLED).isEmpty()) {
            param(QbParams.HELP_ENABLED, "true");
        }
    }

    /** The help questions Claude received, oldest first (their whole bodies). */
    static List<JsonNode> helpRequests() {
        return sent().stream().filter(body -> userText(body).contains("<help_question>")).toList();
    }

    Map<String, Object> askInput(String threadId, String entry, String text, String page) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("threadId", threadId);
        input.put("entry", entry);
        input.put("language", "en");
        input.put("page", page);
        input.put("text", text);
        return input;
    }

    Map<String, Object> ask(String token, String threadId, String entry, String text, String page) {
        return run(HelpProcesses.ASK, token, askInput(threadId, entry, text, page));
    }

    Map<String, Object> escalate(String token, String threadId, String entry, String text) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("threadId", threadId);
        input.put("entry", entry);
        input.put("language", "en");
        input.put("text", text);
        return run(HelpProcesses.ESCALATE, token, input);
    }

    static Map<String, Object> thread(Object threadId) {
        return query("SELECT * FROM qb_help_thread WHERE thread_id = ?", String.valueOf(threadId)).getFirst();
    }

    static List<Map<String, Object>> messages(Object threadId) {
        return query("SELECT * FROM qb_help_message WHERE thread_id = ? ORDER BY created_at, kind DESC",
            String.valueOf(threadId));
    }

    static Map<String, Object> message(Object messageId) {
        return query("SELECT * FROM qb_help_message WHERE message_id = ?", String.valueOf(messageId)).getFirst();
    }

    /** The usage row of a user ("*": everybody's) and month, empty when there is none. */
    static Map<String, Object> helpUsage(String userId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_help_usage WHERE user_id = ? ORDER BY month DESC",
            userId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    /** The external job of a question. */
    static String jobOf(Object threadId, Object messageId) {
        return (String) query("SELECT job_id FROM sys_external_job WHERE reference = ?",
            "help:" + threadId + ":" + messageId).getFirst().get("job_id");
    }

    /** The tasks of a source key as they are now: status, permission and scope. */
    static List<Map<String, Object>> tasksOf(String sourceKey) {
        return query("SELECT status, assignee_permission, scope_name, scope_value, title_key FROM (SELECT DISTINCT ON "
            + "(task_id) * FROM sys_task_version ORDER BY task_id, version_no DESC) t WHERE source_key = ? "
            + "AND NOT is_deleted", sourceKey);
    }

    static List<Map<String, Object>> tasksLike(String sourcePrefix) {
        return query("SELECT status, source_key FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY "
            + "task_id, version_no DESC) t WHERE source_key LIKE ? AND NOT is_deleted", sourcePrefix + "%");
    }

    /** Runs a daily job at the clock's time. */
    JobRunner.JobRun runDaily(JobDefinition<?> job) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun run = jobRunner.execute(job, clock.instant());
        deliverEvents();
        return run;
    }

    /** A token of headquarters' content administrators (the help's people for Takers and direct Sponsors). */
    String hqHelp() {
        return TestTokens.bearer(tokens, "fc10-hq-help", com.jabiz.quizbuks.QbPermissions.ADMIN_HELP,
            com.jabiz.quizbuks.QbPermissions.ADMIN_AI_READ, "task.read");
    }

    static long number(Object value) {
        return value == null ? 0 : new java.math.BigDecimal(value.toString()).longValue();
    }

    StubHttpServer.Answer helpAnswer(String text, List<String> links, boolean quizRequest) {
        return answer("claude-haiku-5-5", "end_turn", JSON.writeValueAsString(Map.of("answer", text, "links", links,
            "quiz_request", quizRequest)), 300, 100);
    }
}
