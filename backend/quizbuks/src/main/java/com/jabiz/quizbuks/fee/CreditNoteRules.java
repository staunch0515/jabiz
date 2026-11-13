package com.jabiz.quizbuks.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * F-M7 (docs/quizbuks/plans/impl/F-M.md §10.3-2, N7, C4): how much of a fee a credit note may be for, and its tax,
 * pure Java. A fee's notes together never come to more than its amount, nor their taxes to more than its tax: a note
 * is at most what is not credited yet; its tax = min(⌊amount × the fee's tax rate⌋, the fee's tax less the notes'
 * taxes so far), and the note that credits the whole amount takes the rest of the tax.
 */
public final class CreditNoteRules {

    private CreditNoteRules() {}

    /**
     * What a fee may still be credited, before tax.
     *
     * @param feeAmount the fee's amount before tax
     * @param credited  its notes' amounts so far
     */
    public static BigDecimal creditable(BigDecimal feeAmount, BigDecimal credited) {
        return feeAmount.subtract(credited).max(BigDecimal.ZERO);
    }

    /**
     * The tax of a note of {@code amount} on a fee of {@code feeAmount} and {@code feeTax} at {@code taxRate}, whose
     * notes so far came to {@code credited} with {@code creditedTax}. The caller has checked that {@code amount} is
     * within {@link #creditable}.
     */
    public static BigDecimal tax(BigDecimal amount, BigDecimal feeAmount, BigDecimal feeTax, BigDecimal taxRate,
        BigDecimal credited, BigDecimal creditedTax) {
        BigDecimal taxLeft = feeTax.subtract(creditedTax).max(BigDecimal.ZERO);
        if (credited.add(amount).compareTo(feeAmount) >= 0) {
            // The whole amount credited: the rest of the tax, so that the notes' taxes come to the fee's.
            return taxLeft;
        }
        BigDecimal byRate = amount.multiply(taxRate).setScale(0, RoundingMode.FLOOR);
        return byRate.min(taxLeft);
    }
}
