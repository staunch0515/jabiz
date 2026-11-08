package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.benefit.BenefitCodes;
import com.jabiz.quizbuks.benefit.OfferProcesses;
import com.jabiz.quizbuks.benefit.PurchaseProcesses;
import com.jabiz.quizbuks.benefit.QbBenefits;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.DigitalCodeProcesses;
import com.jabiz.quizbuks.reward.DigitalPrizeProcesses;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publications whose prize is another sponsor's digital product (Q89b-2, docs/quizbuks/plans/Q89-sponsored-benefits.md):
 * a film studio sells passes to watch a film, a café buys two and gives them away. The prizes come from a pool of
 * codes the studio loads (sealed under its key, opened under it); the café can follow the pool but not load it, and
 * a shared code or a prize sent by hand is refused.
 */
class BenefitDigitalIT extends TreeItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "q89b2-hq", QbPermissions.CERTIFICATION_ADMIN,
            QbPermissions.CERTIFICATION_MANAGE, QbPermissions.ADMIN_REWARD_METHODS, QbPermissions.ADMIN_USERS_READ,
            QbPermissions.ADMIN_PUBLICATION_READ);
    }

    /** F-S2c: the benefit role opened as production opens it: the gate's node lit by the user. */
    private void grant(String userId, String role) {
        unlock(role.equals("BENEFIT_PROVIDER") ? QbParams.TREE_GATE_BENEFIT_PROVIDER : QbParams.TREE_GATE_BENEFIT_SPONSOR,
            userId);
    }

    private record Setting(String studio, String cafe, String purchaseId) {}

    private Setting setting(String name, int passes) {
        String studio = newSponsor(unique("s-q89b2-studio-" + name));
        String cafe = newSponsor(unique("s-q89b2-cafe-" + name));
        grant(studio, "BENEFIT_PROVIDER");
        grant(cafe, "BENEFIT_SPONSOR");
        run(RewardMethodProcesses.SET, hq(), body("userId", cafe, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM, RewardMethod.DIGITAL_PRIZE), "note", "Q89b-2 test"));
        String offerId = (String) run(OfferProcesses.SAVE, sponsorToken(studio), body("kind", QbBenefits.DIGITAL,
            "name", "Film pass", "description", "Watch the film online once", "unitValue", 40, "listed", true))
            .get("offerId");
        String receipt = (String) upload(QbBenefits.PROOF_POLICY, FileSamples.pdf(), "receipt.pdf", "application/pdf",
            sponsorToken(cafe)).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody()
            .get("fileId");
        String today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")).toString();
        String purchaseId = (String) run(PurchaseProcesses.SAVE, sponsorToken(cafe), body("offerId", offerId,
            "quantity", passes, "paidAmount", passes * 40, "paidOn", today, "proofs", List.of(receipt)))
            .get("purchaseId");
        run(PurchaseProcesses.SUBMIT, sponsorToken(cafe), body("purchaseId", purchaseId, "baseRevision", 1));
        run(PurchaseProcesses.CONFIRM, sponsorToken(studio), body("purchaseId", purchaseId, "baseRevision", 2));
        return new Setting(studio, cafe, purchaseId);
    }

    private Map<String, Object> draft(Setting s, String delivery, int winners) {
        String token = sponsorToken(s.cafe());
        Instant now = clock.instant();
        Map<String, Object> save = complete(version(token, "Film quiz " + delivery), "Win a film pass", now,
            now.plus(Duration.ofDays(7)));
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.DIGITAL_PRIZE);
        overrides.put("digitalDelivery", delivery);
        overrides.put("benefitPurchaseId", s.purchaseId());
        overrides.put("maxWinners", winners);
        overrides.put("budgetCap", null);
        overrides.put("tiers", List.of(body("fromScore", 0, "toScore", 2, "reward", 0),
            body("fromScore", 3, "toScore", 5, "reward", 40)));
        save.putAll(overrides);
        return save;
    }

    private List<Map<String, Object>> rows(String template, String token, Map<String, Object> params) {
        return items(template, token, Map.of("params", params, "limit", 200));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStudioLoadsThePassesTheCafeGivesAwayAndTheWinnersOpenThem() {
        Setting s = setting("pool", 2);
        String cafe = sponsorToken(s.cafe());
        String studio = sponsorToken(s.studio());
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", cafe, draft(s, DigitalDelivery.POOL, 2));
        String publicationId = (String) saved.get("publicationId");
        // The description defaults to the product's.
        assertThat(query("SELECT digital_description, fulfiller_id FROM qb_publication_version "
            + "WHERE publication_id = CAST(? AS uuid) ORDER BY version_no DESC LIMIT 1", publicationId).getFirst())
            .containsEntry("fulfiller_id", s.studio())
            .satisfies(row -> assertThat((String) row.get("digital_description")).startsWith("Film pass"));

        // The pool is the studio's: the café cannot load it, and finds it empty until the studio does.
        refused(DigitalCodeProcesses.LOAD, cafe, body("publicationId", publicationId, "pastedCodes", "X-1"), 404);
        assertThat(rows("qb.sponsor.benefit-publications", studio, Map.of("purchaseId", s.purchaseId())))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("publicationId", publicationId)
                .containsEntry("available", 0));
        assertThat(rows("qb.sponsor.benefit-publications", cafe, Map.of())).isEmpty();
        assertThat(run(DigitalCodeProcesses.LOAD, studio, body("publicationId", publicationId, "pastedCodes",
            "https://films.example/watch?k=AAAA1111\nhttps://films.example/watch?k=BBBB2222")))
            .containsEntry("loaded", 2).containsEntry("available", 2);
        // D78: each code keeps whom it is sealed for (the studio), so a new seal key can take over.
        assertThat(query("SELECT fulfiller_id FROM qb_digital_code WHERE publication_id = CAST(? AS uuid)",
            publicationId)).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("fulfiller_id", s.studio()));
        assertThat(rows("qb.sponsor.benefit-digital-pool-summary", studio, Map.of("publicationId", publicationId)))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("available", 2));
        assertThat(rows("qb.sponsor.benefit-digital-codes", studio, Map.of("publicationId", publicationId)))
            .extracting(row -> row.get("hint")).containsExactly("…1111", "…2222");
        // Another sponsor reads none of it; the café follows its pool's counts.
        String stranger = sponsorToken(newSponsor(unique("s-q89b2-stranger")));
        assertThat(rows("qb.sponsor.benefit-digital-codes", stranger, Map.of("publicationId", publicationId)))
            .isEmpty();
        assertThat(rows("qb.sponsor.digital-pool-summary", cafe, Map.of("publicationId", publicationId)))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("available", 2));

        assertThat(submit(cafe, publicationId).get("status")).isIn("LIVE", "SCHEDULED");
        // Running: the codes stay, and the café can no more take them back than load them.
        refused(DigitalCodeProcesses.REVOKE, cafe, body("publicationId", publicationId), 404);

        String winner = newTaker(unique("t-q89b2-1"), "JP");
        assertThat(play(winner, publicationId, true, true)).containsEntry("rewardStatus", "DIGITAL");
        Map<String, Object> prize = latest("qb_digital_prize_version", "digital_prize_id",
            "publication_id = '" + publicationId + "'").getFirst();
        assertThat(prize).containsEntry("fulfiller_id", s.studio()).containsEntry("hint", "…1111");
        // The winner opens it: sealed under the studio's key, opened under it.
        assertThat(run(DigitalPrizeProcesses.OPEN, taker(winner), body("digitalPrizeId",
            String.valueOf(prize.get("digital_prize_id"))))).containsEntry("prizeContent",
                "https://films.example/watch?k=AAAA1111");
    }

    @Test
    void aSharedCodeOrAPrizeSentByHandIsRefused() {
        Setting s = setting("ways", 5);
        String cafe = sponsorToken(s.cafe());
        for (String delivery : List.of(DigitalDelivery.SHARED, DigitalDelivery.SPONSOR_SENT)) {
            Map<String, Object> save = draft(s, delivery, 1);
            save.put("digitalContent", "SHARED-CODE");
            save.put("digitalSendDays", 3);
            String publicationId = (String) run("QB_PUBLICATION_SAVE", cafe, save).get("publicationId");
            assertThat(codes(refused("QB_PUBLICATION_SUBMIT", cafe, body("publicationId", publicationId), 422)))
                .as(delivery).contains(BenefitCodes.DIGITAL_DELIVERY);
        }
        // A digital product is not a prize collected at a place.
        Map<String, Object> save = draft(s, DigitalDelivery.POOL, 1);
        save.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        save.remove("digitalDelivery");
        save.put("redeemDescription", "At the counter");
        assertThat(codes(refused("QB_PUBLICATION_SAVE", cafe, save, 422))).contains(BenefitCodes.PURCHASE_NOT_USABLE);
    }
}
