package com.jabiz.finance.io;

import com.jabiz.entity.SemanticKind;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.bank.BankEntities;
import com.jabiz.finance.bank.StatementProcesses;
import com.jabiz.finance.calc.StatementCheck;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.imports.ImportRow;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bank imports (FIN-BK-003, FIN-DI-001; ROADMAP F5a), each a whole file through one process:
 * <ul>
 *   <li>{@code finance.bank_statement}: a CSV statement of the bank account given as a parameter, in the sample's
 *       columns ({@code date,bank_reference,description,amount}; others through a mapping), its balances on the rows
 *       referenced {@code OPENING} and {@code CLOSING}.</li>
 *   <li>{@code finance.bank_statement_bai2} and {@code finance.bank_statement_camt053}: the same in BAI2 and ISO 20022
 *       camt.053 ({@link Bai2Parser}, {@link Camt053Parser}); the file names its account, which must be the
 *       parameter's.</li>
 *   <li>{@code finance.bank_opening_items}: the outstanding items of a bank account at the cutover, with the statement
 *       balance that day as a parameter.</li>
 * </ul>
 * A statement's lines must add up to its closing balance before anything is read further; {@code
 * FIN_BANK_STATEMENT_RECORD} checks the rest and skips the lines stored before. The same file twice is refused by the
 * platform and the user told so.
 */
@Configuration
class StatementImports {

    /** Statement files: text (CSV, BAI2) and XML (camt.053), kept as uploaded with the import's record. */
    static final String FILE_POLICY = "fin.bank.statement";

    static final String OPENING_REFERENCE = "OPENING";
    static final String CLOSING_REFERENCE = "CLOSING";

    public static final String NO_BALANCES = "FIN_BANK_STATEMENT_BALANCES";

    private static final SemanticKind DATE = new SemanticKind.Date();
    private static final SemanticKind USD = new SemanticKind.Monetary("USD", 2);
    private static final SemanticKind REFERENCE = new SemanticKind.Text(60, false);
    private static final SemanticKind TEXT = new SemanticKind.Text(500, false);
    private static final SemanticKind CODE = new SemanticKind.Text(40, false);

    /** @param bankCode the bank account the statement is of */
    record StatementParams(@NotBlank @Size(max = 20) String bankCode) {}

    /** @param statementBalance the balance on the bank's statement at the cutover */
    record OpeningParams(@NotBlank @Size(max = 20) String bankCode,
        @NotNull @Digits(integer = 13, fraction = 2) BigDecimal statementBalance) {}

    @Bean
    FilePolicy bankStatementPolicy() {
        return FilePolicy.define(FILE_POLICY)
            .allow(MediaTypes.TEXT, MediaTypes.XML)
            .maxBytes(25 * FilePolicy.MB)
            .permissions(FinancePermissions.BANK_STATEMENT_IMPORT, FinancePermissions.BANK_STATEMENT_IMPORT)
            .build();
    }

    @Bean
    ImportDefinition<StatementParams> csvStatementImport() {
        return ImportDefinition.define("finance.bank_statement", 1, StatementParams.class)
            .file(FILE_POLICY, ImportFormat.csv())
            .field("date", DATE, true, "date", "value_date", "booking_date")
            .field("reference", REFERENCE, false, "bank_reference", "reference")
            .field("description", TEXT, false, "description", "text")
            .field("amount", USD, true, "amount")
            .field("type", CODE, false, "type", "type_code")
            // The whole file is one statement: only together do its lines meet its balances.
            .perGroup(row -> "statement", StatementProcesses.RECORD, 1, StatementImports::csvStatement)
            .fileCheck(StatementImports::checkCsv)
            .permissions(FinancePermissions.BANK_STATEMENT_IMPORT)
            .build();
    }

    @Bean
    ImportDefinition<StatementParams> bai2StatementImport() {
        return parsed("finance.bank_statement_bai2", ImportFormat.custom("BAI2", new Bai2Parser()), BankEntities.BAI2);
    }

    @Bean
    ImportDefinition<StatementParams> camt053StatementImport() {
        return parsed("finance.bank_statement_camt053", ImportFormat.custom("camt.053", new Camt053Parser()),
            BankEntities.CAMT053);
    }

    private static ImportDefinition<StatementParams> parsed(String id, ImportFormat format, String formatCode) {
        return ImportDefinition.define(id, 1, StatementParams.class)
            .file(FILE_POLICY, format)
            .field("date", DATE, true, StatementColumns.DATE)
            .field("reference", REFERENCE, false, StatementColumns.REFERENCE)
            .field("description", TEXT, false, StatementColumns.DESCRIPTION)
            .field("amount", USD, true, StatementColumns.AMOUNT)
            .field("type", CODE, false, StatementColumns.TYPE)
            .field("account", new SemanticKind.Text(4, false), false, StatementColumns.ACCOUNT)
            .field("from", DATE, true, StatementColumns.FROM)
            .field("to", DATE, true, StatementColumns.TO)
            .field("opening", USD, true, StatementColumns.OPENING)
            .field("closing", USD, true, StatementColumns.CLOSING)
            .field("currency", new SemanticKind.Text(3, false), false, StatementColumns.CURRENCY)
            .perGroup(row -> "statement", StatementProcesses.RECORD, 1,
                (rows, params) -> parsedStatement(rows, params, formatCode))
            .fileCheck(StatementImports::checkParsed)
            .permissions(FinancePermissions.BANK_STATEMENT_IMPORT)
            .build();
    }

