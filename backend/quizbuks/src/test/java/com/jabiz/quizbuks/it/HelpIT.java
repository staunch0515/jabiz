package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.help.HelpCodes;
import com.jabiz.quizbuks.help.HelpJob;
import com.jabiz.quizbuks.help.HelpProcesses;
import com.jabiz.quizbuks.help.QbHelp;
import com.jabiz.quizbuks.messaging.QbInbox;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-C10 (docs/quizbuks/plans/impl/F-C10.md): the AI help, against the Claude stub. The switch and each limit; its usage
 * is its own, never the Sponsors' AI usage; only the pages on the list reach the AI, no data of the user does; only the
 * site's links stay in an answer; a conversation handed to a person goes to the Sponsor's operator (headquarters for a
 * direct Sponsor and a Taker), whose reply reaches the user's inbox; reminders, closing by itself and the retention; and
 * every job outcome (answered, failed, stopped, unanswered attempts) counted in {@code QbHelpUsage}.
 */
class HelpIT extends HelpItSupport {

    @BeforeEach
    void on() {
        helpOn();
    }

    // ---- the switch and the limits -----------------------------------------------------------------------------------

    @Test
    void theSwitchStopsQuestionsButNotHandingThemToAPerson() {
        String taker = newTaker("t-fc10-off-" + UUID.randomUUID(), "JP");
        param(QbParams.HELP_ENABLED, "false");
        try {
            long jobs = count("sys_external_job");
            assertThat(codes(refused(HelpProcesses.ASK, taker(taker), askInput(null, QbHelp.APP, "How do I withdraw?",
                "wallet"), 422))).containsExactly(HelpCodes.DISABLED);
            assertThat(count("sys_external_job")).isEqualTo(jobs);
            // The page offers a person instead: the question goes over directly.
            Map<String, Object> handed = escalate(taker(taker), null, QbHelp.APP, "How do I withdraw?");
            assertThat(thread(handed.get("threadId"))).containsEntry("status", QbHelp.ESCALATED)
                .containsEntry("operator_id", null);
            assertThat(messages(handed.get("threadId"))).singleElement().satisfies(m ->
                assertThat(m).containsEntry("kind", QbHelp.USER).containsEntry("body", "How do I withdraw?"));
        } finally {
            param(QbParams.HELP_ENABLED, "true");
        }
    }

    @Test
    void eachPersonAsksAtMostTheDailyQuestionsAndEverybodyAtMostTheMonthlyTokens() {
        String taker = newTaker("t-fc10-daily-" + UUID.randomUUID(), "JP");
        setParam(QbParams.HELP_USER_DAILY, "2");
        try {
            ask(taker(taker), null, QbHelp.APP, "First?", null);
            ask(taker(taker), null, QbHelp.APP, "Second?", null);
            Map<String, Object> problem = refused(HelpProcesses.ASK, taker(taker), askInput(null, QbHelp.APP, "Third?",
                null), 422);
            assertThat(codes(problem)).containsExactly(HelpCodes.DAILY_LIMIT);
            // Another person is not held back by it; nor is the same person the next day.
            ask(taker(newTaker("t-fc10-daily-other-" + UUID.randomUUID(), "JP")), null, QbHelp.APP, "Mine?", null);
            advance(Duration.ofDays(1));
            ask(taker(taker), null, QbHelp.APP, "Next day?", null);
        } finally {
            setParam(QbParams.HELP_USER_DAILY, "20");
        }
        runJobs();

        // Everybody's tokens of the month: judged from what was recorded.
        long used = number(helpUsage(QbHelp.EVERYBODY).get("input_tokens"))
            + number(helpUsage(QbHelp.EVERYBODY).get("output_tokens"));
        assertThat(used).isPositive();
        param(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, String.valueOf(used));
        try {
            assertThat(codes(refused(HelpProcesses.ASK, taker(newTaker("t-fc10-month-" + UUID.randomUUID(), "JP")),
                askInput(null, QbHelp.APP, "Anything?", null), 422))).containsExactly(HelpCodes.MONTHLY_LIMIT);
        } finally {
            param(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, "20000000");
        }
    }

