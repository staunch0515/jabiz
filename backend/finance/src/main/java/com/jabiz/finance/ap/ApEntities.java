package com.jabiz.finance.ap;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;
import com.jabiz.finance.FinancePermissions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The payables' master data (FIN-AP-001, 002, 003, 021; docs/finance/00-design.md section 9): vendors, their tax
 * information (W-9), their bank accounts, the 1099 thresholds by tax year and the payables settings. All temporal and
 * written only by their processes ({@link VendorProcesses}, {@link VendorBankProcesses}, {@link ApSettingsProcesses}).
 * A vendor's remit-to address may change from a later day, as a customer's does.
 */
public final class ApEntities {

    public static final String VENDOR = "FinVendor";
    public static final String TAX_INFO = "FinVendorTaxInfo";
    public static final String VENDOR_BANK = "FinVendorBankAccount";
    public static final String THRESHOLD = "Fin1099Threshold";
    public static final String SETTINGS = "FinApSettings";

    public static final String VENDOR_DATASET = "urn:jabiz:dataset:default:FinVendor";
    public static final String TAX_INFO_DATASET = "urn:jabiz:dataset:default:FinVendorTaxInfo";
    public static final String VENDOR_BANK_DATASET = "urn:jabiz:dataset:default:FinVendorBankAccount";
    public static final String THRESHOLD_DATASET = "urn:jabiz:dataset:default:Fin1099Threshold";
    public static final String SETTINGS_DATASET = "urn:jabiz:dataset:default:FinApSettings";

    public static final String VENDOR_STATUSES = "urn:jabiz:dict:finance:vendor-status";
    public static final String PAYMENT_METHODS = "urn:jabiz:dict:finance:payment-method";
    public static final String ENTITY_TYPES = "urn:jabiz:dict:finance:vendor-entity-type";
    public static final String FORMS_1099 = "urn:jabiz:dict:finance:form-1099";
    public static final String TIN_TYPES = "urn:jabiz:dict:finance:tin-type";
    public static final String TIN_STATUSES = "urn:jabiz:dict:finance:tin-status";
    public static final String ACCOUNT_TYPES = "urn:jabiz:dict:finance:bank-account-type";
    public static final String BANK_STATUSES = "urn:jabiz:dict:finance:vendor-bank-status";

    public static final List<String> VENDOR_STATUS_VALUES = List.of("ACTIVE", "INACTIVE");
    /** Card and third-party network payments are reported by the processor on Form 1099-K (FIN-AP-020). */
    public static final List<String> PAYMENT_METHOD_VALUES = List.of("ACH", "CHECK", "WIRE", "CARD");
    public static final List<String> ENTITY_TYPE_VALUES = List.of("INDIVIDUAL", "SINGLE_MEMBER_LLC", "PARTNERSHIP",
        "C_CORPORATION", "S_CORPORATION", "TRUST_ESTATE", "GOVERNMENT", "TAX_EXEMPT", "OTHER");
    public static final List<String> FORM_1099_VALUES = List.of("NEC", "MISC");
    /**
     * The boxes a vendor or bill line may be reported in (FIN-AP-020): 1099-NEC box 1, non-employee compensation;
     * 1099-MISC box 1 rents, 2 royalties, 3 other income, 6 medical and health care payments, 10 gross proceeds paid
     * to an attorney.
     */
    public static final Map<String, List<String>> BOXES = Map.of("NEC", List.of("1"),
        "MISC", List.of("1", "2", "3", "6", "10"));
    public static final List<String> TIN_TYPE_VALUES = List.of("SSN", "EIN", "ITIN");
    /** The IRS TIN matching result (FIN-AP-002). */
    public static final List<String> TIN_STATUS_VALUES = List.of("UNVERIFIED", "MATCHED", "MISMATCH");
    public static final List<String> ACCOUNT_TYPE_VALUES = List.of("CHECKING", "SAVINGS");
    /**
     * A vendor's bank account waits for approval ({@code PENDING}), is paid to ({@code ACTIVE}), was refused
     * ({@code REJECTED}) or replaced by a later approved one ({@code REPLACED}).
     */
    public static final List<String> BANK_STATUS_VALUES = List.of("PENDING", "ACTIVE", "REJECTED", "REPLACED");

    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String REJECTED = "REJECTED";
    public static final String REPLACED = "REPLACED";

    /** The W-9 documents: scans or PDFs, kept with the vendor's tax information (FIN-AP-002). */
    public static final String W9_FILES = "fin.w9";

    public static final String VENDOR_CODE_PATTERN = "[A-Z0-9][A-Z0-9_-]{0,19}";
    public static final String SETTINGS_KEY = "AP";

