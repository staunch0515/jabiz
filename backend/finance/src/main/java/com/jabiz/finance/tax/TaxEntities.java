package com.jabiz.finance.tax;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Sales tax master data (FIN-TX-001, 002; docs/finance/00-design.md section 8): jurisdictions (a state, county, city
 * or special district), their rates with the dates they apply, and tax codes that combine jurisdictions or say why a
 * sale bears no tax. All temporal and written only by {@link TaxProcesses}, so a rate's history and who set it stay.
 */
public final class TaxEntities {

    public static final String JURISDICTION = "FinTaxJurisdiction";
    public static final String RATE = "FinTaxRate";
    public static final String CODE = "FinTaxCode";

    public static final String JURISDICTION_DATASET = "urn:jabiz:dataset:default:FinTaxJurisdiction";
    public static final String RATE_DATASET = "urn:jabiz:dataset:default:FinTaxRate";
    public static final String CODE_DATASET = "urn:jabiz:dataset:default:FinTaxCode";

    public static final String LEVELS = "urn:jabiz:dict:finance:tax-jurisdiction-level";
    public static final String KINDS = "urn:jabiz:dict:finance:tax-kind";
    public static final String REASONS = "urn:jabiz:dict:finance:tax-exempt-reason";

    public static final List<String> LEVEL_VALUES = List.of("STATE", "COUNTY", "CITY", "SPECIAL");
    public static final List<String> KIND_VALUES = List.of("TAXABLE", "EXEMPT", "NON_TAXABLE");
    /** Why a sale bears no tax, as the return data groups exempt sales (FIN-TX-008). */
    public static final List<String> REASON_VALUES =
        List.of("RESALE", "EXEMPT_ORGANIZATION", "GOVERNMENT", "NON_TAXABLE_SERVICE", "NO_SALES_TAX", "EXPORT", "OTHER");

    /** Codes of tax codes and jurisdictions: capitals, digits, hyphens (TX-AUSTIN). */
    public static final String CODE_PATTERN = "[A-Z0-9][A-Z0-9-]{0,19}";
    public static final String STATE_PATTERN = "[A-Z]{2}";

    public static final String RATE_DATES = "FIN_TAX_RATE_DATES";
    public static final String CODE_KIND = "FIN_TAX_CODE_KIND";

    public static final EntityDefinition JURISDICTION_ENTITY = EntityDefinition.define(JURISDICTION, eb -> {
        eb.physicalTable("fi_tax_jurisdiction_version");
        eb.primaryKey("jurisdictionId");
        eb.field("jurisdictionId", f -> f.physicalColumn("jurisdiction_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:tax-jurisdiction"));
        eb.field("jurisdictionCode", f -> f.physicalColumn("jurisdiction_code").immutable(true).required(true)
            .asText(20).apply(Rules.pattern("FIN_TAX_CODE_FORMAT", CODE_PATTERN)));
        eb.field("jurisdictionName", f -> f.physicalColumn("jurisdiction_name").required(true).asText(200)
            .apply(Rules.notBlank("FIN_TAX_NAME_BLANK")));
        eb.field("level", f -> f.physicalColumn("level").required(true).asCode(LEVELS, values(LEVEL_VALUES)));
        eb.field("state", f -> f.physicalColumn("state").required(true).asText(2)
            .apply(Rules.pattern("FIN_STATE_FORMAT", STATE_PATTERN)));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_fi_tax_jurisdiction_code", "jurisdictionCode");
        eb.display("jurisdictionCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("jurisdictionCode", "jurisdictionName", "level", "state", "active")
            .filters("jurisdictionCode", "level", "state", "active")
            .sorts("jurisdictionCode", "state")
            .defaultSort("jurisdictionCode", true));
    });

