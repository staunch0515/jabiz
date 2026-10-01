package com.jabiz.finance.calc;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Due and discount dates from payment terms (FIN-AR-002). */
class PaymentTermsTest {

    private static final LocalDate JAN_6 = LocalDate.of(2026, 1, 6);

    @Test
    void net30FromTheSixthOfJanuaryIsDueOnTheFifthOfFebruary() {
        assertThat(PaymentTerms.net(30).dueDate(JAN_6)).isEqualTo(LocalDate.of(2026, 2, 5));
        assertThat(PaymentTerms.net(30).discountDate(JAN_6)).isEmpty();
        assertThat(PaymentTerms.net(30).discount(new BigDecimal("100.00"))).isEqualByComparingTo("0.00");
    }

    @Test
    void twoTenNet30OffersTheDiscountForTenDays() {
        PaymentTerms terms = new PaymentTerms(30, new BigDecimal("2"), 10, false);
        assertThat(terms.dueDate(JAN_6)).isEqualTo(LocalDate.of(2026, 2, 5));
        assertThat(terms.discountDate(JAN_6)).contains(LocalDate.of(2026, 1, 16));
        assertThat(terms.discountAvailable(JAN_6, LocalDate.of(2026, 1, 16))).isTrue();
        assertThat(terms.discountAvailable(JAN_6, LocalDate.of(2026, 1, 17))).isFalse();
        assertThat(terms.discount(new BigDecimal("53300.00"))).isEqualByComparingTo("1066.00");
        // Half a cent rounds away from zero.
        assertThat(terms.discount(new BigDecimal("0.25"))).isEqualByComparingTo("0.01");
    }

    @Test
    void endOfMonthTermsCountFromTheLastDayOfTheInvoicesMonth() {
        PaymentTerms terms = new PaymentTerms(30, null, null, true);
        assertThat(terms.dueDate(JAN_6)).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(terms.dueDate(LocalDate.of(2026, 2, 28))).isEqualTo(LocalDate.of(2026, 3, 30));
        assertThat(new PaymentTerms(10, new BigDecimal("1.5"), 10, true).discountDate(JAN_6))
            .contains(LocalDate.of(2026, 2, 10));
    }

    @Test
    void aDiscountNeedsItsPercentAndDaysWithinTheNetDays() {
        assertThatThrownBy(() -> new PaymentTerms(30, new BigDecimal("2"), null, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTerms(30, new BigDecimal("2"), 31, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTerms(-1, null, null, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void theDiscountDateNeverFollowsTheDueDate(@ForAll @IntRange(min = 0, max = 365) int net,
        @ForAll @IntRange(min = 0, max = 365) int discount, @ForAll @IntRange(min = 0, max = 3000) int day,
        @ForAll boolean endOfMonth) {
        int days = Math.min(net, discount);
        PaymentTerms terms = new PaymentTerms(net, BigDecimal.ONE, days, endOfMonth);
        LocalDate invoice = LocalDate.of(2024, 1, 1).plusDays(day);
        assertThat(terms.discountDate(invoice).orElseThrow()).isBeforeOrEqualTo(terms.dueDate(invoice));
        assertThat(terms.dueDate(invoice)).isAfterOrEqualTo(invoice);
    }
}