    public static final EntityDefinition VENDOR_ENTITY = EntityDefinition.define(VENDOR, eb -> {
        eb.physicalTable("fi_vendor_version");
        eb.primaryKey("vendorId");
        eb.field("vendorId", f -> f.physicalColumn("vendor_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:vendor"));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).required(true).asText(20)
            .apply(Rules.pattern("FIN_VENDOR_CODE_FORMAT", VENDOR_CODE_PATTERN)));
        eb.field("legalName", f -> f.physicalColumn("legal_name").required(true).asText(200)
            .apply(Rules.notBlank("FIN_VENDOR_NAME_BLANK")));
        eb.field("dbaName", f -> f.physicalColumn("dba_name").asText(200));
        eb.field("remitStreet", f -> f.physicalColumn("remit_street").asText(200));
        eb.field("remitCity", f -> f.physicalColumn("remit_city").asText(100));
        eb.field("remitState", f -> f.physicalColumn("remit_state").asText(20));
        eb.field("remitPostalCode", f -> f.physicalColumn("remit_postal_code").asText(20));
        eb.field("remitCountry", f -> f.physicalColumn("remit_country").asText(60));
        eb.field("contactName", f -> f.physicalColumn("contact_name").asText(100));
        eb.field("contactEmail", f -> f.physicalColumn("contact_email").asText(200));
        eb.field("contactPhone", f -> f.physicalColumn("contact_phone").asText(40));
        eb.field("currency", f -> f.physicalColumn("currency").required(true).asText(3)
            .apply(Rules.pattern("FIN_CURRENCY_CODE_FORMAT", "[A-Z]{3}")));
        eb.field("termsCode", f -> f.physicalColumn("terms_code").required(true).asText(20));
        // Where a bill line names no account (FIN-AP-001).
        eb.field("expenseAccount", f -> f.physicalColumn("expense_account").asText(20));
        // The default of the vendor's payments; a payment run names its own (FIN-AP-013).
        eb.field("paymentMethod", f -> f.physicalColumn("payment_method")
            .asCode(PAYMENT_METHODS, values(PAYMENT_METHOD_VALUES)));
        eb.field("entityType", f -> f.physicalColumn("entity_type").required(true)
            .asCode(ENTITY_TYPES, values(ENTITY_TYPE_VALUES)));
        // None when the vendor is not reportable; a bill line may say otherwise (FIN-AP-020).
        eb.field("form1099", f -> f.physicalColumn("form_1099").asCode(FORMS_1099, values(FORM_1099_VALUES)));
        eb.field("box1099", f -> f.physicalColumn("box_1099").asText(2));
        // The legacy system's "TIN on file": a W-9 was collected; the number itself is the tax information's.
        eb.field("w9OnFile", f -> f.physicalColumn("w9_on_file").required(true).asBool());
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(VENDOR_STATUSES, values(VENDOR_STATUS_VALUES)));
        eb.unique("uk_fi_vendor_code", "vendorCode");
        eb.display("vendorCode");
        // A remit-to address may change from a later day: the version takes effect then.
        eb.temporal(t -> t.allowScheduled(true));
        eb.listView("default", lv -> lv
            .columns("vendorCode", "legalName", "dbaName", "remitState", "currency", "termsCode", "paymentMethod",
                "entityType", "form1099", "box1099", "status")
            .filters("vendorCode", "legalName", "currency", "paymentMethod", "entityType", "form1099", "status")
            .sorts("vendorCode", "legalName")
            .defaultSort("vendorCode", true));
    });

    /** A vendor's W-9: one row per vendor; the TIN is masked but to holders of {@code fin.tax.data.read}. */
    public static final EntityDefinition TAX_INFO_ENTITY = EntityDefinition.define(TAX_INFO, eb -> {
        eb.physicalTable("fi_vendor_tax_info_version");
        eb.primaryKey("taxInfoId");
        eb.field("taxInfoId", f -> f.physicalColumn("tax_info_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:vendor-tax-info"));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).required(true).asText(20));
        eb.field("tinType", f -> f.physicalColumn("tin_type").asCode(TIN_TYPES, values(TIN_TYPE_VALUES)));
        // Shown as ***-**-6789 or **-***6789 (FIN-AP-002 acceptance 1, FIN-SC-004).
        eb.field("tin", f -> f.physicalColumn("tin").asText(11)
            .masked(FinancePermissions.TAX_DATA_READ, MaskStyle.TAX_ID));
        eb.field("w9FileId", f -> f.physicalColumn("w9_file_id").kind(FileKind.of(W9_FILES)).auditMasked());
        eb.field("w9Date", f -> f.physicalColumn("w9_date").asDate());
        eb.field("tinStatus", f -> f.physicalColumn("tin_status").required(true)
            .asCode(TIN_STATUSES, values(TIN_STATUS_VALUES)));
        eb.field("backupWithholding", f -> f.physicalColumn("backup_withholding").required(true).asBool());
        eb.unique("uk_fi_vendor_tax_info_vendor", "vendorCode");
        eb.display("vendorCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("vendorCode", "tinType", "tin", "w9Date", "tinStatus", "backupWithholding")
            .filters("vendorCode", "tinType", "tinStatus", "backupWithholding")
            .sorts("vendorCode", "w9Date")
            .defaultSort("vendorCode", true));
    });

    /**
     * A vendor's bank account. A change is a new row waiting for approval; once another person approves it, it is the
     * one paid to and the one before is replaced (FIN-AP-003). The account number is masked but to holders of
     * {@code fin.vendor.bank.read}; the audit trail keeps the masked old and new values (FIN-CT-010).
     */
    public static final EntityDefinition VENDOR_BANK_ENTITY = EntityDefinition.define(VENDOR_BANK, eb -> {
        eb.physicalTable("fi_vendor_bank_account_version");
        eb.primaryKey("bankAccountId");
        eb.field("bankAccountId", f -> f.physicalColumn("bank_account_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:vendor-bank-account"));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).required(true).asText(20));
        eb.field("bankName", f -> f.physicalColumn("bank_name").immutable(true).asText(100));
        eb.field("routingNumber", f -> f.physicalColumn("routing_number").immutable(true).required(true).asText(9)
            .apply(Rules.pattern("FIN_ROUTING_FORMAT", "[0-9]{9}")));
        eb.field("accountNumber", f -> f.physicalColumn("account_number").immutable(true).required(true).asText(17)
            .masked(FinancePermissions.VENDOR_BANK_READ, MaskStyle.LAST4));
        eb.field("accountType", f -> f.physicalColumn("account_type").immutable(true).required(true)
            .asCode(ACCOUNT_TYPES, values(ACCOUNT_TYPE_VALUES)));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(BANK_STATUSES, values(BANK_STATUS_VALUES)));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).asText(500));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).asText(100));
        eb.field("requestedTime", f -> f.physicalColumn("requested_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("decidedBy", f -> f.physicalColumn("decided_by").processOnly().asText(100));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").immutable(true).asText(100));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").immutable(true).asText(64));
        eb.display("vendorCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("vendorCode", "bankName", "routingNumber", "accountNumber", "accountType", "status",
                "requestedBy", "requestedTime", "decidedBy")
            .filters("vendorCode", "status")
            .sorts("vendorCode", "requestedTime")
            .defaultSort("requestedTime", false));
    });

    /** The 1099 reporting threshold of a tax year and form, read from data, never from code (FIN-AP-021). */
    public static final EntityDefinition THRESHOLD_ENTITY = EntityDefinition.define(THRESHOLD, eb -> {
        eb.physicalTable("fi_1099_threshold_version");
        eb.primaryKey("thresholdId");
        eb.field("thresholdId", f -> f.physicalColumn("threshold_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:1099-threshold"));
        eb.field("taxYear", f -> f.physicalColumn("tax_year").immutable(true).required(true).asNumeric(4, 0)
            .apply(Rules.range("FIN_TAX_YEAR_RANGE", new BigDecimal("2000"), new BigDecimal("2100"))));
        eb.field("form1099", f -> f.physicalColumn("form_1099").immutable(true).required(true)
            .asCode(FORMS_1099, values(FORM_1099_VALUES)));
        eb.field("threshold", f -> f.physicalColumn("threshold").required(true).asMonetary("USD", 2)
            .apply(Rules.range("FIN_THRESHOLD_RANGE", BigDecimal.ZERO, null)));
        eb.unique("uk_fi_1099_threshold", "taxYear", "form1099");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("taxYear", "form1099", "threshold")
            .filters("taxYear", "form1099")
            .sorts("taxYear")
            .defaultSort("taxYear", false));
    });

    /** The payables settings: one row, key {@code AP}, kept by the controller. */
    public static final EntityDefinition SETTINGS_ENTITY = EntityDefinition.define(SETTINGS, eb -> {
        eb.physicalTable("fi_ap_settings_version");
        eb.primaryKey("settingsId");
        eb.field("settingsId", f -> f.physicalColumn("settings_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:ap-settings"));
        eb.field("settingsKey", f -> f.physicalColumn("settings_key").immutable(true).required(true).asText(10));
        eb.field("payableAccount", f -> f.physicalColumn("payable_account").required(true).asText(20));
        // Cash discounts taken on payment (FIN-AP-012).
        eb.field("discountAccount", f -> f.physicalColumn("discount_account").asText(20));
        // Use tax accrued on taxable purchases the vendor charged no tax on (FIN-TX-007).
        eb.field("useTaxAccount", f -> f.physicalColumn("use_tax_account").asText(20));
        // Payments made before the bill (FIN-AP-008).
        eb.field("prepaymentAccount", f -> f.physicalColumn("prepayment_account").asText(20));
        eb.field("defaultBank", f -> f.physicalColumn("default_bank").asText(20));
        eb.unique("uk_fi_ap_settings_key", "settingsKey");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("payableAccount", "discountAccount", "useTaxAccount", "prepaymentAccount", "defaultBank")
            .filters("settingsKey")
            .sorts("settingsKey")
            .defaultSort("settingsKey", true));
    });

    static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    private ApEntities() {}
}
