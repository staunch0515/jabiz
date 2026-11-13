package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposurePrices;
import com.jabiz.quizbuks.publishing.PublicationSharedVideos;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.topic.TopicProcesses;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What the F-M8 exposure fee tests share: the price of the default operator, a shared video, a draft that buys. */
abstract class ExposureFeeItSupport extends PrepaymentItSupport {

    static final String VIDEO = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    /** The classes whose schema has the price and the switch (each test class has a schema of its own). */
    private static final java.util.Set<Class<?>> READY = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The headquarters' exposure price of the default operator (1 yen), the switch on; once. */
    @BeforeEach
    void exposurePrice() {
        if (!READY.add(getClass())) {
            return;
        }
        param(QbParams.EXPOSURE_BILLING_ENABLED, "true");
        String operator = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
            newSponsor(unique("fm8-probe"))).getFirst().get("operator_id"));
        run(ExposurePrices.SET, TestTokens.bearer(tokens, "fm8-finance", QbPermissions.FEE_MANAGE),
            body("operatorId", operator, "unit", "1", "reason", "F-M8"));
    }

    String word(String code) {
        if (query("SELECT 1 FROM qb_topic WHERE topic_id = ?", code).isEmpty()) {
            run(TopicProcesses.ADD, TestTokens.bearer(tokens, "fm8-topic-admin", QbPermissions.ADMIN_TOPIC_READ,
                QbPermissions.ADMIN_TOPIC_WRITE), body("topicId", code, "nameZh", code + " 中", "nameJa", code + " 日",
                "nameEn", code + " en"));
        }
        return code;
    }

    /** The provider shares a video link and the review approves it; the shared video's key. */
    String approvedVideo(String provider) {
        String token = sponsorToken(provider);
        String assetId = (String) run(AssetProcesses.SAVE, token, body("kind", "VIDEO_LINK", "name", "Video",
            "language", "en", "url", VIDEO, "durationSec", 95)).get("assetId");
        Map<String, Object> shared = run(SharedVideoProcesses.SHARE, token, body("assetId", assetId,
            "titleZh", "猫", "titleJa", "猫の動画", "titleEn", "Cats", "topics", List.of(word("fm8-pets")),
            "vertical", false));
        decide((String) shared.get("approvalRequestId"), "APPROVE", null);
        return (String) shared.get("sharedVideoId");
    }

    /** A seven-day draft starting in a day (so that it waits in review), picking the shared video; {@code requested} exposures. */
    String draft(String sponsor, String name, String sharedVideoId, Long requested) {
        return draft(sponsor, name, sharedVideoId, requested, clock.instant().plus(Duration.ofDays(1)));
    }

    String draft(String sponsor, String name, String sharedVideoId, Long requested, Instant start) {
        String token = sponsorToken(sponsor);
        Map<String, Object> save = complete(version(token, name + " quiz"), name, start, start.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("regions", List.of("GLOBAL"));
        save.put("exposureRequested", requested);
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        if (sharedVideoId != null) {
            run(PublicationSharedVideos.SET, token, body("publicationId", publicationId, "sharedVideoId",
                sharedVideoId));
        }
        return publicationId;
    }

    static Map<String, Object> exposurePrepayment(String publicationId) {
        return query("SELECT * FROM qb_prepayment WHERE publication_id = ? AND purpose = 'EXPOSURE'", publicationId)
            .getFirst();
    }

    static void noUpdatesOrDeletes(String table) {
        org.assertj.core.api.Assertions.assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
            + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
            .as(table).singleElement().satisfies(row -> org.assertj.core.api.Assertions
                .assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
    }
}