    @Bean
    ImportDefinition<OpeningParams> bankOpeningItemsImport() {
        return ImportDefinition.define("finance.bank_opening_items", 1, OpeningParams.class)
            .file(FinanceImports.FILE_POLICY, ImportFormat.csv())
            .field("date", DATE, true, "date", "item_date")
            .field("reference", new SemanticKind.Text(40, false), true, "reference", "document", "check")
            .field("description", TEXT, false, "description")
            .field("amount", USD, true, "amount")
            .perGroup(row -> "items", StatementProcesses.OPENING_ITEMS, 1, (rows, params) ->
                new StatementProcesses.OpeningInput(params.bankCode(), params.statementBalance(), rows.stream()
                    .map(row -> new StatementProcesses.ItemInput((LocalDate) row.get("date"), row.text("reference"),
                        row.text("description"), row.decimal("amount"))).toList()))
            .totals("amount")
            .permissions(FinancePermissions.MIGRATION)
            .build();
    }

    static StatementProcesses.StatementInput csvStatement(List<ImportRow> rows, StatementParams params) {
        ImportRow opening = balance(rows, OPENING_REFERENCE);
        ImportRow closing = balance(rows, CLOSING_REFERENCE);
        List<StatementProcesses.LineInput> lines = new ArrayList<>();
        for (ImportRow row : rows) {
            if (row != opening && row != closing) {
                lines.add(line(row));
            }
        }
        return new StatementProcesses.StatementInput(params.bankCode(), BankEntities.CSV,
            (LocalDate) opening.get("date"), (LocalDate) closing.get("date"), opening.decimal("amount"),
            closing.decimal("amount"), null, null, lines);
    }

    static StatementProcesses.StatementInput parsedStatement(List<ImportRow> rows, StatementParams params,
        String format) {
        ImportRow first = rows.getFirst();
        String currency = first.text("currency");
        return new StatementProcesses.StatementInput(params.bankCode(), format, (LocalDate) first.get("from"),
            (LocalDate) first.get("to"), first.decimal("opening"), first.decimal("closing"), first.text("account"),
            currency == null || currency.isBlank() ? null : currency, rows.stream().map(StatementImports::line)
                .toList());
    }

    private static StatementProcesses.LineInput line(ImportRow row) {
        return new StatementProcesses.LineInput((LocalDate) row.get("date"), row.text("reference"),
            row.text("description"), row.decimal("amount"), row.text("type"));
    }

    /** The one row referenced {@code OPENING} or {@code CLOSING}; the file check has made sure there is one. */
    private static ImportRow balance(List<ImportRow> rows, String reference) {
        return rows.stream().filter(row -> reference.equals(upper(row.text("reference")))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("The statement has no " + reference + " row"));
    }

    /** One opening and one closing row, and the lines between them add up (FIN-BK-003, acceptance 3). */
    static void checkCsv(ImportDefinition.FileContent<StatementParams> content, ImportDefinition.Issues issues) {
        List<ImportRow> openings = content.rows().stream()
            .filter(row -> OPENING_REFERENCE.equals(upper(row.text("reference")))).toList();
        List<ImportRow> closings = content.rows().stream()
            .filter(row -> CLOSING_REFERENCE.equals(upper(row.text("reference")))).toList();
        if (openings.size() != 1 || closings.size() != 1) {
            issues.file(NO_BALANCES, "A statement has one row referenced OPENING and one referenced CLOSING with its "
                + "balances", Map.of());
            return;
        }
        List<StatementCheck.Line> lines = content.rows().stream()
            .filter(row -> row != openings.getFirst() && row != closings.getFirst())
            .map(row -> new StatementCheck.Line((LocalDate) row.get("date"), row.text("reference"),
                row.text("description"), row.decimal("amount"))).toList();
        report(StatementCheck.check((LocalDate) openings.getFirst().get("date"),
            (LocalDate) closings.getFirst().get("date"), openings.getFirst().decimal("amount"), lines,
            closings.getFirst().decimal("amount")), issues);
    }

    static void checkParsed(ImportDefinition.FileContent<StatementParams> content, ImportDefinition.Issues issues) {
        if (content.rows().isEmpty()) {
            return;
        }
        ImportRow first = content.rows().getFirst();
        List<StatementCheck.Line> lines = content.rows().stream()
            .map(row -> new StatementCheck.Line((LocalDate) row.get("date"), row.text("reference"),
                row.text("description"), row.decimal("amount"))).toList();
        report(StatementCheck.check((LocalDate) first.get("from"), (LocalDate) first.get("to"),
            first.decimal("opening"), lines, first.decimal("closing")), issues);
    }

    private static void report(List<StatementCheck.Problem> problems, ImportDefinition.Issues issues) {
        for (StatementCheck.Problem problem : problems) {
            issues.file(problem.code(), problem.message(), Map.of());
        }
    }

    private static String upper(String text) {
        return text == null ? null : text.trim().toUpperCase(Locale.ROOT);
    }
}
