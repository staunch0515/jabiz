package com.jabiz.finance.ap;

import com.jabiz.finance.fx.FxRates;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.runtime.EntityInstance;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payables in a foreign currency (F7 plan decisions D3, D4; FIN-FX-003, 004): the US dollars a bill, an application or
 * a payment carries. Rows from before F7 are in US dollars and have no dollar columns: their rate is 1 and their
 * dollars are their amounts.
 */
final class ApFx {

    private ApFx() {}

    static boolean dollars(String currency) {
        return currency == null || FxRates.USD.equals(currency);
    }

    static String currency(EntityInstance row) {
        String currency = row.get("currency");
        return currency == null ? FxRates.USD : currency;
    }

    static BigDecimal rate(EntityInstance row) {
        BigDecimal rate = row.get("exchangeRate");
        return rate == null ? BigDecimal.ONE : rate;
    }

    static BigDecimal openUsd(EntityInstance bill) {
        BigDecimal usd = bill.get("openAmountUsd");
        return usd == null ? bill.get("openAmount") : usd;
    }

    static BigDecimal totalUsd(EntityInstance bill) {
        BigDecimal usd = bill.get("totalUsd");
        return usd == null ? bill.get("total") : usd;
    }

    /** What an application took off its bill in US dollars. */
    static BigDecimal amountUsd(EntityInstance application) {
        BigDecimal usd = application.get("amountUsd");
        return usd == null ? application.get("amount") : usd;
    }

    /** What the payment or credit of an application gave in US dollars. */
    static BigDecimal sourceUsd(EntityInstance application) {
        BigDecimal usd = application.get("sourceAmountUsd");
        return usd == null ? amountUsd(application) : usd;
    }

    /**
     * The dollars {@code taken} (in the document's currency) takes off a bill or credit: all it still carries when it
     * clears it, so a settled document carries nothing; else at the document's own rate.
     */
    static BigDecimal cleared(EntityInstance document, BigDecimal taken) {
        if (taken.compareTo(document.get("openAmount")) == 0) {
            return openUsd(document);
        }
        return BillPosting.usd(taken, rate(document));
    }

    /**
     * The entry of a realized difference between payables and the realized gain or loss account: a gain (positive)
     * debits payables, a loss credits them.
     */
    static List<JournalProcesses.LineInput> difference(String payableAccount, String realizedAccount,
        BigDecimal gainLoss, String memo) {
        return gainLoss.signum() > 0
            ? List.of(new JournalProcesses.LineInput(payableAccount, gainLoss, null, memo, null, null),
                realized(realizedAccount, gainLoss, memo))
            : List.of(realized(realizedAccount, gainLoss, memo),
                new JournalProcesses.LineInput(payableAccount, null, gainLoss.negate(), memo, null, null));
    }

    /** The realized line of a gain (a credit) or a loss (a debit). */
    static JournalProcesses.LineInput realized(String realizedAccount, BigDecimal gainLoss, String memo) {
        String text = (gainLoss.signum() > 0 ? "Realized exchange gain " : "Realized exchange loss ") + memo;
        text = text.length() > 200 ? text.substring(0, 200) : text;
        return gainLoss.signum() > 0
            ? new JournalProcesses.LineInput(realizedAccount, null, gainLoss, text, null, null)
            : new JournalProcesses.LineInput(realizedAccount, gainLoss.negate(), null, text, null, null);
    }
}
