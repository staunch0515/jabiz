package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.benefit.AllocationProcesses;
import com.jabiz.quizbuks.benefit.BenefitCodes;
import com.jabiz.quizbuks.benefit.OfferProcesses;
import com.jabiz.quizbuks.benefit.PurchaseProcesses;
import com.jabiz.quizbuks.benefit.QbBenefits;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.quizbuks.reward.RewardCodes;
import com.jabiz.quizbuks.reward.StaffProcesses;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publications whose prize is another sponsor's goods (Q89b-1, docs/quizbuks/plans/Q89-sponsored-benefits.md): a
 * sponsor publishes with a confirmed purchase; each submission takes its share (its maxWinners) of what is left; the
 * review approves the purchase with the publication; the winners collect at the provider's place, from the provider
 * or its staff, never from the publisher; a publication back to its draft or ended gives its share back. The
 * requirement's acceptance scenario B: a refrigerator maker buys 100 bowls of beef noodles from a noodle shop, the
 * 101st winner finds none left.
 */
class BenefitPublicationIT extends TreeItSupport {

    @Autowired
    @Qualifier("qbBenefitAllocationReleaseJob")
    JobDefinition<AllocationProcesses.AllocationRef> releaseJob;

    @Autowired
    JobRunner jobs;

    private String hq() {
        return TestTokens.bearer(tokens, "q89b-hq", QbPermissions.CERTIFICATION_ADMIN,
            QbPermissions.CERTIFICATION_MANAGE, QbPermissions.ADMIN_REWARD_METHODS, QbPermissions.ADMIN_USERS_READ,
            QbPermissions.ADMIN_PUBLICATION_READ);
    }

    /** F-S2c: the benefit role opened as production opens it: the gate's node lit by the user. */
    private void grant(String userId, String role) {
        unlock(role.equals("BENEFIT_PROVIDER") ? QbParams.TREE_GATE_BENEFIT_PROVIDER : QbParams.TREE_GATE_BENEFIT_SPONSOR,
            userId);
    }

