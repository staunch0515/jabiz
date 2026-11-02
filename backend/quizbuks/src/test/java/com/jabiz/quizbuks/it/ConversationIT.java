package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.conversation.ConversationCodes;
import com.jabiz.quizbuks.conversation.ConversationProcesses;
import com.jabiz.quizbuks.conversation.QbConversations;
import com.jabiz.quizbuks.messaging.QbInbox;
import com.jabiz.quizbuks.report.QbReports;
import com.jabiz.quizbuks.report.ReportProcesses;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestPushService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conversations between takers and sponsors (Q82, docs/quizbuks/plans/Q82-conversations.md): only a taker who
 * completed a quiz opens one about it; both sides write plain text, so many a day; a sponsor's reply is a message in
 * the taker's inbox (one an hour) and a push; either side closes it, only the taker opens it again; blocking a sponsor
 * closes their conversations and keeps them shut; the daily job closes quiet ones; a report may name one. Each side
 * reads only its own through its templates, never the other's user id.
 */
@TestPropertySource(properties = {"spring.profiles.active=push", "JABIZ_PUSH_SUBJECT=mailto:push@example.com"})
class ConversationIT extends InboxItSupport {

    static final String[] VAPID = TestPushService.vapidKeys();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("JABIZ_PUSH_PUBLIC_KEY", () -> VAPID[0]);
        registry.add("JABIZ_PUSH_PRIVATE_KEY", () -> VAPID[1]);
    }

    @Autowired
    @Qualifier("qbConversationExpireJob")
    JobDefinition<ConversationProcesses.ConversationRef> conversationExpireJob;

    /** A sponsor with a live quiz, and a taker who completed it. */
    private record Setting(String sponsor, String publication, String taker) {}

    private Setting setting(String name) {
        String sponsor = newSponsor(unique("s-q82-" + name));
        credit(sponsor, 1_000);
        String publication = livePublication(sponsor, "Q82 " + name, Map.of("regions", List.of("GLOBAL")));
        String taker = newTaker(unique("t-q82-" + name), "JP");
        play(taker, publication, true, true);
        return new Setting(sponsor, publication, taker);
    }

    private Map<String, Object> open(String taker, String publication, String text) {
        return run(ConversationProcesses.OPEN, taker(taker), body("publicationId", publication, "body", text));
    }

    private Map<String, Object> send(String token, Object conversationId, String text) {
        return run(ConversationProcesses.SEND, token, body("conversationId", conversationId, "body", text));
    }

    private List<Object> sendRefused(String token, Object conversationId, String text) {
        return codes(refused(ConversationProcesses.SEND, token, body("conversationId", conversationId, "body", text),
            422));
    }

    private Map<String, Object> conversation(Object conversationId) {
        return query("SELECT * FROM qb_conversation WHERE conversation_id = ?", conversationId).getFirst();
    }

    private static List<String> pushesOf(String userId) {
        return query("""
            SELECT payload->>'template' AS template, payload->>'link' AS link FROM sys_outbox_event
            WHERE event_type = 'jabiz.push.queued' AND payload->>'userId' = ? ORDER BY event_seq""", userId).stream()
            .map(row -> row.get("template") + " " + row.get("link")).toList();
    }

    private Map<String, Object> unread(String template, String token) {
        return items(template, token, Map.of()).getFirst();
    }

    @Test
    void aTakerWhoCompletedAQuizWritesToItsSponsorWhoAnswers() {
        Setting s = setting("open");
        // Only after completing it (nor the sponsor of their own quiz).
        String stranger = newTaker(unique("t-q82-stranger"), "JP");
        assertThat(codes(refused(ConversationProcesses.OPEN, taker(stranger), body("publicationId", s.publication(),
            "body", "Hello"), 422))).containsExactly(ConversationCodes.NOT_ELIGIBLE);
        refused(ConversationProcesses.OPEN, taker(stranger), body("publicationId", java.util.UUID.randomUUID(),
            "body", "Hello"), 404);

        Map<String, Object> opened = open(s.taker(), s.publication(), "  Where do I collect my prize?  ");
        assertThat(opened).containsEntry("created", true);
        String id = (String) opened.get("conversationId");
        assertThat(conversation(id)).containsEntry("taker_id", s.taker()).containsEntry("publisher_id", s.sponsor())
            .containsEntry("status", QbConversations.OPEN).containsEntry("last_sender", QbConversations.TAKER);
        assertThat(conversation(id).get("attempt_id")).isNotNull();
        // Writing again about the same quiz goes into the same conversation.
        clock.advance(Duration.ofMinutes(1));
        assertThat(open(s.taker(), s.publication(), "Any news?")).containsEntry("conversationId", id)
            .containsEntry("created", false);

        // The taker's list: the sponsor's company, never a user; the sponsor's: the taker's name, waiting for them.
        assertThat(items("qb.app.conversations", taker(s.taker()), Map.of())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("conversationId", id).containsEntry("lastBody", "Any news?")
                .containsEntry("unread", false).doesNotContainKeys("takerId", "publisherId", "senderUserId");
            assertThat(row.get("sponsorCompany")).isNotNull();
        });
        String sponsor = sponsorToken(s.sponsor());
        assertThat(items("qb.sponsor.conversations", sponsor, Map.of())).singleElement().satisfies(row -> assertThat(
            row).containsEntry("conversationId", id).containsEntry("unread", true).containsEntry("waitingReply", true)
            .doesNotContainKeys("takerId", "senderUserId"));
        assertThat(unread("qb.sponsor.conversations-unread", sponsor).get("unread")).isEqualTo(1);
        // The text was trimmed; who wrote is a side, never a user.
        assertThat(items("qb.app.conversation-messages", taker(s.taker()), Map.of("params", Map.of("conversationId",
            id)))).extracting(row -> row.get("body")).containsExactly("Where do I collect my prize?", "Any news?");

        // The sponsor answers twice within the hour: one inbox message, one push, leading to the conversation.
        run(ConversationProcesses.READ, sponsor, body("conversationId", id));
        assertThat(unread("qb.sponsor.conversations-unread", sponsor).get("unread")).isEqualTo(0);
        clock.advance(Duration.ofMinutes(1));
        send(sponsor, id, "At the counter, with the code on your result page.");
        clock.advance(Duration.ofMinutes(5));
        send(sponsor, id, "We are open until 6 pm.");
        deliverEvents();
        assertThat(kindsOf(s.taker())).filteredOn(QbInbox.CONVERSATION_REPLY::equals).hasSize(1);
        assertThat(messagesOf(s.taker())).filteredOn(m -> QbInbox.CONVERSATION_REPLY.equals(m.get("kind")))
            .singleElement().satisfies(m -> assertThat(m).containsEntry("action", QbInbox.ACTION_CONVERSATION)
                .containsEntry("action_id", id));
        assertThat(pushesOf(s.taker())).contains("qb.conversation-reply conversations/" + id);
        assertThat(unread("qb.app.conversations-unread", taker(s.taker())).get("unread")).isEqualTo(1);
        assertThat(items("qb.sponsor.conversations", sponsor, Map.of("params", Map.of("waiting", true)))).isEmpty();
        run(ConversationProcesses.READ, taker(s.taker()), body("conversationId", id));
        assertThat(unread("qb.app.conversations-unread", taker(s.taker())).get("unread")).isEqualTo(0);
        assertThat(items("qb.sponsor.conversation-messages", sponsor, Map.of("params", Map.of("conversationId", id))))
            .extracting(row -> row.get("sender")).containsExactly("TAKER", "TAKER", "SPONSOR", "SPONSOR");

        // The side panel: what the taker won on this quiz that the sponsor settles (the platform paid this one).
        assertThat(items("qb.sponsor.conversation-rewards", sponsor, Map.of("params", Map.of("conversationId", id))))
            .isEmpty();

        // Plain text without addresses; nobody else writes in it or reads it.
        assertThat(sendRefused(taker(s.taker()), id, "write to me at me@example.com"))
            .containsExactly(ConversationCodes.ADDRESS);
        assertThat(sendRefused(taker(s.taker()), id, "   ")).containsExactly(ConversationCodes.BODY_REQUIRED);
        refused(ConversationProcesses.SEND, taker(stranger), body("conversationId", id, "body", "Hi"), 404);
        assertThat(items("qb.app.conversation-messages", taker(stranger), Map.of("params", Map.of("conversationId",
            id)))).isEmpty();
        assertThat(items("qb.sponsor.conversations", sponsorToken(sponsorB()), Map.of())).isEmpty();
    }

    @Test
    void eitherSideClosesOnlyTheTakerOpensAgainAndADayHasACap() {
        Setting s = setting("close");
        String id = (String) open(s.taker(), s.publication(), "Hello").get("conversationId");
        String sponsor = sponsorToken(s.sponsor());
        assertThat(run(ConversationProcesses.CLOSE, sponsor, body("conversationId", id)))
            .containsEntry("status", QbConversations.CLOSED);
        assertThat(conversation(id)).containsEntry("closed_by", QbConversations.SPONSOR);
        assertThat(sendRefused(sponsor, id, "One more thing")).containsExactly(ConversationCodes.CLOSED);
        assertThat(sendRefused(taker(s.taker()), id, "Why?")).containsExactly(ConversationCodes.CLOSED);
        // The taker's message opens it again.
        assertThat(open(s.taker(), s.publication(), "Please answer").get("conversationId")).isEqualTo(id);
        assertThat(conversation(id)).containsEntry("status", QbConversations.OPEN).containsEntry("closed_by", null);

        // Twenty a day each (the first two counted); the sponsor has a cap of their own.
        for (int i = 0; i < 18; i++) {
            send(taker(s.taker()), id, "Message " + i);
        }
        assertThat(sendRefused(taker(s.taker()), id, "Too many")).containsExactly(ConversationCodes.DAILY_CAP);
        send(sponsor, id, "We read you");
        clock.advance(Duration.ofHours(25));
        send(taker(s.taker()), id, "A new day");
    }

    @Test
    void aBlockClosesTheSponsorsConversationsUntilItIsRemoved() {
        Setting s = setting("block");
        String second = livePublication(s.sponsor(), "Q82 block 2", Map.of("regions", List.of("GLOBAL")));
        play(s.taker(), second, true, true);
        String first = (String) open(s.taker(), s.publication(), "Hello").get("conversationId");
        String other = (String) open(s.taker(), second, "Hello again").get("conversationId");
        String sponsor = sponsorToken(s.sponsor());
        // The sponsor cannot block; the taker blocks the sponsor of one, closing both.
        refused(ConversationProcesses.BLOCK, sponsor, body("conversationId", first), 404);
        Map<String, Object> blocked = run(ConversationProcesses.BLOCK, taker(s.taker()), body("conversationId",
            first));
        assertThat(blocked).containsEntry("closed", 2);
        assertThat(conversation(first)).containsEntry("status", QbConversations.CLOSED)
            .containsEntry("closed_by", QbConversations.TAKER);
        assertThat(conversation(other)).containsEntry("status", QbConversations.CLOSED);
        assertThat(sendRefused(sponsor, other, "Sorry")).containsExactly(ConversationCodes.CLOSED);
        assertThat(codes(refused(ConversationProcesses.OPEN, taker(s.taker()), body("publicationId", s.publication(),
            "body", "Hello"), 422))).containsExactly(ConversationCodes.BLOCKED);
        assertThat(items("qb.app.conversation-blocks", taker(s.taker()), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("blockId", blocked.get("blockId"))
                .doesNotContainKeys("publisherId"));

        // Someone else's block is as none; removing it lets the taker write again.
        refused(ConversationProcesses.UNBLOCK, taker(newTaker(unique("t-q82-other"), "JP")), body("blockId",
            blocked.get("blockId")), 404);
        run(ConversationProcesses.UNBLOCK, taker(s.taker()), body("blockId", blocked.get("blockId")));
        assertThat(items("qb.app.conversation-blocks", taker(s.taker()), Map.of())).isEmpty();
        assertThat(open(s.taker(), s.publication(), "Back again").get("conversationId")).isEqualTo(first);
    }

    @Test
    void theDailyJobClosesQuietConversations() {
        Setting s = setting("expire");
        String quiet = (String) open(s.taker(), s.publication(), "Hello").get("conversationId");
        clock.advance(Duration.ofDays(89));
        JobRunner.JobRun early = runExpiry();
        assertThat(conversation(quiet)).containsEntry("status", QbConversations.OPEN);
        assertThat(early.outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        clock.advance(Duration.ofDays(2));
        runExpiry();
        assertThat(conversation(quiet)).containsEntry("status", QbConversations.CLOSED)
            .containsEntry("closed_by", QbConversations.SYSTEM);
        // Closed: no message tells the taker of anything.
        refused(ConversationProcesses.EXPIRE, taker(s.taker()), body("conversationId", quiet), 403);
    }

    @Test
    void aReportNamesTheTakersOwnConversation() {
        Setting s = setting("report");
        String id = (String) open(s.taker(), s.publication(), "Hello").get("conversationId");
        send(sponsorToken(s.sponsor()), id, "Buy more at our shop");
        run(ReportProcesses.SUBMIT, taker(s.taker()), body("publicationId", s.publication(), "reason",
            QbReports.MESSAGE, "conversationId", id));
        assertThat(query("SELECT reason, conversation_id FROM qb_report WHERE reporter_id = ?", s.taker()))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("reason", QbReports.MESSAGE)
                .containsEntry("conversation_id", id));
        // Another taker cannot name it.
        String other = newTaker(unique("t-q82-reporter"), "JP");
        play(other, s.publication(), true, true);
        refused(ReportProcesses.SUBMIT, taker(other), body("publicationId", s.publication(), "reason",
            QbReports.MESSAGE, "conversationId", id), 404);
    }

    private JobRunner.JobRun runExpiry() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(conversationExpireJob, clock.instant());
    }
}