    @Test
    void aConversationAsksOneQuestionAtATimeAndSendsItsLatestTurns() {
        String taker = newTaker("t-fc10-turns-" + UUID.randomUUID(), "JP");
        Map<String, Object> first = ask(taker(taker), null, QbHelp.APP, "How do I withdraw?", "wallet");
        String threadId = (String) first.get("threadId");
        assertThat(first).containsEntry("jobStatus", QbHelp.QUEUED);
        assertThat(codes(refused(HelpProcesses.ASK, taker(taker), askInput(threadId, QbHelp.APP, "And then?", null),
            422))).containsExactly(HelpCodes.WAITING);
        runJobs();
        ask(taker(taker), threadId, QbHelp.APP, "And how long does it take?", "wallet");
        runJobs();
        JsonNode last = helpRequests().getLast();
        String user = userText(last);
        assertThat(user).contains("<help_history>").contains("How do I withdraw?").contains("Open your wallet")
            .contains("And how long does it take?");
        // Another's conversation is not found.
        refused(HelpProcesses.ASK, taker(newTaker("t-fc10-turns-other-" + UUID.randomUUID(), "JP")),
            askInput(threadId, QbHelp.APP, "Mine?", null), 404);
    }

    // ---- its usage is its own ----------------------------------------------------------------------------------------

