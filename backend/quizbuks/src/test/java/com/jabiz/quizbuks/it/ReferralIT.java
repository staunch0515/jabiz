package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.CertificationProcesses;
import com.jabiz.quizbuks.certification.Certifications;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.publishing.RefereeRules;
import com.jabiz.quizbuks.publishing.ReferralPolicy;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.referral.QbReferrals;
import com.jabiz.quizbuks.referral.ReferralCodes;
import com.jabiz.quizbuks.referral.ReferralProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Referral advertising (Q70b, docs/quizbuks/plans/Q70-referrals-and-rounds.md): a holder of the referrer
 * certification makes their link to a publication that welcomes referrals; a friend who opens it and passes in time
 * earns the referrer the publication's referral reward, paid by the job afterwards, the publication's way; what does
 * not count (the sponsor's rules, the fixed conditions, the limits) is simply not recorded.
 */
class ReferralIT extends PlayItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "q70-hq", QbPermissions.CERTIFICATION_MANAGE, QbPermissions.CERTIFICATION_ADMIN,
            QbPermissions.ADMIN_REWARD_METHODS, QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    /** A taker who holds the referrer certification (granted by hand, as an operator may). */
    private String referrer(String name) {
        String userId = newTaker(name, "JP");
        run(CertificationProcesses.GRANT, hq(), body("userId", userId, "certification", Certifications.REFERRER.key(),
            "reason", "Q70 test"));
        return userId;
    }

    private static Map<String, Object> referral(String method, int amount, int quota, String requires,
        List<String> rules, String description) {
        Map<String, Object> referral = new LinkedHashMap<>();
        referral.put("quota", quota);
        referral.put("method", method);
        referral.put("amount", amount);
        referral.put("description", description);
        referral.put("referrerRequires", requires);
        referral.put("refereeRules", rules);
        referral.put("days", 7);
        return referral;
    }

    private String welcoming(String sponsor, String name, Map<String, Object> referral, Map<String, Object> more) {
        Map<String, Object> overrides = new LinkedHashMap<>(more);
        overrides.put("referral", referral);
        return livePublication(sponsor, name, overrides);
    }

    private String link(String userId, String publicationId) {
        return (String) run(ReferralProcesses.LINK, taker(userId), body("publicationId", publicationId)).get("linkKey");
    }

    private Map<String, Object> claim(String userId, String key) {
        return run(ReferralProcesses.CLAIM, taker(userId), body("linkKey", key));
    }

    private static Map<String, Object> referralOf(String publicationId, String refereeId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_referral WHERE publication_id = ? AND referee_id = ?",
            publicationId, refereeId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> reward(String referralId) {
        return outcome(systemRun(ReferralProcesses.REWARD, body("referralId", referralId)));
    }

    private Map<String, Object> expire(String referralId) {
        return outcome(systemRun(ReferralProcesses.EXPIRE, body("referralId", referralId)));
    }

    private static Map<String, Object> outcome(Object output) {
        ReferralProcesses.ReferralOutput out = (ReferralProcesses.ReferralOutput) output;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", out.status());
        map.put("changed", out.changed());
        return map;
    }

    private void deliver() {
        while (deliverer.deliverPending().block() > 0) {
            // until the outbox is empty
        }
    }

    private static List<String> messageKinds(String recipient) {
        return query("SELECT kind FROM qb_message WHERE recipient_id = ? ORDER BY created_at", recipient).stream()
            .map(row -> (String) row.get("kind")).toList();
    }

    @Test
    void aFriendWhoPassesInTimeEarnsTheReferrerTheSponsorsKudos() {
        String sponsor = newSponsor("q70-cafe");
        credit(sponsor, 10_000);
        String pub = welcoming(sponsor, "Q70 autumn menu", referral(RewardMethod.PLATFORM, 300, 2,
            ReferralPolicy.ANSWERED, List.of(RefereeRules.NOT_REGULAR), null), Map.of());
        // D-Q70-13: the most it spends counts the referral rewards too.
        assertThat(((BigDecimal) query("SELECT max_spend FROM qb_publication_version WHERE publication_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", pub).getFirst().get("max_spend")).intValue()).isGreaterThan(600);

        String wang = referrer("q70-wang");
        // The publication asks its referrers to have answered it first.
        assertThat(codes(refused(ReferralProcesses.LINK, taker(wang), body("publicationId", pub), 422)))
            .containsExactly(ReferralCodes.REQUIREMENT);
        play(wang, pub, true, true);
        String key = link(wang, pub);
        assertThat(key).hasSize(32);
        // Asking again gives the same link.
        assertThat(link(wang, pub)).isEqualTo(key);
        // Somebody without the referrer certification makes none.
        String plain = newTaker("q70-plain", "JP");
        play(plain, pub, true, true);
        assertThat(codes(refused(ReferralProcesses.LINK, taker(plain), body("publicationId", pub), 422)))
            .containsExactly(ReferralCodes.NOT_REFERRER);
        // Nor does the sponsor for their own.
        assertThat(codes(refused(ReferralProcesses.LINK, taker(sponsor), body("publicationId", pub), 422)))
            .containsExactly(ReferralCodes.OWN);

        String li = newTaker("q70-li", "JP");
        Map<String, Object> claimed = claim(li, key);
        assertThat(claimed).containsEntry("counted", true).containsEntry("publicationId", pub);
        // Opening the link again shows the same referral.
        assertThat(claim(li, key)).containsEntry("counted", true).containsEntry("expiresAt", claimed.get("expiresAt"));
        Map<String, Object> row = referralOf(pub, li);
        assertThat(row).containsEntry("status", QbReferrals.REFERRED).containsEntry("referrer_id", wang);
        String referralId = (String) row.get("referral_id");

        // The friend's page says they were referred and until when; the referrer's lists show it without the friend.
        Map<String, Object> page = items("qb.app.publication", taker(li), Map.of("params", Map.of("publicationId",
            pub, "at", clock.instant().toString(), "country", "JP"))).getFirst();
        assertThat(page).containsEntry("myReferralStatus", QbReferrals.REFERRED);
        assertThat(String.valueOf(page.get("referral"))).contains("\"quota\":2");
        assertThat(items("qb.app.publication", taker(plain), Map.of("params", Map.of("publicationId", pub, "at",
            clock.instant().toString(), "country", "JP"))).getFirst()).containsEntry("myReferralStatus", null);
        List<Map<String, Object>> mine = items("qb.app.referrals", taker(wang), Map.of());
        assertThat(mine).singleElement().satisfies(r -> assertThat(r).containsEntry("publicationId", pub)
            .containsEntry("status", QbReferrals.REFERRED).doesNotContainKeys("refereeId", "referrerId"));
        assertThat(items("qb.app.referrals", taker(li), Map.of())).isEmpty();
        assertThat(items("qb.app.referral-links", taker(wang), Map.of())).singleElement().satisfies(r ->
            assertThat(List.of(r.get("linkKey"), ((Number) r.get("broughtByMe")).intValue(),
                ((Number) r.get("placesLeft")).intValue())).containsExactly(key, 1, 1));
        assertThat(items("qb.sponsor.referrals", sponsorToken(sponsor), Map.of())).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("publicationId", pub).doesNotContainKeys("refereeId"));

        // Not passed yet: the reward waits.
        assertThat(reward(referralId)).containsEntry("status", QbReferrals.REFERRED).containsEntry("changed", false);
        BigDecimal before = ledger("2120", wang);
        play(li, pub, true, true);
        assertThat(reward(referralId)).containsEntry("status", QbReferrals.REWARDED).containsEntry("changed", true);
        assertThat(ledger("2120", wang).subtract(before)).isEqualByComparingTo("300");
        Map<String, Object> paid = referralOf(pub, li);
        assertThat(paid).containsEntry("status", QbReferrals.REWARDED);
        assertThat(paid.get("ledger_transaction_id")).isNotNull();
        assertThat(query("SELECT source_entity, source_id FROM ledger_transaction_version WHERE transaction_id = ?::uuid",
            paid.get("ledger_transaction_id")).getFirst()).containsEntry("source_entity", QbReferrals.REFERRAL)
            .containsEntry("source_id", referralId);
        // Once only.
        assertThat(reward(referralId)).containsEntry("changed", false);
        assertThat(ledger("2120", wang).subtract(before)).isEqualByComparingTo("300");
        assertBooksBalance();

        deliver();
        assertThat(messageKinds(wang)).contains("REFERRAL_CLAIMED", "REFERRAL_REWARDED");
        assertThat(query("SELECT action FROM qb_message WHERE recipient_id = ? AND kind = 'REFERRAL_REWARDED'", wang)
            .getFirst()).containsEntry("action", "REFERRALS");
    }

    @Test
    void whatDoesNotCountIsNotRecorded() {
        String sponsor = newSponsor("q70-shop");
        credit(sponsor, 10_000);
        String summer = livePublication(sponsor, "Q70 summer");
        String pub = welcoming(sponsor, "Q70 rules", referral(RewardMethod.PLATFORM, 100, 4, ReferralPolicy.NONE,
            List.of(RefereeRules.NOT_REGULAR), null), Map.of());
        String wang = referrer("q70-wang-2");
        String key = link(wang, pub);

        // The sponsor chose "not a regular": somebody who passed another of theirs is not counted.
        String regular = newTaker("q70-regular", "JP");
        play(regular, summer, true, true);
        assertThat(claim(regular, key)).containsEntry("counted", false);
        assertThat(referralOf(pub, regular)).isNull();
        // The referrer is not their own referee; an attempt started before is not a referral.
        assertThat(claim(wang, key)).containsEntry("counted", false);
        String early = newTaker("q70-early", "JP");
        start(taker(early), pub);
        assertThat(claim(early, key)).containsEntry("counted", false);

        // At most three a day from one referrer (D-Q70-19b), and the quota.
        for (int i = 1; i <= 3; i++) {
            assertThat(claim(newTaker("q70-friend-" + i, "JP"), key)).containsEntry("counted", true);
        }
        String fourth = newTaker("q70-friend-4", "JP");
        assertThat(claim(fourth, key)).containsEntry("counted", false);
        clock.advance(Duration.ofHours(25));
        String otherReferrer = referrer("q70-zhang");
        String otherKey = link(otherReferrer, pub);
        assertThat(claim(fourth, otherKey)).containsEntry("counted", true);
        // Four referred: the quota is full for everybody.
        assertThat(claim(newTaker("q70-friend-5", "JP"), otherKey)).containsEntry("counted", false);
        assertThat(codes(refused(ReferralProcesses.LINK, taker(referrer("q70-late")), body("publicationId", pub), 422)))
            .containsExactly(ReferralCodes.QUOTA_FULL);

        // Without the rule, a regular counts: the platform adds no rule of its own.
        String open = welcoming(sponsor, "Q70 anybody", referral(RewardMethod.PLATFORM, 100, 4, ReferralPolicy.NONE,
            List.of(), null), Map.of());
        assertThat(claim(regular, link(wang, open))).containsEntry("counted", true);
        // An unknown key is not found.
        refused(ReferralProcesses.CLAIM, taker(regular), body("linkKey", "0".repeat(32)), 404);
    }

    @Test
    void aReferralEndsWhenItsFriendFailsOrItsTimeIsUp() {
        String sponsor = newSponsor("q70-school");
        credit(sponsor, 10_000);
        // Running longer than the referral, so that a pass after it expired is possible.
        String pub = welcoming(sponsor, "Q70 ends", referral(RewardMethod.PLATFORM, 100, 3, ReferralPolicy.NONE,
            List.of(), null), Map.of("endAt", clock.instant().plus(Duration.ofDays(20)).toString()));
        String wang = referrer("q70-wang-3");
        String key = link(wang, pub);
        String failer = newTaker("q70-failer", "JP");
        String slow = newTaker("q70-slow", "JP");
        claim(failer, key);
        claim(slow, key);
        String failed = (String) referralOf(pub, failer).get("referral_id");
        String late = (String) referralOf(pub, slow).get("referral_id");

        play(failer, pub, false, false);
        assertThat(expire(failed)).containsEntry("status", QbReferrals.EXPIRED).containsEntry("changed", true);
        // Still in time and not answered: it waits.
        assertThat(expire(late)).containsEntry("status", QbReferrals.REFERRED).containsEntry("changed", false);
        // Delivered now: a message is not made from an event older than a week.
        deliver();
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        assertThat(expire(late)).containsEntry("status", QbReferrals.EXPIRED);
        // Passing after the time is up earns nothing.
        play(slow, pub, true, true);
        assertThat(reward(late)).containsEntry("status", QbReferrals.EXPIRED).containsEntry("changed", false);
        deliver();
        assertThat(messageKinds(wang)).filteredOn("REFERRAL_EXPIRED"::equals).hasSize(2);
    }

    @Test
    void theSponsorsOwnMethodsWriteARecordWhoseSourceIsTheReferral() {
        String sponsor = newSponsor("q70-methods");
        credit(sponsor, 10_000);
        run(RewardMethodProcesses.SET, hq(), body("userId", sponsor, "methods", List.of(RewardMethod.PLATFORM,
            RewardMethod.SPONSOR_CASH, RewardMethod.OFFLINE_REDEEM, RewardMethod.DIGITAL_PRIZE), "note", "Q70"));
        String wang = referrer("q70-wang-4");
        Map<String, String> tables = Map.of(RewardMethod.SPONSOR_CASH, "qb_cash_reward_version",
            RewardMethod.OFFLINE_REDEEM, "qb_redemption_version", RewardMethod.DIGITAL_PRIZE,
            "qb_digital_prize_version");
        for (String method : List.of(RewardMethod.SPONSOR_CASH, RewardMethod.OFFLINE_REDEEM,
            RewardMethod.DIGITAL_PRIZE)) {
            Map<String, Object> more = new LinkedHashMap<>();
            if (RewardMethod.DIGITAL_PRIZE.equals(method)) {
                more.put("rewardMethod", RewardMethod.DIGITAL_PRIZE);
                more.put("digitalDelivery", "SPONSOR_SENT");
                more.put("digitalDescription", "A gift card");
                more.put("digitalSendDays", 3);
            }
            String pub = welcoming(sponsor, "Q70 " + method, referral(method, 500, 2, ReferralPolicy.NONE, List.of(),
                RewardMethod.SPONSOR_CASH.equals(method) ? null : "A free treatment"), more);
            String friend = newTaker("q70-" + method.toLowerCase(java.util.Locale.ROOT), "JP");
            claim(friend, link(wang, pub));
            play(friend, pub, true, true);
            String referralId = (String) referralOf(pub, friend).get("referral_id");
            BigDecimal ledgerBefore = ledger("2120", wang);
            assertThat(reward(referralId)).containsEntry("status", QbReferrals.REWARDED);
            Map<String, Object> record = query("SELECT * FROM " + tables.get(method) + " WHERE attempt_id = ?",
                referralId).getFirst();
            assertThat(record).as(method).containsEntry("source_kind", "REFERRAL").containsEntry("user_id", wang);
            assertThat(((BigDecimal) record.get("amount")).intValue()).isEqualTo(500);
            assertThat(referralOf(pub, friend).get("reward_record_id")).isNotNull();
            // The referrer reads it as a result's reward; nobody else does.
            Map<String, Object> view = run(ReferralProcesses.VIEW, taker(wang), body("referralId", referralId));
            assertThat(view).containsEntry("status", QbReferrals.REWARDED).containsEntry("rewardMethod", method);
            String part = Map.of(RewardMethod.SPONSOR_CASH, "cash", RewardMethod.OFFLINE_REDEEM, "redeem",
                RewardMethod.DIGITAL_PRIZE, "digital").get(method);
            assertThat(view.get(part)).as(method).isNotNull();
            refused(ReferralProcesses.VIEW, taker(friend), body("referralId", referralId), 404);
            // Neither the wallet nor the ledger is touched.
            assertThat(ledger("2120", wang)).isEqualByComparingTo(ledgerBefore);
        }
        assertBooksBalance();
    }

    @Test
    void aShortWalletKeepsTheReferralWaitingUntilItIsPaid() {
        String sponsor = newSponsor("q70-short");
        credit(sponsor, 2_000);
        String pub = welcoming(sponsor, "Q70 short", referral(RewardMethod.PLATFORM, 5_000, 1, ReferralPolicy.NONE,
            List.of(), null), Map.of());
        String wang = referrer("q70-wang-5");
        String friend = newTaker("q70-short-friend", "JP");
        claim(friend, link(wang, pub));
        play(friend, pub, true, true);
        String referralId = (String) referralOf(pub, friend).get("referral_id");
        assertThat(reward(referralId)).containsEntry("status", QbReferrals.REFERRED).containsEntry("changed", false);
        assertThat(referralOf(pub, friend)).containsEntry("funds_short", true);
        deliver();
        assertThat(messageKinds(sponsor)).contains("REFERRAL_FUNDS_SHORT");
        credit(sponsor, 10_000);
        assertThat(reward(referralId)).containsEntry("status", QbReferrals.REWARDED);
        assertThat(referralOf(pub, friend)).containsEntry("funds_short", false);
        assertBooksBalance();
    }

    @Test
    void anInvitedPublicationOrASurveyAskingAPassIsAProblemOfTheDraft() {
        String sponsor = newSponsor("q70-draft");
        String token = sponsorToken(sponsor);
        String versionId = version(token, "Q70 draft quiz");
        java.time.Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Q70 draft", now, now.plus(Duration.ofDays(7)));
        save.put("audience", "INVITED");
        save.put("referral", referral(RewardMethod.PLATFORM, 100, 0, ReferralPolicy.NONE, List.of("FRIENDS"), null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problems = (List<Map<String, Object>>) run("QB_PUBLICATION_SAVE", token, save)
            .get("problems");
        assertThat(problems).extracting(p -> p.get("code")).contains(PublishingCodes.REFERRAL_INVITED,
            PublishingCodes.REFERRAL_QUOTA_RANGE, PublishingCodes.REFERRAL_RULE_INVALID);
        // The referral reward's method is one the sponsor may use, as the publication's is.
        save.put("audience", "PUBLIC");
        save.put("referral", referral(RewardMethod.SPONSOR_CASH, 100, 5, ReferralPolicy.NONE, List.of(), null));
        refused("QB_PUBLICATION_SAVE", token, save, 422);
    }
}
