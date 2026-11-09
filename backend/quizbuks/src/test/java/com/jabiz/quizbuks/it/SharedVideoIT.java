package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.content.VideoEmbeds;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.publishing.PublicationProcesses;
import com.jabiz.quizbuks.publishing.PublicationSharedVideos;
import com.jabiz.quizbuks.publishing.PublicationVersions;
import com.jabiz.quizbuks.setup.ReviewCodes;
import com.jabiz.quizbuks.sharedvideo.SharedVideoCodes;
import com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses;
import com.jabiz.quizbuks.topic.TopicProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S12 (docs/quizbuks/plans/impl/F-S12.md): a sponsor shares a video of their library; the provider's operator reviews
 * it once; other sponsors pick it from the catalog for their publications, whose cards freeze it and its provider; a
 * withdrawal stops new picks only; a takedown (the provider's operator or headquarters) ends every publication using it
 * and tells both sides.
 */
class SharedVideoIT extends PlayItSupport {

    static final String YOUTUBE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    static final String BILIBILI = "https://www.bilibili.com/video/BV1GJ411x7h7";

    @Autowired
    @Qualifier("qbSharedVideoEndsJob")
    JobDefinition<PublicationSharedVideos.EndInput> endsJob;

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    String topicAdmin() {
        return TestTokens.bearer(tokens, "fs12-topic-admin", QbPermissions.ADMIN_TOPIC_READ,
            QbPermissions.ADMIN_TOPIC_WRITE);
    }

    /** Headquarters' content administrator as far as shared videos go (the platform's emergency takedown). */
    String videoAdmin() {
        return TestTokens.bearer(tokens, "fs12-video-admin", QbPermissions.ADMIN_SHARED_VIDEO_READ,
            QbPermissions.ADMIN_SHARED_VIDEO_WRITE);
    }

    /** A word of the list, added the first time. */
    String word(String code) {
        if (query("SELECT 1 FROM qb_topic WHERE topic_id = ?", code).isEmpty()) {
            run(TopicProcesses.ADD, topicAdmin(), body("topicId", code, "nameZh", code + " 中", "nameJa", code + " 日",
                "nameEn", code + " en"));
        }
        return code;
    }

    /** A video link in the sponsor's library; its key. */
    String videoAsset(String token, String name, String url) {
        return (String) run(AssetProcesses.SAVE, token, body("kind", "VIDEO_LINK", "name", name, "language", "en",
            "url", url, "durationSec", 95)).get("assetId");
    }

    static Map<String, Object> share(String assetId, boolean vertical, String... topics) {
        return body("assetId", assetId, "titleZh", "猫 " + assetId.substring(0, 6), "titleJa", "猫の動画",
            "titleEn", "Cats " + assetId.substring(0, 6), "topics", List.of(topics), "vertical", vertical);
    }

    /** Shares a video link of the provider's and has the review approve it; the shared video's key. */
    String approvedVideo(String provider, String url, boolean vertical) {
        String token = sponsorToken(provider);
        Map<String, Object> shared = run(SharedVideoProcesses.SHARE, token,
            share(videoAsset(token, "Video " + url.length(), url), vertical, word("fs12-pets")));
        assertThat(shared).containsEntry("status", "PENDING");
        decide((String) shared.get("approvalRequestId"), "APPROVE", null);
        String sharedVideoId = (String) shared.get("sharedVideoId");
        assertThat(videoRow(sharedVideoId)).containsEntry("status", "APPROVED");
        return sharedVideoId;
    }

    static Map<String, Object> videoRow(String sharedVideoId) {
        return query("SELECT * FROM qb_shared_video WHERE shared_video_id = ?", sharedVideoId).getFirst();
    }

    /** A draft of the sponsor's, live now for a week once submitted, picking the shared video (none when null). */
    String draftWith(String sponsor, String name, String sharedVideoId, String versionId) {
        String token = sponsorToken(sponsor);
        String version = versionId != null ? versionId : version(token, name + " quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(version, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        if (sharedVideoId != null) {
            run(PublicationSharedVideos.SET, token, body("publicationId", publicationId, "sharedVideoId",
                sharedVideoId));
        }
        return publicationId;
    }

    String liveWith(String sponsor, String name, String sharedVideoId) {
        String publicationId = draftWith(sponsor, name, sharedVideoId, null);
        assertThat(submit(sponsorToken(sponsor), publicationId).get("status")).isEqualTo("LIVE");
        return publicationId;
    }

    void runEnds() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(endsJob, clock.instant());
    }

    /** The state of the review task of an approval request (the platform's ApprovalTasks); null when none. */
    static Object reviewTask(Object requestId) {
        List<Map<String, Object>> tasks = query("SELECT status FROM (SELECT DISTINCT ON (task_id) * FROM "
            + "sys_task_version ORDER BY task_id, version_no DESC) t WHERE source_key = ?", "approval:" + requestId);
        return tasks.isEmpty() ? null : tasks.getFirst().get("status");
    }

    /** F-C3: the provider's in-app notices of a kind about one shared video. */
    static List<Map<String, Object>> notices(String userId, String kind, String sharedVideoId) {
        return query("SELECT kind, action, title::text AS title, body::text AS body FROM qb_message WHERE recipient_id = ? "
            + "AND kind = ? AND source_key LIKE ?", userId, kind, "%" + sharedVideoId);
    }

    static List<Object> mailTemplates(String userId) {
        return query("SELECT template FROM sys_mail_message WHERE user_id = ?::uuid ORDER BY created_time", userId)
            .stream().map(row -> row.get("template")).toList();
    }

    @Test
    void aProviderSharesAVideoItsOperatorReviewsItOnceAndOthersPickIt() {
        String provider = newSponsor(unique("s-fs12-provider"));
        String user = newSponsor(unique("s-fs12-user"));
        credit(user, 10_000);
        String token = sponsorToken(provider);
        String pets = word("fs12-pets");
        String asset = videoAsset(token, "Cats", YOUTUBE);

        // What a share must say: titles in every language and a topic of the list.
        assertThat(codes(refused(SharedVideoProcesses.SHARE, token, body("assetId", asset, "titleEn", "Cats",
            "topics", List.of()), 422))).contains(SharedVideoCodes.TITLE_REQUIRED, SharedVideoCodes.TOPIC_REQUIRED);
        String link = (String) run(AssetProcesses.SAVE, token, body("kind", "LINK", "name", "Shop", "language", "en",
            "url", "https://example.com/shop")).get("assetId");
        assertThat(codes(refused(SharedVideoProcesses.SHARE, token, share(link, false, pets), 422)))
            .containsExactly(SharedVideoCodes.NOT_VIDEO);
        // Another sponsor's asset is not found.
        refused(SharedVideoProcesses.SHARE, sponsorToken(user), share(asset, false, pets), 404);

        Map<String, Object> shared = run(SharedVideoProcesses.SHARE, token, share(asset, true, pets));
        assertThat(shared).containsEntry("status", "PENDING").containsEntry("approval", "PENDING");
        String sharedVideoId = (String) shared.get("sharedVideoId");
        // F-C3: the review is a task of the platform's approvals, open until it is decided.
        assertThat(reviewTask(shared.get("approvalRequestId"))).isEqualTo("OPEN");
        assertThat(videoRow(sharedVideoId)).containsEntry("provider_id", provider).containsEntry("url", YOUTUBE)
            .containsEntry("vertical", true).containsEntry("topics", pets);
        // One share of an asset stands at a time.
        assertThat(codes(refused(SharedVideoProcesses.SHARE, token, share(asset, true, pets), 422)))
            .containsExactly(SharedVideoCodes.ALREADY_SHARED);
        // In review, it is not in the catalog, and cannot be picked.
        assertThat(items("qb.sponsor.shared-videos", sponsorToken(user), body("params", body())))
            .extracting(row -> row.get("sharedVideoId")).doesNotContain(sharedVideoId);
        String draft = draftWith(user, "FS12 pick early", null, null);
        assertThat(codes(refused(PublicationSharedVideos.SET, sponsorToken(user), body("publicationId", draft,
            "sharedVideoId", sharedVideoId), 422))).containsExactly(SharedVideoCodes.UNAVAILABLE);
        // The review is the provider's operator's.
        assertThat(query("SELECT scope_name, scope_value FROM sys_approval_request_version WHERE request_id = ?::uuid",
            shared.get("approvalRequestId"))).first().satisfies(row -> assertThat(row)
            .containsEntry("scope_name", "operator").containsEntry("scope_value", String.valueOf(query(
                "SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?", provider).getFirst()
                .get("operator_id"))));

        // Headquarters (an unscoped session) decides any operator's case (F-C3: no operator is needed for that).
        decide((String) shared.get("approvalRequestId"), "APPROVE", null);
        assertThat(videoRow(sharedVideoId)).containsEntry("status", "APPROVED");
        assertThat(reviewTask(shared.get("approvalRequestId"))).isEqualTo("DONE");
        // F-C3: the provider's own video is not in their catalog (they pick it from their materials); looked up by
        // its key (a draft that picked it before), it still is.
        assertThat(items("qb.sponsor.shared-videos", token, body("params", body())))
            .extracting(row -> row.get("sharedVideoId")).doesNotContain(sharedVideoId);
        assertThat(items("qb.sponsor.shared-videos", token, body("params", body("sharedVideoId", sharedVideoId))))
            .extracting(row -> row.get("sharedVideoId")).containsExactly(sharedVideoId);
        assertThat(items("qb.sponsor.my-shared-videos", token, body("params", body())))
            .filteredOn(row -> sharedVideoId.equals(row.get("sharedVideoId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "APPROVED"));
        // Searched by title, topic and orientation; the provider's company shown.
        String userToken = sponsorToken(user);
        assertThat(items("qb.sponsor.shared-videos", userToken, body("params", body("q", "猫の動画", "topic", pets,
            "vertical", true)))).extracting(row -> row.get("sharedVideoId")).contains(sharedVideoId);
        assertThat(items("qb.sponsor.shared-videos", userToken, body("params", body("vertical", false))))
            .extracting(row -> row.get("sharedVideoId")).doesNotContain(sharedVideoId);
        assertThat(items("qb.sponsor.my-shared-videos", userToken, body("params", body())))
            .extracting(row -> row.get("sharedVideoId")).doesNotContain(sharedVideoId);

        // Picked: the version has no video of its own; the card shows the shared one, vertical, foreign (YouTube),
        // with its provider frozen. Nobody reviews the video again: the publication goes on as before.
        run(PublicationSharedVideos.SET, userToken, body("publicationId", draft, "sharedVideoId", sharedVideoId));
        assertThat(submit(userToken, draft).get("status")).isEqualTo("LIVE");
        assertThat(card(draft)).containsEntry("shared_video_id", sharedVideoId)
            .containsEntry("video_provider_id", provider).containsEntry("video", VideoEmbeds.player(YOUTUBE).get())
            .containsEntry("video_vertical", true).containsEntry("foreign_video", true)
            .containsEntry("watch_required", false);
        assertThat(row(draft)).containsEntry("shared_video_id", sharedVideoId);
        // The start page gets the card's video, with which it leads (the shared video is no material of the version).
        String reader = taker(newTaker(unique("t-fs12-page"), "JP"));
        assertThat(items("qb.app.publication", reader, body("params", body("publicationId", draft,
            "at", clock.instant().toString()))).getFirst()).containsEntry("video", VideoEmbeds.player(YOUTUBE).get());
        // Submitted, frozen: the draft-only process refuses it.
        refused(PublicationSharedVideos.SET, userToken, body("publicationId", draft, "sharedVideoId", null), 422);
        // A copy keeps the pick.
        String copy = (String) run(PublicationProcesses.COPY, userToken, body("publicationId", draft))
            .get("publicationId");
        assertThat(row(copy)).containsEntry("shared_video_id", sharedVideoId);

        // A rejection: the provider reads the reason.
        String second = videoAsset(token, "Dogs", BILIBILI);
        Map<String, Object> rejected = run(SharedVideoProcesses.SHARE, token, share(second, false, pets));
        decide((String) rejected.get("approvalRequestId"), "REJECT", "Not entertainment");
        assertThat(items("qb.sponsor.my-shared-videos", token, body("params", body("assetId", second))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", "REJECTED")
                .containsEntry("rejectReason", "Not entertainment"));
        assertThat(reviewTask(rejected.get("approvalRequestId"))).isEqualTo("DONE");
        // F-C3: and an in-app notice with the reason, leading to the asset library; once, however often delivered.
        String rejectedId = (String) rejected.get("sharedVideoId");
        assertThat(notices(provider, "SHARED_VIDEO_REJECTED", rejectedId)).singleElement().satisfies(notice -> {
            assertThat(notice).containsEntry("action", "ASSETS");
            assertThat(notice.get("title").toString()).contains("Cats " + second.substring(0, 6), "猫の動画");
            assertThat(notice.get("body").toString()).contains("Not entertainment");
        });
        assertThat(items("qb.sponsor.inbox", token, body("params", body())))
            .extracting(row -> row.get("kind")).contains("SHARED_VIDEO_REJECTED");
        // An approval makes no notice.
        assertThat(query("SELECT 1 FROM qb_message WHERE recipient_id = ? AND source_key LIKE ?", provider,
            "%" + sharedVideoId)).isEmpty();
        // Shared again after the rejection: a new share, reviewed anew.
        assertThat(run(SharedVideoProcesses.SHARE, token, share(second, false, pets))).containsEntry("status",
            "PENDING");
        // The platform's audit has every change.
        assertThat(query("SELECT action FROM sys_audit_record WHERE entity_type = 'QbSharedVideo' AND entity_id = ?",
            sharedVideoId)).hasSize(2);
    }

    @Test
    void withoutAReviewRuleNothingIsShared() {
        String provider = newSponsor(unique("s-fs12-norule"));
        String token = sponsorToken(provider);
        String asset = videoAsset(token, "No rule", YOUTUBE);
        Object ruleId = query("SELECT DISTINCT ON (rule_id) rule_id FROM sys_approval_rule_version WHERE rule_code = ? "
            + "ORDER BY rule_id, version_no DESC", "qb.shared-video.review").getFirst().get("rule_id");
        switchRule(ruleId, false);
        try {
            assertThat(codes(refused(SharedVideoProcesses.SHARE, token, share(asset, false, word("fs12-pets")), 422)))
                .containsExactly(ReviewCodes.REVIEW_NOT_CONFIGURED);
            assertThat(query("SELECT 1 FROM qb_shared_video WHERE asset_id = ?", asset)).isEmpty();
        } finally {
            switchRule(ruleId, true);
        }
    }

    private void switchRule(Object ruleId, boolean enabled) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity",
            "SysApprovalRule", "targetId", String.valueOf(ruleId), "values", Map.of("enabled", enabled), "reason",
            "Test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    @Test
    void aWithdrawalStopsNewPicksOnlyWhatWasSubmittedKeepsIt() {
        String provider = newSponsor(unique("s-fs12-withdraw"));
        String user = newSponsor(unique("s-fs12-withdraw-user"));
        credit(user, 10_000);
        String sharedVideoId = approvedVideo(provider, BILIBILI, false);
        String live = liveWith(user, "FS12 withdraw live", sharedVideoId);
        String draft = draftWith(user, "FS12 withdraw draft", sharedVideoId, null);
        String other = draftWith(user, "FS12 withdraw other", null, null);

        // Only the provider withdraws it (another's is not found).
        refused(SharedVideoProcesses.WITHDRAW, sponsorToken(user), body("sharedVideoId", sharedVideoId), 404);
        assertThat(run(SharedVideoProcesses.WITHDRAW, sponsorToken(provider), body("sharedVideoId", sharedVideoId)))
            .containsEntry("status", "WITHDRAWN");
        assertThat(codes(refused(SharedVideoProcesses.WITHDRAW, sponsorToken(provider), body("sharedVideoId",
            sharedVideoId), 422))).containsExactly(SharedVideoCodes.STATE);

        // The publication submitted with it keeps it, live as before (frozen at submission).
        assertThat(status(live)).isEqualTo("LIVE");
        assertThat(card(live)).containsEntry("shared_video_id", sharedVideoId).containsEntry("foreign_video", false);
        // A switch of version keeps it on the card.
        String token = sponsorToken(user);
        String quizId = String.valueOf(latest("qb_quiz_version_version", "version_id", "version_id = '"
            + card(live).get("version_id") + "'").getFirst().get("quiz_id"));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "LINK", "title", "More", "url",
            "https://example.com/more"));
        String next = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        run(PublicationVersions.SWITCH, token, body("publicationId", live, "versionId", next));
        assertThat(card(live)).containsEntry("version_id", java.util.UUID.fromString(next))
            .containsEntry("shared_video_id", sharedVideoId).containsEntry("video", VideoEmbeds.player(BILIBILI).get());

        // A draft that picked it is no longer submitted with it; no new draft picks it; the catalog leaves it out.
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", draft), 422)))
            .contains(SharedVideoCodes.UNAVAILABLE);
        assertThat(codes(refused(PublicationSharedVideos.SET, token, body("publicationId", other, "sharedVideoId",
            sharedVideoId), 422))).containsExactly(SharedVideoCodes.UNAVAILABLE);
        assertThat(items("qb.sponsor.shared-videos", token, body("params", body())))
            .extracting(row -> row.get("sharedVideoId")).doesNotContain(sharedVideoId);
        // The draft drops it and goes on without.
        run(PublicationSharedVideos.SET, token, body("publicationId", draft, "sharedVideoId", null));
        assertThat(submit(token, draft).get("status")).isEqualTo("LIVE");
        assertThat(card(draft)).containsEntry("shared_video_id", null).containsEntry("video_provider_id", null);
    }

    @Test
    void aTakedownEndsEveryPublicationUsingItAndTellsBothSides() {
        String provider = newSponsor(unique("s-fs12-down"));
        String first = newSponsor(unique("s-fs12-down-a"));
        String second = newSponsor(unique("s-fs12-down-b"));
        credit(first, 10_000);
        credit(second, 10_000);
        // Bilibili: mainland China plays it, so the test's unknown address may start it (F-S11).
        String sharedVideoId = approvedVideo(provider, BILIBILI, false);
        String kept = approvedVideo(provider, YOUTUBE, true);
        String a = liveWith(first, "FS12 down A", sharedVideoId);
        String b = liveWith(second, "FS12 down B", sharedVideoId);
        String unaffected = liveWith(second, "FS12 down other", kept);
        // Withdrawn since: still used, still taken down.
        run(SharedVideoProcesses.WITHDRAW, sponsorToken(provider), body("sharedVideoId", sharedVideoId));
        // An attempt in progress completes as after any end.
        String taker = taker(newTaker(unique("t-fs12-down"), "JP"));
        Map<String, Object> started = start(taker, a);

        // The reason is required; the platform's emergency.
        refused(SharedVideoProcesses.TAKEDOWN, videoAdmin(), body("sharedVideoId", sharedVideoId), 400);
        assertThat(run(SharedVideoProcesses.TAKEDOWN, videoAdmin(), body("sharedVideoId", sharedVideoId, "reason",
            "Infringes a copyright"))).containsEntry("status", "TAKEN_DOWN");
        assertThat(videoRow(sharedVideoId)).containsEntry("takedown_reason", "Infringes a copyright")
            .containsEntry("takedown_operator_id", null);
        assertThat(codes(refused(SharedVideoProcesses.TAKEDOWN, videoAdmin(), body("sharedVideoId", sharedVideoId,
            "reason", "Again"), 422))).containsExactly(SharedVideoCodes.STATE);

        runEnds();
        assertThat(row(a)).containsEntry("status", "ENDED").containsEntry("end_reason", "SHARED_VIDEO")
            .containsEntry("paused_by", null).containsEntry("admin_reason", null);
        assertThat(row(b)).containsEntry("status", "ENDED").containsEntry("end_reason", "SHARED_VIDEO");
        assertThat(status(unaffected)).isEqualTo("LIVE");
        assertThat(card(a)).containsEntry("status", "ENDED");
        // A second run finds nothing more.
        long ended = count("qb_publication_version");
        runEnds();
        assertThat(count("qb_publication_version")).isEqualTo(ended);
        // The attempt started before completes.
        submit(taker, (String) started.get("attemptId"), answers(true, true));
        assertThat(attempt((String) started.get("attemptId")).get("submitted_at")).isNotNull();

        // Both sides are told: the provider with the reason, each user that it is not their breach.
        deliverEvents();
        assertThat(mailTemplates(provider)).contains("qb.shared-video.taken-down");
        assertThat(query("SELECT params FROM sys_mail_message WHERE user_id = ?::uuid AND template = "
            + "'qb.shared-video.taken-down'", provider).getFirst().get("params").toString())
            .contains("Infringes a copyright");
        assertThat(mailTemplates(first)).contains("qb.publication.shared-video-ended")
            .doesNotContain("qb.publication.taken-down", "qb.publication.ended");
        assertThat(mailTemplates(second)).contains("qb.publication.shared-video-ended");
        // F-C3: the provider's in-app notice too, with the reason.
        assertThat(notices(provider, "SHARED_VIDEO_TAKEN_DOWN", sharedVideoId)).singleElement()
            .satisfies(notice -> assertThat(notice.get("body").toString()).contains("Infringes a copyright"));
        // F-C3: the publications the takedown ended, for headquarters every sponsor's: why and when they ended.
        assertThat(items("qb.admin.shared-video-uses", videoAdmin(), body("params", body("sharedVideoId",
            sharedVideoId)))).extracting(r -> r.get("publicationId"), r -> r.get("status"), r -> r.get("endReason"),
            r -> r.get("publisherId")).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(a, "ENDED", "SHARED_VIDEO", first),
                org.assertj.core.groups.Tuple.tuple(b, "ENDED", "SHARED_VIDEO", second));
        assertThat(items("qb.admin.shared-video-uses", videoAdmin(), body("params", body("sharedVideoId",
            sharedVideoId)))).allSatisfy(r -> assertThat(r.get("endedAt")).isNotNull());
        assertThat(items("qb.admin.shared-video-uses", videoAdmin(), body("params", body())))
            .isEmpty();
        // Not a takedown of the user's publication: it is copied as any ended one.
        assertThat(run(PublicationProcesses.COPY, sponsorToken(first), body("publicationId", a)))
            .containsKey("publicationId");
        // The provider reads it on their own list.
        assertThat(items("qb.sponsor.my-shared-videos", sponsorToken(provider), body("params", body())))
            .filteredOn(row -> sharedVideoId.equals(row.get("sharedVideoId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "TAKEN_DOWN")
                .containsEntry("takedownReason", "Infringes a copyright"));
    }

    @Test
    void anOperatorReviewsAndTakesDownItsOwnSponsorsVideosOnlyThePlatformSeesAll() {
        String hq = operatorsAdmin();
        NewOperator north = newOperatorWithLead(hq, unique("FS12 North"), null);
        NewOperator south = newOperatorWithLead(hq, unique("FS12 South"), null);
        String mine = newSponsor(unique("s-fs12-north"));
        String theirs = newSponsor(unique("s-fs12-south"));
        String user = newSponsor(unique("s-fs12-op-user"));
        credit(user, 10_000);
        run(OperatorProcesses.ASSIGN, hq, body("userId", mine, "operatorId", north.operatorId(), "reason", "F-S12"));
        run(OperatorProcesses.ASSIGN, hq, body("userId", theirs, "operatorId", south.operatorId(), "reason",
            "F-S12"));
        String northSession = memberSession(north.leadId(), north.operatorId());
        String southSession = memberSession(south.leadId(), south.operatorId());

        // North's member reviews North's sponsor's video (the case is in North's scope).
        String token = sponsorToken(mine);
        Map<String, Object> shared = run(SharedVideoProcesses.SHARE, token,
            share(videoAsset(token, "North cats", YOUTUBE), false, word("fs12-pets")));
        String sharedVideoId = (String) shared.get("sharedVideoId");
        assertThat(query("SELECT scope_value FROM sys_approval_request_version WHERE request_id = ?::uuid",
            shared.get("approvalRequestId")).getFirst()).containsEntry("scope_value", north.operatorId());
        run("APPROVAL_DECIDE", northSession, body("requestId", shared.get("approvalRequestId"), "decision",
            "APPROVE"));
        deliverEvents();
        assertThat(videoRow(sharedVideoId)).containsEntry("status", "APPROVED");
        String theirVideo = approvedVideo(theirs, BILIBILI, false);
        String using = liveWith(user, "FS12 operator user", sharedVideoId);

        // Each operator sees its own sponsors' videos only; the platform every one, filtered by operator.
        Map<String, Object> all = body("params", body(), "limit", 200);
        assertThat(items("qb.admin.shared-videos", northSession, all)).extracting(row -> row.get("sharedVideoId"))
            .contains(sharedVideoId).doesNotContain(theirVideo);
        assertThat(items("qb.admin.shared-videos", southSession, all)).extracting(row -> row.get("sharedVideoId"))
            .contains(theirVideo).doesNotContain(sharedVideoId);
        assertThat(items("qb.admin.shared-videos", videoAdmin(), all)).extracting(row -> row.get("sharedVideoId"))
            .contains(sharedVideoId, theirVideo);
        assertThat(items("qb.admin.shared-videos", videoAdmin(), body("params", body(), "filters", List.of(body(
            "field", "operatorId", "op", "eq", "value", north.operatorId())), "limit", 200)))
            .extracting(row -> row.get("sharedVideoId")).contains(sharedVideoId).doesNotContain(theirVideo);
        assertThat(rows(com.jabiz.quizbuks.sharedvideo.QbSharedVideos.DEFAULT_DATASET, northSession))
            .extracting(row -> row.get("providerId")).containsOnly(mine);

        // Another operator's sponsor's video is not found; North takes its own down.
        refused(SharedVideoProcesses.TAKEDOWN, southSession, body("sharedVideoId", sharedVideoId, "reason",
            "Mine?"), 404);
        assertThat(run(SharedVideoProcesses.TAKEDOWN, northSession, body("sharedVideoId", sharedVideoId, "reason",
            "Illegal"))).containsEntry("status", "TAKEN_DOWN");
        assertThat(videoRow(sharedVideoId)).containsEntry("takedown_operator_id", north.operatorId());
        runEnds();
        assertThat(row(using)).containsEntry("status", "ENDED").containsEntry("end_reason", "SHARED_VIDEO");
        // F-C3: the publications it ended: headquarters reads them all, an operator only its own sponsors' (the user
        // is not North's) until the user is North's.
        Map<String, Object> uses = body("params", body("sharedVideoId", sharedVideoId));
        assertThat(items("qb.admin.shared-video-uses", videoAdmin(), uses)).extracting(r -> r.get("publicationId"))
            .containsExactly(using);
        assertThat(items("qb.admin.shared-video-uses", northSession, uses)).isEmpty();
        run(OperatorProcesses.ASSIGN, hq, body("userId", user, "operatorId", north.operatorId(), "reason", "F-C3"));
        assertThat(items("qb.admin.shared-video-uses", northSession, uses)).extracting(r -> r.get("publicationId"))
            .containsExactly(using);
        assertThat(items("qb.admin.shared-video-uses", southSession, uses)).isEmpty();
        client.post().uri("/api/queries/qb.admin.shared-video-uses")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, sponsorToken(user)).bodyValue(uses).exchange()
            .expectStatus().isForbidden();
        // Neither a taker nor a sponsor reads the administrators' list.
        call("QB_SHARED_VIDEO_TAKEDOWN", sponsorToken(user), body("sharedVideoId", theirVideo, "reason", "x"))
            .expectStatus().isForbidden();
        assertThat(query("SELECT action FROM sys_audit_record WHERE entity_type = 'QbSharedVideo' AND entity_id = ?",
            sharedVideoId)).hasSize(3);
    }

    @Test
    void aVideoToWatchStaysTheCardsVideoTheSharedOneCountsAsForeign() {
        String provider = newSponsor(unique("s-fs12-watch"));
        String user = newSponsor(unique("s-fs12-watch-user"));
        credit(user, 10_000);
        String sharedVideoId = approvedVideo(provider, YOUTUBE, false);
        String token = sponsorToken(user);
        // The version's own video is to be watched before answering (Vimeo, foreign either way).
        String quizId = newQuiz(token, "FS12 watch quiz");
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "FS12 watch quiz", "timeLimitSec", 300));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "VIDEO_LINK", "title", "Watch", "url",
            "https://vimeo.com/76979871", "watchToAnswer", true, "durationSec", 60));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        String publicationId = draftWith(user, "FS12 watch", sharedVideoId, versionId);
        assertThat(submit(token, publicationId).get("status")).isEqualTo("LIVE");
        assertThat(card(publicationId)).containsEntry("watch_required", true)
            .containsEntry("video", VideoEmbeds.player("https://vimeo.com/76979871").get())
            .containsEntry("shared_video_id", sharedVideoId).containsEntry("video_provider_id", provider)
            .containsEntry("foreign_video", true);
    }

    /** 总控 2026-11-08: a provider's account deleted withdraws their shares; submitted publications keep them. */
    @Test
    void aDeletedProvidersVideosAreWithdrawnWhatWasSubmittedKeepsThem() {
        String provider = newSponsor(unique("s-fs12-erased"));
        String user = newSponsor(unique("s-fs12-erased-user"));
        credit(user, 10_000);
        String approved = approvedVideo(provider, BILIBILI, false);
        String token = sponsorToken(provider);
        String pending = (String) run(SharedVideoProcesses.SHARE, token,
            share(videoAsset(token, "Pending", YOUTUBE), false, word("fs12-pets"))).get("sharedVideoId");
        String rejectedAsset = videoAsset(token, "Rejected", YOUTUBE);
        Map<String, Object> rejected = run(SharedVideoProcesses.SHARE, token, share(rejectedAsset, false,
            word("fs12-pets")));
        decide((String) rejected.get("approvalRequestId"), "REJECT", "No");
        String live = liveWith(user, "FS12 erased live", approved);

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", provider, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(videoRow(approved)).containsEntry("status", "WITHDRAWN");
        assertThat(videoRow(pending)).containsEntry("status", "WITHDRAWN");
        assertThat(videoRow((String) rejected.get("sharedVideoId"))).containsEntry("status", "REJECTED");
        // The review in progress is withdrawn with it.
        assertThat(query("SELECT status FROM sys_approval_request_version WHERE entity_id = ? ORDER BY version_no "
            + "DESC LIMIT 1", pending).getFirst().get("status")).isNotEqualTo("PENDING");
        // The user's publication keeps it, live; no new publication picks it.
        assertThat(status(live)).isEqualTo("LIVE");
        assertThat(card(live)).containsEntry("shared_video_id", approved);
        String draft = draftWith(user, "FS12 erased draft", null, null);
        assertThat(codes(refused(PublicationSharedVideos.SET, sponsorToken(user), body("publicationId", draft,
            "sharedVideoId", approved), 422))).containsExactly(SharedVideoCodes.UNAVAILABLE);
    }

    /**
     * F-C3 (总控 2026-11-08): banning a provider withdraws their shares in review (with the review) and approved, in the
     * ban's transaction; rejected and taken down ones stay as they are; a publication submitted with one goes on. Both
     * bans: headquarters' and an operator's.
     */
    @Test
    void aBannedProvidersVideosAreWithdrawnWhatWasSubmittedKeepsThem() {
        String boss = userWithRole(unique("fc3-boss"), com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER);
        String bossToken = tokenOf(boss, com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER);
        String hqProvider = newSponsor(unique("s-fc3-banned-hq"));
        assertBanWithdraws(hqProvider, () -> run(com.jabiz.quizbuks.identity.BanProcesses.BAN, bossToken,
            body("userId", hqProvider, "reason", "Fraud")), "QB_USER_BAN");

        String hq = operatorsAdmin();
        NewOperator east = newOperatorWithLead(hq, unique("FC3 East"), null);
        String opProvider = newSponsor(unique("s-fc3-banned-op"));
        run(OperatorProcesses.ASSIGN, hq, body("userId", opProvider, "operatorId", east.operatorId(), "reason",
            "F-C3"));
        String eastSession = memberSession(east.leadId(), east.operatorId());
        assertBanWithdraws(opProvider, () -> run(com.jabiz.quizbuks.identity.BanProcesses.OPERATOR_BAN, eastSession,
            body("userId", opProvider, "reason", "Fraud")), "QB_OPERATOR_SPONSOR_BAN");
    }

    private void assertBanWithdraws(String provider, Runnable ban, String banProcess) {
        String user = newSponsor(unique("s-fc3-banned-user"));
        credit(user, 10_000);
        String approved = approvedVideo(provider, BILIBILI, false);
        String token = sponsorToken(provider);
        Map<String, Object> pendingShare = run(SharedVideoProcesses.SHARE, token,
            share(videoAsset(token, "Pending", YOUTUBE), false, word("fs12-pets")));
        String pending = (String) pendingShare.get("sharedVideoId");
        Map<String, Object> rejected = run(SharedVideoProcesses.SHARE, token,
            share(videoAsset(token, "Rejected", YOUTUBE), false, word("fs12-pets")));
        decide((String) rejected.get("approvalRequestId"), "REJECT", "No");
        String takenDown = approvedVideo(provider, YOUTUBE, true);
        String live = liveWith(user, "FC3 banned live", approved);
        run(SharedVideoProcesses.TAKEDOWN, videoAdmin(), body("sharedVideoId", takenDown, "reason", "Illegal"));
        assertThat(items("qb.sponsor.shared-videos", sponsorToken(user), body("params", body(), "limit", 200)))
            .extracting(row -> row.get("sharedVideoId")).contains(approved);

        ban.run();

        assertThat(videoRow(approved)).containsEntry("status", "WITHDRAWN");
        assertThat(videoRow(pending)).containsEntry("status", "WITHDRAWN");
        assertThat(videoRow((String) rejected.get("sharedVideoId"))).containsEntry("status", "REJECTED");
        assertThat(videoRow(takenDown)).containsEntry("status", "TAKEN_DOWN");
        // The review in progress is withdrawn with it, and its task closed.
        assertThat(query("SELECT status FROM sys_approval_request_version WHERE request_id = ?::uuid ORDER BY "
            + "version_no DESC LIMIT 1", pendingShare.get("approvalRequestId")).getFirst().get("status"))
            .isNotEqualTo("PENDING");
        assertThat(reviewTask(pendingShare.get("approvalRequestId"))).isNotEqualTo("OPEN");
        // Inside the ban: its sub-processes, one per share standing.
        assertThat(query("SELECT count(*) AS n FROM op_process c JOIN op_process p ON p.process_seq_id = "
            + "c.parent_seq_id WHERE p.process_name = ? AND c.process_name = ? AND c.input_summary ->> 'providerId' = ?",
            banProcess, SharedVideoProcesses.ERASED_WITHDRAW, provider).getFirst().get("n"))
            .isEqualTo(2L);
        // The catalog no longer lists the banned provider's video; the publication submitted with it goes on.
        assertThat(items("qb.sponsor.shared-videos", sponsorToken(user), body("params", body(), "limit", 200)))
            .extracting(row -> row.get("sharedVideoId")).doesNotContain(approved, pending);
        assertThat(status(live)).isEqualTo("LIVE");
        assertThat(card(live)).containsEntry("shared_video_id", approved);
        String draft = draftWith(user, "FC3 banned draft", null, null);
        assertThat(codes(refused(PublicationSharedVideos.SET, sponsorToken(user), body("publicationId", draft,
            "sharedVideoId", approved), 422))).containsExactly(SharedVideoCodes.UNAVAILABLE);
    }

    /** 总控 2026-11-08: a publication in review whose shared video is taken down meanwhile does not go on. */
    @Test
    void aReviewedPublicationWhoseVideoWasTakenDownMeanwhileDoesNotGoOn() {
        String provider = newSponsor(unique("s-fs12-golive"));
        String user = newSponsor(unique("s-fs12-golive-user"));
        credit(user, 10_000);
        String sharedVideoId = approvedVideo(provider, BILIBILI, false);
        String other = approvedVideo(provider, YOUTUBE, false);
        autoApprove(false);
        try {
            String token = sponsorToken(user);
            String publicationId = draftWith(user, "FS12 go-live", sharedVideoId, null);
            Map<String, Object> submitted = submit(token, publicationId);
            assertThat(submitted).containsEntry("status", "PENDING_REVIEW");
            String kept = draftWith(user, "FS12 go-live kept", other, null);
            Map<String, Object> keptSubmitted = submit(token, kept);
            run(SharedVideoProcesses.TAKEDOWN, videoAdmin(), body("sharedVideoId", sharedVideoId, "reason",
                "Infringes"));
            runEnds();
            assertThat(status(publicationId)).isEqualTo("PENDING_REVIEW");

            decide((String) submitted.get("approvalRequestId"), "APPROVE", null);
            assertThat(row(publicationId)).containsEntry("status", "DRAFT")
                .containsEntry("reject_code", SharedVideoCodes.TAKEN_DOWN);
            assertThat(query("SELECT 1 FROM qb_publication_card WHERE publication_id = ?::uuid", publicationId))
                .isEmpty();
            // Another video's publication goes on as before.
            decide((String) keptSubmitted.get("approvalRequestId"), "APPROVE", null);
            assertThat(status(kept)).isEqualTo("LIVE");
            // The sponsor is told, in their language, why.
            deliverEvents();
            assertThat(query("SELECT params FROM sys_mail_message WHERE user_id = ?::uuid AND template = "
                + "'qb.publication.rejected'", user)).singleElement()
                .satisfies(m -> assertThat(m.get("params").toString()).contains("taken down while it was in review"));
            // Another video, and it goes on.
            run(PublicationSharedVideos.SET, token, body("publicationId", publicationId, "sharedVideoId", other));
            assertThat(submit(token, publicationId)).containsEntry("status", "PENDING_REVIEW");
        } finally {
            autoApprove(true);
        }
    }
}
