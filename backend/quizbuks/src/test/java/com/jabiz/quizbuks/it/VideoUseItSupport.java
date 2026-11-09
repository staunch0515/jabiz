package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.publishing.PublicationSharedVideos;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses;
import com.jabiz.quizbuks.topic.TopicProcesses;
import com.jabiz.quizbuks.videouse.VideoUseFigures;
import com.jabiz.quizbuks.videouse.VideoUseReminders;
import com.jabiz.runtime.test.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the tests of the ledger of shared videos' uses share (F-S14): two sponsors, the provider's shared video and the
 * user's live publication using it, entries, the counting tables' rows and the three daily jobs.
 */
abstract class VideoUseItSupport extends PlayItSupport {

    /** A site mainland China plays (F-S11): the tests' unknown address counts as mainland China. */
    static final String VIDEO = "https://www.bilibili.com/video/BV1GJ411x7h7";

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Autowired
    @Qualifier("qbVideoUseFiguresJob")
    JobDefinition<VideoUseFigures.RefreshInput> figuresJob;

    @Autowired
    @Qualifier("qbVideoUseRemindJob")
    JobDefinition<VideoUseReminders.RemindInput> remindJob;

    @Autowired
    @Qualifier("qbVideoUseCheckJob")
    JobDefinition<VideoUseFigures.CheckInput> checkJob;

    // ---- the world --------------------------------------------------------------------------------------------------

    String topicAdmin() {
        return TestTokens.bearer(tokens, "fs14-topic-admin", QbPermissions.ADMIN_TOPIC_READ,
            QbPermissions.ADMIN_TOPIC_WRITE);
    }

    String word(String code) {
        if (query("SELECT 1 FROM qb_topic WHERE topic_id = ?", code).isEmpty()) {
            run(TopicProcesses.ADD, topicAdmin(), body("topicId", code, "nameZh", code + " 中", "nameJa", code + " 日",
                "nameEn", code + " en"));
        }
        return code;
    }

    /** A video link of the provider's, shared and approved; the shared video's key. */
    String approvedVideo(String provider) {
        String token = sponsorToken(provider);
        String assetId = (String) run(AssetProcesses.SAVE, token, body("kind", "VIDEO_LINK", "name", "Cats",
            "language", "en", "url", VIDEO, "durationSec", 95)).get("assetId");
        Map<String, Object> shared = run(SharedVideoProcesses.SHARE, token, body("assetId", assetId,
            "titleZh", "猫 " + assetId.substring(0, 6), "titleJa", "猫の動画", "titleEn", "Cats " + assetId.substring(0, 6),
            "topics", List.of(word("fs14-pets")), "vertical", false));
        decide((String) shared.get("approvalRequestId"), "APPROVE", null);
        return (String) shared.get("sharedVideoId");
    }

    /** A publication of the version for everybody, live for a week, picking the shared video when given. */
    String live(String sponsor, String name, String sharedVideoId) {
        String token = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> save = complete(version(token, name + " quiz"), name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        if (sharedVideoId != null) {
            run(PublicationSharedVideos.SET, token, body("publicationId", publicationId, "sharedVideoId",
                sharedVideoId));
        }
        assertThat(submit(token, publicationId).get("status")).isEqualTo("LIVE");
        return publicationId;
    }

    /** Two sponsors, the provider's shared video and a live publication of the user's using it. */
    record World(String provider, String user, String sharedVideoId, String publicationId) {}

    World world(String mark) {
        String provider = newSponsor(unique("s-fs14-" + mark + "-provider"));
        String user = newSponsor(unique("s-fs14-" + mark + "-user"));
        credit(user, 100_000);
        String shared = approvedVideo(provider);
        return new World(provider, user, shared, live(user, "FS14 " + mark, shared));
    }

    String today() {
        return LocalDate.ofInstant(clock.instant(), TOKYO).toString();
    }

    Map<String, Object> entry(String publicationId, String pricing, long price) {
        return body("publicationId", publicationId, "periodFrom", today(), "periodTo",
            LocalDate.parse(today()).plusDays(6).toString(), "pricing", pricing, "price", price, "note",
            "Agreed by mail");
    }

    Map<String, Object> userRow(String user, String publicationId) {
        List<Map<String, Object>> rows = items("qb.sponsor.video-uses", sponsorToken(user), body("params",
            body("publicationId", publicationId)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> providerRow(String provider, String publicationId) {
        List<Map<String, Object>> rows = items("qb.sponsor.video-uses-provided", sponsorToken(provider), body("params",
            body("publicationId", publicationId)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static long versions(String videoUseId) {
        return ((Number) query("SELECT count(*) AS n FROM qb_video_use_version WHERE video_use_id = ?::uuid",
            videoUseId).getFirst().get("n")).longValue();
    }

    static List<Map<String, Object>> openTasks(String userId) {
        return query("SELECT task_type, title_key, source_key FROM sys_task_version t WHERE assignee_user_id = ? "
            + "AND status = 'OPEN' AND version_no = (SELECT max(version_no) FROM sys_task_version x "
            + "WHERE x.task_id = t.task_id)", userId);
    }

    static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** The jobs' runs: each at a scheduled time of its own (a time that ran is not run again). */
    private int runs;

    void runFigures() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(figuresJob, clock.instant().plusSeconds(runs++));
    }

    void runReminders() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(remindJob, clock.instant().plusSeconds(runs++));
    }

    void runCheck() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(checkJob, clock.instant().plusSeconds(runs++));
    }

    void exposures(String publicationId, String publisher, String provider, int n, boolean opened, boolean counted,
        boolean went, Instant at) {
        for (int i = 0; i < n; i++) {
            exposure(publicationId, "fs14-t-" + UUID.randomUUID(), publisher, provider, opened, counted, went, at);
        }
    }

    void exposure(String publicationId, String userId, String publisher, String provider, boolean opened,
        boolean counted, boolean went, Instant at) {
        java.sql.Timestamp time = java.sql.Timestamp.from(at);
        execute("INSERT INTO qb_exposure (exposure_id, publication_id, user_id, publisher_id, provider_id, opened_at, "
            + "counted_at, surface, counted_sec, timer, went_to_answer_at, capped_days, created_at, version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 1)", UUID.randomUUID().toString(), publicationId, userId,
            publisher, provider, opened ? time : null, counted ? time : null, counted ? "START" : null,
            counted ? 10 : null, counted ? "SCREEN" : null, went ? time : null, time);
    }

    void minGroup(String value) {
        setParam(QbParams.STATS_MIN_GROUP, value);
    }

}
