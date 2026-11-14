package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.DigitalPrizeProcesses;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.videouse.VideoUseProcesses;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sponsor's "to do" counts (F-F3b, docs/quizbuks/plans/impl/F-F3.md section 3, template {@code qb.sponsor.todo}):
 * each column has a case of its own, the caller's counts are the caller's, and another sponsor's data is not counted.
 */
class SponsorTodoIT extends VideoUseItSupport {

    private static final List<String> COLUMNS = List.of("claimsToConfirm", "digitalToSend", "fundsShort",
        "rewardsWaiting", "invoicesOverdue", "feeInvoicesUncovered", "publicationsInReview", "pageInReview", "sharedVideosInReview",
        "videoUsesToConfirm", "creditsExpiring", "exposureLow");

    private Map<String, Object> todo(String sponsor) {
        List<Map<String, Object>> rows = items("qb.sponsor.todo", sponsorToken(sponsor), body("params",
            body("at", clock.instant().toString())));
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private int count(String sponsor, String column) {
        return ((Number) todo(sponsor).get(column)).intValue();
    }

    private void assertCounts(String sponsor, Map<String, Integer> expected) {
        Map<String, Object> row = todo(sponsor);
        assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(COLUMNS);
        for (String column : COLUMNS) {
            assertThat(((Number) row.get(column)).intValue()).as(column).isEqualTo(expected.getOrDefault(column, 0));
        }
    }

    private String rewardAdmin() {
        return TestTokens.bearer(tokens, "ff3b-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private void allow(String sponsor) {
        run(RewardMethodProcesses.SET, rewardAdmin(), body("userId", sponsor, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM, RewardMethod.DIGITAL_PRIZE), "note", "F-F3b"));
    }

    /** A taker who passes the publication with full marks; returns their token and the attempt. */
    private String[] winner(String publicationId) {
        String token = taker(newTaker(unique("t-ff3b"), "JP"));
        String attemptId = (String) start(token, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        submit(token, attemptId, answers(true, true));
        deliverEvents();
        return new String[] {token, attemptId};
    }

    private static Map<String, Object> digital(String delivery, String description) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.DIGITAL_PRIZE);
        overrides.put("digitalDelivery", delivery);
        overrides.put("digitalDescription", description);
        return overrides;
    }

    private void insertGrant(String sponsor, String key, long remaining, Instant expiresAt) {
        Instant granted = clock.instant().minus(Duration.ofDays(1));
        execute("INSERT INTO qb_fee_credit_grant (grant_id, sponsor_id, source, source_key, amount, remaining, "
            + "granted_at, expires_at) VALUES (?, ?, 'MANUAL', ?, 1000, ?, ?, ?)", UUID.randomUUID().toString(),
            sponsor, key, remaining, Timestamp.from(granted), Timestamp.from(expiresAt));
    }

    @Test
    void aSponsorWithNothingToDoHasAllZeros() {
        assertCounts(newSponsor(unique("s-ff3b-none")), Map.of());
    }

