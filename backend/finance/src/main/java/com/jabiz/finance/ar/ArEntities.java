package com.jabiz.finance.ar;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.Violation;
import com.jabiz.file.FileKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The receivables' master data (FIN-AR-001, 002, FIN-TX-004; docs/finance/00-design.md section 8): customers, payment
 * terms, customers' exemption certificates and the receivables settings. All temporal and written only by their
 * processes ({@link CustomerProcesses}, {@link ArSettingsProcesses}). A customer's addresses are versions with the
 * day they take effect, so a document reads the address valid on its own date (FIN-AR-001 acceptance 2).
 */
public final class ArEntities {

    public static final String CUSTOMER = "FinCustomer";
    public static final String PAYMENT_TERMS = "FinPaymentTerms";
    public static final String CERTIFICATE = "FinExemptionCertificate";
    public static final String SETTINGS = "FinArSettings";

    public static final String CUSTOMER_DATASET = "urn:jabiz:dataset:default:FinCustomer";
    public static final String PAYMENT_TERMS_DATASET = "urn:jabiz:dataset:default:FinPaymentTerms";
    public static final String CERTIFICATE_DATASET = "urn:jabiz:dataset:default:FinExemptionCertificate";
    public static final String SETTINGS_DATASET = "urn:jabiz:dataset:default:FinArSettings";

    public static final String CUSTOMER_STATUSES = "urn:jabiz:dict:finance:customer-status";
    public static final String CERTIFICATE_TYPES = "urn:jabiz:dict:finance:certificate-type";
    public static final String MISSING_CERTIFICATE_POLICIES = "urn:jabiz:dict:finance:missing-certificate";
    public static final String CREDIT_LIMIT_CHECKS = "urn:jabiz:dict:finance:credit-limit-check";

    public static final List<String> CUSTOMER_STATUS_VALUES = List.of("ACTIVE", "INACTIVE");
    /** The kinds of certificate; the type is also the reason the return data gives for the exempt sale. */
    public static final List<String> CERTIFICATE_TYPE_VALUES =
        List.of("RESALE", "EXEMPT_ORGANIZATION", "GOVERNMENT", "OTHER");
    public static final List<String> MISSING_CERTIFICATE_VALUES = List.of("BLOCK", "CHARGE");
    public static final List<String> CREDIT_LIMIT_CHECK_VALUES = List.of("OFF", "WARN");

    /** The certificate documents: scans or PDFs, kept with the customer (FIN-TX-004). */
    public static final String CERTIFICATE_FILES = "fin.certificate";

    public static final String CUSTOMER_CODE_PATTERN = "[A-Z0-9][A-Z0-9_-]{0,19}";
    public static final String TERMS_CODE_PATTERN = "[A-Z0-9][A-Z0-9_/-]{0,19}";
    public static final String SETTINGS_KEY = "AR";

    public static final String TERMS_DISCOUNT = "FIN_TERMS_DISCOUNT";
    public static final String CERTIFICATE_DATES = "FIN_CERTIFICATE_DATES";