    /** A rate in percent from a date; {@code effectiveTo} follows from the next rate (FIN-TX-001 acceptance 2). */
    public static final EntityDefinition RATE_ENTITY = EntityDefinition.define(RATE, eb -> {
        eb.physicalTable("fi_tax_rate_version");
        eb.primaryKey("rateId");
        eb.field("rateId", f -> f.physicalColumn("rate_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:tax-rate"));
        eb.field("jurisdictionCode", f -> f.physicalColumn("jurisdiction_code").immutable(true).required(true)
            .asText(20));
        eb.field("effectiveFrom", f -> f.physicalColumn("effective_from").immutable(true).required(true).asDate());
        eb.field("effectiveTo", f -> f.physicalColumn("effective_to").asDate());
        eb.field("ratePercent", f -> f.physicalColumn("rate_percent").required(true).asNumeric(7, 4)
            .apply(Rules.range("FIN_TAX_RATE_RANGE", BigDecimal.ZERO, new BigDecimal("100"))));
        eb.check(RATE_DATES, (state, ctx) -> state.get("effectiveFrom") instanceof LocalDate from
            && state.get("effectiveTo") instanceof LocalDate to && to.isBefore(from)
            ? List.of(new Violation("effectiveTo", RATE_DATES, "A rate ends on or after the day it starts",
                Map.of())) : List.of());
        eb.unique("uk_fi_tax_rate", "jurisdictionCode", "effectiveFrom");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("jurisdictionCode", "effectiveFrom", "effectiveTo", "ratePercent")
            .filters("jurisdictionCode", "effectiveFrom")
            .sorts("jurisdictionCode", "effectiveFrom")
            .defaultSort("effectiveFrom", false));
    });

    public static final EntityDefinition CODE_ENTITY = EntityDefinition.define(CODE, eb -> {
        eb.physicalTable("fi_tax_code_version");
        eb.primaryKey("taxCodeId");
        eb.field("taxCodeId", f -> f.physicalColumn("tax_code_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:tax-code"));
        eb.field("taxCode", f -> f.physicalColumn("tax_code").immutable(true).required(true).asText(20)
            .apply(Rules.pattern("FIN_TAX_CODE_FORMAT", CODE_PATTERN)));
        eb.field("description", f -> f.physicalColumn("description").required(true).asText(200)
            .apply(Rules.notBlank("FIN_TAX_NAME_BLANK")));
        eb.field("kind", f -> f.physicalColumn("kind").required(true).asCode(KINDS, values(KIND_VALUES)));
        eb.field("reason", f -> f.physicalColumn("reason").asCode(REASONS, values(REASON_VALUES)));
        eb.field("state", f -> f.physicalColumn("state").asText(2)
            .apply(Rules.pattern("FIN_STATE_FORMAT", STATE_PATTERN)));
        // The jurisdictions whose rates add up to the code's, comma separated (TX,TX-AUSTIN-LOCAL).
        eb.field("jurisdictions", f -> f.physicalColumn("jurisdictions").asText(200));
        eb.field("certificateRequired", f -> f.physicalColumn("certificate_required").required(true).asBool());
        // Taxed under this code instead when a needed certificate is missing and the settings charge (FIN-TX-004).
        eb.field("chargeCode", f -> f.physicalColumn("charge_code").asText(20));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.check(CODE_KIND, (state, ctx) -> {
            boolean taxable = "TAXABLE".equals(state.get("kind"));
            Object jurisdictions = state.get("jurisdictions");
            boolean hasJurisdictions = jurisdictions instanceof String s && !s.isBlank();
            if (taxable != hasJurisdictions) {
                return List.of(new Violation("jurisdictions", CODE_KIND, "A taxable code names its jurisdictions; "
                    + "an exempt or non-taxable code names none", Map.of()));
            }
            if (!taxable && state.get("reason") == null) {
                return List.of(new Violation("reason", CODE_KIND, "An exempt or non-taxable code says why",
                    Map.of()));
            }
            if (Boolean.TRUE.equals(state.get("certificateRequired"))
                && (!"EXEMPT".equals(state.get("kind")) || state.get("state") == null)) {
                return List.of(new Violation("certificateRequired", CODE_KIND, "Only an exempt code of a state "
                    + "needs a certificate: certificates are per state", Map.of()));
            }
            return List.of();
        });
        eb.unique("uk_fi_tax_code", "taxCode");
        eb.display("taxCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("taxCode", "description", "kind", "reason", "state", "jurisdictions", "certificateRequired",
                "active")
            .filters("taxCode", "kind", "state", "active")
            .sorts("taxCode")
            .defaultSort("taxCode", true));
    });

    static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    private TaxEntities() {}
}