    private String today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")).toString();
    }

    /** A noodle shop (the provider) and a refrigerator maker (the sponsor) with a confirmed purchase of noodles. */
    private record Setting(String shop, String maker, String purchaseId) {}

    private Setting setting(String name, int bowls) {
        String shop = newSponsor(unique("s-q89b-shop-" + name));
        String maker = newSponsor(unique("s-q89b-maker-" + name));
        grant(shop, "BENEFIT_PROVIDER");
        grant(maker, "BENEFIT_SPONSOR");
        run(RewardMethodProcesses.SET, hq(), body("userId", maker, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM), "note", "Q89b test"));
        String offerId = (String) run(OfferProcesses.SAVE, sponsorToken(shop), body("kind", QbBenefits.GOODS,
            "name", "Beef noodles", "description", "One bowl of beef noodles", "unitValue", 30,
            "pickupAddress", "Ramen B, 1-2-3 Shibuya", "pickupHours", "11:00-21:00", "listed", true)).get("offerId");
        String receipt = (String) upload(QbBenefits.PROOF_POLICY, FileSamples.pdf(), "receipt.pdf", "application/pdf",
            sponsorToken(maker)).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody()
            .get("fileId");
        String purchaseId = (String) run(PurchaseProcesses.SAVE, sponsorToken(maker), body("offerId", offerId,
            "quantity", bowls, "paidAmount", bowls * 30, "paidOn", today(), "proofs", List.of(receipt)))
            .get("purchaseId");
        run(PurchaseProcesses.SUBMIT, sponsorToken(maker), body("purchaseId", purchaseId, "baseRevision", 1));
        run(PurchaseProcesses.CONFIRM, sponsorToken(shop), body("purchaseId", purchaseId, "baseRevision", 2));
        return new Setting(shop, maker, purchaseId);
    }

    /** One tier of nothing below the pass, one of the bowl (its value) at it; {@code winners} bowls. */
    private Map<String, Object> noodleQuiz(String purchaseId, int winners) {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        overrides.put("benefitPurchaseId", purchaseId);
        overrides.put("maxWinners", winners);
        overrides.put("budgetCap", null);
        overrides.put("tiers", List.of(body("fromScore", 0, "toScore", 2, "reward", 0),
            body("fromScore", 3, "toScore", 5, "reward", 30)));
        return overrides;
    }

    private String draftOf(String maker, String purchaseId, int winners) {
        String token = sponsorToken(maker);
        String versionId = version(token, "Fridge quiz " + winners);
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Free beef noodles", now, now.plus(Duration.ofDays(7)));
        save.putAll(noodleQuiz(purchaseId, winners));
        return (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
    }

    private static List<Map<String, Object>> allocations(String purchaseId) {
        return query("SELECT publication_id, quantity, used, status FROM qb_benefit_allocation WHERE purchase_id = ? "
            + "ORDER BY allocated_at", purchaseId);
    }

    /** Runs the release job as its schedule would (its lock from a run before is released, as the other ITs do). */
    private void release() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        clock.advance(Duration.ofMinutes(10));
        jobs.run(releaseJob, clock.instant());
    }

    @Test
    void theRefrigeratorMakersNoodlesGoToAHundredWinnersAtTheShop() {
        Setting s = setting("acceptance", 100);
        String publicationId = livePublication(s.maker(), "Free beef noodles", noodleQuiz(s.purchaseId(), 100));
        assertThat(allocations(s.purchaseId())).singleElement().satisfies(a -> assertThat(a)
            .containsEntry("status", QbBenefits.OPEN).containsEntry("quantity", java.math.BigDecimal.valueOf(100)));
        // The card says who hands the prize over; the page names the shop.
        assertThat(query("SELECT fulfiller_id FROM qb_publication_card WHERE publication_id = CAST(? AS uuid)",
            publicationId).getFirst()).containsEntry("fulfiller_id", s.shop());
        String someone = newTaker(unique("t-q89b-page"), "JP");
        assertThat(items("qb.app.publication", taker(someone), Map.of("params", Map.of("publicationId", publicationId,
            "at", clock.instant().toString())))).singleElement().satisfies(row -> assertThat(row.get(
                "fulfillerCompany")).isNotNull());

        // A hundred takers pass: each prize is the shop's to hand over.
        List<String> winners = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String taker = newTaker(unique("t-q89b-" + i), "JP");
            assertThat(play(taker, publicationId, true, true)).containsEntry("rewardStatus", "REDEEM");
            winners.add(taker);
        }
        assertThat(query("SELECT count(DISTINCT redemption_id) AS n FROM qb_redemption_version "
            + "WHERE publication_id = CAST(? AS uuid) AND fulfiller_id = ?", publicationId, s.shop()).getFirst()
            .get("n")).isEqualTo(100L);
        // The 101st finds the publication ended: the bowls are gone.
        String late = newTaker(unique("t-q89b-101"), "JP");
        refused("QB_ATTEMPT_START", taker(late), body("publicationId", publicationId), 422);

        // The first winner collects at the shop's counter: the maker's code is refused, the shop's works.
        String winner = taker(winners.getFirst());
        String makerKey = (String) run(RedemptionProcesses.ISSUE, sponsorToken(s.maker()), body("kind", "ROTATING"))
            .get("redeemKey");
        assertThat(((List<?>) run(RedemptionProcesses.CHECK, winner, body("scannedKey", makerKey)).get("prizes")))
            .isEmpty();
        String shopKey = (String) run(RedemptionProcesses.ISSUE, sponsorToken(s.shop()), body("kind", "ROTATING"))
            .get("redeemKey");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prizes = (List<Map<String, Object>>) run(RedemptionProcesses.CHECK, winner,
            body("scannedKey", shopKey)).get("prizes");
        String prize = (String) prizes.getFirst().get("redemptionId");
        assertThat(codes(refused(RedemptionProcesses.CLAIM, winner, body("scannedKey", makerKey, "redemptionId", prize),
            422))).containsExactly(RewardCodes.REDEEM_TOKEN_SPONSOR);
        assertThat(run(RedemptionProcesses.CLAIM, winner, body("scannedKey", shopKey, "redemptionId", prize)))
            .containsEntry("status", "REDEEMED");
        // The shop lists what it handed over for the maker; the maker's own counter lists none of them.
        assertThat(items("qb.sponsor.benefit-redemptions", sponsorToken(s.shop()), Map.of("limit", 500)))
            .hasSize(100).anySatisfy(row -> assertThat(row).containsEntry("status", "REDEEMED"));
        assertThat(items("qb.sponsor.redemptions", sponsorToken(s.maker()), Map.of("limit", 500))).isEmpty();

        // Ended (its winners reached its quota): its share is given back with what it used (all of it).
        assertThat(query("SELECT status, end_reason FROM (SELECT DISTINCT ON (publication_id) * FROM "
            + "qb_publication_version WHERE publication_id = CAST(? AS uuid) ORDER BY publication_id, version_no DESC) p",
            publicationId).getFirst()).containsEntry("status", "ENDED").containsEntry("end_reason", "QUOTA");
        release();
        assertThat(allocations(s.purchaseId())).singleElement().satisfies(a -> assertThat(a)
            .containsEntry("status", QbBenefits.RELEASED).containsEntry("used", java.math.BigDecimal.valueOf(100)));
    }

    @Test
    void aSubmissionTakesOnlyWhatIsLeftAndAWithdrawalGivesItBack() {
        Setting s = setting("shares", 10);
        String token = sponsorToken(s.maker());
        String first = draftOf(s.maker(), s.purchaseId(), 6);
        String second = draftOf(s.maker(), s.purchaseId(), 5);
        // With a person's review: the first waits, holding six.
        autoApprove(false);
        try {
            sharesUnderReview(s, token, first, second);
        } finally {
            autoApprove(true);
        }
    }

    private void sharesUnderReview(Setting s, String token, String first, String second) {
        assertThat(submit(token, first)).containsEntry("status", "PENDING_REVIEW");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", second), 422)))
            .containsExactly(BenefitCodes.STOCK_SHORT);
        // The review approves the purchase as the draft saw it (the review's content is built from it).
        assertThat(String.valueOf(query("SELECT benefit_snapshot FROM (SELECT DISTINCT ON (publication_id) * FROM "
            + "qb_publication_version WHERE publication_id = CAST(? AS uuid) ORDER BY publication_id, version_no DESC) p",
            first).getFirst().get("benefit_snapshot"))).contains(s.purchaseId()).contains("Beef noodles")
            .contains(s.shop());
        // Withdrawn: back to its draft; the job gives its six back and the second fits.
        run("QB_PUBLICATION_WITHDRAW", token, body("publicationId", first));
        release();
        assertThat(allocations(s.purchaseId())).isEmpty();
        assertThat(submit(token, second)).containsEntry("status", "PENDING_REVIEW");
        assertThat(allocations(s.purchaseId())).singleElement().satisfies(a -> assertThat(a)
            .containsEntry("quantity", java.math.BigDecimal.valueOf(5)));
    }

    @Test
    void aPublicationDrawsOnlyOnAConfirmedPurchaseOfItsOwnWithOneTierOfItsValue() {
        Setting s = setting("rules", 5);
        String token = sponsorToken(s.maker());
        // Another sponsor's purchase is none.
        String stranger = newSponsor(unique("s-q89b-stranger"));
        run(RewardMethodProcesses.SET, hq(), body("userId", stranger, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM), "note", "Q89b test"));
        String versionId = version(sponsorToken(stranger), "Stranger quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Not mine", now, now.plus(Duration.ofDays(7)));
        save.putAll(noodleQuiz(s.purchaseId(), 5));
        refused("QB_PUBLICATION_SAVE", sponsorToken(stranger), save, 404);
        // The prize's description is the benefit's when none is written.
        String draft = draftOf(s.maker(), s.purchaseId(), 5);
        assertThat(String.valueOf(query("SELECT redeem_description FROM (SELECT DISTINCT ON (publication_id) * FROM "
            + "qb_publication_version WHERE publication_id = CAST(? AS uuid) ORDER BY publication_id, version_no DESC) p",
            draft).getFirst().get("redeem_description"))).contains("Beef noodles").contains("Ramen B");
        // Two paying tiers, or one not worth the bowl, are refused; so is a quota above what was bought.
        Map<String, Object> twoTiers = new HashMap<>(save);
        twoTiers.put("versionId", version(token, "Two tiers quiz"));
        twoTiers.put("tiers", tiers());
        twoTiers.put("maxWinners", 6);
        String twice = (String) run("QB_PUBLICATION_SAVE", token, twoTiers).get("publicationId");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", twice), 422)))
            .contains(BenefitCodes.ONE_TIER, BenefitCodes.STOCK_SHORT);
        // A change of the purchase after the draft was saved: save the draft again first.
        run(PurchaseProcesses.SAVE, token, body("purchaseId", s.purchaseId(), "quantity", 6, "paidAmount", 180,
            "paidOn", today(), "proofs", query("SELECT proof_1 FROM qb_benefit_purchase_version WHERE purchase_id = "
                + "CAST(? AS uuid) ORDER BY version_no DESC LIMIT 1", s.purchaseId()).stream()
                .map(row -> String.valueOf(row.get("proof_1"))).toList()));
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", draft), 422)))
            .contains(BenefitCodes.PURCHASE_NOT_USABLE);
    }

    @Test
    void theProvidersStaffConfirmAPrintedCodesClaimAndThePublisherCannot() {
        Setting s = setting("staff", 2);
        String publicationId = livePublication(s.maker(), "Staff noodles", noodleQuiz(s.purchaseId(), 2));
        String kaiId = newTaker(unique("t-q89b-kai"), "JP");
        run(StaffProcesses.ADD, sponsorToken(s.shop()), body("userId", kaiId));
        String winnerId = newTaker(unique("t-q89b-claim"), "JP");
        assertThat(play(winnerId, publicationId, true, true)).containsEntry("rewardStatus", "REDEEM");
        String printed = (String) run(RedemptionProcesses.ISSUE, sponsorToken(s.shop()), body("kind", "PRINTED"))
            .get("redeemKey");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prizes = (List<Map<String, Object>>) run(RedemptionProcesses.CHECK,
            taker(winnerId), body("scannedKey", printed)).get("prizes");
        String prize = (String) prizes.getFirst().get("redemptionId");
        assertThat(run(RedemptionProcesses.CLAIM, taker(winnerId), body("scannedKey", printed, "redemptionId", prize)))
            .containsEntry("status", "CLAIMED");
        // The publisher does not answer for the shop; the shop's staff member does.
        refused(RedemptionProcesses.CONFIRM, sponsorToken(s.maker()), body("redemptionId", prize), 404);
        assertThat(run(StaffProcesses.CONFIRM, taker(kaiId), body("redemptionId", prize)))
            .containsEntry("status", "REDEEMED");
    }
}
