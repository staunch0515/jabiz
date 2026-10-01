package com.jabiz.finance.gl;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.ledger.LedgerEntities;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The general ledger's master data (docs/finance/00-design.md sections 6 and 7): the finance side of each ledger
 * account, the values of the two analysis dimensions, currencies and exchange rates, fiscal years and their periods.
 * All are temporal, so every change keeps its history with who and when (FIN-GL-004, FIN-FX-002).
 *
 * <p>Accounts, fiscal years and periods change through their processes only ({@link AccountProcesses},
 * {@link PeriodProcesses}): an account is two entities that must change together, and a period's states are the
 * business of the close. Dimension values, currencies and rates are plain master data maintained through their
 * datasets.
 */
public final class GlEntities {

    public static final String ACCOUNT = "FinAccount";
    public static final String DEPARTMENT = "FinDepartment";
    public static final String LOCATION = "FinLocation";
    public static final String CURRENCY = "FinCurrency";
    public static final String EXCHANGE_RATE = "FinExchangeRate";
    public static final String FISCAL_YEAR = "FinFiscalYear";
    public static final String PERIOD = "FinPeriod";

    public static final String ACCOUNT_DATASET = "urn:jabiz:dataset:default:FinAccount";
    public static final String DEPARTMENT_DATASET = "urn:jabiz:dataset:default:FinDepartment";
    public static final String LOCATION_DATASET = "urn:jabiz:dataset:default:FinLocation";
    public static final String CURRENCY_DATASET = "urn:jabiz:dataset:default:FinCurrency";
    public static final String EXCHANGE_RATE_DATASET = "urn:jabiz:dataset:default:FinExchangeRate";
    public static final String FISCAL_YEAR_DATASET = "urn:jabiz:dataset:default:FinFiscalYear";
    public static final String PERIOD_DATASET = "urn:jabiz:dataset:default:FinPeriod";

    public static final String FINANCIAL_TYPES = "urn:jabiz:dict:finance:financial-type";
    public static final String NORMAL_BALANCES = "urn:jabiz:dict:finance:normal-balance";
    public static final String CASH_FLOW_CLASSES = "urn:jabiz:dict:finance:cash-flow-class";
    public static final String CONTROL_CLASSES = "urn:jabiz:dict:finance:control-class";
    public static final String DIMENSIONS = "urn:jabiz:dict:finance:dimension";
    public static final String RATE_TYPES = "urn:jabiz:dict:finance:rate-type";
    public static final String PERIOD_STATUSES = "urn:jabiz:dict:finance:period-status";
    public static final String SUBLEDGER_STATUSES = "urn:jabiz:dict:finance:subledger-status";

    /** Account types of the requirements (FIN-GL-001); OTHER is other income or expense, TAX income tax. */
    public static final List<String> FINANCIAL_TYPE_VALUES =
        List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE", "OTHER", "TAX");
    public static final List<String> NORMAL_BALANCE_VALUES = List.of("DEBIT", "CREDIT");
    public static final List<String> CASH_FLOW_VALUES = List.of("CASH", "OPERATING", "INVESTING", "FINANCING");
    /** Accounts only their subledger posts to (FIN-GL-005). */
    public static final List<String> CONTROL_CLASS_VALUES = List.of("AR", "AP", "FA_COST", "FA_ACCUM", "BANK");
    public static final List<String> DIMENSION_VALUES = List.of("department", "location");
    public static final List<String> RATE_TYPE_VALUES = List.of("SPOT", "CLOSING", "AVERAGE");
    public static final List<String> PERIOD_STATUS_VALUES = List.of("OPEN", "SOFT_CLOSED", "CLOSED");
    public static final List<String> SUBLEDGER_STATUS_VALUES = List.of("OPEN", "CLOSED");

    /** Account codes: digits and capitals, a hyphen inside (1010, 6100-NY). */
    public static final String ACCOUNT_CODE_PATTERN = "[0-9A-Z][0-9A-Z-]{0,19}";
    public static final String DIMENSION_CODE_PATTERN = "[0-9A-Z][0-9A-Z_-]{0,19}";

    public static final String EXCHANGE_RATE_SAME_CURRENCY = "FIN_EXCHANGE_RATE_SAME_CURRENCY";
    public static final String FISCAL_YEAR_DATES = "FIN_FISCAL_YEAR_DATES";