    public static final EntityDefinition CUSTOMER_ENTITY = EntityDefinition.define(CUSTOMER, eb -> {
        eb.physicalTable("fi_customer_version");
        eb.primaryKey("customerId");
        eb.field("customerId", f -> f.physicalColumn("customer_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:customer"));
        eb.field("customerCode", f -> f.physicalColumn("customer_code").immutable(true).required(true).asText(20)
            .apply(Rules.pattern("FIN_CUSTOMER_CODE_FORMAT", CUSTOMER_CODE_PATTERN)));
        eb.field("legalName", f -> f.physicalColumn("legal_name").required(true).asText(200)
            .apply(Rules.notBlank("FIN_CUSTOMER_NAME_BLANK")));
        address(eb, "billing");
        address(eb, "shipping");
        eb.field("contactName", f -> f.physicalColumn("contact_name").asText(100));
        eb.field("contactEmail", f -> f.physicalColumn("contact_email").asText(200));
        eb.field("contactPhone", f -> f.physicalColumn("contact_phone").asText(40));
        eb.field("currency", f -> f.physicalColumn("currency").required(true).asText(3)
            .apply(Rules.pattern("FIN_CURRENCY_CODE_FORMAT", "[A-Z]{3}")));
        eb.field("termsCode", f -> f.physicalColumn("terms_code").required(true).asText(20));
        // In US dollars; none is no limit (FIN-AR-013).
        eb.field("creditLimit", f -> f.physicalColumn("credit_limit").asNumeric(15, 2)
            .apply(Rules.range("FIN_CREDIT_LIMIT_RANGE", BigDecimal.ZERO, null)));
        // The tax code of the customer's ship-to jurisdiction, used where a line does not name its own (FIN-TX-002).
        eb.field("taxCode", f -> f.physicalColumn("tax_code").required(true).asText(20));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(CUSTOMER_STATUSES, values(CUSTOMER_STATUS_VALUES)));
        eb.unique("uk_fi_customer_code", "customerCode");
        eb.display("customerCode");
        // An address may change from a later day: the version takes effect then.
        eb.temporal(t -> t.allowScheduled(true));
        eb.listView("default", lv -> lv
            .columns("customerCode", "legalName", "shippingState", "currency", "termsCode", "taxCode",
                "creditLimit", "status")
            .filters("customerCode", "legalName", "currency", "taxCode", "status")
            .sorts("customerCode", "legalName")
            .defaultSort("customerCode", true));
    });

    private static void address(com.jabiz.entity.EntityBuilder eb, String prefix) {
        eb.field(prefix + "Street", f -> f.physicalColumn(prefix + "_street").asText(200));
        eb.field(prefix + "City", f -> f.physicalColumn(prefix + "_city").asText(100));
        eb.field(prefix + "State", f -> f.physicalColumn(prefix + "_state").asText(20));
        eb.field(prefix + "PostalCode", f -> f.physicalColumn(prefix + "_postal_code").asText(20));
        eb.field(prefix + "Country", f -> f.physicalColumn(prefix + "_country").asText(60));
    }

    public static final EntityDefinition PAYMENT_TERMS_ENTITY = EntityDefinition.define(PAYMENT_TERMS, eb -> {
        eb.physicalTable("fi_payment_terms_version");
        eb.primaryKey("termsId");
        eb.field("termsId", f -> f.physicalColumn("terms_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:payment-terms"));
        eb.field("termsCode", f -> f.physicalColumn("terms_code").immutable(true).required(true).asText(20)
            .apply(Rules.pattern("FIN_TERMS_CODE_FORMAT", TERMS_CODE_PATTERN)));
        eb.field("description", f -> f.physicalColumn("description").required(true).asText(100)
            .apply(Rules.notBlank("FIN_TERMS_DESCRIPTION_BLANK")));
        eb.field("netDays", f -> f.physicalColumn("net_days").required(true).asNumeric(3, 0)
            .apply(Rules.range("FIN_TERMS_DAYS_RANGE", BigDecimal.ZERO, new BigDecimal("365"))));
        eb.field("discountPercent", f -> f.physicalColumn("discount_percent").asNumeric(5, 2)
            .apply(Rules.range("FIN_TERMS_DISCOUNT_RANGE", new BigDecimal("0.01"), new BigDecimal("99.99"))));
        eb.field("discountDays", f -> f.physicalColumn("discount_days").asNumeric(3, 0)
            .apply(Rules.range("FIN_TERMS_DAYS_RANGE", BigDecimal.ZERO, new BigDecimal("365"))));
        eb.field("endOfMonth", f -> f.physicalColumn("end_of_month").required(true).asBool());
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.check(TERMS_DISCOUNT, (state, ctx) -> {
            Object percent = state.get("discountPercent");
            Object days = state.get("discountDays");
            boolean partial = (percent == null) != (days == null);
            boolean late = days instanceof BigDecimal d && state.get("netDays") instanceof BigDecimal net
                && d.compareTo(net) > 0;
            return partial || late ? List.of(new Violation("discountDays", TERMS_DISCOUNT, "A discount has both a "
                + "percent and days, and its days are within the net days", Map.of())) : List.of();
        });
        eb.unique("uk_fi_payment_terms_code", "termsCode");
        eb.display("termsCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("termsCode", "description", "netDays", "discountPercent", "discountDays", "endOfMonth",
                "active")
            .filters("termsCode", "active")
            .sorts("termsCode")
            .defaultSort("termsCode", true));
    });

    public static final EntityDefinition CERTIFICATE_ENTITY = EntityDefinition.define(CERTIFICATE, eb -> {
        eb.physicalTable("fi_exemption_certificate_version");
        eb.primaryKey("certificateId");
        eb.field("certificateId", f -> f.physicalColumn("certificate_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:exemption-certificate"));
        eb.field("customerCode", f -> f.physicalColumn("customer_code").immutable(true).required(true).asText(20));
        eb.field("state", f -> f.physicalColumn("state").immutable(true).required(true).asText(2)
            .apply(Rules.pattern("FIN_STATE_FORMAT", "[A-Z]{2}")));
        eb.field("certificateNo", f -> f.physicalColumn("certificate_no").immutable(true).required(true).asText(40)
            .apply(Rules.notBlank("FIN_CERTIFICATE_NO_BLANK")));
        eb.field("certificateType", f -> f.physicalColumn("certificate_type").required(true)
            .asCode(CERTIFICATE_TYPES, values(CERTIFICATE_TYPE_VALUES)));
        eb.field("description", f -> f.physicalColumn("description").asText(200));
        eb.field("fileId", f -> f.physicalColumn("file_id").kind(FileKind.of(CERTIFICATE_FILES)));
        eb.field("issueDate", f -> f.physicalColumn("issue_date").asDate());
        eb.field("expiryDate", f -> f.physicalColumn("expiry_date").asDate());
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.check(CERTIFICATE_DATES, (state, ctx) -> state.get("issueDate") instanceof LocalDate issued
            && state.get("expiryDate") instanceof LocalDate expires && expires.isBefore(issued)
            ? List.of(new Violation("expiryDate", CERTIFICATE_DATES, "A certificate expires after it is issued",
                Map.of())) : List.of());
        eb.unique("uk_fi_exemption_certificate", "customerCode", "state", "certificateNo");
        eb.display("certificateNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("customerCode", "state", "certificateNo", "certificateType", "issueDate", "expiryDate",
                "active")
            .filters("customerCode", "state", "certificateNo", "expiryDate", "active")
            .sorts("customerCode", "expiryDate")
            .defaultSort("customerCode", true));
    });

    /** The receivables settings: one row, key {@code AR}, kept by the controller (docs/finance/ROADMAP.md F3). */
    public static final EntityDefinition SETTINGS_ENTITY = EntityDefinition.define(SETTINGS, eb -> {
        eb.physicalTable("fi_ar_settings_version");
        eb.primaryKey("settingsId");
        eb.field("settingsId", f -> f.physicalColumn("settings_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:ar-settings"));
        eb.field("settingsKey", f -> f.physicalColumn("settings_key").immutable(true).required(true).asText(10));
        eb.field("receivableAccount", f -> f.physicalColumn("receivable_account").required(true).asText(20));
        eb.field("allowanceAccount", f -> f.physicalColumn("allowance_account").required(true).asText(20));
        eb.field("returnsAccount", f -> f.physicalColumn("returns_account").required(true).asText(20));
        eb.field("salesTaxAccount", f -> f.physicalColumn("sales_tax_account").required(true).asText(20));
        // Optional: without it a receipt must be applied in full (FIN-AR-007, FIN-CT-005).
        eb.field("unappliedCashAccount", f -> f.physicalColumn("unapplied_cash_account").asText(20));
        eb.field("discountAccount", f -> f.physicalColumn("discount_account").asText(20));
        eb.field("missingCertificate", f -> f.physicalColumn("missing_certificate").required(true)
            .asCode(MISSING_CERTIFICATE_POLICIES, values(MISSING_CERTIFICATE_VALUES)));
        eb.field("creditLimitCheck", f -> f.physicalColumn("credit_limit_check").required(true)
            .asCode(CREDIT_LIMIT_CHECKS, values(CREDIT_LIMIT_CHECK_VALUES)));
        eb.unique("uk_fi_ar_settings_key", "settingsKey");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("receivableAccount", "allowanceAccount", "returnsAccount", "salesTaxAccount",
                "unappliedCashAccount", "discountAccount", "missingCertificate", "creditLimitCheck")
            .filters("settingsKey")
            .sorts("settingsKey")
            .defaultSort("settingsKey", true));
    });

    static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    private ArEntities() {}
}