    @Test
    void theHelpIsNeverASponsorsAiUsage() {
        String sponsor = newSponsor("s-fc10-usage-" + UUID.randomUUID());
        Map<String, Object> asked = ask(sponsorToken(sponsor), null, QbHelp.SPONSOR, "How do I publish?", "dashboard");
        runJobs();
        assertThat(message(asked.get("messageId"))).containsEntry("job_status", QbHelp.ANSWERED);
        assertThat(query("SELECT 1 FROM qb_ai_usage WHERE sponsor_id = ?", sponsor)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_ai_job WHERE sponsor_id = ?", sponsor)).isEmpty();
        Map<String, Object> usage = helpUsage(sponsor);
        assertThat(number(usage.get("questions"))).isEqualTo(1);
        assertThat(number(usage.get("input_tokens"))).isEqualTo(300);
        assertThat(number(usage.get("output_tokens"))).isEqualTo(100);
        assertThat(helpUsage(QbHelp.EVERYBODY)).isNotEmpty();
        // Headquarters' AI page reads it apart from the Sponsors' AI usage.
        List<Map<String, Object>> months = items(QbHelp.USAGE_TEMPLATE, hqHelp(), Map.of());
        assertThat(months).isNotEmpty();
        assertThat(number(months.getFirst().get("people"))).isPositive();
        assertThat(months.getFirst().get("percentUsed")).isNotNull();
        // A Taker cannot ask as a Sponsor.
        assertThat(codes(refused(HelpProcesses.ASK, taker(newTaker("t-fc10-entry-" + UUID.randomUUID(), "JP")),
            askInput(null, QbHelp.SPONSOR, "As a Sponsor?", null), 422))).containsExactly(HelpCodes.ENTRY);
    }

    // ---- what the AI is sent, and what is kept of its answer ---------------------------------------------------------

    @Test
    void onlyThePagesOnTheListAndTheQuestionReachTheAi() {
        String taker = newTaker("t-fc10-pages-" + UUID.randomUUID(), "JP");
        Map<String, Object> listed = ask(taker(taker), null, QbHelp.APP, "Where is my money?", "wallet");
        runJobs();
        Map<String, Object> play = ask(taker(taker), null, QbHelp.APP, "Which option is right?", "/play/123");
        Map<String, Object> sponsors = ask(taker(taker), null, QbHelp.APP, "What is this?", "quiz-edit");
        runJobs();
        assertThat(thread(listed.get("threadId"))).containsEntry("start_page", "wallet");
        assertThat(thread(play.get("threadId"))).containsEntry("start_page", null);
        assertThat(message(play.get("messageId"))).containsEntry("page", null);
        assertThat(thread(sponsors.get("threadId"))).containsEntry("start_page", null);
        List<JsonNode> requests = helpRequests();
        assertThat(requests.stream().map(AiItSupport::userText).filter(t -> t.contains("Where is my money?"))
            .findFirst().orElseThrow()).contains("<help_page>the Kudos wallet");
        for (String question : List.of("Which option is right?", "What is this?")) {
            String user = requests.stream().map(AiItSupport::userText).filter(t -> t.contains(question)).findFirst()
                .orElseThrow();
            assertThat(user).doesNotContain("<help_page>").doesNotContain("/play/");
        }
        // The user's anonymous key only: never the id, the name or the address.
        for (JsonNode body : requests) {
            assertThat(body.get("metadata").get("user_id").asString()).startsWith("qb-");
            assertThat(body.toString()).doesNotContain(taker);
            assertThat(body.get("system").asString()).contains("Never help answer a quiz");
            assertThat(body.get("model").asString()).isEqualTo("claude-haiku-5-5");
        }
    }

    @Test
    void onlyTheSitesLinksStayInAnAnswerAndAQuizRequestIsRefused() {
        String taker = newTaker("t-fc10-links-" + UUID.randomUUID(), "JP");
        Map<String, Object> asked = ask(taker(taker), null, QbHelp.APP, "How do I withdraw?", "wallet");
        runJobs();
        Map<String, Object> answer = messages(asked.get("threadId")).getLast();
        assertThat(answer).containsEntry("kind", QbHelp.ANSWER);
        assertThat((String) answer.get("body")).doesNotContain("evil.example").contains("the FAQ")
            .contains("/help/wallet").startsWith("Open your wallet");
        assertThat(answer.get("links")).isEqualTo("/help/wallet");
        // The user reads it through their own template, links one a line.
        List<Map<String, Object>> mine = items(QbHelp.MY_MESSAGES, taker(taker), Map.of("params",
            Map.of("threadId", asked.get("threadId"))));
        assertThat(mine).extracting(m -> m.get("kind")).containsExactly(QbHelp.QUESTION, QbHelp.ANSWER);
        assertThat(items(QbHelp.MY_MESSAGES, taker(newTaker("t-fc10-links-other-" + UUID.randomUUID(), "JP")),
            Map.of("params", Map.of("threadId", asked.get("threadId"))))).isEmpty();

        // The AI said it was asked to answer a quiz: the help's own refusal is kept, whatever it wrote.
        CLAUDE.script(MESSAGES, helpAnswer("The answer is B.", List.of(), true));
        Map<String, Object> quiz = ask(taker(taker), null, QbHelp.APP, "Which answer is right in this quiz?", null);
        runJobs();
        Map<String, Object> refusal = messages(quiz.get("threadId")).getLast();
        assertThat((String) refusal.get("body")).doesNotContain("B.").contains("quiz");
        assertThat(refusal).containsEntry("failure_code", HelpCodes.QUIZ_REFUSED);
    }

    // ---- handing over: who answers, and the user hears --------------------------------------------------------------

    @Test
    void aSponsorsQuestionGoesToItsOperatorOnlyAndTheReplyReachesTheInbox() {
        NewOperator north = newOperatorWithLead(operatorsAdmin(), unique("Help North"), null);
        NewOperator south = newOperatorWithLead(operatorsAdmin(), unique("Help South"), null);
        String northStaff = memberSession(north.leadId(), north.operatorId());
        String southStaff = memberSession(south.leadId(), south.operatorId());
        String sponsor = newSponsor(unique("s-fc10-north"));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId", north.operatorId(),
            "reason", "F-C10"));

