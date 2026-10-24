package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.quizbuks.reward.RedemptionStatus;
import com.jabiz.quizbuks.reward.RewardCodes;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Collecting a prize at the sponsor's place (docs/quizbuks/plans/Q22-reward-methods.md, Q22b): the sponsor's console
 * shows a rotating code the taker scans to collect at once; a printed code makes a claim the sponsor confirms or
 * declines, or that lapses; prizes expire; an administrator voids one; every step is a message to the other side,
 * and the sponsor reads theirs in the console's inbox.
 */
class RedemptionIT extends PlayItSupport {

    @Autowired
    @Qualifier("qbRedemptionExpireJob")
    JobDefinition<RedemptionProcesses.RedemptionRef> expireJob;

    @Autowired
    @Qualifier("qbRedemptionLapseJob")
    JobDefinition<RedemptionProcesses.RedemptionRef> lapseJob;

    private String methodsAdmin() {
        return TestTokens.bearer(tokens, "q22b-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.REDEMPTION_VOID, QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private void allow(String sponsor, String... methods) {
        run(com.jabiz.quizbuks.publishing.RewardMethodProcesses.SET, methodsAdmin(), body("userId", sponsor, "methods",
            List.of(methods), "note", "Q22b test"));
    }

    private Map<String, Object> redemption(String attemptId) {
        return latest("qb_redemption_version", "redemption_id", "attempt_id = '" + attemptId + "'").getFirst();
    }

    private JobRunner.Outcome runJob(JobDefinition<RedemptionProcesses.RedemptionRef> job, Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.run(job, at);
    }

    private static Instant at(Object value) {
        return value instanceof java.sql.Timestamp time ? time.toInstant()
            : value instanceof java.time.OffsetDateTime time ? time.toInstant()
            : value instanceof String text ? Instant.parse(text) : (Instant) value;
    }

    private List<Map<String, Object>> rows(String template, String token, Map<String, Object> params) {
        return items(template, token, Map.of("params", params, "limit", 200));
    }

    /** A live publication of the sponsor's paid with a prize. */
    private String prizePublication(String sponsor, String name) {
        Map<String, Object> overrides = new java.util.LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        overrides.put("redeemDescription", "A coffee at our counter");
        return livePublication(sponsor, name, overrides);
    }

    /** A taker who passed the publication: returns their id, token and the attempt. */
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

    private String issue(String sponsorToken, String kind, boolean renew) {
        Map<String, Object> input = body("kind", kind);
        if (renew) {
            input.put("renew", true);
        }
        return (String) run(RedemptionProcesses.ISSUE, sponsorToken, input).get("redeemKey");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theConsolesRotatingCodeCollectsAPrizeAtOnce() {
        String sponsor = newSponsor("s-q22b-rot");
        allow(sponsor, RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM);
        String sponsorToken = sponsorToken(sponsor);
        String publicationId = prizePublication(sponsor, "Coffee week");
        Winner mei = winner("t-q22b-mei", publicationId);
        String otherTaker = taker(newTaker("t-q22b-other", "JP"));

        // The code: good for two minutes, a secret the datasets never show.
        Map<String, Object> issued = run(RedemptionProcesses.ISSUE, sponsorToken, body("kind", "ROTATING"));
        String token = (String) issued.get("redeemKey");
        assertThat(token).hasSizeGreaterThanOrEqualTo(30);
        assertThat(at(issued.get("expiresAt"))).isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));
        assertThat(codes(refused(RedemptionProcesses.ISSUE, sponsorToken, body("kind", "GOLD"), 422)))
            .containsExactly(RewardCodes.REDEEM_TOKEN_KIND);
        refused(RedemptionProcesses.ISSUE, mei.token(), body("kind", "ROTATING"), 403);
        assertThat(rows("urn:jabiz:dataset:sponsor:QbRedeemToken", sponsorToken)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("kind", "ROTATING").doesNotContainKey("tokenValue"));

        // What the scan stands for: the sponsor and the caller's own prizes there (none for another taker).
        Map<String, Object> checked = run(RedemptionProcesses.CHECK, mei.token(), body("scannedKey", token));
        assertThat(checked).containsEntry("kind", "ROTATING");
        assertThat(checked.get("sponsorCompany")).isNotNull();
        List<Map<String, Object>> prizes = (List<Map<String, Object>>) checked.get("prizes");
        assertThat(prizes).singleElement().satisfies(prize -> assertThat(prize)
            .containsEntry("status", "DUE").containsEntry("description", "A coffee at our counter")
            .containsEntry("publicationName", "Coffee week").containsEntry("amount", 100)
            .containsEntry("attemptId", mei.attemptId()));
        String redemptionId = (String) prizes.getFirst().get("redemptionId");
        assertThat((List<Object>) run(RedemptionProcesses.CHECK, otherTaker, body("scannedKey", token)).get("prizes"))
            .isEmpty();
        // Another taker cannot collect Mei's prize, even with the code.
        refused(RedemptionProcesses.CLAIM, otherTaker, body("scannedKey", token, "redemptionId", redemptionId), 404);

        // Collected at once: the sponsor showed the code in person.
        assertThat(run(RedemptionProcesses.CLAIM, mei.token(), body("scannedKey", token, "redemptionId", redemptionId)))
            .containsEntry("status", "REDEEMED");
        Map<String, Object> redemption = redemption(mei.attemptId());
        assertThat(redemption).containsEntry("status", "REDEEMED").containsEntry("claim_kind", "ROTATING")
            .containsEntry("redeemed_by", sponsor);
        assertThat(at(redemption.get("redeemed_at"))).isEqualTo(clock.instant());
        assertThat(codes(refused(RedemptionProcesses.CLAIM, mei.token(), body("scannedKey", token, "redemptionId",
            redemptionId), 422))).containsExactly(RewardCodes.REDEMPTION_STATE);
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(mei.userId())).containsExactlyInAnyOrder("REDEEM_DUE", "REDEEM_REDEEMED");
        Map<String, Object> redeem = (Map<String, Object>) run("QB_ATTEMPT_RESULT", mei.token(),
            body("attemptId", mei.attemptId())).get("redeem");
        assertThat(redeem).containsEntry("status", "REDEEMED");
        assertThat(rows("qb.sponsor.redemptions", sponsorToken, Map.of())).singleElement().satisfies(row ->
            assertThat(row).containsEntry("status", "REDEEMED").containsEntry("claimKind", "ROTATING")
                .containsEntry("takerName", "Taker JP"));
        assertThat(rows("qb.app.redemptions", mei.token(), Map.of("open", true))).isEmpty();
        assertThat(rows("qb.app.redemptions", mei.token(), Map.of())).hasSize(1);
        assertThat(rows("qb.admin.redemptions", methodsAdmin(), Map.of("q", "Taker JP"))).hasSizeGreaterThanOrEqualTo(1);

