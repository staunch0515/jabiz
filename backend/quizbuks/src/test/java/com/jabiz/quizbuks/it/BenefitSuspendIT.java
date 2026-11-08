package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.benefit.BenefitCodes;
import com.jabiz.quizbuks.benefit.IncidentProcesses;
import com.jabiz.quizbuks.benefit.OfferProcesses;
import com.jabiz.quizbuks.benefit.PurchaseProcesses;
import com.jabiz.quizbuks.benefit.QbBenefits;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a purchase goes wrong (Q89d-1, docs/quizbuks/plans/Q89-sponsored-benefits.md, D-Q89-12): either side suspends
 * it, which pauses every running publication drawing on it and, when asked, voids its prizes not collected yet (the
 * job); its publications cannot be resumed nor new ones submitted until the purchase is resumed; either side records
 * incidents and their outcome; the purchase lists say what became of it (D-Q89-13).
 */
class BenefitSuspendIT extends TreeItSupport {

    @Autowired
    @Qualifier("qbBenefitVoidJob")
    JobDefinition<RedemptionProcesses.SuspendVoidInput> voidJob;

    @Autowired
    JobRunner jobs;

    private String hq() {
        return TestTokens.bearer(tokens, "q89d-hq", QbPermissions.CERTIFICATION_ADMIN,
            QbPermissions.CERTIFICATION_MANAGE, QbPermissions.ADMIN_REWARD_METHODS, QbPermissions.ADMIN_USERS_READ,
            QbPermissions.ADMIN_PUBLICATION_READ);
    }

    /** F-S2c: the benefit role opened as production opens it: the gate's node lit by the user. */
    private void grant(String userId, String role) {
        unlock(role.equals("BENEFIT_PROVIDER") ? QbParams.TREE_GATE_BENEFIT_PROVIDER : QbParams.TREE_GATE_BENEFIT_SPONSOR,
            userId);
    }

    private record Setting(String shop, String maker, String purchaseId) {}

