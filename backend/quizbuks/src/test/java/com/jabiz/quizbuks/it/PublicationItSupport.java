package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationLifecycle;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publishing integration tests (docs/quizbuks/plans/Q4-publishing.md): two sponsors who also publish, a content
 * administrator who reviews, a super administrator who proposes controlled changes and a finance administrator who
 * publishes them. Each class installs QuizBuks once ({@code QB_SETUP}, every proposal published) unless it says not to.
 */
abstract class PublicationItSupport extends ContentItSupport {

    /** The nine tables of content and publishing, only ever inserted into. */
    static final List<String> PUBLISHING_TABLES = List.of("qb_publication_version", "qb_reward_tier_version");

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    JobRunner jobs;

    @Autowired
    @Qualifier("qbPublicationTickJob")
    JobDefinition<PublicationLifecycle.TickInput> tick;

    @Override
    String sponsorA() {
        return TestTokens.bearer(tokens, "sponsor-a", QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ,
            QbPermissions.PUBLICATION_WRITE);
    }

    @Override
    String sponsorB() {
        return TestTokens.bearer(tokens, "sponsor-b", QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ,
            QbPermissions.PUBLICATION_WRITE);
    }

    /** A content administrator: reads every publication and reviews them, as {@code QB_ADMIN_CONTENT} does. */
    String reviewer() {
        return TestTokens.bearer(tokens, "reviewer", QbPermissions.ADMIN_PUBLICATION_READ,
            QbPermissions.REVIEW_PUBLICATION, QbPermissions.ADMIN_QUIZ_READ, "approval.decide", "task.read");
    }

    String proposer() {
        return TestTokens.bearer(tokens, "super-admin", "control.propose");
    }

    String financeAdmin() {
        return TestTokens.bearer(tokens, "finance-admin", "control.publish");
    }

    /** Whether the class installs QuizBuks (setup and every proposal published) before each test, once. */
    boolean installs() {
        return true;
    }

    /**
     * Moves the clock (set back to the start before each test) past everything the class's earlier tests did: the
     * minute's job and the sweep see every publication and file in the schema, and time only goes forward in
     * business (a version cannot take effect before an existing one).
     */
    @BeforeEach
    void install() {
        Object last = query("SELECT max(op_time) AS t FROM op_process").getFirst().get("t");
        if (last != null) {
            Instant latest = last instanceof java.time.OffsetDateTime time ? time.toInstant()
                : ((java.sql.Timestamp) last).toInstant();
            if (latest.isAfter(clock.instant())) {
                clock.set(latest.plus(Duration.ofHours(1)));
            }
        }
        if (installs() && query("SELECT 1 FROM qb_setup_record_version LIMIT 1").isEmpty()) {
            publishProposals(setup());
        }
    }

    /** Sets the controlled parameter {@code qb.review.publication.auto}, with four eyes. */
    void autoApprove(boolean on) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", QbParams.REVIEW_PUBLICATION_AUTO, "value", String.valueOf(on)),
            "reason", "Review switch"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** A complete quiz of the sponsor's with two questions (2 and 3 points: full score 5), saved as a version. */
    String version(String token, String title) {
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "timeLimitSec", 300));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    /** Three tiers over a full score of 5: nothing below 3, 10 Kudos for 3–4, 100 for 5. */
    static List<Object> tiers() {
        return List.of(body("fromScore", 0, "toScore", 2, "reward", 0), body("fromScore", 3, "toScore", 4,
            "reward", 10), body("fromScore", 5, "toScore", 5, "reward", 100));
    }

    /** A complete publication of the version, running from {@code start} to {@code end}. */
    Map<String, Object> complete(String versionId, String name, Instant start, Instant end) {
        return body("versionId", versionId, "name", name, "regions", List.of("JP", "ASIA"), "startAt",
            start.toString(), "endAt", end.toString(), "passScore", 3, "budgetCap", 10_000, "maxWinners", 50,
            "tiers", tiers());
    }

    /** Saves a complete publication starting in a day and running a week; returns its key. */
    String draft(String token, String versionId, String name) {
        Instant now = clock.instant();
        return (String) run("QB_PUBLICATION_SAVE", token, complete(versionId, name, now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)))).get("publicationId");
    }

    Map<String, Object> submit(String token, String publicationId) {
        return run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));
    }

    /** Decides a review request as the reviewer and delivers the approval events. */
    void decide(String requestId, String decision, String reason) {
        Map<String, Object> input = body("requestId", requestId, "decision", decision);
        if (reason != null) {
            input.put("reason", reason);
        }
        run("APPROVAL_DECIDE", reviewer(), input);
        deliverEvents();
    }

    void deliverEvents() {
        while (deliverer.deliverPending().block() > 0) {
            // Until nothing is left.
        }
    }

    /** Runs the minute's job as the scheduler would at the clock's time. */
    JobRunner.Outcome runTick() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobs.run(tick, clock.instant());
    }

    /** The publication's current row. */
    Map<String, Object> row(String publicationId) {
        return latest("qb_publication_version", "publication_id", "publication_id = '" + publicationId + "'")
            .getFirst();
    }

    static long count(String table) {
        return ((Number) query("SELECT count(*) AS n FROM " + table).getFirst().get("n")).longValue();
    }

    String status(String publicationId) {
        return (String) row(publicationId).get("status");
    }

    /** The open tasks of the caller. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tasks(String token) {
        Map<String, Object> result = client.get().uri("/api/tasks/mine")
            .header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        return (List<Map<String, Object>>) result.get("tasks");
    }

    /** Runs a process with an {@code Idempotency-Key}; must succeed. Returns the whole answer. */
    Map<String, Object> runWithKey(String process, String token, Object body, String key) {
        return client.post().uri("/api/processes/" + process + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .header("Idempotency-Key", key).bodyValue(body).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
    }

    /**
     * The content and publishing tables were only ever inserted into, and carry the append-only guard. The guard (a
     * trigger refusing UPDATE, DELETE and TRUNCATE) is what guarantees it; the {@code pg_stat_user_tables} counters
     * only confirm it and may lag (they are flushed after the transaction, so a late write can go unseen).
     */
    static void assertNothingUpdated() {
        assertOnlyInserted();
        List<String> tables = new ArrayList<>(PUBLISHING_TABLES);
        for (String table : tables) {
            assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
                .as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
            assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
                + "AND c.relname = ? AND NOT t.tgisinternal", table)).as(table).isNotEmpty();
        }
    }
}
