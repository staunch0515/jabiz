package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payouts of the platform's Stripe balance to its bank (docs/quizbuks/plans/Q6-payments.md, D-Q6-13): recorded once
 * (debit 1120 / credit 1110), reversed when they fail after, ignored when the failure came first; what does not fit is
 * a task.
 */
class StripePayoutIT extends PayoutItSupport {

    Map<String, Object> payout(String id, long amount, String status) {
        Map<String, Object> payout = new LinkedHashMap<>();
        payout.put("id", id);
        payout.put("object", "payout");
        payout.put("amount", amount);
        payout.put("currency", "jpy");
        payout.put("status", status);
        payout.put("livemode", false);
        return payout;
    }

    static List<Map<String, Object>> payoutPostings(String payoutId) {
        return query("SELECT a.account_code, e.direction, e.amount FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "JOIN qb_stripe_payout p ON p.payout_row_id = t.source_id "
            + "WHERE t.source_entity = 'QbStripePayout' AND p.payout_id = ? ORDER BY t.process_seq_id, e.line_no",
            payoutId);
    }

    @Test
    void aPayoutIsRecordedOnceAndReversedWhenItFails() {
        deliver(event(eventId(), "payout.paid", payout("po_test1", 5000, "paid")));
        deliver(event(eventId(), "payout.paid", payout("po_test1", 5000, "paid")));
        assertThat(payoutPostings("po_test1")).extracting(l -> l.get("account_code") + " " + l.get("direction"))
            .containsExactly("1120 DEBIT", "1110 CREDIT");
        assertThat(query("SELECT * FROM qb_stripe_payout WHERE payout_id = 'po_test1'")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "PAID"));
        Map<String, Object> failed = event(eventId(), "payout.failed", payout("po_test1", 5000, "failed"));
        deliver(failed);
        assertThat(payoutPostings("po_test1")).hasSize(4);
        assertThat(query("SELECT * FROM qb_stripe_payout WHERE payout_id = 'po_test1'")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "FAILED"));
        assertThat(tasksAbout("stripe:" + failed.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.transfer-check.PAYOUT_FAILED");
        // The failure again: nothing more.
        deliver(event(eventId(), "payout.failed", payout("po_test1", 5000, "failed")));
        assertThat(payoutPostings("po_test1")).hasSize(4);
        assertBooksBalance();
    }

    @Test
    void aFailureFirstMakesALatePaymentNothing() {
        deliver(event(eventId(), "payout.failed", payout("po_test2", 3000, "failed")));
        deliver(event(eventId(), "payout.paid", payout("po_test2", 3000, "paid")));
        assertThat(payoutPostings("po_test2")).isEmpty();
        assertThat(query("SELECT * FROM qb_stripe_payout WHERE payout_id = 'po_test2'")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "FAILED"));
    }

    @Test
    void payoutsThatDoNotFitAreTasks() {
        Map<String, Object> usd = payout("po_test3", 3000, "paid");
        usd.put("currency", "usd");
        Map<String, Object> event = event(eventId(), "payout.paid", usd);
        deliver(event);
        assertThat(tasksAbout("stripe:" + event.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.transfer-check.PAYOUT_MISMATCH");
        Map<String, Object> live = event(eventId(), "payout.paid", payout("po_test4", 3000, "paid"));
        live.put("livemode", true);
        deliver(live);
        assertThat(tasksAbout("stripe:" + live.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.transfer-check.LIVEMODE");
        assertThat(query("SELECT 1 FROM qb_stripe_payout WHERE payout_id IN ('po_test3', 'po_test4')")).isEmpty();
    }
}