    private static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    public static final EntityDefinition ACCOUNT_ENTITY = EntityDefinition.define(ACCOUNT, eb -> {
        eb.physicalTable("fi_account_version");
        eb.primaryKey("finAccountId");
        eb.field("finAccountId", f -> f.physicalColumn("fin_account_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:account"));
        eb.field("accountCode", f -> f.physicalColumn("account_code").immutable(true).required(true).asText(20)
            .apply(Rules.pattern("FIN_ACCOUNT_CODE_FORMAT", ACCOUNT_CODE_PATTERN)));
        // The platform account carries the name, the parent, the summary flag and whether it is active (14c).
        eb.field("ledgerAccountId", f -> f.physicalColumn("ledger_account_id").immutable(true).required(true)
            .asReference(LedgerEntities.ACCOUNT));
        eb.field("financialType", f -> f.physicalColumn("financial_type").immutable(true).required(true)
            .asCode(FINANCIAL_TYPES, values(FINANCIAL_TYPE_VALUES)));
        eb.field("normalBalance", f -> f.physicalColumn("normal_balance").required(true)
            .asCode(NORMAL_BALANCES, values(NORMAL_BALANCE_VALUES)));
        eb.field("statementLine", f -> f.physicalColumn("statement_line").required(true).asText(100)
            .apply(Rules.notBlank("FIN_STATEMENT_LINE_BLANK")));
        eb.field("cashFlowClass", f -> f.physicalColumn("cash_flow_class")
            .asCode(CASH_FLOW_CLASSES, values(CASH_FLOW_VALUES)));
        eb.field("controlClass", f -> f.physicalColumn("control_class")
            .asCode(CONTROL_CLASSES, values(CONTROL_CLASS_VALUES)));
        // A clearing or suspense account must be at zero when a period closes (FIN-CT-005).
        eb.field("clearing", f -> f.physicalColumn("clearing").required(true).asBool());
        eb.field("requiredDimension", f -> f.physicalColumn("required_dimension")
            .asCode(DIMENSIONS, values(DIMENSION_VALUES)));
        eb.unique("uk_fi_account_code", "accountCode");
        eb.unique("uk_fi_account_ledger", "ledgerAccountId");
        eb.display("accountCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("accountCode", "financialType", "normalBalance", "statementLine", "controlClass", "clearing")
            .filters("accountCode", "financialType", "statementLine", "controlClass", "clearing")
            .sorts("accountCode")
            .defaultSort("accountCode", true));
    });

    public static final EntityDefinition DEPARTMENT_ENTITY = dimension(DEPARTMENT, "fi_department_version",
        "departmentId", "departmentCode", "department_code", "departmentName", "department_name");

    public static final EntityDefinition LOCATION_ENTITY = dimension(LOCATION, "fi_location_version",
        "locationId", "locationCode", "location_code", "locationName", "location_name");

    /** The validation list of a dimension (FIN-GL-006): a code, a name, whether new lines may use it. */
    private static EntityDefinition dimension(String name, String table, String key, String code, String codeColumn,
        String label, String labelColumn) {
        String keyColumn = table.replace("fi_", "").replace("_version", "") + "_id";
        return EntityDefinition.define(name, eb -> {
            eb.physicalTable(table);
            eb.primaryKey(key);
            eb.field(key, f -> f.physicalColumn(keyColumn).immutable(true).required(true).generated(true)
                .asSemanticIdentity("urn:jabiz:entity:finance:" + keyColumn.replace("_id", "")));
            eb.field(code, f -> f.physicalColumn(codeColumn).immutable(true).required(true).asText(20)
                .apply(Rules.pattern("FIN_DIMENSION_CODE_FORMAT", DIMENSION_CODE_PATTERN)));
            eb.field(label, f -> f.physicalColumn(labelColumn).required(true).asText(100)
                .apply(Rules.notBlank("FIN_DIMENSION_NAME_BLANK")));
            eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
            eb.unique("uk_" + table.replace("_version", "") + "_code", code);
            eb.display(code);
            eb.temporal(t -> t.allowScheduled(false));
            eb.listView("default", lv -> lv
                .columns(code, label, "active")
                .filters(code, label, "active")
                .sorts(code, label)
                .defaultSort(code, true));
        });
    }

