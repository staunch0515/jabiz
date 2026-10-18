package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.stats.AnalyticsJob;
import com.jabiz.runtime.job.JobRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sponsors' analytics (docs/quizbuks/plans/Q9e-analytics.md): the job {@code qb.analytics} run at chosen times,
 * the sponsor's templates read as the sponsor, and the quiz of {@link #version} (question 1: 2 points, question 2: 3
 * points; full score 5, pass 3, rewards 0 / 10 / 100).
 */
abstract class AnalyticsItSupport extends PlayItSupport {

    static final String DASHBOARD = "qb.sponsor.dashboard";
    static final String TREND = "qb.sponsor.analytics.trend";
    static final String TOP = "qb.sponsor.analytics.top";
    static final String OVERVIEW = "qb.sponsor.analytics.overview";
    static final String QUESTIONS = "qb.sponsor.analytics.questions";
    static final List<String> TEMPLATES = List.of(DASHBOARD, TREND, TOP, OVERVIEW, QUESTIONS);

    @Autowired
    @Qualifier("qbAnalyticsJob")
    JobDefinition<AnalyticsJob.ItemInput> analyticsJob;

    /** Runs the analytics job at {@code at} as the scheduler would; every item must succeed. */
    void runAnalytics(Instant at) {
        JobRunner.JobRun run = analyticsRun(at);
        assertThat(run.outcome()).as("qb.analytics at " + at + ": " + run).isEqualTo(JobRunner.Outcome.SUCCEEDED);
    }

    /** Runs the analytics job at {@code at} (the clock is set there first); its outcome and item counts. */
    JobRunner.JobRun analyticsRun(Instant at) {
        if (clock.instant().isBefore(at)) {
            clock.set(at);
        }
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(analyticsJob, at);
    }

    /** The first whole UTC hour at least two hours after the clock: room for a test's own timeline. */
    Instant nextHour() {
        return clock.instant().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(2));
    }

    void at(Instant time) {
        assertThat(time).as("time only goes forward").isAfterOrEqualTo(clock.instant());
        clock.set(time);
    }

    /** Sets the minimum group (an ordinary parameter, as the super administrator would). */
    void minGroup(String value) {
        run("PARAM_SET", admin(), body("key", QbParams.STATS_MIN_GROUP, "value", value));
    }

    /** A publication of the sponsor's, live for a week from now, with a thousand winners at most. */
    String analysedPublication(String sponsorId, String name) {
        return livePublication(sponsorId, name, Map.of("regions", List.of("GLOBAL")));
    }

    void view(String userId, String publicationId) {
        run("QB_VIEW", taker(userId), body("publicationId", publicationId));
    }

    String startAttempt(String userId, String publicationId) {
        return (String) start(taker(userId), publicationId).get("attemptId");
    }

    Map<String, Object> submitAttempt(String userId, String attemptId, boolean first, boolean second) {
        return submit(taker(userId), attemptId, answers(first, second));
    }

    /** A new taker (GLOBAL only), started and submitted at once with the answers given. */
    void complete(String name, String publicationId, boolean first, boolean second) {
        play(newTaker(name, null), publicationId, first, second);
    }

    Map<String, Object> one(String template, String token, Map<String, Object> params) {
        List<Map<String, Object>> rows = rows(template, token, params);
        assertThat(rows).as(template + " " + params).hasSize(1);
        return rows.getFirst();
    }

    List<Map<String, Object>> rows(String template, String token, Map<String, Object> params) {
        return items(template, token, Map.of("params", params, "limit", 500));
    }

    Map<String, Object> overview(String token, String publicationId) {
        return one(OVERVIEW, token, Map.of("publicationId", publicationId));
    }

    List<Map<String, Object>> questions(String token, String publicationId) {
        return rows(QUESTIONS, token, Map.of("publicationId", publicationId));
    }

    List<Map<String, Object>> trend(String token, String publicationId, Instant from, Instant to, String bucket) {
        Map<String, Object> params = new HashMap<>();
        if (publicationId != null) {
            params.put("publicationId", publicationId);
        }
        params.put("from", from.toString());
        params.put("to", to.toString());
        params.put("bucket", bucket);
        return rows(TREND, token, params);
    }

    Map<String, Object> dashboard(String token, Instant at) {
        return one(DASHBOARD, token, Map.of("at", at.toString()));
    }

    Map<String, Object> cursor(String publicationId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_analytics_cursor WHERE publication_id = ?::uuid",
            publicationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<Map<String, Object>> hours(String publicationId) {
        return query("SELECT * FROM qb_analytics_hour WHERE publication_id = ?::uuid ORDER BY hour_start",
            publicationId);
    }

    /** The question totals the job recounts from the attempts directly: what the increments must add up to. */
    String recount(String publicationId) {
        List<Map<String, Object>> rows = query("SELECT CAST(q ->> 'no' AS int) AS no, count(*) AS n, "
            + "count(*) FILTER (WHERE CAST(q ->> 'earned' AS numeric) > 0) AS c FROM qb_attempt a "
            + "CROSS JOIN LATERAL jsonb_array_elements(CAST(a.result AS jsonb)) q "
            + "WHERE a.publication_id = ?::uuid AND a.submitted_at IS NOT NULL GROUP BY 1 ORDER BY 1",
            publicationId);
        StringBuilder json = new StringBuilder("[");
        for (Map<String, Object> row : rows) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('[').append(row.get("no")).append(',').append(row.get("n")).append(',').append(row.get("c"))
                .append(']');
        }
        return json.append(']').toString();
    }

    static long count(Object value) {
        return value == null ? -1 : new BigDecimal(value.toString()).longValueExact();
    }

    static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof java.sql.Timestamp time ? time.toInstant() : Instant.parse(value.toString());
    }

    /** A row's counts in a fixed order: views, starts, completions, passes, winners, spent. */
    static List<Long> counts(Map<String, Object> row) {
        return List.of(count(row.get("views")), count(row.get("starts")), count(row.get("completions")),
            count(row.get("passes")), count(row.get("winners")), count(row.get("spent")));
    }

    /** The platform's export of a template, as text without the byte order mark. */
    String export(String template, String token, String format, Map<String, Object> params, int status,
        String language) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("params", params);
        byte[] bytes = client.post().uri("/api/queries/" + template + "/export?format=" + format)
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .header(HttpHeaders.ACCEPT_LANGUAGE, language).bodyValue(request).exchange()
            .expectStatus().isEqualTo(status).expectBody().returnResult().getResponseBody();
        String text = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