    private Setting setting(String name, int bowls) {
        String shop = newSponsor(unique("s-q89d-shop-" + name));
        String maker = newSponsor(unique("s-q89d-maker-" + name));
        grant(shop, "BENEFIT_PROVIDER");
        grant(maker, "BENEFIT_SPONSOR");
        run(RewardMethodProcesses.SET, hq(), body("userId", maker, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM), "note", "Q89d test"));
        String offerId = (String) run(OfferProcesses.SAVE, sponsorToken(shop), body("kind", QbBenefits.GOODS,
            "name", "Beef noodles", "description", "One bowl of beef noodles", "unitValue", 30,
            "pickupAddress", "Ramen B, 1-2-3 Shibuya", "pickupHours", "11:00-21:00", "listed", true)).get("offerId");
        String receipt = (String) upload(QbBenefits.PROOF_POLICY, FileSamples.pdf(), "receipt.pdf", "application/pdf",
            sponsorToken(maker)).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody()
            .get("fileId");
        String today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")).toString();
        String purchaseId = (String) run(PurchaseProcesses.SAVE, sponsorToken(maker), body("offerId", offerId,
            "quantity", bowls, "paidAmount", bowls * 30, "paidOn", today, "proofs", List.of(receipt)))
            .get("purchaseId");
        run(PurchaseProcesses.SUBMIT, sponsorToken(maker), body("purchaseId", purchaseId, "baseRevision", 1));
        run(PurchaseProcesses.CONFIRM, sponsorToken(shop), body("purchaseId", purchaseId, "baseRevision", 2));
        return new Setting(shop, maker, purchaseId);
    }

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

    private String publicationStatus(String publicationId) {
        return (String) query("SELECT status FROM (SELECT DISTINCT ON (publication_id) * FROM qb_publication_version "
            + "WHERE publication_id = CAST(? AS uuid) ORDER BY publication_id, version_no DESC) p", publicationId)
            .getFirst().get("status");
    }

    private Map<String, Object> purchase(String purchaseId) {
        return query("SELECT DISTINCT ON (purchase_id) * FROM qb_benefit_purchase_version WHERE purchase_id = CAST(? AS uuid) "
            + "ORDER BY purchase_id, version_no DESC", purchaseId).getFirst();
    }

    private void runVoidJob() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        clock.advance(Duration.ofMinutes(10));
        jobs.run(voidJob, clock.instant());
    }

    private List<Map<String, Object>> rows(String template, String token, Map<String, Object> params) {
        return items(template, token, Map.of("params", params, "limit", 100));
    }

    @Test
    void aSuspensionPausesThePublicationsVoidsTheUnusedPrizesAndHoldsUntilResumed() {
        Setting s = setting("suspend", 10);
        String maker = sponsorToken(s.maker());
        String shop = sponsorToken(s.shop());
        String first = livePublication(s.maker(), "Free noodles one", noodleQuiz(s.purchaseId(), 3));
        String second = livePublication(s.maker(), "Free noodles two", noodleQuiz(s.purchaseId(), 3));
        String winner = newTaker(unique("t-q89d-1"), "JP");
        assertThat(play(winner, first, true, true)).containsEntry("rewardStatus", "REDEEM");
        String prize = String.valueOf(query("SELECT redemption_id FROM qb_redemption_version WHERE publication_id = "
            + "CAST(? AS uuid) LIMIT 1", first).getFirst().get("redemption_id"));

        // Nobody else's to suspend; the provider suspends it, voiding what is unused.
        String stranger = sponsorToken(newSponsor(unique("s-q89d-stranger")));
        refused(IncidentProcesses.SUSPEND, stranger, body("purchaseId", s.purchaseId(), "reason", "x"), 404);
        assertThat(run(IncidentProcesses.SUSPEND, shop, body("purchaseId", s.purchaseId(), "reason",
            "The shop is closed for repairs", "voidUnused", true)))
            .containsEntry("status", "SUSPENDED").containsEntry("paused", 2);
        assertThat(publicationStatus(first)).isEqualTo("PAUSED");
        assertThat(publicationStatus(second)).isEqualTo("PAUSED");
        assertThat(purchase(s.purchaseId())).containsEntry("status", "SUSPENDED")
            .containsEntry("suspended_by", s.shop()).containsEntry("void_unused", true);
        // Suspended twice is refused; the sponsor cannot resume its publication; no new one takes a share.
        refused(IncidentProcesses.SUSPEND, maker, body("purchaseId", s.purchaseId(), "reason", "again"), 422);
        assertThat(codes(refused("QB_PUBLICATION_RESUME", maker, body("publicationId", first), 422)))
            .containsExactly(BenefitCodes.PURCHASE_SUSPENDED);
        String token = maker;
        Instant now = clock.instant();
        Map<String, Object> save = complete(version(token, "Third noodles quiz"), "Free noodles three", now,
            now.plus(Duration.ofDays(7)));
        save.putAll(noodleQuiz(s.purchaseId(), 2));
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
            .contains(BenefitCodes.PURCHASE_NOT_USABLE);

        // The job voids the unused prize; its note is the suspension's reason.
        runVoidJob();
        assertThat(latest("qb_redemption_version", "redemption_id", "redemption_id = '" + prize + "'").getFirst())
            .containsEntry("status", "VOID").containsEntry("void_note", "The shop is closed for repairs");

        // Both sides read what became of it.
        assertThat(rows("qb.sponsor.benefit-purchases", maker, Map.of("purchaseId", s.purchaseId())))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", "SUSPENDED")
                .containsEntry("suspendReason", "The shop is closed for repairs")
                .containsEntry("allocated", 6).containsEntry("issued", 1).containsEntry("voided", 1)
                .containsEntry("collected", 0));
        assertThat(rows("qb.sponsor.benefit-orders", shop, Map.of("purchaseId", s.purchaseId())))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("issued", 1));

        // Resumed by the sponsor: confirmed again (the revision unchanged), the publication resumable.
        BigDecimal revision = (BigDecimal) purchase(s.purchaseId()).get("revision");
        assertThat(run(IncidentProcesses.RESUME, maker, body("purchaseId", s.purchaseId())))
            .containsEntry("status", "CONFIRMED");
        assertThat(purchase(s.purchaseId())).containsEntry("revision", revision).containsEntry("void_unused", false);
        assertThat(run("QB_PUBLICATION_RESUME", maker, body("publicationId", first)).get("status"))
            .isIn("LIVE", "SCHEDULED");
        refused(IncidentProcesses.RESUME, maker, body("purchaseId", s.purchaseId()), 422);
    }

    @Test
    void eitherSideRecordsAnIncidentAndHowItEnded() {
        Setting s = setting("incident", 5);
        Setting other = setting("other", 5);
        String maker = sponsorToken(s.maker());
        String shop = sponsorToken(s.shop());
        String publication = livePublication(s.maker(), "Incident noodles", noodleQuiz(s.purchaseId(), 2));
        String otherPublication = livePublication(other.maker(), "Other noodles", noodleQuiz(other.purchaseId(), 2));
        String winner = newTaker(unique("t-q89d-2"), "JP");
        play(winner, publication, true, true);
        play(newTaker(unique("t-q89d-3"), "JP"), otherPublication, true, true);
        String prize = String.valueOf(query("SELECT redemption_id FROM qb_redemption_version WHERE publication_id = "
            + "CAST(? AS uuid) LIMIT 1", publication).getFirst().get("redemption_id"));
        String otherPrize = String.valueOf(query("SELECT redemption_id FROM qb_redemption_version WHERE "
            + "publication_id = CAST(? AS uuid) LIMIT 1", otherPublication).getFirst().get("redemption_id"));

        String stranger = sponsorToken(newSponsor(unique("s-q89d-nobody")));
        refused(IncidentProcesses.RECORD, stranger, body("purchaseId", s.purchaseId(), "kind", "OTHER", "note", "x"),
            404);
        assertThat(codes(refused(IncidentProcesses.RECORD, maker, body("purchaseId", s.purchaseId(), "redemptionId",
            otherPrize, "kind", "NOT_FULFILLED", "note", "Not given"), 422))).containsExactly(BenefitCodes.INCIDENT_PRIZE);
        String incident = (String) run(IncidentProcesses.RECORD, maker, body("purchaseId", s.purchaseId(),
            "redemptionId", prize, "kind", "NOT_FULFILLED", "note", "The winner was told to come back tomorrow"))
            .get("incidentId");

        // Both sides see it, by side, never by id; the provider says how it ended, once.
        assertThat(rows("qb.sponsor.benefit-order-incidents", shop, Map.of("purchaseId", s.purchaseId())))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("recordedBy", "SPONSOR")
                .containsEntry("kind", "NOT_FULFILLED").containsEntry("resolution", null));
        assertThat(rows("qb.sponsor.benefit-incidents", stranger, Map.of("purchaseId", s.purchaseId()))).isEmpty();
        refused(IncidentProcesses.RESOLVE, stranger, body("incidentId", incident, "resolution", "x"), 404);
        assertThat(run(IncidentProcesses.RESOLVE, shop, body("incidentId", incident, "resolution",
            "Handed over the next day"))).containsEntry("resolved", true);
        assertThat(codes(refused(IncidentProcesses.RESOLVE, maker, body("incidentId", incident, "resolution", "y"),
            422))).containsExactly(BenefitCodes.INCIDENT_RESOLVED);
        assertThat(rows("qb.sponsor.benefit-incidents", maker, Map.of("purchaseId", s.purchaseId())))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("resolvedBy", "PROVIDER")
                .containsEntry("resolution", "Handed over the next day"));
    }
}