    public static final EntityDefinition CURRENCY_ENTITY = EntityDefinition.define(CURRENCY, eb -> {
        eb.physicalTable("fi_currency_version");
        eb.primaryKey("currencyId");
        eb.field("currencyId", f -> f.physicalColumn("currency_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:currency"));
        eb.field("currencyCode", f -> f.physicalColumn("currency_code").immutable(true).required(true).asText(3)
            .apply(Rules.pattern("FIN_CURRENCY_CODE_FORMAT", "[A-Z]{3}")));
        eb.field("currencyName", f -> f.physicalColumn("currency_name").required(true).asText(100)
            .apply(Rules.notBlank("FIN_CURRENCY_NAME_BLANK")));
        // ISO 4217 minor units: amounts in the currency are held with this many decimals (FIN-FX-001).
        eb.field("minorUnits", f -> f.physicalColumn("minor_units").immutable(true).required(true).asNumeric(1, 0)
            .apply(Rules.range("FIN_CURRENCY_MINOR_UNITS", BigDecimal.ZERO, new BigDecimal("4"))));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_fi_currency_code", "currencyCode");
        eb.display("currencyCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("currencyCode", "currencyName", "minorUnits", "active")
            .filters("currencyCode", "active")
            .sorts("currencyCode")
            .defaultSort("currencyCode", true));
    });