        Map<String, Object> asked = ask(sponsorToken(sponsor), null, QbHelp.SPONSOR, "How do I publish?", "dashboard");
        runJobs();
        String threadId = (String) asked.get("threadId");
        escalate(sponsorToken(sponsor), threadId, QbHelp.SPONSOR, "The answer did not help.");
        assertThat(thread(threadId)).containsEntry("status", QbHelp.ESCALATED)
            .containsEntry("operator_id", north.operatorId()).containsEntry("waiting_for", QbHelp.WAIT_STAFF);
        assertThat(tasksOf(QbHelp.taskSource(threadId))).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("status", "OPEN").containsEntry("assignee_permission", "qb.admin.help")
            .containsEntry("scope_name", "operator").containsEntry("scope_value", north.operatorId()));
        assertThat(tasks(northStaff)).extracting(t -> t.get("subjectId")).contains(threadId);
        assertThat(tasks(southStaff)).extracting(t -> t.get("subjectId")).doesNotContain(threadId);
        assertThat(items(QbHelp.ADMIN_THREADS, northStaff, Map.of())).extracting(t -> t.get("threadId"))
            .contains(threadId);
        assertThat(items(QbHelp.ADMIN_THREADS, southStaff, Map.of())).extracting(t -> t.get("threadId"))
            .doesNotContain(threadId);
        assertThat(items(QbHelp.ADMIN_MESSAGES, northStaff, Map.of("params", Map.of("threadId", threadId))))
            .extracting(m -> m.get("kind")).containsExactly(QbHelp.QUESTION, QbHelp.ANSWER, QbHelp.USER);
        assertThat(items(QbHelp.ADMIN_MESSAGES, southStaff, Map.of("params", Map.of("threadId", threadId)))).isEmpty();

        // Another operator cannot reply; the Sponsor's operator can, and the Sponsor hears it.
        refused(HelpProcesses.REPLY, southStaff, body("threadId", threadId, "text", "Not mine.", "close", false), 404);
        Map<String, Object> reply = run(HelpProcesses.REPLY, northStaff, body("threadId", threadId, "text",
            "Open Publications and choose New.", "close", false));
        deliverEvents();
        assertThat(thread(threadId)).containsEntry("waiting_for", QbHelp.WAIT_USER);
        assertThat(message(reply.get("messageId"))).containsEntry("kind", QbHelp.STAFF).containsEntry("user_id", sponsor);
        assertThat(query("SELECT kind, action, action_id FROM qb_message WHERE recipient_id = ? AND kind = ?", sponsor,
            QbInbox.HELP_REPLY)).singleElement().satisfies(m -> assertThat(m).containsEntry("action",
                QbInbox.ACTION_HELP).containsEntry("action_id", threadId));

        // "Solved" on the reply closes the conversation and its task.
        run(HelpProcesses.RATE, sponsorToken(sponsor), body("threadId", threadId, "messageId", reply.get("messageId"),
            "rating", QbHelp.SOLVED));
        assertThat(thread(threadId)).containsEntry("status", QbHelp.CLOSED)
            .containsEntry("close_reason", QbHelp.CLOSED_RESOLVED).containsEntry("resolved", true);
        assertThat(tasksOf(QbHelp.taskSource(threadId))).singleElement().satisfies(task ->
            assertThat(task).containsEntry("status", "DONE"));
        assertThat(codes(refused(HelpProcesses.ASK, sponsorToken(sponsor), askInput(threadId, QbHelp.SPONSOR, "More?",
            null), 422))).containsExactly(HelpCodes.CLOSED);
    }

    @Test
    void aReassignedSponsorsConversationAndItsTasksGoToTheNewOperator() {
        NewOperator north = newOperatorWithLead(operatorsAdmin(), unique("Help Old"), null);
        NewOperator south = newOperatorWithLead(operatorsAdmin(), unique("Help New"), null);
        String sponsor = newSponsor(unique("s-fc10-moved"));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId", north.operatorId(),
            "reason", "F-C10"));
        String threadId = (String) escalate(sponsorToken(sponsor), null, QbHelp.SPONSOR, "Who looks after me?")
            .get("threadId");
        advance(Duration.ofDays(3));
        runDaily(remindJob);
        assertThat(tasksLike("qb.help-remind:" + threadId)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "OPEN"));

        // Moved with its pending work: the platform's APPROVAL_SCOPE_MOVE needs approval.scope.move besides.
        String hq = TestTokens.bearer(tokens, "fc10-operators-admin", com.jabiz.quizbuks.QbPermissions.OPERATORS_WRITE,
            com.jabiz.quizbuks.QbPermissions.OPERATORS_READ, "approval.scope.move");
        run(OperatorProcesses.ASSIGN, hq, body("userId", sponsor, "operatorId", south.operatorId(), "reason",
            "F-C10 moved"));
        // Sessions of now (the clock moved past the earlier tokens' life).
        String northStaff = memberSession(north.leadId(), north.operatorId());
        String southStaff = memberSession(south.leadId(), south.operatorId());
        assertThat(thread(threadId)).containsEntry("operator_id", south.operatorId());
        assertThat(tasksOf(QbHelp.taskSource(threadId))).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("status", "OPEN").containsEntry("scope_value", south.operatorId()));
        assertThat(query("SELECT scope_value FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY "
            + "task_id, version_no DESC) t WHERE source_key LIKE ? AND status = 'OPEN'", "qb.help-remind:" + threadId
            + "%")).singleElement().satisfies(task -> assertThat(task).containsEntry("scope_value",
                south.operatorId()));
        assertThat(tasks(southStaff)).extracting(t -> t.get("subjectId")).contains(threadId);
        assertThat(tasks(northStaff)).extracting(t -> t.get("subjectId")).doesNotContain(threadId);

        // The old operator no longer answers it; the new one does, and the Sponsor hears it.
        refused(HelpProcesses.REPLY, northStaff, body("threadId", threadId, "text", "Still mine?", "close", false),
            404);
        run(HelpProcesses.REPLY, southStaff, body("threadId", threadId, "text", "We look after you now.", "close",
            false));
        deliverEvents();
        assertThat(query("SELECT 1 FROM qb_message WHERE recipient_id = ? AND kind = ?", sponsor, QbInbox.HELP_REPLY))
            .hasSize(1);
        assertThat(tasksLike("qb.help-remind:" + threadId)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
    }

    @Test
    void aQuestionIsNoLongerThanTheParameterSays() {
        String taker = newTaker("t-fc10-long-" + UUID.randomUUID(), "JP");
        setParam(QbParams.HELP_QUESTION_MAX_LENGTH, "100");
        try {
            String long101 = "x".repeat(101);
            assertThat(codes(refused(HelpProcesses.ASK, taker(taker), askInput(null, QbHelp.APP, long101, null), 422)))
                .containsExactly(HelpCodes.TEXT_TOO_LONG);
            assertThat(codes(refused(HelpProcesses.ESCALATE, taker(taker), body("entry", QbHelp.APP, "language", "en",
                "text", long101), 422))).containsExactly(HelpCodes.TEXT_TOO_LONG);
            ask(taker(taker), null, QbHelp.APP, "x".repeat(100), null);
            runJobs();
        } finally {
            setParam(QbParams.HELP_QUESTION_MAX_LENGTH, "2000");
        }
        // The answers keep at most qb.help.max-links of the site's links.
        setParam(QbParams.HELP_MAX_LINKS, "1");
        try {
            CLAUDE.script(MESSAGES, helpAnswer("See these.", List.of("/help/a", "/help/b"), false));
            Map<String, Object> asked = ask(taker(taker), null, QbHelp.APP, "Links?", null);
            runJobs();
            assertThat(messages(asked.get("threadId")).getLast()).containsEntry("links", "/help/a");
        } finally {
            setParam(QbParams.HELP_MAX_LINKS, "5");
        }
    }

    @Test
    void aTakersAndADirectSponsorsQuestionGoToHeadquarters() {
        NewOperator north = newOperatorWithLead(operatorsAdmin(), unique("Help Direct"), null);
        String northStaff = memberSession(north.leadId(), north.operatorId());
        String taker = newTaker("t-fc10-hq-" + UUID.randomUUID(), "JP");
        Map<String, Object> takers = escalate(taker(taker), null, QbHelp.APP, "Where is my prize?");
        // The operator's lead is its own direct Sponsor: headquarters answers it, not the operator.
        Map<String, Object> directs = escalate(sponsorToken(north.leadId()), null, QbHelp.SPONSOR, "Who answers me?");
        for (Map<String, Object> handed : List.of(takers, directs)) {
            String threadId = (String) handed.get("threadId");
            assertThat(thread(threadId)).containsEntry("operator_id", null);
            assertThat(tasksOf(QbHelp.taskSource(threadId))).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("scope_name", null).containsEntry("assignee_permission", "qb.admin.help"));
            assertThat(tasks(northStaff)).extracting(t -> t.get("subjectId"))
                .doesNotContain(threadId);
            assertThat(tasks(hqHelp())).extracting(t -> t.get("subjectId")).contains(threadId);
            refused(HelpProcesses.REPLY, northStaff, body("threadId", threadId, "text", "Hello", "close", false), 404);
        }
        // Headquarters replies and closes in one go; the user is told.
        run(HelpProcesses.REPLY, hqHelp(), body("threadId", takers.get("threadId"), "text", "It is on its way.",
            "close", true));
        deliverEvents();
        assertThat(thread(takers.get("threadId"))).containsEntry("status", QbHelp.CLOSED)
            .containsEntry("close_reason", QbHelp.CLOSED_BY_STAFF);
        assertThat(tasksOf(QbHelp.taskSource(takers.get("threadId")))).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
        assertThat(query("SELECT 1 FROM qb_message WHERE recipient_id = ? AND kind = ?", taker, QbInbox.HELP_REPLY))
            .hasSize(1);
        // The user closes the other: its task closes.
        run(HelpProcesses.CLOSE, sponsorToken(north.leadId()), body("threadId", directs.get("threadId")));
        assertThat(tasksOf(QbHelp.taskSource(directs.get("threadId")))).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
    }

    // ---- the daily upkeep -----------------------------------------------------------------------------------------

    @Test
    void aWaitingConversationIsRemindedOnceAndAnUnansweredReplyClosesIt() {
        String taker = newTaker("t-fc10-remind-" + UUID.randomUUID(), "JP");
        String threadId = (String) escalate(taker(taker), null, QbHelp.APP, "Nobody answers?").get("threadId");
        advance(Duration.ofDays(1));
        runDaily(remindJob);
        assertThat(tasksLike("qb.help-remind:" + threadId)).isEmpty();
        advance(Duration.ofDays(2));
        runDaily(remindJob);
        runDaily(remindJob);
        assertThat(tasksLike("qb.help-remind:" + threadId)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "OPEN"));
        // A reply ends the wait and its reminder.
        run(HelpProcesses.REPLY, hqHelp(), body("threadId", threadId, "text", "Sorry for the wait.", "close", false));
        assertThat(tasksLike("qb.help-remind:" + threadId)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "DONE"));

        // The user writes again: waiting for a person again, reminded again in time.
        ask(taker(taker), threadId, QbHelp.APP, "Thanks, one more thing.", null);
        assertThat(thread(threadId)).containsEntry("waiting_for", QbHelp.WAIT_STAFF);
        assertThat(messages(threadId).getLast()).containsEntry("kind", QbHelp.USER);
        run(HelpProcesses.REPLY, hqHelp(), body("threadId", threadId, "text", "Here you are.", "close", false));

        // No new question for qb.help.auto-close-days days: closed by itself, with its task.
        advance(Duration.ofDays(13));
        runDaily(autoCloseJob);
        assertThat(thread(threadId)).containsEntry("status", QbHelp.ESCALATED);
        advance(Duration.ofDays(2));
        runDaily(autoCloseJob);
        assertThat(thread(threadId)).containsEntry("status", QbHelp.CLOSED)
            .containsEntry("close_reason", QbHelp.CLOSED_AUTO);
        assertThat(tasksOf(QbHelp.taskSource(threadId))).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
    }

    @Test
    void aConversationPastItsRetentionIsDeleted() {
        String taker = newTaker("t-fc10-retention-" + UUID.randomUUID(), "JP");
        Map<String, Object> asked = ask(taker(taker), null, QbHelp.APP, "Old question?", null);
        runJobs();
        String threadId = (String) asked.get("threadId");
        String kept = (String) ask(taker(taker), null, QbHelp.APP, "Newer question?", null).get("threadId");
        runJobs();
        advance(Duration.ofDays(179));
        runDaily(retentionJob);
        assertThat(query("SELECT 1 FROM qb_help_thread WHERE thread_id = ?", threadId)).hasSize(1);
        advance(Duration.ofDays(2));
        runDaily(retentionJob);
        assertThat(query("SELECT 1 FROM qb_help_thread WHERE thread_id IN (?, ?)", threadId, kept)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_help_message WHERE thread_id IN (?, ?)", threadId, kept)).isEmpty();
        // Everybody's usage stays: it holds no person.
        assertThat(helpUsage(QbHelp.EVERYBODY)).isNotEmpty();
    }

    // ---- every outcome counted -----------------------------------------------------------------------------------

    @Test
    void aFailedQuestionIsCountedAndSaysWhy() {
        String taker = newTaker("t-fc10-failed-" + UUID.randomUUID(), "JP");
        CLAUDE.script(MESSAGES, com.jabiz.runtime.test.StubHttpServer.Answer.json(400,
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}"));
        Map<String, Object> asked = ask(taker(taker), null, QbHelp.APP, "Will this fail?", null);
        runJobs();
        Map<String, Object> question = message(asked.get("messageId"));
        assertThat(question).containsEntry("job_status", QbHelp.FAILED);
        assertThat(question.get("failure_code")).isNotNull();
        Map<String, Object> usage = helpUsage(taker);
        assertThat(number(usage.get("questions"))).isEqualTo(1);
        assertThat(number(usage.get("failed_jobs"))).isEqualTo(1);
        // The user may ask again in the same conversation.
        ask(taker(taker), (String) asked.get("threadId"), QbHelp.APP, "Again?", null);
    }

    @Test
    void attemptsWithoutAnAnswerAreCountedAtTheirEstimate() {
        String taker = newTaker("t-fc10-timeout-" + UUID.randomUUID(), "JP");
        for (int i = 0; i < HelpJob.ATTEMPTS; i++) {
            CLAUDE.script(MESSAGES, helpAnswer("Late.", List.of(), false).after(Duration.ofSeconds(5)));
        }
        Map<String, Object> asked = ask(taker(taker), null, QbHelp.APP, "Is anyone there?", null);
        long reserved = number(message(asked.get("messageId")).get("reserved_input_tokens"));
        assertThat(reserved).isPositive();
        for (int i = 0; i < 4 && QbHelp.QUEUED.equals(message(asked.get("messageId")).get("job_status")); i++) {
            runJobs();
            advance(Duration.ofMinutes(1));
        }
        assertThat(message(asked.get("messageId"))).containsEntry("job_status", QbHelp.FAILED);
        Map<String, Object> usage = helpUsage(taker);
        assertThat(number(usage.get("input_tokens"))).isEqualTo(HelpJob.ATTEMPTS * reserved);
        assertThat(number(usage.get("output_tokens"))).isEqualTo((long) HelpJob.ATTEMPTS * HelpJob.MAX_TOKENS);
    }

    @Test
    void aStoppedQuestionIsCountedWhetherOrNotItRan() throws Exception {
        String operator = TestTokens.bearer(tokens, "fc10-operator", "external-job.cancel");
        // Stopped while it waited: counted, no tokens.
        String taker = newTaker("t-fc10-stopped-" + UUID.randomUUID(), "JP");
        Map<String, Object> waiting = ask(taker(taker), null, QbHelp.APP, "Stop me?", null);
        cancel(operator, jobOf(waiting.get("threadId"), waiting.get("messageId")));
        runJobs();
        assertThat(message(waiting.get("messageId"))).containsEntry("job_status", QbHelp.CANCELLED)
            .containsEntry("failure_code", HelpCodes.STOPPED);
        Map<String, Object> usage = helpUsage(taker);
        assertThat(number(usage.get("questions"))).isEqualTo(1);
        assertThat(number(usage.get("cancelled_jobs"))).isEqualTo(1);
        assertThat(number(usage.get("input_tokens")) + number(usage.get("output_tokens"))).isZero();

        // Stopped while its attempt ran: what the attempt used is counted.
        String other = newTaker("t-fc10-stopped-running-" + UUID.randomUUID(), "JP");
        CLAUDE.script(MESSAGES, helpAnswer("Slow.", List.of(), false).after(Duration.ofMillis(1200)));
        Map<String, Object> running = ask(taker(other), null, QbHelp.APP, "Stop me while running?", null);
        CompletableFuture<Integer> attempt = runningAttempt();
        cancel(operator, jobOf(running.get("threadId"), running.get("messageId")));
        assertThat(attempt.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        runJobs();
        assertThat(message(running.get("messageId"))).containsEntry("job_status", QbHelp.CANCELLED);
        Map<String, Object> ran = helpUsage(other);
        assertThat(number(ran.get("cancelled_jobs"))).isEqualTo(1);
        assertThat(number(ran.get("input_tokens")) + number(ran.get("output_tokens"))).isEqualTo(400);
    }

    @Test
    void theMonthsGapsListUnsolvedAndHandedOverQuestionsAndTheGaugeShowsTheShare() {
        String taker = newTaker("t-fc10-gaps-" + UUID.randomUUID(), "JP");
        Instant from = clock.instant().minusSeconds(1);
        Map<String, Object> asked = ask(taker(taker), null, QbHelp.APP, "Unsolved question about wallets?", "wallet");
        runJobs();
        String answerId = (String) messages(asked.get("threadId")).getLast().get("message_id");
        run(HelpProcesses.RATE, taker(taker), body("threadId", asked.get("threadId"), "messageId", answerId, "rating",
            QbHelp.UNSOLVED));
        assertThat(thread(asked.get("threadId"))).containsEntry("resolved", false)
            .containsEntry("status", QbHelp.OPEN);
        Map<String, Object> handed = escalate(taker(taker), null, QbHelp.APP, "Handed over question?");
        Instant to = clock.instant().plusSeconds(1);
        List<Map<String, Object>> gaps = items(QbHelp.GAPS, hqHelp(), Map.of("params", Map.of("from", from.toString(),
            "to", to.toString())));
        assertThat(gaps).anySatisfy(row -> assertThat(row).containsEntry("reason", "UNSOLVED")
            .containsEntry("question", "Unsolved question about wallets?").containsEntry("page", "wallet"));
        assertThat(gaps).anySatisfy(row -> assertThat(row).containsEntry("reason", "ESCALATED")
            .containsEntry("question", "Handed over question?").containsEntry("threadId", handed.get("threadId")));
        // Only what was not solved: an answer rated solved is not a gap; neither is another period.
        assertThat(items(QbHelp.GAPS, hqHelp(), Map.of("params", Map.of("from", to.toString(), "to",
            to.plusSeconds(60).toString())))).isEmpty();

        param(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, "1000");
        try {
            runDaily(usageJob);
            assertThat(gauge.value()).isGreaterThanOrEqualTo(40);
        } finally {
            param(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, "20000000");
        }
    }

    private void cancel(String operator, String jobId) {
        client.post().uri("/api/external-jobs/" + jobId + "/cancel").header("Authorization", operator)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isOk();
    }
}
