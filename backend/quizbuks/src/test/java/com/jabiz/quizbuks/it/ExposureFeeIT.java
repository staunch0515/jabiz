package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposurePrices;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.Prepayments;
import com.jabiz.quizbuks.publishing.PublicationExposureTopUp;
import com.jabiz.quizbuks.publishing.PublicationSharedVideos;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.topic.TopicProcesses;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8 (docs/quizbuks/plans/impl/F-M8.md §3.3-§3.4): the exposures bought when a publication using another sponsor's
 * shared video is submitted (the deposit set aside whole, the price frozen, the total set on the publication), and
 * bought more of later (the deposit of the new total, never different from buying it at once).
 */
class ExposureFeeIT extends PrepaymentItSupport {

    static final String VIDEO = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    private static boolean ready;

    /** The headquarters' exposure price of the default operator (1 yen), the switch on; once. */
    @BeforeEach
    void exposurePrice() {
        if (ready) {
            return;
        }
        param(QbParams.EXPOSURE_BILLING_ENABLED, "true");
        String operator = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
            newSponsor(unique("fm8-probe"))).getFirst().get("operator_id"));
        run(ExposurePrices.SET, TestTokens.bearer(tokens, "fm8-finance", QbPermissions.FEE_MANAGE),
            body("operatorId", operator, "unit", "1", "reason", "F-M8"));
        ready = true;
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
        String token = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> save = complete(version(token, name + " quiz"), name, now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)));
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

    @Test
    void aPublicationWithAnotherSponsorsVideoBuysItsExposuresAtTheSubmission() {
        String provider = reviewedSponsor("fm8-provider");
        String sharedVideoId = approvedVideo(provider);
        String sponsor = reviewedSponsor("fm8-buyer");
        grant(sponsor, 5_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = draft(sponsor, "Buy", sharedVideoId, 105L);

        // The wizard's look shows the deposit: 105 at 1 yen, and 10% tax rounded down: 105 + 10.
        assertThat(check(sponsor, publicationId)).containsEntry("enough", true);
        Map<String, Object> submitted = submit(sponsorToken(sponsor), publicationId);
        assertThat(submitted.get("status")).isEqualTo("SCHEDULED");

        assertThat(exposurePrepayment(publicationId)).satisfies(p -> {
            assertThat(p).containsEntry("mode", "PREPAID").containsEntry("status", "OPEN");
            assertThat((BigDecimal) p.get("amount")).isEqualByComparingTo("115");
            assertThat((BigDecimal) p.get("remaining")).isEqualByComparingTo("115");
            assertThat((BigDecimal) p.get("charge_amount")).isEqualByComparingTo("0");
        });
        Map<String, Object> row = row(publicationId);
        assertThat((BigDecimal) row.get("exposure_unit")).isEqualByComparingTo("1");
        assertThat((BigDecimal) row.get("exposure_purchased")).isEqualByComparingTo("105");
        // The answer carries the revision the purchase wrote last.
        assertThat(((Number) submitted.get("revision")).longValue()).isEqualTo(((Number) row.get("revision")).longValue());
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("115");
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void fewerThanTheLeastOrNoneIsRefusedAndNothingIsWritten() {
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-provider-least"));
        String sponsor = reviewedSponsor("fm8-least");
        grant(sponsor, 5_000, clock.instant().plus(Duration.ofDays(90)));
        for (Long requested : new Long[] {99L, null}) {
            String publicationId = draft(sponsor, "Least " + requested, sharedVideoId, requested);
            assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorToken(sponsor), body("publicationId",
                publicationId), 422))).contains(FeeCodes.EXPOSURE_PURCHASE_REQUIRED);
            assertThat(status(publicationId)).isEqualTo("DRAFT");
            assertThat(prepayments(publicationId)).isEmpty();
            assertThat(row(publicationId).get("exposure_purchased")).isNull();
        }
    }

    @Test
    void ownVideoOrNoVideoOrTheSwitchOffBuysNothing() {
        String sponsor = reviewedSponsor("fm8-own");
        grant(sponsor, 5_000, clock.instant().plus(Duration.ofDays(90)));
        // Its own video: the exposures asked for are ignored.
        String own = draft(sponsor, "Own video", approvedVideo(sponsor), 500L);
        submit(sponsorToken(sponsor), own);
        assertThat(prepayments(own)).isEmpty();
        assertThat(row(own).get("exposure_unit")).isNull();
        assertThat(row(own).get("exposure_purchased")).isNull();
        // No shared video at all.
        String none = draft(sponsor, "No video", null, 500L);
        submit(sponsorToken(sponsor), none);
        assertThat(prepayments(none)).isEmpty();
    }

    @Test
    void aShortfallRefusesTheSubmissionLikeAnyOtherFee() {
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-provider-short"));
        String sponsor = reviewedSponsor("fm8-short");
        grant(sponsor, 100, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = draft(sponsor, "Short", sharedVideoId, 105L);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorToken(sponsor), body("publicationId",
            publicationId), 422))).contains(FeeCodes.PUBLICATION_FUNDS);
        assertThat(prepayments(publicationId)).isEmpty();
        assertThat(status(publicationId)).isEqualTo("DRAFT");
    }

    @Test
    void withdrawnItGivesTheDepositBackAndTheDraftHasBoughtNothing() {
        autoApprove(false);
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-provider-back"));
        String sponsor = reviewedSponsor("fm8-back");
        grant(sponsor, 5_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = draft(sponsor, "Back", sharedVideoId, 105L);
        submit(sponsorToken(sponsor), publicationId);
        assertThat(status(publicationId)).isEqualTo("PENDING_REVIEW");
        run("QB_PUBLICATION_WITHDRAW", sponsorToken(sponsor), body("publicationId", publicationId));
        assertThat(exposurePrepayment(publicationId)).containsEntry("status", "RELEASED");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        Map<String, Object> row = row(publicationId);
        assertThat(row.get("exposure_purchased")).isNull();
        assertThat(row.get("exposure_unit")).isNull();
        // What the sponsor asked for stays on the draft.
        assertThat((BigDecimal) row.get("exposure_requested")).isEqualByComparingTo("105");
        assertThat(differences(sponsor)).isEmpty();
        autoApprove(true);
    }

    @Test
    void aTopUpFreezesTheDifferenceOfTheCumulativeDepositsAndRefusesAStaleTotal() {
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-provider-top"));
        String sponsor = reviewedSponsor("fm8-top");
        grant(sponsor, 5_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = draft(sponsor, "Top", sharedVideoId, 105L);
        String token = sponsorToken(sponsor);
        submit(token, publicationId);
        // A publication that bought none (its own video) has nothing to add to.
        String own = draft(sponsor, "Top own", null, null);
        submit(token, own);
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, token, body("publicationId", own,
            "expectedPurchased", 0, "additional", 5), 422))).contains(FeeCodes.EXPOSURE_NOT_BOUGHT);
        // A stale total.
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, token, body("publicationId", publicationId,
            "expectedPurchased", 100, "additional", 5), 422))).contains(FeeCodes.EXPOSURE_PURCHASED_CHANGED);
        // 105 froze 115; 110 would freeze 121: the top-up of 5 freezes 6 (not 5, which buying one at a time would).
        Map<String, Object> out = run(PublicationExposureTopUp.TOP_UP, token, body("publicationId", publicationId,
            "expectedPurchased", 105, "additional", 5));
        assertThat(((Number) out.get("purchased")).longValue()).isEqualTo(110);
        assertThat(new BigDecimal(String.valueOf(out.get("frozen")))).isEqualByComparingTo("6");
        assertThat((BigDecimal) exposurePrepayment(publicationId).get("amount")).isEqualByComparingTo("121");
        assertThat((BigDecimal) exposurePrepayment(publicationId).get("remaining")).isEqualByComparingTo("121");
        assertThat((BigDecimal) row(publicationId).get("exposure_purchased")).isEqualByComparingTo("110");
        assertThat(((Number) out.get("revision")).longValue()).isEqualTo(((Number) row(publicationId).get("revision")).longValue());
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("121");
        assertThat(differences(sponsor)).isEmpty();
        // Sent again with the old total: refused, so it bought once.
        refused(PublicationExposureTopUp.TOP_UP, token, body("publicationId", publicationId, "expectedPurchased", 105,
            "additional", 5), 422);
        // Only the sponsor's own publication.
        refused(PublicationExposureTopUp.TOP_UP, sponsorToken(reviewedSponsor("fm8-other")), body("publicationId",
            publicationId, "expectedPurchased", 110, "additional", 5), 404);
    }
}