    /** A rate: units of {@code toCurrency} per unit of {@code fromCurrency}; a correction is a new version. */
    public static final EntityDefinition EXCHANGE_RATE_ENTITY = EntityDefinition.define(EXCHANGE_RATE, eb -> {
        eb.physicalTable("fi_exchange_rate_version");
        eb.primaryKey("rateId");
        eb.field("rateId", f -> f.physicalColumn("rate_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:exchange-rate"));
        eb.field("fromCurrency", f -> f.physicalColumn("from_currency").immutable(true).required(true).asText(3)
            .apply(Rules.pattern("FIN_CURRENCY_CODE_FORMAT", "[A-Z]{3}")));
        eb.field("toCurrency", f -> f.physicalColumn("to_currency").immutable(true).required(true).asText(3)
            .apply(Rules.pattern("FIN_CURRENCY_CODE_FORMAT", "[A-Z]{3}")));
        eb.field("rateDate", f -> f.physicalColumn("rate_date").immutable(true).required(true).asDate());
        eb.field("rateType", f -> f.physicalColumn("rate_type").immutable(true).required(true)
            .asCode(RATE_TYPES, values(RATE_TYPE_VALUES)));
        eb.field("rate", f -> f.physicalColumn("rate").required(true).asNumeric(19, 10)
            .apply(Rules.range("FIN_EXCHANGE_RATE_POSITIVE", new BigDecimal("0.0000000001"), null)));
        eb.check(EXCHANGE_RATE_SAME_CURRENCY, (state, ctx) -> state.get("fromCurrency") != null
            && state.get("fromCurrency").equals(state.get("toCurrency"))
            ? List.of(new Violation("toCurrency", EXCHANGE_RATE_SAME_CURRENCY,
                "A rate converts between two different currencies")) : List.of());
        eb.unique("uk_fi_exchange_rate", "fromCurrency", "toCurrency", "rateDate", "rateType");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("fromCurrency", "toCurrency", "rateDate", "rateType", "rate")
            .filters("fromCurrency", "toCurrency", "rateDate", "rateType")
            .sorts("rateDate", "fromCurrency")
            .defaultSort("rateDate", false));
    });

    public static final EntityDefinition FISCAL_YEAR_ENTITY = EntityDefinition.define(FISCAL_YEAR, eb -> {
        eb.physicalTable("fi_fiscal_year_version");
        eb.primaryKey("fiscalYearId");
        eb.field("fiscalYearId", f -> f.physicalColumn("fiscal_year_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:fiscal-year"));
        eb.field("fiscalYear", f -> f.physicalColumn("fiscal_year").immutable(true).required(true)
            .asNumeric(4, 0));
        eb.field("startDate", f -> f.physicalColumn("start_date").immutable(true).required(true).asDate());
        eb.field("endDate", f -> f.physicalColumn("end_date").immutable(true).required(true).asDate());
        eb.field("adjustmentPeriod", f -> f.physicalColumn("adjustment_period").immutable(true).required(true)
            .asBool());
        eb.check(FISCAL_YEAR_DATES, (state, ctx) -> state.get("startDate") instanceof LocalDate start
            && state.get("endDate") instanceof LocalDate end && !start.isBefore(end)
            ? List.of(new Violation("endDate", FISCAL_YEAR_DATES, "A fiscal year ends after it starts",
                Map.of())) : List.of());
        eb.unique("uk_fi_fiscal_year", "fiscalYear");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("fiscalYear", "startDate", "endDate", "adjustmentPeriod")
            .filters("fiscalYear")
            .sorts("fiscalYear")
            .defaultSort("fiscalYear", false));
    });

    public static final EntityDefinition PERIOD_ENTITY = EntityDefinition.define(PERIOD, eb -> {
        eb.physicalTable("fi_period_version");
        eb.primaryKey("periodId");
        eb.field("periodId", f -> f.physicalColumn("period_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:period"));
        eb.field("fiscalYearId", f -> f.physicalColumn("fiscal_year_id").immutable(true).required(true)
            .asReference(FISCAL_YEAR));
        eb.field("fiscalYear", f -> f.physicalColumn("fiscal_year").immutable(true).required(true)
            .asNumeric(4, 0));
        eb.field("periodNo", f -> f.physicalColumn("period_no").immutable(true).required(true).asNumeric(2, 0));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        // Period 13 spans December's days; only it is an adjustment period.
        eb.field("adjustment", f -> f.physicalColumn("adjustment").immutable(true).required(true).asBool());
        // Period 0 of the first year: the day before it, holding only the opening entry of the books (FIN-PC-002).
        eb.field("opening", f -> f.physicalColumn("opening").immutable(true).required(true).asBool());
        eb.field("startDate", f -> f.physicalColumn("start_date").immutable(true).required(true).asDate());
        eb.field("endDate", f -> f.physicalColumn("end_date").immutable(true).required(true).asDate());
        // States change only through FIN_PERIOD_SET_STATE / FIN_PERIOD_SET_SUBLEDGER_STATE (FIN-PC-003).
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(PERIOD_STATUSES, values(PERIOD_STATUS_VALUES)));
        eb.field("arStatus", f -> f.physicalColumn("ar_status").required(true).processOnly()
            .asCode(SUBLEDGER_STATUSES, values(SUBLEDGER_STATUS_VALUES)));
        eb.field("apStatus", f -> f.physicalColumn("ap_status").required(true).processOnly()
            .asCode(SUBLEDGER_STATUSES, values(SUBLEDGER_STATUS_VALUES)));
        eb.field("bankStatus", f -> f.physicalColumn("bank_status").required(true).processOnly()
            .asCode(SUBLEDGER_STATUSES, values(SUBLEDGER_STATUS_VALUES)));
        eb.field("faStatus", f -> f.physicalColumn("fa_status").required(true).processOnly()
            .asCode(SUBLEDGER_STATUSES, values(SUBLEDGER_STATUS_VALUES)));
        eb.unique("uk_fi_period_key", "periodKey");
        // Years never overlap: two years starting a regular period on the same day cannot both be created, even at
        // once (the platform's unique check serializes them, decision D6). Periods start on the first of a month, so
        // any two overlapping years share one.
        eb.unique("uk_fi_period_start", "startDate", "adjustment");
        eb.display("periodKey");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("periodKey", "startDate", "endDate", "status", "arStatus", "apStatus", "bankStatus",
                "faStatus")
            .filters("fiscalYear", "periodKey", "status", "startDate", "opening")
            .sorts("periodKey", "startDate")
            .defaultSort("periodKey", true));
    });

    /** The default dataset of a finance entity. */
    public static DatasetDefinition dataset(String id, String entity, String readPermission, String writePermission,
        boolean processOnlyWrites, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(readPermission, writePermission)
            .policy(p -> {
                p.maxQueryBatchSize(500);
                if (processOnlyWrites) {
                    p.processOnlyWrites();
                }
            })
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private GlEntities() {}
}
