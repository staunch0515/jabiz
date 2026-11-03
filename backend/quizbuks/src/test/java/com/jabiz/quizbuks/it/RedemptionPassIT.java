package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.PassProcesses;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.quizbuks.reward.RewardCodes;
import com.jabiz.quizbuks.reward.StaffProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The taker's pass (Q89c, docs/quizbuks/plans/Q89-sponsored-benefits.md, D-Q89-10): the taker shows a QR and six
 * digits, good for a minute and once; the sponsor or its staff read it and hand the prize over; asking again replaces
 * it; another counter finds nothing; a pass and the sponsor's code collecting the same prize succeed once; only hashes
 * are stored.
 */
class RedemptionPassIT extends PlayItSupport {

    private void allowRedeem(String sponsor) {
        String admin = TestTokens.bearer(tokens, "q89c-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
        run(RewardMethodProcesses.SET, admin, body("userId", sponsor, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM), "note", "Q89c test"));
    }

    private String prizePublication(String sponsor, String name) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        overrides.put("redeemDescription", "A coffee at our counter");
        return livePublication(sponsor, name, overrides);
    }

    private record Winner(String userId, String token, String redemptionId) {}

    private Winner winner(String name, String publicationId) {
        String userId = newTaker(name, "JP");
        String token = taker(userId);
        String attemptId = (String) start(token, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        assertThat(submit(token, attemptId, answers(true, true))).containsEntry("rewardStatus", "REDEEM");
        String redemptionId = String.valueOf(latest("qb_redemption_version", "redemption_id",
            "attempt_id = '" + attemptId + "'").getFirst().get("redemption_id"));
        return new Winner(userId, token, redemptionId);
    }

    private Map<String, Object> redemption(String redemptionId) {
        return latest("qb_redemption_version", "redemption_id", "redemption_id = '" + redemptionId + "'").getFirst();
    }

    private Map<String, Object> pass(Winner winner) {
        return run(PassProcesses.ISSUE, winner.token(), body("redemptionId", winner.redemptionId()));
    }

    @Test
    void theSponsorReadsThePassOrItsDigitsAndHandsThePrizeOver() {
        String sponsor = newSponsor(unique("s-q89c-cafe"));
        allowRedeem(sponsor);
        String sponsorToken = sponsorToken(sponsor);
        String publicationId = prizePublication(sponsor, "Pass coffee");
        Winner mei = winner(unique("t-q89c-mei"), publicationId);

        Map<String, Object> issued = pass(mei);
        String key = (String) issued.get("passKey");
        String digits = (String) issued.get("passDigits");
        assertThat(key).hasSizeGreaterThan(20);
        assertThat(digits).matches("\\d{6}");
        // Only the hashes are kept; the operation records mask both.
        assertThat(query("SELECT pass_hash, digits_hash FROM qb_redemption_pass WHERE redemption_id = ?",
            mei.redemptionId())).singleElement().satisfies(row -> assertThat(row.values())
                .noneMatch(v -> String.valueOf(v).contains(key) || String.valueOf(v).contains(digits)));
        assertThat(query("SELECT coalesce(r.output::text, '') AS s FROM op_process p JOIN op_process_result r "
            + "ON p.process_seq_id = r.process_seq_id WHERE p.process_name = ?", PassProcesses.ISSUE))
            .allSatisfy(row -> assertThat((String) row.get("s")).doesNotContain(key));

        // The counter reads it by the QR and by the digits; the taker cannot read their own.
        Map<String, Object> byKey = run(PassProcesses.CHECK, sponsorToken, body("scannedPass", key));
        assertThat(byKey).containsEntry("redemptionId", mei.redemptionId()).containsEntry("status", "DUE")
            .containsEntry("publicationName", "Pass coffee").containsEntry("description", "A coffee at our counter");
        assertThat(byKey.get("takerName")).isNotNull();
        assertThat(run(PassProcesses.CHECK, sponsorToken, body("typedDigits", digits.substring(0, 3) + " "
            + digits.substring(3)))).containsEntry("passId", byKey.get("passId"));
        refused(PassProcesses.CHECK, mei.token(), body("scannedPass", key), 403);
        assertThat(codes(refused(PassProcesses.CHECK, sponsorToken, body(), 422)))
            .containsExactly(RewardCodes.PASS_INPUT);

        // Another sponsor's counter finds nothing: by the QR it is not theirs, by the digits there is none.
        String other = sponsorToken(newSponsor(unique("s-q89c-other")));
        refused(PassProcesses.CHECK, other, body("scannedPass", key), 404);
        assertThat(codes(refused(PassProcesses.CHECK, other, body("typedDigits", digits), 422)))
            .containsExactly(RewardCodes.PASS_INVALID);
        refused(PassProcesses.REDEEM, other, body("passId", byKey.get("passId")), 404);

        // Handed over: redeemed by the pass, once.
        assertThat(run(PassProcesses.REDEEM, sponsorToken, body("passId", byKey.get("passId"))))
            .containsEntry("status", "REDEEMED");
        assertThat(redemption(mei.redemptionId())).containsEntry("status", "REDEEMED")
            .containsEntry("claim_kind", "PASS").containsEntry("redeemed_by", sponsor);
        assertThat(codes(refused(PassProcesses.REDEEM, sponsorToken, body("passId", byKey.get("passId")), 422)))
            .containsExactly(RewardCodes.PASS_INVALID);
        assertThat(codes(refused(PassProcesses.CHECK, sponsorToken, body("scannedPass", key), 422)))
            .containsExactly(RewardCodes.PASS_INVALID);
        // A collected prize has no pass to show.
        assertThat(codes(refused(PassProcesses.ISSUE, mei.token(), body("redemptionId", mei.redemptionId()), 422)))
            .containsExactly(RewardCodes.REDEMPTION_STATE);
    }

    @Test
    void aPassLastsAMinuteAndAskingAgainReplacesIt() {
        String sponsor = newSponsor(unique("s-q89c-time"));
        allowRedeem(sponsor);
        String sponsorToken = sponsorToken(sponsor);
        String publicationId = prizePublication(sponsor, "Timed coffee");
        Winner ren = winner(unique("t-q89c-ren"), publicationId);
        Winner aki = winner(unique("t-q89c-aki"), publicationId);

        // Somebody else's prize is none of the caller's.
        refused(PassProcesses.ISSUE, aki.token(), body("redemptionId", ren.redemptionId()), 404);

        String first = (String) pass(ren).get("passKey");
        Map<String, Object> read = run(PassProcesses.CHECK, sponsorToken, body("scannedPass", first));
        // Asked again: the first stops, the second works, and the pass read before cannot be redeemed.
        Map<String, Object> second = pass(ren);
        assertThat(codes(refused(PassProcesses.CHECK, sponsorToken, body("scannedPass", first), 422)))
            .containsExactly(RewardCodes.PASS_INVALID);
        assertThat(run(PassProcesses.CHECK, sponsorToken, body("scannedPass", second.get("passKey"))))
            .containsEntry("redemptionId", ren.redemptionId());
        // The same row, renewed: its id stays; the key read first is gone.
        assertThat(query("SELECT count(*) AS n FROM qb_redemption_pass WHERE redemption_id = ?", ren.redemptionId())
            .getFirst().get("n")).isEqualTo(1L);

        // A minute later it has run out, by the QR and by the digits.
        clock.advance(Duration.ofSeconds(61));
        assertThat(codes(refused(PassProcesses.CHECK, sponsorToken, body("scannedPass", second.get("passKey")), 422)))
            .containsExactly(RewardCodes.PASS_INVALID);
        assertThat(codes(refused(PassProcesses.CHECK, sponsorToken, body("typedDigits", second.get("passDigits")),
            422))).containsExactly(RewardCodes.PASS_INVALID);
        assertThat(codes(refused(PassProcesses.REDEEM, sponsorToken, body("passId", read.get("passId")), 422)))
            .containsExactly(RewardCodes.PASS_INVALID);
        assertThat(redemption(ren.redemptionId())).containsEntry("status", "DUE");
    }

    @Test
    void theStaffHandOverByThePassAndAPrizeIsCollectedOnce() {
        String sponsor = newSponsor(unique("s-q89c-staff"));
        allowRedeem(sponsor);
        String sponsorToken = sponsorToken(sponsor);
        String publicationId = prizePublication(sponsor, "Staff pass coffee");
        String kaiId = newTaker(unique("t-q89c-kai"), "JP");
        run(StaffProcesses.ADD, sponsorToken, body("userId", kaiId));
        String kai = taker(kaiId);
        Winner mei = winner(unique("t-q89c-mei2"), publicationId);

        // Kai reads Mei's pass as staff; a taker on no staff cannot.
        String key = (String) pass(mei).get("passKey");
        Map<String, Object> read = run(PassProcesses.STAFF_CHECK, kai, body("scannedPass", key));
        assertThat(read).containsEntry("redemptionId", mei.redemptionId());
        assertThat(codes(refused(PassProcesses.STAFF_CHECK, mei.token(), body("scannedPass", key), 422)))
            .containsExactly(RewardCodes.NOT_STAFF);

        // Meanwhile the prize is collected by the sponsor's rotating code: the pass then hands over nothing.
        String rotating = (String) run(RedemptionProcesses.ISSUE, sponsorToken, body("kind", "ROTATING"))
            .get("redeemKey");
        assertThat(run(RedemptionProcesses.CLAIM, mei.token(), body("scannedKey", rotating, "redemptionId",
            mei.redemptionId()))).containsEntry("status", "REDEEMED");
        assertThat(codes(refused(PassProcesses.STAFF_REDEEM, kai, body("passId", read.get("passId")), 422)))
            .containsExactly(RewardCodes.REDEMPTION_STATE);
        assertThat(redemption(mei.redemptionId())).containsEntry("claim_kind", "ROTATING");

        // Another prize, handed over by Kai through the pass, in Kai's name.
        Winner ren = winner(unique("t-q89c-ren2"), publicationId);
        Map<String, Object> renPass = pass(ren);
        Map<String, Object> renRead = run(PassProcesses.STAFF_CHECK, kai, body("typedDigits",
            renPass.get("passDigits")));
        assertThat(run(PassProcesses.STAFF_REDEEM, kai, body("passId", renRead.get("passId"))))
            .containsEntry("status", "REDEEMED");
        assertThat(redemption(ren.redemptionId())).containsEntry("claim_kind", "PASS").containsEntry("redeemed_by",
            kaiId);

        // Kai's own prize is handed over by someone else.
        String kaiAttempt = (String) start(kai, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        submit(kai, kaiAttempt, answers(true, true));
        String kaiPrize = String.valueOf(latest("qb_redemption_version", "redemption_id",
            "attempt_id = '" + kaiAttempt + "'").getFirst().get("redemption_id"));
        String kaiKey = (String) run(PassProcesses.ISSUE, kai, body("redemptionId", kaiPrize)).get("passKey");
        assertThat(codes(refused(PassProcesses.STAFF_CHECK, kai, body("scannedPass", kaiKey), 422)))
            .containsExactly(RewardCodes.STAFF_OWN_PRIZE);
    }

    @Test
    void guessingDigitsIsLimited() {
        String sponsor = newSponsor(unique("s-q89c-guess"));
        String sponsorToken = sponsorToken(sponsor);
        int refused = 0;
        for (int i = 0; i < 11; i++) {
            int status = call(PassProcesses.CHECK, sponsorToken, body("typedDigits", String.format("%06d", i)))
                .returnResult(String.class).getStatus().value();
            assertThat(status).isIn(422, 429);
            refused += status == 429 ? 1 : 0;
        }
        assertThat(refused).as("the 11th check in a minute").isEqualTo(1);
    }
}
