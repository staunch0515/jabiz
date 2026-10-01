package com.jabiz.finance.io;

import com.jabiz.entity.SemanticKind;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.ar.CustomerProcesses;
import com.jabiz.finance.tax.TaxProcesses;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.imports.ImportRow;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The receivables' master data imports (FIN-DI-001; ROADMAP F3a), each row through the process used by hand:
 * <ul>
 *   <li>{@code finance.customers}: one {@code FIN_CUSTOMER_SAVE} per row ({@link CustomerRows} reads the sample's
 *       location and certificate columns); a legacy code the migration merged into another customer creates
 *       nothing.</li>
 *   <li>{@code finance.tax_codes}: one {@code FIN_TAX_CODE_SAVE} per row with its jurisdictions and their rates from
 *       the date given ({@link TaxCodeRows}).</li>
 * </ul>
 */
@Configuration
class ReceivablesImports {

    private static final SemanticKind CODE = new SemanticKind.Text(20, false);
    private static final SemanticKind TEXT = new SemanticKind.Text(200, false);

    /** @param ratesFrom the date the file's rates take effect */
    record TaxCodeParams(@NotNull LocalDate ratesFrom) {}

    @Bean
    ImportDefinition<ImportDefinition.NoParams> customerImport() {
        return ImportDefinition.define("finance.customers", 1)
            .file(FinanceImports.FILE_POLICY, ImportFormat.csv())
            .field("customerCode", CODE, true, "id", "code", "customer", "customer_code")
            .field("legalName", TEXT, true, "name", "legal_name")
            .field("location", TEXT, false, "location")
            .field("street", TEXT, false, "street", "address")
            .field("city", new SemanticKind.Text(100, false), false, "city")
            .field("state", CODE, false, "state")
            .field("postalCode", CODE, false, "postal_code", "zip")
            .field("country", new SemanticKind.Text(60, false), false, "country")
            .field("currency", new SemanticKind.Text(3, false), true, "currency")
            .field("taxCode", CODE, true, "tax_code")
            .field("termsCode", CODE, false, "terms", "terms_code")
            .field("termsDays", new SemanticKind.Numeric(3, 0), false, "terms_days")
            .field("creditLimit", new SemanticKind.Monetary("USD", 2), false, "credit_limit")
            .field("contactName", new SemanticKind.Text(100, false), false, "contact", "contact_name")
            .field("contactEmail", TEXT, false, "email", "contact_email")
            .field("contactPhone", new SemanticKind.Text(40, false), false, "phone", "contact_phone")
            .field("certificate", new SemanticKind.Text(500, false), false, "exemption_certificate", "certificate")
            .perRow(CustomerProcesses.SAVE, 1, (row, params) -> customer(row))
            .permissions(FinancePermissions.CUSTOMER_MAINTAIN)
            .build();
    }

    static CustomerProcesses.CustomerInput customer(ImportRow row) {
        CustomerRows.Location location = CustomerRows.location(row.text("location"));
        CustomerProcesses.Address address = new CustomerProcesses.Address(row.text("street"),
            first(row.text("city"), location.city()), first(row.text("state"), location.state()),
            row.text("postalCode"), first(row.text("country"), location.country()));
        BigDecimal days = row.decimal("termsDays");
        return new CustomerProcesses.CustomerInput(row.text("customerCode"), row.text("legalName"), address, address,
            row.text("contactName"), row.text("contactEmail"), row.text("contactPhone"), row.text("currency"),
            row.text("termsCode"), days == null ? null : days.intValueExact(), row.decimal("creditLimit"),
            row.text("taxCode"), null, null, CustomerRows.certificate(row.text("certificate")));
    }

    @Bean
    ImportDefinition<TaxCodeParams> taxCodeImport() {
        return ImportDefinition.define("finance.tax_codes", 1, TaxCodeParams.class)
            .file(FinanceImports.FILE_POLICY, ImportFormat.csv())
            .field("code", CODE, true, "code", "tax_code")
            .field("description", TEXT, false, "description")
            .field("ratePercent", new SemanticKind.Numeric(7, 4), false, "rate_percent", "rate")
            .field("note", TEXT, false, "note")
            .field("kind", CODE, false, "kind")
            .field("reason", CODE, false, "reason")
            .field("state", CODE, false, "state")
            .field("jurisdictions", TEXT, false, "jurisdictions")
            .field("certificateRequired", new SemanticKind.Bool(), false, "certificate_required")
            .field("chargeCode", CODE, false, "charge_code")
            .perRow(TaxProcesses.CODE_SAVE, 1, (row, params) -> TaxCodeRows.read(new TaxCodeRows.Row(
                row.text("code"), row.text("description"), row.decimal("ratePercent"), row.text("note"),
                row.text("kind"), row.text("reason"), row.text("state"), row.text("jurisdictions"),
                row.bool("certificateRequired"), row.text("chargeCode")), params == null ? null : params.ratesFrom()))
            .permissions(FinancePermissions.TAX_MAINTAIN)
            .build();
    }

    private static String first(String explicit, String inferred) {
        return explicit != null && !explicit.isBlank() ? explicit : inferred;
    }
}
