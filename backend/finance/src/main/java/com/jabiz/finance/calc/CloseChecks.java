package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The automatic checks of a period's close (FIN-PC-004, FIN-CT-005; ROADMAP F8a), worked out from what the reports
 * show: each passes or fails with what it found and where its evidence is (the reports and their parameters), and
 * the hashes that make a close artifact reproducible (FIN-PC-005).
 */
public final class CloseChecks {

    /** Journal entries and documents dated in the period are all posted and approved. */
    public static final String ENTRIES_POSTED = "ENTRIES_POSTED";
    /** Every active bank account has a signed-off reconciliation of a statement closing in the period. */
    public static final String BANK_RECONCILED = "BANK_RECONCILED";
    /** The receivables and payables agings and the asset register equal their control accounts. */
    public static final String SUBLEDGERS = "SUBLEDGERS";
    /** The recurring entries and invoices of the period are made. */
    public static final String RECURRING_RUN = "RECURRING_RUN";
    /** The entries due to reverse by the period's end are reversed. */
    public static final String AUTO_REVERSALS = "AUTO_REVERSALS";
    /** The period's depreciation is run while assets are in service. */
    public static final String DEPRECIATION_RUN = "DEPRECIATION_RUN";
    /** The period's revaluation is run when foreign currency items are open at its end. */
    public static final String REVALUATION_RUN = "REVALUATION_RUN";
    /** Clearing and suspense accounts are at zero at the period's end (FIN-CT-005). */
    public static final String CLEARING_ZERO = "CLEARING_ZERO";

    /** Every check, in the order a checklist shows them. */
    public static final List<String> ALL = List.of(ENTRIES_POSTED, BANK_RECONCILED, SUBLEDGERS, RECURRING_RUN,
        AUTO_REVERSALS, DEPRECIATION_RUN, REVALUATION_RUN, CLEARING_ZERO);

    /** The template listing the exceptions of every check but the subledgers and the revaluation. */
    public static final String EXCEPTIONS_TEMPLATE = "finance.close.exceptions";

    /** The longest result kept; a longer one ends with how many more there were. */
    static final int MAX_RESULT = 2000;

    /** @param evidence the reports showing it: {@code template?name=value&…}, several separated by {@code "; "} */
    public record Result(String code, boolean passed, String result, String evidence) {
        public Result {
            Objects.requireNonNull(code, "code");
        }
    }

    /** One exception, as the template lists it. */
    public record Finding(String checkCode, String reference, String description) {}

    /**
     * A subledger beside its control accounts, both as the books carry them: receivables and asset cost as debits,
     * payables and accumulated depreciation as credits (positive).
     */
    public record Subledger(String code, String name, BigDecimal subledger, BigDecimal ledger) {
        public BigDecimal difference() {
            return subledger.subtract(ledger);
        }
    }

    /** A trial balance's account as an artifact keeps it. */
    public record Account(String code, String name, BigDecimal debit, BigDecimal credit) {}

    /** The result of a check the exceptions template answers: passed when it lists nothing for it. */
    public static Result fromExceptions(String code, List<Finding> exceptions, String periodKey) {
        List<Finding> mine = exceptions.stream().filter(e -> code.equals(e.checkCode())).toList();
        String evidence = EXCEPTIONS_TEMPLATE + "?periodKey=" + periodKey + "&checkCode=" + code;
        if (mine.isEmpty()) {
            return new Result(code, true, "No exceptions", evidence);
        }
        List<String> items = mine.stream().map(e -> e.reference() + ": " + e.description()).toList();
        return new Result(code, false, list(mine.size() + (mine.size() == 1 ? " exception" : " exceptions"),
            items), evidence);
    }

