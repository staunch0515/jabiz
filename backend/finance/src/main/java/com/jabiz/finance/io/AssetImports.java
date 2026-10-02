package com.jabiz.finance.io;

import com.jabiz.entity.SemanticKind;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.fa.AssetOpeningProcesses;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.imports.ImportRow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code finance.fixed_assets}: the legacy fixed asset register (FIN-SCN-01 step 3; ROADMAP F6a) as one
 * {@code FIN_FA_OPENING}, which refuses it unless it adds up to the opening entry's asset accounts. The sample's
 * columns: number, description, cost account, cost, in-service date, method, life in months and the accumulated
 * depreciation at the cutover; the class's defaults stand for what a file leaves out.
 */
@Configuration
class AssetImports {

    private static final SemanticKind CODE = new SemanticKind.Text(20, false);

    @Bean
    ImportDefinition<ImportDefinition.NoParams> fixedAssetImport() {
        return ImportDefinition.define("finance.fixed_assets", 1)
            .file(FinanceImports.FILE_POLICY, ImportFormat.csv())
            .field("assetNo", CODE, true, "id", "asset", "asset_no", "number")
            .field("description", new SemanticKind.Text(500, false), true, "description", "name")
            .field("costAccount", CODE, true, "account", "cost_account")
            .field("cost", new SemanticKind.Monetary("USD", 2), true, "cost")
            .field("inServiceDate", new SemanticKind.Date(), true, "in_service", "in_service_date", "placed_in_service")
            .field("method", new SemanticKind.Text(30, false), false, "method")
            .field("lifeMonths", new SemanticKind.Numeric(4, 0), false, "life_months", "life")
            .field("accumulated", new SemanticKind.Monetary("USD", 2), true, "accumulated_at_2025_12_31",
                "accumulated", "accumulated_depreciation")
            .field("salvage", new SemanticKind.Monetary("USD", 2), false, "salvage", "salvage_value")
            .field("convention", new SemanticKind.Text(12, false), false, "convention")
            .field("totalUnits", new SemanticKind.Numeric(15, 2), false, "total_units", "units")
            .field("location", CODE, false, "location")
            .field("custodian", new SemanticKind.Text(100, false), false, "custodian")
            // The whole file is one register: only together can it be checked against the ledger.
            .perGroup(row -> "register", AssetOpeningProcesses.OPENING, 1, (rows, params) ->
                new AssetOpeningProcesses.OpeningInput(rows.stream().map(AssetImports::item).toList()))
            .totals("cost", "accumulated")
            .permissions(FinancePermissions.MIGRATION)
            .build();
    }

    static AssetOpeningProcesses.Item item(ImportRow row) {
        BigDecimal life = row.decimal("lifeMonths");
        return new AssetOpeningProcesses.Item(row.text("assetNo"), row.text("description"), row.text("costAccount"),
            row.decimal("cost"), (LocalDate) row.get("inServiceDate"), row.text("method"),
            life == null ? null : life.intValueExact(), row.decimal("accumulated"), row.decimal("salvage"),
            row.text("convention"), row.decimal("totalUnits"), row.text("location"), row.text("custodian"));
    }
}
