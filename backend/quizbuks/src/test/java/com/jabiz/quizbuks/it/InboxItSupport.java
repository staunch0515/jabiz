package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.BroadcastProcesses;
import com.jabiz.quizbuks.messaging.MessageProcesses;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inbox's integration tests (docs/quizbuks/plans/Q7-messages-legal.md, Q7c): announcements written and sent by a
 * content administrator, sponsors' notices, the inbox as the app's and the sponsor console's templates show it, the
 * marks, and the two jobs. Every test uses users of its own (the schema is the class's): announcements to everyone
 * of earlier tests are in every inbox, so tests look for their own items by id.
 */
abstract class InboxItSupport extends PayoutItSupport {

    @Autowired
    @Qualifier("qbBroadcastDispatchJob")
    JobDefinition<BroadcastProcesses.DispatchInput> dispatchJob;

    @Autowired
    @Qualifier("qbPayoutReminderJob")
    JobDefinition<MessageProcesses.ReminderInput> reminderJob;

    @Autowired
    @Qualifier("qbBroadcastExpireJob")
    JobDefinition<BroadcastProcesses.DispatchInput> expireJob;

    @Autowired
    @Qualifier("qbBroadcastStatsJob")
    JobDefinition<BroadcastProcesses.StatsInput> statsJob;

    /** A content administrator (with a second factor), as {@code QB_ADMIN_CONTENT} has it. */
    String contentAdmin() {
        return tokenOf("content-admin-q7c", QbRoles.ADMIN_CONTENT);
    }

    /** A sponsor's own token, the console's (no taker permissions: the sponsor console's inbox). */
    String console(String sponsorId) {
        return tokenOf(sponsorId, QbRoles.SPONSOR);
    }

    /** A token at a sign-in entry, with the permissions of a role. */
    String atEntry(String userId, String role, String entry) {
        return "Bearer " + tokens.issue(new Actor(userId, null, Set.of(), Set.copyOf(QbRoles.of(role).permissions()),
            clock.instant(), null, entry, true)).token();
    }

    static Map<String, Object> texts(String en) {
        return body("en", en, "ja", en + "（日本語）", "zh", en + "（中文）");
    }

    /** An announcement to the app's users, to everyone, a system one: {@code more} replaces its fields. */
    static Map<String, Object> announcement(String title, Object... more) {
        Map<String, Object> save = body("audience", "APP", "type", "SYSTEM", "title", texts(title), "body",
            texts("Text of " + title), "targetMode", "ALL");
        save.putAll(body(more));
        return save;
    }

    Map<String, Object> adminSave(Map<String, Object> save) {
        return run("QB_ADMIN_BROADCAST_SAVE", contentAdmin(), save);
    }

    /** Saves and sends an announcement now; its id. */
    String announce(Map<String, Object> save) {
        Map<String, Object> saved = adminSave(save);
        assertThat((List<?>) saved.get("problems")).as("problems of " + save).isEmpty();
        Map<String, Object> sent = run("QB_ADMIN_BROADCAST_SEND", contentAdmin(), body("broadcastId",
            saved.get("broadcastId"), "baseRevision", saved.get("revision")));
        assertThat(sent.get("status")).isIn("SENT", "SCHEDULED");
        return (String) saved.get("broadcastId");
    }

    /** Saves a sponsor's notice draft; its output. */
    Map<String, Object> noticeDraft(String sponsorId, Object... fields) {
        return run("QB_BROADCAST_SAVE", console(sponsorId), body(fields));
    }

    /** Saves and sends a sponsor's notice; its id. */
    String notice(String sponsorId, String publicationId, List<String> regions, Object... more) {
        Map<String, Object> save = body("title", body("en", "Notice of " + sponsorId.substring(0, 8)), "body",
            body("en", "Come and play"), "publicationId", publicationId, "regions", regions);
        save.putAll(body(more));
        Map<String, Object> saved = run("QB_BROADCAST_SAVE", console(sponsorId), save);
        assertThat((List<?>) saved.get("problems")).as("problems").isEmpty();
        run("QB_BROADCAST_SEND", console(sponsorId), body("broadcastId", saved.get("broadcastId"), "baseRevision",
            saved.get("revision")));
        return (String) saved.get("broadcastId");
    }

    /** The app's inbox of a taker now: its items (the server's time; the client gives none, review L1). */
    List<Map<String, Object>> inbox(String token, Object... params) {
        Map<String, Object> values = new LinkedHashMap<>(body(params));
        return items("qb.app.inbox", token, Map.of("params", values, "limit", 500));
    }

    /** The sponsor console's announcements now. */
    List<Map<String, Object>> consoleInbox(String token, Object... params) {
        Map<String, Object> values = new LinkedHashMap<>(body(params));
        return items("qb.sponsor.inbox", token, Map.of("params", values, "limit", 500));
    }

    static List<Object> itemIds(List<Map<String, Object>> items) {
        return items.stream().map(item -> item.get("itemId")).toList();
    }

    /** The unread counts of a taker's inbox now: messages and broadcasts. */
    Map<String, Object> unread(String token) {
        return items("qb.app.inbox-unread", token, Map.of("params", Map.of())).getFirst();
    }

    static long count(Map<String, Object> unread, String key) {
        return ((Number) unread.get(key)).longValue();
    }

    Map<String, Object> mark(String token, String kind, Object id, String action) {
        return run("QB_MESSAGE_MARK", token, body("kind", kind, "id", id, "action", action));
    }

    /** Runs the minute's job of scheduled announcements at the clock's time. */
    JobRunner.JobRun runDispatch() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(dispatchJob, clock.instant());
    }

    JobRunner.JobRun runReminders() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(reminderJob, clock.instant());
    }

    /** Runs the minute's job that takes expired and ended broadcasts out of the inboxes. */
    JobRunner.JobRun runExpire() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(expireJob, clock.instant());
    }

    /** Runs the daily snapshot of the sponsors' statistics. */
    JobRunner.JobRun runStats() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(statsJob, clock.instant());
    }

    /** The messages of a user, oldest first (from the table). */
    static List<Map<String, Object>> messagesOf(String userId) {
        return query("SELECT * FROM qb_message WHERE recipient_id = ? ORDER BY created_at, message_id", userId);
    }

    static List<Object> kindsOf(String userId) {
        return messagesOf(userId).stream().map(row -> row.get("kind")).toList();
    }

    /** The current row of a broadcast. */
    static Map<String, Object> broadcast(Object id) {
        return latest("qb_broadcast_version", "broadcast_id", "broadcast_id = '" + id + "'").getFirst();
    }

    /** A row of the administrators' list (with the statistics). */
    Map<String, Object> adminRow(Object id) {
        return items("qb.admin.broadcasts", contentAdmin(), Map.of("params", Map.of("broadcastId", id)))
            .getFirst();
    }

    /** A row of the sponsor's list (with the statistics as the sponsor sees them). */
    Map<String, Object> sponsorRow(String sponsorId, Object id) {
        return items("qb.sponsor.broadcasts", console(sponsorId), Map.of("params", Map.of("broadcastId", id)))
            .getFirst();
    }

    /** The codes of the problems a save answers with. */
    @SuppressWarnings("unchecked")
    static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<Map<String, Object>>) saved.get("problems")).stream().map(p -> p.get("code")).toList();
    }

    String finance() {
        return TestTokens.bearer(tokens, "finance-q7c", QbRoles.of(QbRoles.ADMIN_FINANCE).permissions()
            .toArray(String[]::new));
    }

    String messagingSystem() {
        return TestTokens.bearer(tokens, "system-q7c", QbPermissions.MESSAGING_SYSTEM);
    }
}