    /** The subledgers against their control accounts on the period's last day. */
    public static Result subledgers(List<Subledger> subledgers, LocalDate end) {
        String evidence = "finance.ar.aging?agingDate=" + end + "; finance.ap.aging?agingDate=" + end
            + "; finance.fa.register?asOf=" + end + "; finance.gl.trial_balance?through=" + end;
        List<String> differences = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (Subledger s : subledgers) {
            String text = s.name() + " " + plain(s.subledger()) + " against the control accounts " + plain(s.ledger());
            all.add(text);
            if (s.difference().signum() != 0) {
                differences.add(text + ", a difference of " + plain(s.difference()));
            }
        }
        return differences.isEmpty()
            ? new Result(SUBLEDGERS, true, String.join("; ", all), evidence)
            : new Result(SUBLEDGERS, false, list("Subledgers differ from their control accounts", differences),
                evidence);
    }

    /** The revaluation is needed when foreign currency items are open at the period's end. */
    public static Result revaluation(int openItems, String runNo, String periodKey, LocalDate end) {
        String evidence = "finance.fx.revaluation_items?revaluationDate=" + end;
        if (openItems == 0) {
            return new Result(REVALUATION_RUN, true, "No foreign currency items open on " + end, evidence);
        }
        return runNo != null
            ? new Result(REVALUATION_RUN, true, "Run " + runNo + " for " + openItems + " items", evidence)
            : new Result(REVALUATION_RUN, false, openItems + " foreign currency items open on " + end
                + " and no revaluation run for " + periodKey, evidence);
    }

    /**
     * The control totals of the subledgers: each aging's or the register's total beside the balances of the accounts
     * of its control class ({@code AR}, {@code AP}, {@code FA_COST}, {@code FA_ACCUM}), debit positive.
     */
    public static List<Subledger> compare(BigDecimal receivables, BigDecimal payables, BigDecimal assetCost,
        BigDecimal accumulated, Map<String, String> controlClasses, Map<String, BigDecimal> balances) {
        Map<String, BigDecimal> byClass = new LinkedHashMap<>();
        controlClasses.forEach((account, controlClass) -> byClass.merge(controlClass,
            balances.getOrDefault(account, BigDecimal.ZERO), BigDecimal::add));
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        return List.of(
            new Subledger("AR", "Receivables aging", money(receivables), money(byClass.getOrDefault("AR", zero))),
            new Subledger("AP", "Payables aging", money(payables),
                money(byClass.getOrDefault("AP", zero)).negate()),
            new Subledger("FA_COST", "Asset register cost", money(assetCost),
                money(byClass.getOrDefault("FA_COST", zero))),
            new Subledger("FA_ACCUM", "Asset register accumulated depreciation", money(accumulated),
                money(byClass.getOrDefault("FA_ACCUM", zero)).negate()));
    }

    /**
     * The trial balance's hash: its accounts in order, each code, debit and credit. Names are left out: a rename
     * after the close changes no figure.
     */
    public static String trialBalanceHash(List<Account> accounts) {
        StringBuilder text = new StringBuilder();
        for (Account a : accounts) {
            text.append(a.code()).append('|').append(plain(a.debit())).append('|')
                .append(plain(a.credit())).append('\n');
        }
        return sha256(text.toString());
    }

    /** The hash of an artifact: its heading and every line, each field in order, nulls empty. */
    public static String contentHash(List<?> heading, List<List<Object>> lines) {
        StringBuilder text = new StringBuilder(String.join("|", heading.stream().map(CloseChecks::text).toList()));
        text.append('\n');
        for (List<Object> line : lines) {
            text.append(String.join("|", line.stream().map(CloseChecks::text).toList())).append('\n');
        }
        return sha256(text.toString());
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof BigDecimal d ? plain(d) : String.valueOf(value);
    }

    private static String list(String heading, List<String> items) {
        StringBuilder text = new StringBuilder(heading).append(": ");
        for (int i = 0; i < items.size(); i++) {
            String item = (i == 0 ? "" : "; ") + items.get(i);
            String more = "; and " + (items.size() - i) + " more";
            if (text.length() + item.length() + (i == items.size() - 1 ? 0 : more.length()) > MAX_RESULT) {
                return text.append(i == 0 ? more.substring(2) : more).toString();
            }
            text.append(item);
        }
        return text.toString();
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private CloseChecks() {}
}
