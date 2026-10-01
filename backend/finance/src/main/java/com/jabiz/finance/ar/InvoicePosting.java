package com.jabiz.finance.ar;

import com.jabiz.finance.calc.Money;
import com.jabiz.finance.gl.JournalValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The general ledger lines of a receivables document (FIN-AR-003, 006; docs/finance/00-design.md section 5), in US
 * dollars: revenue per account and dimensions (each converted at the document's rate, design section 4.1), sales tax
 * on the sales tax account, and the receivable as their sum, so the entry balances whatever the rounding. An invoice
 * debits the receivable and credits revenue and tax; a credit memo the other way round. Pure computation.
 */
public final class InvoicePosting {

    /** A document line: its amount in the document's currency, its account and dimensions. */
    public record Line(BigDecimal amount, String account, String department, String location) {}

    /**
     * @param lines   the general ledger lines, receivable first
     * @param totalUsd the receivable in US dollars
     * @param taxUsd   the tax in US dollars
     */
    public record Result(List<JournalValidator.Line> lines, BigDecimal totalUsd, BigDecimal taxUsd) {}

    private InvoicePosting() {}

    /**
     * @param rate        US dollars per unit of the document's currency
     * @param tax         the document's tax in its currency
     * @param taxMemo     the memo of the tax line, such as the jurisdictions
     * @param receivableDepartment the department of the receivable line, if the account wants one
     */
    public static Result lines(boolean creditMemo, List<Line> lines, BigDecimal tax, BigDecimal rate,
        String receivableAccount, String salesTaxAccount, String memo, String taxMemo,
        String receivableDepartment) {
        Objects.requireNonNull(rate, "rate must not be null");
        Map<List<String>, BigDecimal> revenue = new LinkedHashMap<>();
        for (Line line : lines) {
            revenue.merge(Arrays.asList(line.account(), line.department(), line.location()), line.amount(),
                BigDecimal::add);
        }
        List<JournalValidator.Line> other = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<List<String>, BigDecimal> entry : revenue.entrySet()) {
            BigDecimal usd = Money.usd(entry.getValue().multiply(rate));
            if (usd.signum() == 0) {
                continue;
            }
            total = total.add(usd);
            other.add(side(!creditMemo, entry.getKey().get(0), usd, memo, entry.getKey().get(1),
                entry.getKey().get(2)));
        }
        BigDecimal taxUsd = Money.usd(tax.multiply(rate));
        if (taxUsd.signum() != 0) {
            total = total.add(taxUsd);
            other.add(side(!creditMemo, salesTaxAccount, taxUsd, taxMemo, null, null));
        }
        List<JournalValidator.Line> result = new ArrayList<>();
        if (total.signum() != 0) {
            result.add(side(creditMemo, receivableAccount, total, memo, receivableDepartment, null));
        }
        result.addAll(other);
        return new Result(List.copyOf(result), total, taxUsd);
    }

    /** A line on {@code account}: a credit when {@code credit}, else a debit. */
    private static JournalValidator.Line side(boolean credit, String account, BigDecimal amount, String memo,
        String department, String location) {
        String text = memo == null ? null : memo.length() > 200 ? memo.substring(0, 200) : memo;
        return new JournalValidator.Line(account, credit ? null : amount, credit ? amount : null, text, department,
            location);
    }
}
