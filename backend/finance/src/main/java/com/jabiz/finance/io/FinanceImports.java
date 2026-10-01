package com.jabiz.finance.io;

import com.jabiz.entity.SemanticKind;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.AccountProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.gl.ExchangeRateProcesses;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.finance.gl.OpeningProcesses;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.imports.ImportRow;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The finance imports (FIN-DI-001, FIN-DI-002, FIN-PC-002; docs/finance/00-design.md section 13), on the platform's
 * import framework: every row or group goes to the process used for entering it by hand, the whole file is checked
 * before anything is written and refused with every problem when any row is wrong, a preview is a dry run, and a
 * file is imported once.
 * <ul>
 *   <li>{@code finance.chart}: the chart of accounts, one {@code FIN_ACCOUNT_CREATE} per row in file order (a parent
 *       before its accounts); the sample company's chart and the chart template's columns both read.</li>
 *   <li>{@code finance.fx_rates}: exchange rates, one {@code FIN_EXCHANGE_RATE_SET} per row. A file with one column
 *       per pair ({@code date,eur_usd}) is read through a mapping: the rate column and the currencies as
 *       constants.</li>
 *   <li>{@code finance.opening_balances}: the opening trial balance as one {@code FIN_OPENING_POST}; debits and
 *       credits must agree before anything is posted, the difference is reported.</li>
 * </ul>
 */
@Configuration
class FinanceImports {

    /** The files of the finance imports: CSV and XLSX, kept as uploaded with the import's record. */
    static final String FILE_POLICY = "fin.import";

    static final String OPENING_DESCRIPTION = "Opening balances";

    private static final SemanticKind CODE = new SemanticKind.Text(20, false);
    private static final SemanticKind NAME = new SemanticKind.Text(200, false);
    private static final SemanticKind DATE = new SemanticKind.Date();
    private static final SemanticKind USD = new SemanticKind.Monetary("USD", 2);

    /** @param description of the opening entry; "Opening balances" when absent */
    record OpeningParams(@Size(max = 500) String description) {}

    @Bean
    FilePolicy financeImportPolicy() {
        return FilePolicy.define(FILE_POLICY)
            .allow(MediaTypes.TEXT, MediaTypes.XLSX)
            .maxBytes(25 * FilePolicy.MB)
            .permissions(FinancePermissions.IMPORT, FinancePermissions.IMPORT)
            .build();
    }

    @Bean
    ImportDefinition<ImportDefinition.NoParams> chartImport() {
        return ImportDefinition.define("finance.chart", 1)
            .file(FILE_POLICY, ImportFormat.csv())
            .field("accountCode", CODE, true, "code", "account", "account_code")
            .field("accountName", NAME, true, "name", "account_name")
            .field("financialType", CODE, true, "type", "financial_type")
            .field("normalBalance", CODE, true, "normal_balance", "balance")
            .field("statementLine", NAME, true, "statement_line")
            .field("parentCode", CODE, false, "parent", "parent_code")
            .field("summary", new SemanticKind.Bool(), false, "summary")
            .field("controlClass", CODE, false, "control_class")
            .field("cashFlowClass", CODE, false, "cash_flow", "cash_flow_class")
            .field("clearing", new SemanticKind.Bool(), false, "clearing")
            .field("requiredDimension", CODE, false, "required_dimension", "dimension")
            .perRow(AccountProcesses.CREATE, 1, (row, params) -> new AccountProcesses.AccountInput(
                row.text("accountCode"), row.text("accountName"), AccountTypes.fromChart(row.text("financialType")),
                AccountTypes.normalBalanceFromChart(row.text("normalBalance")), row.text("statementLine"),
                upper(row.text("cashFlowClass")), upper(row.text("controlClass")), row.bool("clearing"),
                lower(row.text("requiredDimension")), row.text("parentCode"), row.bool("summary")))
            .permissions(FinancePermissions.ACCOUNT_MAINTAIN)
            .build();
    }

