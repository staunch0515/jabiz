package com.jabiz.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * The transaction-currency side of a ledger entry (docs/design/11-ledger-events-jobs.md section 1.8; decision D24
 * item 5): an amount in a currency other than the ledger's and the rate that converts it into the ledger currency
 * (units of the ledger currency per unit of {@code currency}).
 *
 * @param currency ISO 4217 code
 * @param amount   positive amount in {@code currency}
 * @param rate     positive exchange rate, at most {@value #RATE_SCALE} decimal places
 */
public record ForeignAmount(String currency, BigDecimal amount, BigDecimal rate) {

    /** Most decimal places of a rate. */
    public static final int RATE_SCALE = 10;

    /** Decimal places of a currency without a standard number (such as gold): the ledger's column allows four. */
    static final int DEFAULT_CURRENCY_SCALE = 4;

    public ForeignAmount {
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(rate, "rate must not be null");
    }

    /**
     * The amount in the ledger currency: amount × rate, rounded half away from zero to the ledger's {@code scale}.
     * The ledger requires the functional amount of a foreign-currency entry to be exactly this.
     */
    public BigDecimal converted(int scale) {
        return amount.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
    }

    /** The standard decimal places of an ISO 4217 currency; empty (-1) for an unknown code. */
    static int scaleOf(String currency) {
        try {
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            return digits < 0 ? DEFAULT_CURRENCY_SCALE : Math.min(digits, DEFAULT_CURRENCY_SCALE);
        } catch (IllegalArgumentException | NullPointerException e) {
            return -1;
        }
    }
}
