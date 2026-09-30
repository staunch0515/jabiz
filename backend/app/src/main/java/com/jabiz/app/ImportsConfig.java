package com.jabiz.app;

import com.jabiz.app.commerce.CommerceProcesses;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.imports.ImportRow;
import com.jabiz.ledger.Direction;
import com.jabiz.runtime.ledger.LedgerProcesses;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The demonstration imports (docs/design/20-imports.md): stock receipts from CSV (one call per row, each receipt
 * imported once however often the file is sent), prices from an XLSX workbook and from an XML price list, and
 * opening balances from CSV (one ledger transaction per entry; a file whose debits and credits differ is refused
 * before anything is posted).
 */
@Configuration
class ImportsConfig {

    static final String FILE_POLICY = "app.import";
    private static final SemanticKind USD = new SemanticKind.Monetary("USD", 2);
    private static final SemanticKind TEXT = new SemanticKind.Text(100, false);

    /** @param bookingTime when the opening balances take effect, usually the end of the last closed period */
    record OpeningParams(@NotNull Instant bookingTime, @NotBlank String description) {}

    @Bean
    FilePolicy appImportPolicy() {
        return FilePolicy.define(FILE_POLICY)
            .allow(MediaTypes.TEXT, MediaTypes.XLSX, MediaTypes.XML)
            .maxBytes(5 * FilePolicy.MB)
            .permissions("app.import.upload", "app.import.read")
            .build();
    }

    @Bean
    ImportDefinition<ImportDefinition.NoParams> stockReceiptImport() {
        return ImportDefinition.define("commerce.stock", 1)
            .file(FILE_POLICY, ImportFormat.csv())
            .field("receipt", TEXT, true, "Receipt", "Receipt no")
            .field("warehouse", TEXT, true, "Warehouse")
            .field("sku", TEXT, true, "SKU")
            .field("quantity", new SemanticKind.Numeric(9, 0), true, "Quantity", "Qty")
            .externalRef(row -> row.text("receipt"), ImportDefinition.OnDuplicate.SKIP)
            .perRow(CommerceProcesses.STOCK_RECEIVE, 1, (row, params) -> new CommerceProcesses.ReceiveInput(
                row.text("warehouse"), row.text("sku"), row.decimal("quantity").intValueExact()))
            .totals("quantity")
            .permissions("commerce.stock.import", "commerce.stock.mapping")
            .build();
    }

    @Bean
    ImportDefinition<ImportDefinition.NoParams> priceImport() {
        return ImportDefinition.define("commerce.prices", 1)
            .file(FILE_POLICY, ImportFormat.xlsx())
            .field("sku", TEXT, true, "SKU")
            .field("unitPrice", USD, true, "Price", "Unit price")
            .field("effectiveTime", new SemanticKind.Temporal(TemporalRole.EVENT_TIME), false, "Effective", "From")
            .perRow(CommerceProcesses.PRODUCT_REPRICE, 1, ImportsConfig::reprice)
            .zone(ZoneId.of("America/New_York"))
            .datePatterns("MM/dd/yyyy")
            .permissions("commerce.price.import")
            .build();
    }

    @Bean
    ImportDefinition<ImportDefinition.NoParams> priceListImport() {
        return ImportDefinition.define("commerce.price-list", 1)
            .file(FILE_POLICY, ImportFormat.xml("PriceList/Item", "SKU", "@sku", "Price", "Price", "Effective",
                "Price/@from"))
            .field("sku", TEXT, true, "SKU")
            .field("unitPrice", USD, true, "Price")
            .field("effectiveTime", new SemanticKind.Temporal(TemporalRole.EVENT_TIME), false, "Effective")
            .perRow(CommerceProcesses.PRODUCT_REPRICE, 1, ImportsConfig::reprice)
            .permissions("commerce.price.import")
            .build();
    }

    private static CommerceProcesses.RepriceInput reprice(ImportRow row, ImportDefinition.NoParams params) {
        return new CommerceProcesses.RepriceInput(row.text("sku"), row.decimal("unitPrice"),
            row.instant("effectiveTime"));
    }

    @Bean
    ImportDefinition<OpeningParams> openingBalanceImport() {
        return ImportDefinition.define("ledger.opening", 1, OpeningParams.class)
            .file(FILE_POLICY, ImportFormat.csv())
            .field("entry", TEXT, true, "Entry")
            .field("account", TEXT, true, "Account")
            .field("debit", USD, false, "Debit")
            .field("credit", USD, false, "Credit")
            .field("memo", new SemanticKind.Text(200, false), false, "Memo")
            .perGroup(row -> row.text("entry"), LedgerProcesses.POST, 1, ImportsConfig::opening)
            .fileCheck((content, issues) -> {
                BigDecimal debits = BigDecimal.ZERO;
                BigDecimal credits = BigDecimal.ZERO;
                for (ImportRow row : content.rows()) {
                    boolean debit = row.decimal("debit") != null && row.decimal("debit").signum() != 0;
                    boolean credit = row.decimal("credit") != null && row.decimal("credit").signum() != 0;
                    if (debit == credit) {
                        issues.row(row, "debit", PlatformErrorCodes.INVALID_VALUE,
                            "A line has either a debit or a credit", Map.of());
                    }
                    debits = debits.add(debit ? row.decimal("debit") : BigDecimal.ZERO);
                    credits = credits.add(credit ? row.decimal("credit") : BigDecimal.ZERO);
                }
                if (debits.compareTo(credits) != 0) {
                    issues.file(PlatformErrorCodes.LEDGER_UNBALANCED, "Debits " + debits + " and credits " + credits
                        + " differ", Map.of("debit", debits, "credit", credits,
                        "difference", debits.subtract(credits).abs()));
                }
            })
            .totals("debit", "credit")
            .permissions("ledger.opening.import")
            .build();
    }

    private static LedgerProcesses.PostInput opening(List<ImportRow> rows, OpeningParams params) {
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        for (ImportRow row : rows) {
            boolean debit = row.decimal("debit") != null && row.decimal("debit").signum() != 0;
            lines.add(new LedgerProcesses.Line(row.text("account"), debit ? Direction.DEBIT : Direction.CREDIT,
                debit ? row.decimal("debit") : row.decimal("credit"), row.text("memo"), null));
        }
        return new LedgerProcesses.PostInput(params.bookingTime(), params.description(), rows.getFirst().text("entry"),
            lines);
    }
}