    @Test
    void eachColumnCountsItsCaseForTheCallerOnly() {
        // The provider of a shared video is the sponsor under test; the user of it is "another sponsor".
        World w = world("todo");
        String a = w.provider();
        String other = w.user();
        allow(a);
        credit(a, 100);
        String tokenA = sponsorToken(a);
        assertCounts(a, Map.of());

        // Claims to confirm: a printed code scanned by a winner of a prize at the sponsor's place.
        String prize = livePublication(a, "Todo coffee", Map.of("regions", List.of("GLOBAL"), "rewardMethod",
            RewardMethod.OFFLINE_REDEEM, "redeemDescription", "A coffee"));
        String[] aki = winner(prize);
        String printed = (String) run(RedemptionProcesses.ISSUE, tokenA, body("kind", "PRINTED")).get("redeemKey");
        String redemptionId = (String) ((List<Map<String, Object>>) run(RedemptionProcesses.CHECK, aki[0],
            body("scannedKey", printed)).get("prizes")).getFirst().get("redemptionId");
        assertCounts(a, Map.of());
        assertThat(run(RedemptionProcesses.CLAIM, aki[0], body("scannedKey", printed, "redemptionId", redemptionId)))
            .containsEntry("status", "CLAIMED");
        assertCounts(a, Map.of("claimsToConfirm", 1));

        // Digital prizes: one the sponsor has to send, one the taker disputes.
        String sent = livePublication(a, "Todo sent", digital(DigitalDelivery.SPONSOR_SENT, "A code by mail"));
        winner(sent);
        assertCounts(a, Map.of("claimsToConfirm", 1, "digitalToSend", 1));
        Map<String, Object> shared = digital(DigitalDelivery.SHARED, "A month of Premium");
        shared.put("digitalContent", "https://capitals.example/premium?code=TODO");
        String[] bea = winner(livePublication(a, "Todo shared", shared));
        String digitalPrizeId = String.valueOf(latest("qb_digital_prize_version", "digital_prize_id",
            "attempt_id = '" + bea[1] + "'").getFirst().get("digital_prize_id"));
        assertCounts(a, Map.of("claimsToConfirm", 1, "digitalToSend", 1));
        run(DigitalPrizeProcesses.DISPUTE, bea[0], body("digitalPrizeId", digitalPrizeId, "note", "Used already"));
        assertCounts(a, Map.of("claimsToConfirm", 1, "digitalToSend", 2));

        // Short of budget: the wallet holds one reward of the full score, so the second winner waits for a top-up.
        String shortOf = livePublication(a, "Todo short", Map.of("regions", List.of("GLOBAL")));
        String first = taker(newTaker(unique("t-ff3b"), "JP"));
        String second = taker(newTaker(unique("t-ff3b"), "JP"));
        String firstAttempt = (String) start(first, shortOf).get("attemptId");
        String secondAttempt = (String) start(second, shortOf).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        submit(first, firstAttempt, answers(true, true));
        submit(second, secondAttempt, answers(true, true));
        deliverEvents();
        assertThat(status(shortOf)).isEqualTo("FUNDS_SHORT");
        assertCounts(a, Map.of("claimsToConfirm", 1, "digitalToSend", 2, "fundsShort", 1, "rewardsWaiting", 1));

        // A publication in review (before the overdue invoice, which would stop the submit).
        autoApprove(false);
        try {
            String draft = draft(tokenA, version(tokenA, "Todo review quiz"), "Todo review");
            assertThat(run("QB_PUBLICATION_SUBMIT", tokenA, body("publicationId", draft)))
                .containsEntry("status", "PENDING_REVIEW");
        } finally {
            autoApprove(true);
        }
        Map<String, Integer> want = new LinkedHashMap<>(Map.of("claimsToConfirm", 1, "digitalToSend", 2,
            "fundsShort", 1, "rewardsWaiting", 1, "publicationsInReview", 1));
        assertCounts(a, want);

        // Overdue invoices count, open and paid ones do not.
        invoice(a, "2026-08", "OPEN");
        invoice(a, "2026-09", "PAID");
        want.put("feeInvoicesUncovered", 1);
        assertCounts(a, want);
        invoice(a, "2026-10", "OVERDUE");
        want.put("invoicesOverdue", 1);
        want.put("feeInvoicesUncovered", 2);
        assertCounts(a, want);
        // The fee account covers an invoice when it holds at least what is owed (1100): fee credit and cash less held.
        feeCredit(a, 1099);
        assertCounts(a, want);
        feeCredit(a, 1100);
        want.put("feeInvoicesUncovered", 0);
        assertCounts(a, want);
        feeCredit(a, 0);
        // Cash the sponsor has set aside is not available: 2000 in cash with 1000 held covers nothing.
        execute("INSERT INTO qb_wallet (wallet_id, owner_id, kind, balance, held, pending_count, pending_amount, "
            + "pending_out, version) VALUES (?, ?, 'CASH', 2000, 1000, 0, 0, 0, 1)", UUID.randomUUID().toString(), a);
        want.put("feeInvoicesUncovered", 2);
        assertCounts(a, want);
        execute("UPDATE qb_wallet SET held = 900 WHERE owner_id = ? AND kind = 'CASH'", a);
        want.put("feeInvoicesUncovered", 0);
        assertCounts(a, want);
        execute("DELETE FROM qb_wallet WHERE owner_id = ? AND kind = 'CASH'", a);
        want.put("feeInvoicesUncovered", 2);
        assertCounts(a, want);

        // The public page in review (a draft is the sponsor's own and not counted).
        page(a, "DRAFT");
        assertCounts(a, want);
        page(a, "PENDING");
        want.put("pageInReview", 1);
        assertCounts(a, want);

        // A shared video waiting for its review (the approved one of the world does not count).
        String assetId = (String) run(AssetProcesses.SAVE, tokenA, body("kind", "VIDEO_LINK", "name", "Dogs",
            "language", "en", "url", VIDEO, "durationSec", 60)).get("assetId");
        run(SharedVideoProcesses.SHARE, tokenA, body("assetId", assetId, "titleZh", "狗", "titleJa", "犬",
            "titleEn", "Dogs " + assetId.substring(0, 6), "topics", List.of(word("fs14-pets")), "vertical", false));
        want.put("sharedVideosInReview", 1);
        assertCounts(a, want);

        // A use of the sponsor's shared video: counted for the provider once its user sent it, not while a draft.
        String useId = (String) run(VideoUseProcesses.SAVE, sponsorToken(other), entry(w.publicationId(), "FIXED",
            50_000)).get("videoUseId");
        assertCounts(a, want);
        run(VideoUseProcesses.SUBMIT, sponsorToken(other), body("videoUseId", useId, "baseRevision", 1));
        want.put("videoUsesToConfirm", 1);
        assertCounts(a, want);

        // Platform fee Kudos: one lot expires within the notice (the default 14 days), others too late, gone or empty.
        insertGrant(a, "in-5-days", 1000, clock.instant().plus(Duration.ofDays(5)));
        insertGrant(a, "in-60-days", 1000, clock.instant().plus(Duration.ofDays(60)));
        insertGrant(a, "spent", 0, clock.instant().plus(Duration.ofDays(5)));
        insertGrant(a, "expired", 1000, clock.instant().minus(Duration.ofHours(1)));
        want.put("creditsExpiring", 1);
        assertCounts(a, want);
        // The notice is the parameter the reminder reads: a longer one reaches the lot 60 days out.
        setParam(QbParams.FEE_CREDIT_EXPIRY_NOTICE_DAYS, "90");
        try {
            assertThat(count(a, "creditsExpiring")).isEqualTo(2);
        } finally {
            setParam(QbParams.FEE_CREDIT_EXPIRY_NOTICE_DAYS, "14");
        }

        // The user of the shared video has none of it: the entry it sent is the provider's to confirm.
        assertCounts(other, Map.of());
    }