    @Bean
    ImportDefinition<ImportDefinition.NoParams> exchangeRateImport() {
        return ImportDefinition.define("finance.fx_rates", 1)
            .file(FILE_POLICY, ImportFormat.csv())
            .field("rateDate", DATE, true, "date", "rate_date")
            .field("fromCurrency", new SemanticKind.Text(3, false), true, "from", "from_currency")
            .field("toCurrency", new SemanticKind.Text(3, false), true, "to", "to_currency")
            .field("rateType", new SemanticKind.Text(10, false), false, "type", "rate_type")
            .field("rate", new SemanticKind.Numeric(19, 10), true, "rate")
            .perRow(ExchangeRateProcesses.SET, 1, (row, params) -> new ExchangeRateProcesses.RateInput(
                row.text("fromCurrency"), row.text("toCurrency"), (LocalDate) row.get("rateDate"),
                row.text("rateType"), row.decimal("rate")))
            .permissions(FinancePermissions.FX_MAINTAIN)
            .build();
    }

    @Bean
    ImportDefinition<OpeningParams> openingBalanceImport() {
        return ImportDefinition.define("finance.opening_balances", 1, OpeningParams.class)
            .file(FILE_POLICY, ImportFormat.csv())
            .field("date", DATE, true, "date", "posting_date")
            .field("account", CODE, true, "account", "account_code")
            .field("debit", USD, false, "debit")
            .field("credit", USD, false, "credit")
            .field("memo", NAME, false, "memo")
            .field("department", CODE, false, "department")
            .field("location", CODE, false, "location")
            // The whole file is the one opening entry.
            .perGroup(row -> "opening", OpeningProcesses.POST_OPENING, 1, FinanceImports::opening)
            .fileCheck(FinanceImports::checkOpening)
            .totals("debit", "credit")
            .permissions(FinancePermissions.MIGRATION)
            .build();
    }

    static OpeningProcesses.OpeningInput opening(List<ImportRow> rows, OpeningParams params) {
        List<JournalProcesses.LineInput> lines = new ArrayList<>();
        for (ImportRow row : rows) {
            lines.add(new JournalProcesses.LineInput(row.text("account"), row.decimal("debit"), row.decimal("credit"),
                row.text("memo"), row.text("department"), row.text("location")));
        }
        String description = params == null || params.description() == null || params.description().isBlank()
            ? OPENING_DESCRIPTION : params.description().trim();
        return new OpeningProcesses.OpeningInput((LocalDate) rows.getFirst().get("date"), description, lines);
    }

    /**
     * One date for the whole entry, a debit or a credit on every line, and debits equal to credits; the difference
     * is reported (FIN-PC-002, acceptance 2). The process checks the rest: the date opens the first year, the
     * accounts exist and take postings.
     */
    static void checkOpening(ImportDefinition.FileContent<OpeningParams> content, ImportDefinition.Issues issues) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (ImportRow row : content.rows()) {
            dates.add((LocalDate) row.get("date"));
            BigDecimal debit = row.decimal("debit");
            BigDecimal credit = row.decimal("credit");
            boolean hasDebit = debit != null && debit.signum() != 0;
            boolean hasCredit = credit != null && credit.signum() != 0;
            if (hasDebit == hasCredit || (hasDebit ? debit : credit).signum() < 0) {
                issues.row(row, hasDebit ? "debit" : "credit", JournalValidator.LINE_AMOUNT,
                    "A line has either a debit or a credit, more than zero", Map.of("line", row.number()));
            }
            debits = debits.add(hasDebit ? debit : BigDecimal.ZERO);
            credits = credits.add(hasCredit ? credit : BigDecimal.ZERO);
        }
        if (dates.size() > 1) {
            issues.file(OPENING_DATES, "The opening entry has one date; the file has " + dates.size(),
                Map.of("dates", String.join(", ", dates.stream().map(LocalDate::toString).toList())));
        }
        if (debits.compareTo(credits) != 0) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("debit", debits);
            params.put("credit", credits);
            params.put("difference", debits.subtract(credits).abs());
            issues.file(JournalValidator.UNBALANCED, "Debits " + debits.toPlainString() + " and credits "
                + credits.toPlainString() + " differ by " + debits.subtract(credits).abs().toPlainString(), params);
        }
    }

    static final String OPENING_DATES = "FIN_OPENING_DATES";

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
