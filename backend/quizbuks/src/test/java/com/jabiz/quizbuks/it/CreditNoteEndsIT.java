package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.publishing.PublicationSharedVideos;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses;
import com.jabiz.quizbuks.topic.TopicProcesses;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M7, M2 of the review (§10.3-1, Q3; N6): an audience fee's publication ended through each real path. A takedown
 * (ADMIN) gives nothing back; a retired map (TREE) and a shared video taken down (SHARED_VIDEO) give back the unused
 * days. (PUBLISHER is a channel's publisher, the Shopify storefront, reached only through its own base class:
 * {@code CreditNoteRulesTest} covers it.) A LIVE publication is not withdrawn, and a pause and a resume leave one fee.
 */
class CreditNoteEndsIT extends CreditNoteItSupport {

    static final String BILIBILI = "https://www.bilibili.com/video/BV1GJ411x7h7";

    @Autowired
    @Qualifier("qbSharedVideoEndsJob")
    JobDefinition<PublicationSharedVideos.EndInput> endsJob;

    @Test
    void aTakedownGivesNothingBack() {
        String sponsor = refundingSponsor("fm7-admin");
        String publicationId = liveAudience(sponsor, "FM7 takedown", Map.of());
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fm7-content-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", publicationId, "reason", "Fraudulent"));
        assertThat(row(publicationId)).containsEntry("status", "ENDED").containsEntry("end_reason", "ADMIN");

        assertThat(decide(sponsor, publicationId)).isEqualTo("NONE_REASON");
        assertThat(notes(sponsor)).isEmpty();
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void aRetiredMapGivesBackTheUnusedDays() {
        // The map is reviewed by the sponsor's operator: made while they are the platform operator's, then they
        // move to an operator that prices the audience rules.
        autoApprove(true);
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        String sponsor = newSponsor(unique("fm7-tree"));
        Map3 map = map(sponsor);
        String hq = TestTokens.bearer(tokens, "fm7-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
        String operator = newOperator(hq, unique("fm7-tree operator"));
        run(com.jabiz.quizbuks.operator.OperatorProcesses.ASSIGN, hq, body("userId", sponsor, "operatorId", operator,
            "reason", "F-M7"));
        price(operator);
        cash(sponsor, 1_000);
        credit(sponsor, 10_000);
        String publicationId = liveAudience(sponsor, "FM7 tree", Map.of("treeId", map.treeId(), "treeNodeId",
            map.basics()));
        String token = sponsorToken(sponsor);
        Map<String, Object> tree = query("SELECT revision FROM qb_tree WHERE tree_id = ?", map.treeId()).getFirst();
        Map<String, Object> draft = save(token, Map.of("treeId", map.treeId(), "revision", tree.get("revision")),
            List.of(node(map.basics(), null, "Basics", all(passed()))));
        run(TreeProcesses.RETIRE, token, body("treeId", map.treeId(), "baseRevision", draft.get("revision")));
        assertThat(row(publicationId)).containsEntry("status", "ENDED").containsEntry("end_reason", "TREE");

        assertRefunded(sponsor, publicationId, "TREE");
    }

    @Test
    void aSharedVideoTakenDownGivesBackTheUnusedDays() {
        String sponsor = refundingSponsor("fm7-video");
        String provider = newSponsor(unique("s-fm7-provider"));
        String sharedVideoId = approvedVideo(provider);
        String publicationId = liveAudience(sponsor, "FM7 video", Map.of("regions", List.of("GLOBAL")),
            sharedVideoId);
        run(SharedVideoProcesses.TAKEDOWN, TestTokens.bearer(tokens, "fm7-video-admin",
            QbPermissions.ADMIN_SHARED_VIDEO_READ, QbPermissions.ADMIN_SHARED_VIDEO_WRITE),
            body("sharedVideoId", sharedVideoId, "reason", "Infringes a copyright"));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(endsJob, clock.instant());
        assertThat(row(publicationId)).containsEntry("status", "ENDED").containsEntry("end_reason", "SHARED_VIDEO");

        assertRefunded(sponsor, publicationId, "SHARED_VIDEO");
    }

    /** N6: a LIVE publication is not withdrawn (its fee stands); paused and resumed, it still has one fee. */
    @Test
    void aLivePublicationKeepsOneFee() {
        String sponsor = refundingSponsor("fm7-n6");
        String publicationId = liveAudience(sponsor, "FM7 one fee", Map.of());
        String token = sponsorToken(sponsor);
        assertThat(codes(refused("QB_PUBLICATION_WITHDRAW", token, body("publicationId", publicationId), 422)))
            .containsExactly("ILLEGAL_TRANSITION");
        run("QB_PUBLICATION_PAUSE", token, body("publicationId", publicationId));
        assertThat(status(publicationId)).isEqualTo("PAUSED");
        run("QB_PUBLICATION_RESUME", token, body("publicationId", publicationId));
        assertThat(status(publicationId)).isEqualTo("LIVE");
        assertThat(query("SELECT charge_id FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'AUDIENCE' "
            + "AND source_key = ?", sponsor, "publication:" + publicationId)).hasSize(1);
        assertThat(prepayments(publicationId)).hasSize(1);
        assertThat(differences(sponsor)).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** A priced sponsor with money, the refund switch on. */
    String refundingSponsor(String name) {
        autoApprove(true);
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        String sponsor = pricedSponsor(name);
        cash(sponsor, 1_000);
        credit(sponsor, 10_000);
        return sponsor;
    }

    String liveAudience(String sponsor, String name, Map<String, Object> overrides) {
        return liveAudience(sponsor, name, overrides, null);
    }

    /** A seven-day audience publication (gender: FEMALE) from tomorrow, on now: its fee charged from its prepayment. */
    String liveAudience(String sponsor, String name, Map<String, Object> overrides, String sharedVideoId) {
        String token = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> input = complete(version(token, name + " quiz"), name, now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)));
        input.put("maxWinners", 1000);
        input.putAll(overrides);
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, input);
        assertThat((List<?>) saved.get("problems")).as("problems").isEmpty();
        String publicationId = (String) saved.get("publicationId");
        if (sharedVideoId != null) {
            run(PublicationSharedVideos.SET, token, body("publicationId", publicationId, "sharedVideoId",
                sharedVideoId));
        }
        Map<String, Object> gender = new HashMap<>();
        gender.put("key", "gender");
        gender.put("options", List.of("FEMALE"));
        run("QB_PUBLICATION_AUDIENCE_SAVE", token, body("publicationId", publicationId, "criteria",
            new ArrayList<>(List.of(gender))));
        submit(token, publicationId);
        goLive(publicationId);
        assertThat(chargeOf(sponsor)).isNotNull();
        return publicationId;
    }

