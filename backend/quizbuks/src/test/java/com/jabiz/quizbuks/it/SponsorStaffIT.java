package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.QbRewards;
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
 * A sponsor's staff (docs/quizbuks/plans/Q39-sponsor-staff.md): a taker gives the sponsor their ID, the sponsor names
 * them; they show the sponsor's rotating code and answer claims from the printed one in the App, in their own name,
 * and nothing more; another sponsor's prizes are not theirs; a removal or a leave takes effect at once.
 */
class SponsorStaffIT extends PlayItSupport {

    private void allowRedeem(String sponsor) {
        String admin = TestTokens.bearer(tokens, "q39-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
        run(RewardMethodProcesses.SET, admin, body("userId", sponsor, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM), "note", "Q39 test"));
    }

    private String prizePublication(String sponsor, String name) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        overrides.put("redeemDescription", "A coffee at our counter");
        return livePublication(sponsor, name, overrides);
    }

    private record Winner(String userId, String token, String attemptId) {}

    private Winner winner(String name, String publicationId) {
        String userId = newTaker(name, "JP");
        String token = taker(userId);
        String attemptId = (String) start(token, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        assertThat(submit(token, attemptId, answers(true, true))).containsEntry("rewardStatus", "REDEEM");
        deliverEvents();
        return new Winner(userId, token, attemptId);
    }

    @SuppressWarnings("unchecked")
    private String prizeOf(Winner winner, String key) {
        return (String) ((List<Map<String, Object>>) run(RedemptionProcesses.CHECK, winner.token(),
            body("scannedKey", key)).get("prizes")).getFirst().get("redemptionId");
    }

    private Map<String, Object> redemption(String attemptId) {
        return latest("qb_redemption_version", "redemption_id", "attempt_id = '" + attemptId + "'").getFirst();
    }

    private List<Map<String, Object>> rows(String template, String token, Map<String, Object> params) {
        return items(template, token, Map.of("params", params, "limit", 200));
    }

    @Test
    void theSponsorNamesATakerWhoGaveTheirId() {
        String sponsor = newSponsor(unique("s-q39-name"));
        String sponsorToken = sponsorToken(sponsor);
        String kai = newTaker(unique("t-q39-kai"), "JP");

        // The ID the App shows is the user id; the sponsor sees the name before naming them (any case, any spaces).
        assertThat(run(StaffProcesses.FIND, sponsorToken, body("userId", " " + kai.toUpperCase() + " ")))
            .containsEntry("userId", kai).containsEntry("displayName", "Taker JP").containsEntry("onStaff", false);
        assertThat(run(StaffProcesses.ADD, sponsorToken, body("userId", kai))).containsEntry("changed", true);
        assertThat(run(StaffProcesses.ADD, sponsorToken, body("userId", kai))).containsEntry("changed", false);
        assertThat(run(StaffProcesses.FIND, sponsorToken, body("userId", kai))).containsEntry("onStaff", true);
        assertThat(query("SELECT sponsor_id, added_by FROM qb_sponsor_staff WHERE user_id = ?", kai)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("sponsor_id", sponsor).containsEntry("added_by", sponsor));
        assertThat(rows("qb.sponsor.staff", sponsorToken, Map.of())).singleElement().satisfies(row ->
            assertThat(row).containsEntry("userId", kai).containsEntry("displayName", "Taker JP"));
        assertThat(rows("qb.app.staff-of", taker(kai), Map.of())).singleElement().satisfies(row ->
            assertThat(row).containsEntry("sponsorId", sponsor).containsKey("sponsorCompany"));

        // Who cannot be named: an unknown ID, a person without a profile, a sponsor, the sponsor themselves, another
        // sponsor's staff member.
        assertThat(codes(refused(StaffProcesses.FIND, sponsorToken, body("userId", "no-such-id"), 422)))
            .containsExactly(RewardCodes.STAFF_NOT_A_TAKER);
        assertThat(codes(refused(StaffProcesses.ADD, sponsorToken, body("userId", newTaker(unique("t-q39-bare"), null)),
            422))).containsExactly(RewardCodes.STAFF_NOT_A_TAKER);
        assertThat(codes(refused(StaffProcesses.ADD, sponsorToken, body("userId", newSponsor(unique("s-q39-peer"))),
            422))).containsExactly(RewardCodes.STAFF_NOT_A_TAKER);
        assertThat(codes(refused(StaffProcesses.ADD, sponsorToken, body("userId", sponsor), 422)))
            .containsExactly(RewardCodes.STAFF_SELF);
        String other = sponsorToken(newSponsor(unique("s-q39-other")));
        assertThat(codes(refused(StaffProcesses.ADD, other, body("userId", kai), 422)))
            .containsExactly(RewardCodes.STAFF_ELSEWHERE);
        assertThat(rows("qb.sponsor.staff", other, Map.of())).isEmpty();
        // Only a sponsor names staff; a taker's ID lookup is refused.
        refused(StaffProcesses.FIND, taker(kai), body("userId", sponsor), 403);
        refused(StaffProcesses.ADD, taker(kai), body("userId", kai), 403);

        // A sponsor names at most MAX_STAFF.
        execute("INSERT INTO qb_sponsor_staff (staff_id, sponsor_id, user_id, added_at, added_by, version) "
            + "SELECT gen_random_uuid()::text, ?, 'filler-' || g || '-' || ?, now(), ?, 1 "
            + "FROM generate_series(1, ?) g", sponsor, sponsor, sponsor, QbRewards.MAX_STAFF - 1);
        assertThat(codes(refused(StaffProcesses.ADD, sponsorToken, body("userId", newTaker(unique("t-q39-one-more"),
            "JP")), 422))).containsExactly(RewardCodes.STAFF_FULL);
        execute("DELETE FROM qb_sponsor_staff WHERE user_id LIKE 'filler-%'");

        // The other sponsor may name Kai once Kai leaves; leaving twice changes nothing.
        assertThat(run(StaffProcesses.LEAVE, taker(kai), body())).containsEntry("changed", true);
        assertThat(run(StaffProcesses.LEAVE, taker(kai), body())).containsEntry("changed", false);
        assertThat(rows("qb.app.staff-of", taker(kai), Map.of())).isEmpty();
        assertThat(run(StaffProcesses.ADD, other, body("userId", kai))).containsEntry("changed", true);
        // Removing someone not on the staff changes nothing.
        assertThat(run(StaffProcesses.REMOVE, sponsorToken, body("userId", kai))).containsEntry("changed", false);
        assertThat(query("SELECT 1 FROM qb_sponsor_staff WHERE user_id = ?", kai)).hasSize(1);
    }

    @Test
    void aStaffMemberShowsTheRotatingCodeAndThePrizeIsHandedOverByThem() {
        String sponsor = newSponsor(unique("s-q39-rot"));
        allowRedeem(sponsor);
        String sponsorToken = sponsorToken(sponsor);
        String publicationId = prizePublication(sponsor, "Staff coffee");
        String kaiId = newTaker(unique("t-q39-staff"), "JP");
        run(StaffProcesses.ADD, sponsorToken, body("userId", kaiId));
        String kai = taker(kaiId);
        Winner mei = winner(unique("t-q39-mei"), publicationId);

        // Kai shows the sponsor's rotating code; a taker who is no staff cannot.
        Map<String, Object> issued = run(StaffProcesses.ISSUE, kai, body());
        assertThat(issued).containsEntry("kind", "ROTATING");
        String key = (String) issued.get("redeemKey");
        assertThat(query("SELECT sponsor_id, issued_by FROM qb_redeem_token WHERE issued_by = ?", kaiId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("sponsor_id", sponsor));
        assertThat(codes(refused(StaffProcesses.ISSUE, mei.token(), body(), 422)))
            .containsExactly(RewardCodes.NOT_STAFF);
        // Kai's role in the App is the counter only: the sponsor's own processes stay refused.
        refused(RedemptionProcesses.ISSUE, kai, body("kind", "PRINTED"), 403);

        // Mei scans it: the prize is the sponsor's, handed over by Kai.
        assertThat(run(RedemptionProcesses.CLAIM, mei.token(), body("scannedKey", key, "redemptionId",
            prizeOf(mei, key)))).containsEntry("status", "REDEEMED");
        assertThat(redemption(mei.attemptId())).containsEntry("redeemed_by", kaiId);
        assertThat(rows("qb.sponsor.redemptions", sponsorToken, Map.of())).singleElement().satisfies(row ->
            assertThat(row).containsEntry("status", "REDEEMED").containsEntry("redeemedByName", "Taker JP"));
        assertThat(rows("qb.app.staff-redemptions", kai, Map.of("since", clock.instant().toString())))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", "REDEEMED")
                .containsEntry("takerName", "Taker JP").doesNotContainKey("redeemedByName"));
        // A taker on no staff reads nothing of anyone's counter.
        assertThat(rows("qb.app.staff-redemptions", mei.token(), Map.of())).isEmpty();

        // The sponsor's own code still redeems in the sponsor's name, which the list does not show as a person.
        Winner ren = winner(unique("t-q39-ren"), publicationId);
        String own = (String) run(RedemptionProcesses.ISSUE, sponsorToken, body("kind", "ROTATING")).get("redeemKey");
        run(RedemptionProcesses.CLAIM, ren.token(), body("scannedKey", own, "redemptionId", prizeOf(ren, own)));
        assertThat(redemption(ren.attemptId())).containsEntry("redeemed_by", sponsor);
        // The counter shows the claims to answer and what Kai handed over: not what the sponsor did.
        assertThat(rows("qb.app.staff-redemptions", kai, Map.of())).extracting(row -> row.get("code"))
            .containsExactly(redemption(mei.attemptId()).get("code"));
        assertThat(rows("qb.sponsor.redemptions", sponsorToken, Map.of()))
            .filteredOn(row -> row.get("redeemedByName") == null).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "REDEEMED"));

        // Kai's own prize is not Kai's to hand over: someone else's code is needed.
        Winner kaiAsTaker = new Winner(kaiId, kai, null);
        String kaiPrizePublication = prizePublication(sponsor, "Staff tea");
        String kaiAttempt = (String) start(kai, kaiPrizePublication).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        assertThat(submit(kai, kaiAttempt, answers(true, true))).containsEntry("rewardStatus", "REDEEM");
        String fresh = (String) run(StaffProcesses.ISSUE, kai, body()).get("redeemKey");
        String kaiPrize = prizeOf(kaiAsTaker, fresh);
        assertThat(codes(refused(RedemptionProcesses.CLAIM, kai, body("scannedKey", fresh, "redemptionId", kaiPrize),
            422))).containsExactly(RewardCodes.STAFF_OWN_PRIZE);
        String sponsorCode = (String) run(RedemptionProcesses.ISSUE, sponsorToken, body("kind", "ROTATING"))
            .get("redeemKey");
        assertThat(run(RedemptionProcesses.CLAIM, kai, body("scannedKey", sponsorCode, "redemptionId", kaiPrize)))
            .containsEntry("status", "REDEEMED");

        // Removed: Kai's codes stop working at once, and Kai cannot show another.
        String last = (String) run(StaffProcesses.ISSUE, kai, body()).get("redeemKey");
        assertThat(run(StaffProcesses.REMOVE, sponsorToken, body("userId", kaiId))).containsEntry("changed", true);
        assertThat(query("SELECT 1 FROM qb_redeem_token WHERE issued_by = ?", kaiId)).isEmpty();
        Winner sho = winner(unique("t-q39-sho"), publicationId);
        assertThat(codes(refused(RedemptionProcesses.CHECK, sho.token(), body("scannedKey", last), 422)))
            .containsExactly(RewardCodes.REDEEM_TOKEN_INVALID);
        assertThat(codes(refused(StaffProcesses.ISSUE, kai, body(), 422))).containsExactly(RewardCodes.NOT_STAFF);
        assertThat(rows("qb.app.staff-redemptions", kai, Map.of())).isEmpty();
    }

    @Test
    void aStaffMemberAnswersClaimsFromThePrintedCodeOfTheirSponsorOnly() {
        String sponsor = newSponsor(unique("s-q39-print"));
        allowRedeem(sponsor);
        String sponsorToken = sponsorToken(sponsor);
        String publicationId = prizePublication(sponsor, "Poster coffee");
        String kaiId = newTaker(unique("t-q39-desk"), "JP");
        run(StaffProcesses.ADD, sponsorToken, body("userId", kaiId));
        String kai = taker(kaiId);
        Winner aki = winner(unique("t-q39-aki"), publicationId);
        Winner ren = winner(unique("t-q39-ren"), publicationId);

        // The printed code stays the sponsor's: two claims wait at the counter.
        String printed = (String) run(RedemptionProcesses.ISSUE, sponsorToken, body("kind", "PRINTED")).get("redeemKey");
        String akiPrize = prizeOf(aki, printed);
        String renPrize = prizeOf(ren, printed);
        run(RedemptionProcesses.CLAIM, aki.token(), body("scannedKey", printed, "redemptionId", akiPrize));
        run(RedemptionProcesses.CLAIM, ren.token(), body("scannedKey", printed, "redemptionId", renPrize));
        List<Map<String, Object>> claims = items("qb.app.staff-redemptions", kai, Map.of("limit", 50,
            "filters", List.of(Map.of("field", "status", "op", "eq", "value", "CLAIMED"))));
        assertThat(claims).extracting(row -> row.get("redemptionId")).containsExactlyInAnyOrder(akiPrize, renPrize);

        // Kai confirms Aki's and declines Ren's; the takers are told as by the sponsor.
        assertThat(run(StaffProcesses.CONFIRM, kai, body("redemptionId", akiPrize))).containsEntry("status", "REDEEMED");
        assertThat(redemption(aki.attemptId())).containsEntry("redeemed_by", kaiId);
        assertThat(run(StaffProcesses.DECLINE, kai, body("redemptionId", renPrize))).containsEntry("status", "DUE");
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(aki.userId())).contains("REDEEM_REDEEMED");
        assertThat(InboxItSupport.kindsOf(ren.userId())).contains("REDEEM_DECLINED");
        // Declined, the prize is due again: confirming it now is a lapsed claim, as for the sponsor.
        assertThat(codes(refused(StaffProcesses.CONFIRM, kai, body("redemptionId", renPrize), 422)))
            .containsExactly(RewardCodes.REDEMPTION_CLAIM_LAPSED);

        // Another sponsor's prize is as if there were none; so is any prize to a taker on no staff.
        String elsewhere = newSponsor(unique("s-q39-elsewhere"));
        allowRedeem(elsewhere);
        Winner sho = winner(unique("t-q39-sho"), prizePublication(elsewhere, "Elsewhere coffee"));
        String elsewherePrinted = (String) run(RedemptionProcesses.ISSUE, sponsorToken(elsewhere),
            body("kind", "PRINTED")).get("redeemKey");
        String shoPrize = prizeOf(sho, elsewherePrinted);
        run(RedemptionProcesses.CLAIM, sho.token(), body("scannedKey", elsewherePrinted, "redemptionId", shoPrize));
        refused(StaffProcesses.CONFIRM, kai, body("redemptionId", shoPrize), 404);
        refused(StaffProcesses.DECLINE, kai, body("redemptionId", shoPrize), 404);
        assertThat(codes(refused(StaffProcesses.CONFIRM, aki.token(), body("redemptionId", renPrize), 422)))
            .containsExactly(RewardCodes.NOT_STAFF);

        // Kai leaves: the counter is no longer theirs.
        run(RedemptionProcesses.CLAIM, ren.token(), body("scannedKey", printed, "redemptionId", renPrize));
        run(StaffProcesses.LEAVE, kai, body());
        assertThat(codes(refused(StaffProcesses.CONFIRM, kai, body("redemptionId", renPrize), 422)))
            .containsExactly(RewardCodes.NOT_STAFF);
        assertThat(run(RedemptionProcesses.CONFIRM, sponsorToken, body("redemptionId", renPrize)))
            .containsEntry("status", "REDEEMED");
    }

    @Test
    void aBannedSponsorsCounterIsClosed() {
        String sponsor = newSponsor(unique("s-q39-banned"));
        String kaiId = newTaker(unique("t-q39-banned-staff"), "JP");
        run(StaffProcesses.ADD, sponsorToken(sponsor), body("userId", kaiId));
        String kai = taker(kaiId);
        run(StaffProcesses.ISSUE, kai, body());

        String admin = userWithRole(unique("q39-boss"), com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(admin, com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER), body("userId", sponsor,
            "reason", "Fraud"));
        assertThat(codes(refused(StaffProcesses.ISSUE, kai, body(), 422)))
            .containsExactly(RewardCodes.STAFF_SPONSOR_BANNED);
        // Kai may still leave.
        assertThat(run(StaffProcesses.LEAVE, kai, body())).containsEntry("changed", true);
    }
}
