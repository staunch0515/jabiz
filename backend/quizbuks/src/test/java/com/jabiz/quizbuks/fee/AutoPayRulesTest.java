package com.jabiz.quizbuks.fee;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M13b: the rules of the automatic payment of invoices that need no database. */
class AutoPayRulesTest {

    private static ParamValues params(Object... pairs) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return new ParamValues(Instant.EPOCH, values);
    }

    private static EntityInstance invoice(String id, String no, String status, long total, long paid, long credit,
        String issuedAt) {
        return new EntityInstance(id, QbFees.INVOICE, 1, null, Map.of("invoiceNo", no, "status", status,
            "total", BigDecimal.valueOf(total), "paidAmount", BigDecimal.valueOf(paid),
            "creditApplied", BigDecimal.valueOf(credit), "issuedAt", Instant.parse(issuedAt)));
    }

    @Test
    void theSwitchIsOnOnlyWhenItsValueIsTrue() {
        assertThat(FeeAutoPay.on(null)).isFalse();
        assertThat(FeeAutoPay.on(params())).as("never published: no value").isFalse();
        assertThat(FeeAutoPay.on(params(QbParams.FEE_AUTO_PAY_ENABLED, false))).isFalse();
        assertThat(FeeAutoPay.on(params(QbParams.FEE_AUTO_PAY_ENABLED, true))).isTrue();
    }

    @Test
    void theLimitIsTheParametersValueHeldWithinOneAndFiftyAndTwentyWithoutOne() {
        assertThat(FeeAutoPay.max(null)).isEqualTo(20);
        assertThat(FeeAutoPay.max(params())).isEqualTo(20);
        assertThat(FeeAutoPay.max(params(QbParams.FEE_AUTO_PAY_MAX_INVOICES, new BigDecimal("7")))).isEqualTo(7);
        assertThat(FeeAutoPay.max(params(QbParams.FEE_AUTO_PAY_MAX_INVOICES, new BigDecimal("0")))).isEqualTo(1);
        assertThat(FeeAutoPay.max(params(QbParams.FEE_AUTO_PAY_MAX_INVOICES, new BigDecimal("99")))).isEqualTo(50);
    }

    @Test
    void theEarliestIssuedOwingInvoiceComesFirstTheNumberBreaksATieAndWhatIsOwedDecides() {
        EntityInstance late = invoice("a", "N-3", "OPEN", 1100, 0, 0, "2026-10-05T00:00:00Z");
        EntityInstance early = invoice("b", "N-2", "PARTIAL", 1100, 400, 0, "2026-10-01T00:00:00Z");
        EntityInstance tieLow = invoice("c", "N-1", "OVERDUE", 1100, 0, 0, "2026-10-03T00:00:00Z");
        EntityInstance tieHigh = invoice("d", "N-9", "OPEN", 1100, 0, 0, "2026-10-03T00:00:00Z");
        EntityInstance paid = invoice("e", "N-0", "PAID", 1100, 1100, 0, "2026-09-01T00:00:00Z");
        EntityInstance covered = invoice("f", "N-4", "OPEN", 1100, 0, 1100, "2026-09-02T00:00:00Z");
        EntityInstance voided = invoice("g", "N-5", "VOID", 1100, 0, 0, "2026-09-03T00:00:00Z");
        List<EntityInstance> owing = FeeAutoPay.owing(List.of(late, tieHigh, paid, covered, tieLow, voided, early));
        assertThat(owing).extracting(EntityInstance::id).containsExactly("b", "c", "d", "a");
    }

    @Test
    void aRunPaysAtMostTheLimitAndSaysWhetherMoreWereLeft() {
        List<EntityInstance> unpaid = List.of(invoice("a", "N-1", "OPEN", 1100, 0, 0, "2026-10-01T00:00:00Z"),
            invoice("b", "N-2", "OPEN", 1100, 0, 0, "2026-10-02T00:00:00Z"),
            invoice("c", "N-3", "OPEN", 1100, 0, 0, "2026-10-03T00:00:00Z"));
        FeeAutoPay.Chosen two = FeeAutoPay.choose(unpaid, 2);
        assertThat(two.invoices()).extracting(EntityInstance::id).containsExactly("a", "b");
        assertThat(two.more()).isTrue();
        FeeAutoPay.Chosen all = FeeAutoPay.choose(unpaid, 3);
        assertThat(all.invoices()).hasSize(3);
        assertThat(all.more()).isFalse();
        assertThat(FeeAutoPay.choose(List.of(), 5).invoices()).isEmpty();
    }
}