    private void feeCredit(String sponsor, long balance) {
        execute("INSERT INTO qb_wallet (wallet_id, owner_id, kind, balance, pending_count, pending_amount, "
            + "pending_out, version) VALUES (?, ?, 'FEE_CREDIT', ?, 0, 0, 0, 1) ON CONFLICT (owner_id, kind) DO "
            + "UPDATE SET balance = EXCLUDED.balance", UUID.randomUUID().toString(), sponsor, balance);
    }

    private void invoice(String sponsor, String period, String status) {
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        boolean paid = "PAID".equals(status);
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, paid_amount, status, issued_at, due_on, paid_at, payment_transaction_id) VALUES (?, ?, ?, "
            + "'POSTPAID', ?, 1000, 100, 1100, ?, ?, ?, ?::date, ?, ?)", id, "TODO-" + id.substring(0, 8), sponsor,
            period, paid ? 1100 : 0, status, Timestamp.from(now.minus(Duration.ofDays(40))),
            now.minus(Duration.ofDays(10)).toString().substring(0, 10), paid ? Timestamp.from(now) : null,
            paid ? "pay-" + id : null);
    }

    private void page(String sponsor, String status) {
        execute("DELETE FROM qb_sponsor_page WHERE user_id = ?", sponsor);
        execute("INSERT INTO qb_sponsor_page (user_id, handle, language, headline, status, edited_at, revision, "
            + "version) VALUES (?, ?, 'en', 'Todo', ?, ?, 1, 1)", sponsor, "todo-" + UUID.randomUUID().toString()
            .substring(0, 8), status, Timestamp.from(clock.instant()));
    }
}
