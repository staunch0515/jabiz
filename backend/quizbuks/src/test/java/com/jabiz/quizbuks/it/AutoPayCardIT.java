package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13b: cash arriving by card pays the unpaid invoice by itself; Kudos arriving by card do not. The invoices are
 * written by hand (no ledger posting), so this class keeps to itself: the daily check of another class would find them.
 */
class AutoPayCardIT extends TopUpItSupport {

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-M13b"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    BigDecimal cash(String sponsorId) {
        return (BigDecimal) query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = 'CASH'", sponsorId)
            .getFirst().get("balance");
    }

    @Test
    void aCashTopUpByCardPaysTheUnpaidInvoiceByItselfAndKudosDoNot() {
        param(QbParams.TOPUP_TARGETS, "KUDOS,CASH");
        autoPayOn();
        String cashSponsor = newSponsor(unique("s-fm13b-card-cash"));
        String kudosSponsor = newSponsor(unique("s-fm13b-card-kudos"));
        String cashInvoice = unpaidInvoice(cashSponsor, 1100);
        String kudosInvoice = unpaidInvoice(kudosSponsor, 1100);
        String cashTopUp = (String) run("QB_TOPUP_START", sponsorToken(cashSponsor), body("amount", 3000, "target",
            "CASH")).get("topUpId");
        String kudosTopUp = (String) run("QB_TOPUP_START", sponsorToken(kudosSponsor), body("amount", 3000, "target",
            "KUDOS")).get("topUpId");
        runJobs();
        deliver(event(eventId(), "checkout.session.completed", completed(cashTopUp)));
        deliver(event(eventId(), "checkout.session.completed", completed(kudosTopUp)));
        deliverEvents();
        assertThat(invoiceStatus(cashInvoice)).isEqualTo("PAID");
        assertThat(cash(cashSponsor)).isEqualByComparingTo("1900");
        assertThat(invoiceStatus(kudosInvoice)).isEqualTo("OPEN");
    }
}
