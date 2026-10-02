package com.jabiz.finance.ap;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.entity.MaskStyle;

import java.util.List;

/**
 * Form 1099 (FIN-AP-020…023; docs/finance/00-design.md section 9).
 * <ul>
 *   <li>{@code Fin1099Amount}: what a payment counts on a vendor's Form 1099, by form and box, in the calendar year of
 *       its day (cash basis, FIN-AP-021). {@code FIN_PAYMENT_RECORD} writes it as it posts a payment: a bill's cash
 *       spread over its lines' forms and boxes, a prepayment in the vendor's form and box; card payments are none
 *       (Form 1099-K reports them). A void writes the same amounts negated in the year of the void. Recorded once, never
 *       changed: the report and the filings add them up.</li>
 *   <li>{@code Fin1099Filing}: a record filed for a vendor, form and box of a tax year: the amount and the TIN filed,
 *       the export file, and whether it is an original or a correction of the filing it supersedes (FIN-AP-022,
 *       FIN-AP-023). The TIN is masked as on the vendor's tax information.</li>
 * </ul>
 */
public final class Form1099Entities {

    public static final String AMOUNT = "Fin1099Amount";
    public static final String FILING = "Fin1099Filing";

    public static final String AMOUNT_DATASET = "urn:jabiz:dataset:default:Fin1099Amount";
    public static final String FILING_DATASET = "urn:jabiz:dataset:default:Fin1099Filing";

    public static final String SOURCES = "urn:jabiz:dict:finance:1099-source";
    public static final String FILING_KINDS = "urn:jabiz:dict:finance:1099-filing-kind";

    public static final String PAYMENT_SOURCE = "PAYMENT";
    public static final String PREPAYMENT_SOURCE = "PREPAYMENT";
    public static final String VOID_SOURCE = "VOID";
    public static final List<String> SOURCE_VALUES = List.of(PAYMENT_SOURCE, PREPAYMENT_SOURCE, VOID_SOURCE);

    public static final String ORIGINAL = "ORIGINAL";
    public static final String CORRECTION = "CORRECTION";
    public static final List<String> FILING_KIND_VALUES = List.of(ORIGINAL, CORRECTION);

    public static final EntityDefinition AMOUNT_ENTITY = EntityDefinition.define(AMOUNT, eb -> {
        eb.physicalTable("fi_1099_amount_version");
        eb.primaryKey("amountId");
        eb.field("amountId", f -> f.physicalColumn("amount_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:1099-amount"));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).required(true).asText(20));
        eb.field("taxYear", f -> f.physicalColumn("tax_year").immutable(true).required(true).asNumeric(4, 0));
        eb.field("form1099", f -> f.physicalColumn("form_1099").immutable(true).required(true)
            .asCode(ApEntities.FORMS_1099, ApEntities.FORM_1099_VALUES.toArray(String[]::new)));
        eb.field("box1099", f -> f.physicalColumn("box_1099").immutable(true).required(true).asText(2));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.field("source", f -> f.physicalColumn("source").immutable(true).required(true)
            .asCode(SOURCES, SOURCE_VALUES.toArray(String[]::new)));
        // The day that decides the tax year: the payment's, or the void's.
        eb.field("paymentDate", f -> f.physicalColumn("payment_date").immutable(true).required(true).asDate());
        eb.field("paymentId", f -> f.physicalColumn("payment_id").immutable(true).required(true)
            .asReference(PaymentEntities.PAYMENT));
        eb.field("paymentNo", f -> f.physicalColumn("payment_no").immutable(true).required(true).asText(20));
        eb.field("billId", f -> f.physicalColumn("bill_id").immutable(true).asReference(BillEntities.BILL));
        eb.field("billNo", f -> f.physicalColumn("bill_no").immutable(true).asText(40));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("taxYear", "vendorCode", "form1099", "box1099", "amount", "source", "paymentDate", "paymentNo",
                "billNo")
            .filters("taxYear", "vendorCode", "form1099", "source", "paymentId", "paymentNo")
            .sorts("paymentDate", "vendorCode")
            .defaultSort("paymentDate", false));
    });

    public static final EntityDefinition FILING_ENTITY = EntityDefinition.define(FILING, eb -> {
        eb.physicalTable("fi_1099_filing_version");
        eb.primaryKey("filingId");
        eb.field("filingId", f -> f.physicalColumn("filing_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:1099-filing"));
        eb.field("taxYear", f -> f.physicalColumn("tax_year").immutable(true).required(true).asNumeric(4, 0));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).required(true).asText(20));
        eb.field("form1099", f -> f.physicalColumn("form_1099").immutable(true).required(true)
            .asCode(ApEntities.FORMS_1099, ApEntities.FORM_1099_VALUES.toArray(String[]::new)));
        eb.field("box1099", f -> f.physicalColumn("box_1099").immutable(true).required(true).asText(2));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.field("tin", f -> f.physicalColumn("tin").immutable(true).required(true).asText(11)
            .masked(FinancePermissions.TAX_DATA_READ, MaskStyle.TAX_ID));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(FILING_KINDS, FILING_KIND_VALUES.toArray(String[]::new)));
        eb.field("supersedesId", f -> f.physicalColumn("supersedes_id").immutable(true).asReference(FILING));
        eb.field("generatedFileId", f -> f.physicalColumn("generated_file_id").immutable(true).required(true)
            .asText(36));
        eb.field("filedBy", f -> f.physicalColumn("filed_by").immutable(true).required(true).asText(100));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("taxYear", "vendorCode", "form1099", "box1099", "amount", "tin", "kind", "filedBy")
            .filters("taxYear", "vendorCode", "form1099", "kind")
            .sorts("taxYear", "vendorCode")
            .defaultSort("taxYear", false));
    });

    private Form1099Entities() {}
}
