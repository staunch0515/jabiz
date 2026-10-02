package com.jabiz.finance.io;

import com.jabiz.entity.SemanticKind;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.VendorProcesses;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.imports.ImportRow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * The payables' master data imports (FIN-DI-001; ROADMAP F4a), each row through the process used by hand:
 * <ul>
 *   <li>{@code finance.vendors}: one {@code FIN_VENDOR_SAVE} per row ({@link VendorRows} reads the sample's entity
 *       type and 1099 columns); the currency is US dollars where the file names none. A file with one row that does
 *       not read is refused whole.</li>
 *   <li>{@code finance.ap_thresholds}: the 1099 threshold table, one {@code FIN_1099_THRESHOLD_SET} per tax year; the
 *       sample's single column is the threshold of 1099-NEC and 1099-MISC alike.</li>
 * </ul>
 */
@Configuration
class PayablesImports {

    private static final SemanticKind CODE = new SemanticKind.Text(20, false);
    private static final SemanticKind TEXT = new SemanticKind.Text(200, false);

    @Bean
    ImportDefinition<ImportDefinition.NoParams> vendorImport() {
        return ImportDefinition.define("finance.vendors", 1)
            .file(FinanceImports.FILE_POLICY, ImportFormat.csv())
            .field("vendorCode", CODE, true, "id", "code", "vendor", "vendor_code")
            .field("legalName", TEXT, true, "name", "legal_name")
            .field("dbaName", TEXT, false, "dba", "dba_name")
            .field("entityType", new SemanticKind.Text(40, false), true, "entity_type")
            .field("form1099", new SemanticKind.Text(60, false), false, "form_1099", "1099")
            .field("tinOnFile", new SemanticKind.Text(10, false), false, "tin_on_file", "w9_on_file")
            .field("currency", new SemanticKind.Text(3, false), false, "currency")
            .field("termsCode", CODE, false, "terms", "terms_code")
            .field("termsDays", new SemanticKind.Numeric(3, 0), false, "terms_days")
            .field("expenseAccount", CODE, false, "expense_account")
            .field("paymentMethod", new SemanticKind.Text(10, false), false, "payment_method")
            .field("street", TEXT, false, "street", "address")
            .field("city", new SemanticKind.Text(100, false), false, "city")
            .field("state", CODE, false, "state")
            .field("postalCode", CODE, false, "postal_code", "zip")
            .field("country", new SemanticKind.Text(60, false), false, "country")
            .field("contactName", new SemanticKind.Text(100, false), false, "contact", "contact_name")
            .field("contactEmail", TEXT, false, "email", "contact_email")
            .field("contactPhone", new SemanticKind.Text(40, false), false, "phone", "contact_phone")
            .perRow(VendorProcesses.SAVE, 1, (row, params) -> vendor(row))
            .permissions(FinancePermissions.VENDOR_MAINTAIN)
            .build();
    }

    static VendorProcesses.VendorInput vendor(ImportRow row) {
        VendorRows.Form1099 form = VendorRows.form1099(row.text("form1099"));
        BigDecimal days = row.decimal("termsDays");
        String currency = row.text("currency");
        VendorProcesses.Address remit = new VendorProcesses.Address(row.text("street"), row.text("city"),
            row.text("state"), row.text("postalCode"), row.text("country"));
        return new VendorProcesses.VendorInput(row.text("vendorCode"), row.text("legalName"), row.text("dbaName"),
            remit, row.text("contactName"), row.text("contactEmail"), row.text("contactPhone"),
            currency == null || currency.isBlank() ? "USD" : currency, row.text("termsCode"),
            days == null ? null : days.intValueExact(), row.text("expenseAccount"), row.text("paymentMethod"),
            VendorRows.entityType(row.text("entityType")), form.form() == null ? "" : form.form(), form.box(),
            VendorRows.yes(row.text("tinOnFile")), null, null);
    }

    @Bean
    ImportDefinition<ImportDefinition.NoParams> thresholdImport() {
        return ImportDefinition.define("finance.ap_thresholds", 1)
            .file(FinanceImports.FILE_POLICY, ImportFormat.csv())
            .field("taxYear", new SemanticKind.Numeric(4, 0), true, "tax_year", "year")
            .field("form1099", new SemanticKind.Text(10, false), false, "form", "form_1099")
            .field("threshold", new SemanticKind.Monetary("USD", 2), true, "nec_misc_threshold_usd", "threshold_usd",
                "threshold")
            .perRow(ApSettingsProcesses.THRESHOLD_SET, 1, (row, params) -> new ApSettingsProcesses.ThresholdInput(
                row.decimal("taxYear").intValueExact(), row.text("form1099"), row.decimal("threshold")))
            .permissions(FinancePermissions.FORM_1099_MAINTAIN)
            .build();
    }
}
