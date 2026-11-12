package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-F11a (docs/quizbuks/plans/impl/F-F11.md section 6.1, the controller's decision 1: the operator's lead confirms the
 * onboarding credit of a sample Sponsor): a Sponsor that was granted the onboarding credit and never topped up pays a
 * fee from the credit, with the cash account untouched and no part of it owed; only a fee the credit cannot cover is
 * left owed (the existing behaviour of F-M1). No test of the delivery fee: it is not built (F-M6 brings its own, which
 * must pay from the credit first).
 */
class SampleSponsorFeesIT extends PlayItSupport {

    private static boolean switched;

    private void switchOn() {
        if (!switched) {
            Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
                "values", body("paramKey", QbParams.FEE_CREDIT_GRANT_ENABLED, "value", "true"), "reason",
                "F-F11 test"));
            run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
            switched = true;
        }
    }

    private String hq() {
        return TestTokens.bearer(tokens, "ff11-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String finance() {
        return tokenOf(user("ff11-finance"), QbRoles.ADMIN_FINANCE);
    }

    private String content() {
        return tokenOf(user("ff11-content"), QbRoles.ADMIN_CONTENT);
    }

    private void approve(String requestId) {
        run("APPROVAL_DECIDE", content(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
    }

    private BigDecimal balance(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    private Map<String, Object> fee(String sponsor, int amount, String reason) {
        Map<String, Object> requested = run(FeeRequests.RECORD, finance(), body("kind", "MANUAL", "sponsorId", sponsor,
            "amount", amount, "reason", reason));
        approve((String) requested.get("approvalRequestId"));
        return requested;
    }

    private List<Map<String, Object>> invoices(String sponsor) {
        return query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? ORDER BY issued_at, invoice_no", sponsor);
    }

    private List<Map<String, Object>> parts(String sponsor) {
        return query("SELECT sub_account, amount FROM qb_fee_payment_version WHERE sponsor_id = ? "
            + "ORDER BY process_seq_id, row_id", sponsor);
    }

    @Test
    void aSampleSponsorWithOnlyTheOnboardingCreditPaysAFeeFromItAndOnlyWhatItCannotCoverIsOwed() {
        switchOn();
        NewOperator operator = newOperatorWithLead(operatorsAdmin(), unique("FF11 Sample partner"), null);
        String sponsor = newSponsor(unique("s-ff11-fees"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operator.operatorId(), "reason",
            "F-F11"));

        // The operator's lead confirms the sample's details are complete: 1000 of platform fee Kudos, nothing else.
        run(FeeCredits.ONBOARDING, memberSession(operator.leadId(), operator.operatorId()), body("sponsorId", sponsor));
        assertThat(balance(sponsor, QbWallets.FEE_CREDIT)).isEqualByComparingTo("1000");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("0");

        // A fee by hand of 300 (330 with the tax) is paid whole from the credit: the cash account does not move and the
        // invoice is paid, not partly paid.
        fee(sponsor, 300, "Sample report");
        assertThat(balance(sponsor, QbWallets.FEE_CREDIT)).isEqualByComparingTo("670");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("0");
        assertThat(invoices(sponsor)).singleElement().satisfies(invoice -> {
            assertThat(invoice).containsEntry("status", QbFees.PAID);
            assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo("330");
        });
        assertThat(parts(sponsor)).singleElement().satisfies(part -> assertThat(part)
            .containsEntry("sub_account", "FEE_CREDIT"));

        // A fee larger than what is left (1100 with the tax against 670): the credit pays what it has, the rest is
        // owed, and nothing was taken from cash.
        fee(sponsor, 1000, "Sample fee above the credit");
        assertThat(balance(sponsor, QbWallets.FEE_CREDIT)).isEqualByComparingTo("0");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("0");
        List<Map<String, Object>> invoices = invoices(sponsor);
        assertThat(invoices).hasSize(2);
        Map<String, Object> short_ = invoices.getLast();
        assertThat(short_).containsEntry("status", QbFees.PARTIAL);
        assertThat((BigDecimal) short_.get("paid_amount")).isEqualByComparingTo("670");
        assertThat((BigDecimal) short_.get("total")).isEqualByComparingTo("1100");
        assertThat(parts(sponsor)).extracting(part -> part.get("sub_account")).containsOnly("FEE_CREDIT");
        assertBooksBalance();
    }
}