    /** The refund item for the publication, as the job runs it: its outcome. */
    String decide(String sponsor, String publicationId) {
        Map<String, Object> ref = body("prepaymentId", String.valueOf(prepayments(publicationId).getFirst()
            .get("prepayment_id")), "publicationId", publicationId, "sponsorId", sponsor, "endedAt",
            clock.instant().toString());
        return ((FeeProcesses.ItemOutput) systemRun(AudienceRefundJob.ITEM, ref)).outcome();
    }

    /** Ended on its first day: one of seven days used, 600 and 60 tax given back through its prepayment. */
    void assertRefunded(String sponsor, String publicationId, String endReason) {
        BigDecimal cashBefore = balance(sponsor, com.jabiz.quizbuks.wallet.QbWallets.CASH);
        assertThat(decide(sponsor, publicationId)).isEqualTo("REFUNDED");
        assertThat(notes(sponsor)).singleElement().satisfies(note -> {
            assertThat(note).containsEntry("end_reason", endReason).containsEntry("publication_id", publicationId);
            assertThat((BigDecimal) note.get("used_days")).isEqualByComparingTo("1");
            assertThat((BigDecimal) note.get("total")).isEqualByComparingTo("660");
        });
        assertThat(balance(sponsor, com.jabiz.quizbuks.wallet.QbWallets.CASH))
            .isEqualByComparingTo(cashBefore.add(new BigDecimal("660")));
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    /** A shared video of the provider's, approved (as SharedVideoIT shares one). */
    String approvedVideo(String provider) {
        String token = sponsorToken(provider);
        String topic = "fm7-pets";
        if (query("SELECT 1 FROM qb_topic WHERE topic_id = ?", topic).isEmpty()) {
            run(TopicProcesses.ADD, TestTokens.bearer(tokens, "fm7-topic-admin", QbPermissions.ADMIN_TOPIC_READ,
                QbPermissions.ADMIN_TOPIC_WRITE), body("topicId", topic, "nameZh", topic + " 中", "nameJa",
                topic + " 日", "nameEn", topic + " en"));
        }
        String assetId = (String) run(AssetProcesses.SAVE, token, body("kind", "VIDEO_LINK", "name", "Video", "language",
            "en", "url", BILIBILI, "durationSec", 95)).get("assetId");
        Map<String, Object> shared = run(SharedVideoProcesses.SHARE, token, body("assetId", assetId, "titleZh",
            "猫 " + assetId.substring(0, 6), "titleJa", "猫の動画", "titleEn", "Cats " + assetId.substring(0, 6),
            "topics", List.of(topic), "vertical", false));
        decide((String) shared.get("approvalRequestId"), "APPROVE", null);
        return (String) shared.get("sharedVideoId");
    }
}
