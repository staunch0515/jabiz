package com.jabiz.finance.ap;

import com.jabiz.finance.gl.JournalValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The general ledger lines of a payables document (FIN-AP-004, FIN-TX-007; docs/finance/00-design.md section 5), in US
 * dollars: the lines' expense or asset accounts with their dimensions, the payable as their sum, and use tax accrued
 * on the line's account against the use tax account (it is the state's, not the vendor's). A bill debits the accounts
 * and credits payables; a vendor credit the other way round. Pure computation.
 */
public final class BillPosting {

    /** A document line: its amount, its account and dimensions, and the use tax it accrues. */
    public record Line(BigDecimal amount, String account, String department, String location, BigDecimal useTax) {}

    /** @param lines the general ledger lines, payable first */
    public record Result(List<JournalValidator.Line> lines, BigDecimal total, BigDecimal useTax) {}

    private BillPosting() {}

    public static Result lines(boolean credit, List<Line> lines, String payableAccount, String useTaxAccount,
        String memo, String useTaxMemo) {
        Map<List<String>, BigDecimal> accounts = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal useTax = BigDecimal.ZERO;
        for (Line line : lines) {
            BigDecimal tax = line.useTax() == null ? BigDecimal.ZERO : line.useTax();
            // Use tax is part of what the purchase cost: on the line's own account.
            accounts.merge(Arrays.asList(line.account(), line.department(), line.location()),
                line.amount().add(tax), BigDecimal::add);
            total = total.add(line.amount());
            useTax = useTax.add(tax);
        }
        List<JournalValidator.Line> result = new ArrayList<>();
        if (total.signum() != 0) {
            result.add(side(!credit, payableAccount, total, memo, null, null));
        }
        accounts.forEach((key, amount) -> {
            if (amount.signum() != 0) {
                result.add(side(credit, key.get(0), amount, memo, key.get(1), key.get(2)));
            }
        });
        if (useTax.signum() != 0) {
            result.add(side(!credit, useTaxAccount, useTax, useTaxMemo, null, null));
        }
        return new Result(List.copyOf(result), total, useTax);
    }

    /** A line on {@code account}: a credit when {@code credit}, else a debit. */
    private static JournalValidator.Line side(boolean credit, String account, BigDecimal amount, String memo,
        String department, String location) {
        String text = memo == null ? null : memo.length() > 200 ? memo.substring(0, 200) : memo;
        return new JournalValidator.Line(account, credit ? null : amount, credit ? amount : null, text, department,
            location);
    }
}
