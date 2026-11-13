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
class ExposureFeeIT extends ExposureFeeItSupport {

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

    // ---- the top-up as the prepayment was set aside, not as the sponsor's billing is now (Fable M1) --------------------

    private Map<String, Object> topUp(String sponsor, String publicationId, long expected, long additional) {
        return body("publicationId", publicationId, "expectedPurchased", expected, "additional", additional);
    }

    private String bought(String name, long requested, long grant) {
        String sharedVideoId = approvedVideo(reviewedSponsor(name + "-provider"));
        String sponsor = reviewedSponsor(name);
        if (grant > 0) {
            grant(sponsor, grant, clock.instant().plus(Duration.ofDays(90)));
        }
        return sponsor + "|" + draft(sponsor, name, sharedVideoId, requested);
    }

    @Test
    void aPostpaidSponsorsPurchaseAndTopUpCountAgainstTheCreditLimitAndMoveNoMoney() {
        String sponsor = reviewedSponsor("fm8-post");
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-post-provider"));
        billing(sponsor, "POSTPAID", 130);
        String publicationId = draft(sponsor, "Post", sharedVideoId, 105L);
        submit(sponsorToken(sponsor), publicationId);
        assertThat(exposurePrepayment(publicationId)).containsEntry("mode", "POSTPAID");
        assertThat(dec(exposurePrepayment(publicationId).get("amount"))).isEqualByComparingTo("115");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        // 6 more fit the limit of 130 (121 set aside); 11 more (132 in all) do not, and nothing is written.
        run(PublicationExposureTopUp.TOP_UP, sponsorToken(sponsor), topUp(sponsor, publicationId, 105, 5));
        assertThat(dec(exposurePrepayment(publicationId).get("amount"))).isEqualByComparingTo("121");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, sponsorToken(sponsor), topUp(sponsor, publicationId,
            110, 10), 422))).contains(FeeCodes.PUBLICATION_FUNDS);
        assertThat(dec(exposurePrepayment(publicationId).get("amount"))).isEqualByComparingTo("121");
        assertThat(dec(row(publicationId).get("exposure_purchased"))).isEqualByComparingTo("110");
    }

    @Test
    void aTopUpFollowsTheBillingTheExposuresWereSetAsideWithWhateverTheBillingIsNow() {
        // Prepaid when bought, postpaid now: the top-up still takes the money from the account, so with just the 115 of
        // the purchase in it the 6 more are short (the old check passed as postpaid and the freeze then failed: a 500).
        String[] a = bought("fm8-mode-a", 105, 115).split("\\|");
        submit(sponsorToken(a[0]), a[1]);
        billing(a[0], "POSTPAID", 100_000);
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5), 422)))
            .contains(FeeCodes.PUBLICATION_FUNDS);
        assertThat(dec(exposurePrepayment(a[1]).get("amount"))).isEqualByComparingTo("115");
        assertThat(ledger("2180", a[0])).isEqualByComparingTo("115");
        // With the money in the account it takes it as the purchase did.
        grant(a[0], 1_000, clock.instant().plus(Duration.ofDays(90)));
        run(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5));
        assertThat(ledger("2180", a[0])).isEqualByComparingTo("121");
        assertThat(differences(a[0])).isEmpty();

        // Postpaid when bought, prepaid now: the credit limit is gone, so the top-up of what was set aside against it is
        // refused as the postpaid funds check says (it neither adds without setting aside nor takes money it never
        // took), and nothing is written.
        String sponsor = reviewedSponsor("fm8-mode-b");
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-mode-b-provider"));
        // Money in the account, so that the old check (as prepaid) would have passed and added without the credit.
        grant(sponsor, 5_000, clock.instant().plus(Duration.ofDays(90)));
        billing(sponsor, "POSTPAID", 100_000);
        String publicationId = draft(sponsor, "Mode b", sharedVideoId, 105L);
        submit(sponsorToken(sponsor), publicationId);
        billing(sponsor, "PREPAID", 0);
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, sponsorToken(sponsor), topUp(sponsor, publicationId,
            105, 5), 422))).contains(FeeCodes.PUBLICATION_FUNDS);
        assertThat(dec(exposurePrepayment(publicationId).get("amount"))).isEqualByComparingTo("115");
        assertThat(exposurePrepayment(publicationId)).containsEntry("mode", "POSTPAID");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        // The credit limit given back, it goes through and still moves no money.
        billing(sponsor, "POSTPAID", 100_000);
        run(PublicationExposureTopUp.TOP_UP, sponsorToken(sponsor), topUp(sponsor, publicationId, 105, 5));
        assertThat(dec(exposurePrepayment(publicationId).get("amount"))).isEqualByComparingTo("121");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
    }

    @Test
    void aShortfallRefusesATopUpAndWritesNothing() {
        // Exactly the 115 of the purchase in the account: the 6 more are short.
        String[] a = bought("fm8-short-top", 105, 115).split("\\|");
        submit(sponsorToken(a[0]), a[1]);
        long versions = query("SELECT 1 FROM qb_publication_version WHERE publication_id = CAST(? AS uuid)", a[1]).size();
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5), 422)))
            .contains(FeeCodes.PUBLICATION_FUNDS);
        assertThat(query("SELECT 1 FROM qb_publication_version WHERE publication_id = CAST(? AS uuid)", a[1]))
            .hasSize((int) versions);
        assertThat(dec(exposurePrepayment(a[1]).get("amount"))).isEqualByComparingTo("115");
        assertThat(ledger("2180", a[0])).isEqualByComparingTo("115");
    }

    @Test
    void aTopUpIsRefusedForAClosedPrepaymentAnEndedOrWaitingPublicationAndTooManyAtOnce() {
        String[] a = bought("fm8-states", 105, 5_000).split("\\|");
        String token = sponsorToken(a[0]);
        submit(token, a[1]);
        // Too many at once (the single limit) and in all.
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, token, topUp(a[0], a[1], 105, 1_000_001), 422)))
            .contains(FeeCodes.EXPOSURE_TOP_UP_RANGE);
        // A prepayment already closed.
        execute("UPDATE qb_prepayment SET status = 'RELEASED', released_at = ?, release_reason = 'ENDED', "
            + "version = version + 1 WHERE publication_id = ? AND purpose = 'EXPOSURE'",
            java.sql.Timestamp.from(clock.instant()), a[1]);
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, token, topUp(a[0], a[1], 105, 5), 422)))
            .contains(FeeCodes.EXPOSURE_PREPAYMENT_CLOSED);
        // Ended: not running any more.
        run("QB_PUBLICATION_ADMIN_END", com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm8-content-end",
            com.jabiz.quizbuks.QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", a[1], "reason", "F-M8 test"));
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, token, topUp(a[0], a[1], 105, 5), 422)))
            .contains(FeeCodes.EXPOSURE_NOT_BOUGHT);
    }

    @Test
    void aTopUpOfAPublicationInReviewIsRefusedAndAnUnverifiedOrBannedSponsorCannotTopUp() {
        autoApprove(false);
        String[] a = bought("fm8-review", 105, 5_000).split("\\|");
        submit(sponsorToken(a[0]), a[1]);
        assertThat(status(a[1])).isEqualTo("PENDING_REVIEW");
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5), 422)))
            .contains(FeeCodes.EXPOSURE_NOT_BOUGHT);
        autoApprove(true);
        // An address not verified: refused before anything.
        refused(PublicationExposureTopUp.TOP_UP, com.jabiz.runtime.test.TestTokens.unverified(tokens, a[0],
            com.jabiz.quizbuks.setup.QbRoles.of(com.jabiz.quizbuks.setup.QbRoles.SPONSOR).permissions()
                .toArray(String[]::new)), topUp(a[0], a[1], 105, 5), 403);
        // Banned: refused with the ban, nothing written; works again when unbanned.
        String boss = com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm8-boss", com.jabiz.quizbuks.setup.QbRoles
            .of(com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER).permissions().toArray(String[]::new));
        run("QB_USER_BAN", boss, body("userId", a[0], "reason", "Fraud"));
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5), 422)))
            .contains("QB_USER_BANNED");
        assertThat(dec(exposurePrepayment(a[1]).get("amount"))).isEqualByComparingTo("115");
        run(com.jabiz.quizbuks.identity.BanProcesses.UNBAN, boss, body("userId", a[0], "note", "Cleared"));
    }

    /** Gives the prepayment {@code n} more parts (rows of the payments table, as top-ups would make). */
    private void parts(String sponsor, String prepaymentId, int n) {
        java.sql.Timestamp at = java.sql.Timestamp.from(clock.instant());
        long seq = ((Number) query("SELECT nextval('op_process_seq') AS n").getFirst().get("n")).longValue();
        execute("INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id, op_time) "
            + "VALUES (?, 'it', 1, 'it', now())", seq);
        execute("WITH ids AS (INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) "
            + "SELECT gen_random_uuid(), 'QbFeePayment', ? FROM generate_series(1, ?) RETURNING entity_id) "
            + "INSERT INTO qb_fee_payment_version (payment_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, target_entity, target_id, sub_account, cash_lot_id, amount, paid_at, "
            + "transaction_id) SELECT entity_id, 1, ?, ?, ?, ?, 'QbPrepayment', ?, 'CASH', 'lot-it', 1, ?, "
            + "'it' FROM ids", seq, n, at, at, seq, sponsor, prepaymentId, at);
    }

    @Test
    void aPrepaidPrepaymentWithTooManyPartsTakesNoMoreAndAPostpaidOneHasNone() {
        String[] a = bought("fm8-parts", 105, 5_000).split("\\|");
        submit(sponsorToken(a[0]), a[1]);
        parts(a[0], String.valueOf(exposurePrepayment(a[1]).get("prepayment_id")), 1_000);
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5), 422)))
            .contains(FeeCodes.EXPOSURE_PARTS_LIMIT);
        assertThat(dec(exposurePrepayment(a[1]).get("amount"))).isEqualByComparingTo("115");
        assertThat(ledger("2180", a[0])).isEqualByComparingTo("115");

        // A postpaid one has no parts to give back: the limit does not apply to it.
        String sponsor = reviewedSponsor("fm8-parts-post");
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-parts-post-provider"));
        billing(sponsor, "POSTPAID", 100_000);
        String publicationId = draft(sponsor, "Parts post", sharedVideoId, 105L);
        submit(sponsorToken(sponsor), publicationId);
        parts(sponsor, String.valueOf(exposurePrepayment(publicationId).get("prepayment_id")), 1_000);
        run(PublicationExposureTopUp.TOP_UP, sponsorToken(sponsor), topUp(sponsor, publicationId, 105, 5));
        assertThat(dec(exposurePrepayment(publicationId).get("amount"))).isEqualByComparingTo("121");
    }

    @Test
    void aSponsorWithUnpaidFeesBeyondWhatTheirBillingAllowsCannotTopUp() {
        param(QbParams.FEE_OVERDUE_BLOCK, "true");
        String[] a = bought("fm8-unpaid", 105, 5_000).split("\\|");
        submit(sponsorToken(a[0]), a[1]);
        String invoiceId = java.util.UUID.randomUUID().toString();
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, status, issued_at, due_on) VALUES (?, ?, ?, 'PREPAID', '2026-10', 1000, 100, 1100, 'OPEN', now(), "
            + "current_date)", invoiceId, "IT-" + invoiceId.substring(0, 8), a[0]);
        assertThat(codes(refused(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5), 422)))
            .contains(FeeCodes.FEE_UNPAID);
        assertThat(dec(exposurePrepayment(a[1]).get("amount"))).isEqualByComparingTo("115");
        // Paid, it goes through.
        execute("UPDATE qb_fee_invoice SET status = 'PAID', paid_at = now(), paid_amount = total, payment_transaction_id = 'it' WHERE invoice_id = ?", invoiceId);
        run(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5));
        assertThat(dec(exposurePrepayment(a[1]).get("amount"))).isEqualByComparingTo("121");
    }

    @Test
    void aBannedSponsorTopsUpAgainOnceUnbanned() {
        String[] a = bought("fm8-unban", 105, 5_000).split("\\|");
        submit(sponsorToken(a[0]), a[1]);
        String boss = com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm8-boss2", com.jabiz.quizbuks.setup.QbRoles
            .of(com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER).permissions().toArray(String[]::new));
        run("QB_USER_BAN", boss, body("userId", a[0], "reason", "Fraud"));
        refused(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5), 422);
        run(com.jabiz.quizbuks.identity.BanProcesses.UNBAN, boss, body("userId", a[0], "note", "Cleared"));
        run(PublicationExposureTopUp.TOP_UP, sponsorToken(a[0]), topUp(a[0], a[1], 105, 5));
        assertThat(dec(exposurePrepayment(a[1]).get("amount"))).isEqualByComparingTo("121");
    }
}
