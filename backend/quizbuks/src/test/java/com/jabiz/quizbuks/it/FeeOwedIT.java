package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M7 (docs/quizbuks/plans/impl/F-M.md §10.3-5, N4): what an invoice owes is one thing, its total less what credit
 * notes paid of it and what was paid of it, and every reader says the same: the Sponsor's wallet, the operator's
 * billing page, finance's invoices, the ledger (1130) and the daily checks. Credit left and an invoice unpaid are never
 * both there: a note pays the unpaid invoices at once, and a new invoice takes the credit first.
 */
class FeeOwedIT extends CreditNoteItSupport {

    String hq() {
        return TestTokens.bearer(tokens, "fm7-hq", QbPermissions.FEE_BILLING, QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ);
    }

    /** A lead member's session of a new operator, and that operator. */
    String[] operatorSession(String name) {
        String operator = newOperator(hq(), unique(name + " operator"));
        String member = verifiedUser(unique(name + "-member"));
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operator, "userId", member, "lead", true));
        Actor actor = new Actor(member, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operator));
        return new String[] {"Bearer " + tokens.issue(actor).token(), operator};
    }

    /** The receivables check too (recon-fee-receivable), with the fee account's (differences()). */
    List<Map<String, Object>> receivable(String sponsor) {
        return items("qb.system.recon-fee-receivable", TestTokens.bearer(tokens, "fm7-recon",
            QbPermissions.WALLET_SYSTEM), Map.of()).stream()
            .filter(row -> sponsor.equals(String.valueOf(row.get("sponsorId")))).toList();
    }

    @Test
    void everyReaderOfWhatIsOwedSaysTheSame() {
        param(QbParams.FEE_MONTHLY, "1000");
        String[] session = operatorSession("fm7-owed");
        String sponsor = newSponsor(unique("s-fm7-owed"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", session[1], "reason", "F-M7"));
        billing(sponsor, "POSTPAID", 50_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("ISSUED");
        // A note of 300 on the month's fee: 330 of its 1100 paid by credit, 770 owed.
        creditNote(monthlyCharge(sponsor).get("charge_id"), 300);

        Map<String, Object> wallet = items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst();
        assertThat(wallet).containsEntry("unpaidTotal", 770).containsEntry("unpaidCount", 1)
            .containsEntry("creditNoteCredit", 0);
        assertThat(items("qb.sponsor.fee-invoices", sponsorToken(sponsor), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("creditApplied", 330).containsEntry("paidAmount", 0)
                .containsEntry("status", QbFees.PARTIAL));
        assertThat(items("qb.operator.sponsor-billing", session[0], Map.of())).filteredOn(row ->
            sponsor.equals(row.get("userId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("unpaidTotal", 770));
        assertThat(items("qb.admin.fee-invoices", finance(), Map.of("filters",
            List.of(Map.of("field", "sponsorId", "op", "eq", "value", sponsor)))))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("total")).longValue()
                - ((Number) row.get("creditApplied")).longValue() - ((Number) row.get("paidAmount")).longValue())
                .isEqualTo(770));
        // 1130 (credit less debit here) is what is owed, and the checks agree.
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("-770");
        assertThat(receivable(sponsor)).isEmpty();
        assertThat(differences(sponsor)).isEmpty();
        // The operator reads the note on its sponsor's fee, without who in headquarters issued it.
        assertThat(items("qb.operator.fee-credit-notes", session[0], Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("sponsorId", sponsor).containsEntry("total", 330)
                .containsEntry("issuedBy", null));
        assertThat(items("qb.operator.fee-credit-notes", operatorSession("fm7-other")[0], Map.of())).isEmpty();
    }

    @Test
    void creditLeftAndAnUnpaidInvoiceAreNeverBothThere() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-never"));
        billing(sponsor, "POSTPAID", 50_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        // The fee's whole amount credited before its invoice: 1100 of credit, nothing unpaid.
        creditNote(monthlyCharge(sponsor).get("charge_id"), 1_000);
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("creditNoteCredit", 1100).containsEntry("unpaidTotal", 0);
        // The month's invoice takes it all: paid by credit, nothing left on either side.
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("ISSUED");
        assertThat(query("SELECT status, credit_applied FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor))
            .singleElement().satisfies(row -> {
                assertThat(row).containsEntry("status", QbFees.PAID);
                assertThat((BigDecimal) row.get("credit_applied")).isEqualByComparingTo("1100");
            });
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("creditNoteCredit", 0).containsEntry("unpaidTotal", 0);
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        assertThat(receivable(sponsor)).isEmpty();
        assertThat(differences(sponsor)).isEmpty();
    }
}