        // Expired: the scan says so; the next code issued drops the expired one.
        clock.advance(Duration.ofMinutes(3));
        String later = taker(mei.userId());
        assertThat(codes(refused(RedemptionProcesses.CHECK, later, body("scannedKey", token), 422)))
            .containsExactly(RewardCodes.REDEEM_TOKEN_INVALID);
        assertThat(codes(refused(RedemptionProcesses.CHECK, later, body("scannedKey", "no-such-code"), 422)))
            .containsExactly(RewardCodes.REDEEM_TOKEN_INVALID);
        issue(sponsorToken(sponsor), "ROTATING", false);
        assertThat(query("SELECT kind FROM qb_redeem_token WHERE sponsor_id = ?", sponsor)).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPrintedCodeMakesAClaimTheSponsorConfirmsOrDeclines() {
        String sponsor = newSponsor("s-q22b-print");
        allow(sponsor, RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM);
        String sponsorToken = sponsorToken(sponsor);
        String publicationId = prizePublication(sponsor, "Poster week");
        Winner aki = winner("t-q22b-aki", publicationId);
        Winner ren = winner("t-q22b-ren", publicationId);

        // The printed code lasts: asked twice, it is the same.
        String printed = issue(sponsorToken, "PRINTED", false);
        assertThat(issue(sponsorToken, "PRINTED", false)).isEqualTo(printed);
        Map<String, Object> checked = run(RedemptionProcesses.CHECK, aki.token(), body("scannedKey", printed));
        assertThat(checked).containsEntry("kind", "PRINTED");
        String akiPrize = (String) ((List<Map<String, Object>>) checked.get("prizes")).getFirst().get("redemptionId");

        // A scan of it is a claim the sponsor has ten minutes to confirm; the sponsor is told in their inbox.
        assertThat(run(RedemptionProcesses.CLAIM, aki.token(), body("scannedKey", printed, "redemptionId", akiPrize)))
            .containsEntry("status", "CLAIMED");
        Map<String, Object> claimed = redemption(aki.attemptId());
        assertThat(claimed).containsEntry("claim_kind", "PRINTED");
        assertThat(at(claimed.get("confirm_by"))).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(sponsor)).containsExactly("REDEEM_CLAIMED");
        Map<String, Object> message = InboxItSupport.messagesOf(sponsor).getFirst();
        assertThat(message).containsEntry("action", "REDEEM").containsEntry("action_id", akiPrize);
        List<Map<String, Object>> inbox = rows("qb.sponsor.inbox", sponsorToken, Map.of());
        assertThat(inbox).singleElement().satisfies(item -> assertThat(item).containsEntry("itemKind", "MESSAGE")
            .containsEntry("kind", "REDEEM_CLAIMED").containsEntry("unread", true));
        run("QB_MESSAGE_MARK", sponsorToken, body("kind", "MESSAGE", "id", inbox.getFirst().get("itemId"),
            "action", "READ"));
        assertThat(rows("qb.sponsor.inbox", sponsorToken, Map.of("unread", true))).isEmpty();
        // Only the sponsor confirms; the prize's taker cannot, nor another sponsor.
        refused(RedemptionProcesses.CONFIRM, aki.token(), body("redemptionId", akiPrize), 403);
        refused(RedemptionProcesses.CONFIRM, sponsorToken(newSponsor("s-q22b-else")),
            body("redemptionId", akiPrize), 404);
        assertThat(run(RedemptionProcesses.CONFIRM, sponsorToken, body("redemptionId", akiPrize)))
            .containsEntry("status", "REDEEMED");
        assertThat(redemption(aki.attemptId())).containsEntry("redeemed_by", sponsor);
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(aki.userId())).containsExactlyInAnyOrder("REDEEM_DUE", "REDEEM_REDEEMED");

        // Ren's claim is not the person at the counter: declined, the prize is due again and Ren is told.
        String renPrize = (String) ((List<Map<String, Object>>) run(RedemptionProcesses.CHECK, ren.token(),
            body("scannedKey", printed)).get("prizes")).getFirst().get("redemptionId");
        run(RedemptionProcesses.CLAIM, ren.token(), body("scannedKey", printed, "redemptionId", renPrize));
        assertThat(rows("qb.sponsor.redemptions", sponsorToken, Map.of("since", clock.instant().toString())))
            .anySatisfy(row -> assertThat(row).containsEntry("redemptionId", renPrize)
                .containsEntry("status", "CLAIMED"));
        assertThat(run(RedemptionProcesses.DECLINE, sponsorToken, body("redemptionId", renPrize)))
            .containsEntry("status", "DUE");
        assertThat(redemption(ren.attemptId())).containsEntry("claim_kind", null).containsEntry("confirm_by", null);
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(ren.userId())).containsExactlyInAnyOrder("REDEEM_DUE", "REDEEM_DECLINED");

        // Claimed again and forgotten: after ten minutes the sponsor cannot confirm, the job undoes the claim, and
        // the scan shows the prize as due meanwhile.
        run(RedemptionProcesses.CLAIM, ren.token(), body("scannedKey", printed, "redemptionId", renPrize));
        clock.advance(Duration.ofMinutes(11));
        String renLater = taker(ren.userId());
        String sponsorLater = sponsorToken(sponsor);
        assertThat(codes(refused(RedemptionProcesses.CONFIRM, sponsorLater, body("redemptionId", renPrize), 422)))
            .containsExactly(RewardCodes.REDEMPTION_CLAIM_LAPSED);
        assertThat(((List<Map<String, Object>>) run(RedemptionProcesses.CHECK, renLater, body("scannedKey", printed))
            .get("prizes")).getFirst()).containsEntry("status", "DUE");
        assertThat(runJob(lapseJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(redemption(ren.attemptId())).containsEntry("status", "DUE");

        // A new printed code replaces the old one: posters carrying the old one stop working.
        String renewed = issue(sponsorLater, "PRINTED", true);
        assertThat(renewed).isNotEqualTo(printed);
        assertThat(codes(refused(RedemptionProcesses.CHECK, renLater, body("scannedKey", printed), 422)))
            .containsExactly(RewardCodes.REDEEM_TOKEN_INVALID);
        // Another sponsor's code does not collect this sponsor's prize.
        String otherSponsor = newSponsor("s-q22b-shop2");
        String otherCode = issue(sponsorToken(otherSponsor), "ROTATING", false);
        assertThat(codes(refused(RedemptionProcesses.CLAIM, renLater, body("scannedKey", otherCode, "redemptionId",
            renPrize), 422))).containsExactly(RewardCodes.REDEEM_TOKEN_SPONSOR);
        // The right code does.
        assertThat(run(RedemptionProcesses.CLAIM, renLater, body("scannedKey", renewed, "redemptionId", renPrize)))
            .containsEntry("status", "CLAIMED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPrizeExpiresOrIsVoided() {
        String sponsor = newSponsor("s-q22b-exp");
        allow(sponsor, RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM);
        String publicationId = prizePublication(sponsor, "Expiry week");
        Winner yu = winner("t-q22b-yu", publicationId);
        Winner kai = winner("t-q22b-kai", publicationId);

        // Voided by an administrator (not by the sponsor): the taker is told.
        String kaiPrize = (String) redemption(kai.attemptId()).get("redemption_id").toString();
        refused(RedemptionProcesses.VOID, sponsorToken(sponsor), body("redemptionId", kaiPrize), 403);
        assertThat(run(RedemptionProcesses.VOID, methodsAdmin(), body("redemptionId", kaiPrize, "note",
            "Duplicate account"))).containsEntry("status", "VOID");
        assertThat(redemption(kai.attemptId())).containsEntry("void_note", "Duplicate account")
            .containsEntry("voided_by", "q22b-admin");
        assertThat(codes(refused(RedemptionProcesses.VOID, methodsAdmin(), body("redemptionId", kaiPrize), 422)))
            .containsExactly(RewardCodes.REDEMPTION_STATE);
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(kai.userId())).containsExactlyInAnyOrder("REDEEM_DUE", "REDEEM_VOID");

        // Not collected in 30 days: a scan refuses it before the daily job runs and lists nothing; the job expires
        // it, the taker is told, and the code no longer collects it.
        clock.advance(Duration.ofDays(31));
        String earlyCode = issue(sponsorToken(sponsor), "ROTATING", false);
        String yuEarly = taker(yu.userId());
        assertThat((List<Object>) run(RedemptionProcesses.CHECK, yuEarly, body("scannedKey", earlyCode)).get("prizes"))
            .isEmpty();
        assertThat(codes(refused(RedemptionProcesses.CLAIM, yuEarly, body("scannedKey", earlyCode, "redemptionId",
            redemption(yu.attemptId()).get("redemption_id").toString()), 422)))
            .containsExactly(RewardCodes.REDEMPTION_STATE);
        assertThat(runJob(expireJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(redemption(yu.attemptId())).containsEntry("status", "EXPIRED");
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(yu.userId())).containsExactlyInAnyOrder("REDEEM_DUE", "REDEEM_EXPIRED");
        String code = issue(sponsorToken(sponsor), "ROTATING", false);
        String yuLater = taker(yu.userId());
        assertThat(codes(refused(RedemptionProcesses.CLAIM, yuLater, body("scannedKey", code, "redemptionId",
            redemption(yu.attemptId()).get("redemption_id").toString()), 422)))
            .containsExactly(RewardCodes.REDEMPTION_STATE);
        assertThat(rows("qb.app.redemptions", yuLater, Map.of("open", true))).isEmpty();
        assertThat(rows("qb.app.history", yuLater, Map.of("at", clock.instant().toString()))).anySatisfy(row ->
            assertThat(row).containsEntry("attemptId", yu.attemptId()).containsEntry("redeemStatus", "EXPIRED"));
    }
}
