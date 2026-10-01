package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * Payment terms (FIN-AR-002): net days, an optional early-payment discount ("2/10 net 30": 2 % if paid within 10
 * days, otherwise due in 30) and the end-of-month variant, where the days count from the last day of the invoice's
 * month. Due and discount dates follow from the invoice date alone.
 *
 * @param netDays         days from the invoice date (or the end of its month) to the due date
 * @param discountPercent the early-payment discount, or null for none
 * @param discountDays    days within which the discount is offered; set exactly when the discount is
 * @param endOfMonth      whether the days count from the last day of the invoice's month
 */
public record PaymentTerms(int netDays, BigDecimal discountPercent, Integer discountDays, boolean endOfMonth) {

    public PaymentTerms {
        if (netDays < 0) {
            throw new IllegalArgumentException("net days must not be negative");
        }
        if ((discountPercent == null) != (discountDays == null)) {
            throw new IllegalArgumentException("a discount needs both its percent and its days");
        }
        if (discountPercent != null && (discountPercent.signum() <= 0 || discountPercent.compareTo(BigDecimal.valueOf(100)) >= 0
            || discountDays < 0 || discountDays > netDays)) {
            throw new IllegalArgumentException("a discount is above 0 and below 100 percent, within the net days");
        }
    }

    /** Plain net terms without discount. */
    public static PaymentTerms net(int days) {
        return new PaymentTerms(days, null, null, false);
    }

    /** The date the invoice is due. */
    public LocalDate dueDate(LocalDate invoiceDate) {
        return start(invoiceDate).plusDays(netDays);
    }

    /** The last day a payment earns the discount, if the terms offer one. */
    public Optional<LocalDate> discountDate(LocalDate invoiceDate) {
        return discountDays == null ? Optional.empty() : Optional.of(start(invoiceDate).plusDays(discountDays));
    }

    /** Whether a payment on {@code paidOn} earns the discount. */
    public boolean discountAvailable(LocalDate invoiceDate, LocalDate paidOn) {
        return discountDate(invoiceDate).map(last -> !paidOn.isAfter(last)).orElse(false);
    }

    /** The discount on {@code amount}, in cents; zero without a discount. */
    public BigDecimal discount(BigDecimal amount) {
        if (discountPercent == null) {
            return Money.usd(BigDecimal.ZERO);
        }
        return Money.usd(amount.multiply(discountPercent).movePointLeft(2));
    }

    private LocalDate start(LocalDate invoiceDate) {
        return endOfMonth ? invoiceDate.with(TemporalAdjusters.lastDayOfMonth()) : invoiceDate;
    }
}
