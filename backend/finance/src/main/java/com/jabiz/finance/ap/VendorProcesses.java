package com.jabiz.finance.ap;

import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.ar.ArEntities;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.calc.TaxIds;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.Sensitive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Vendors and their tax information (FIN-AP-001, 002), written only here, by hand and by the import alike:
 * <ul>
 *   <li>{@code FIN_VENDOR_SAVE}: a vendor, new or changed. As with customers, a change takes effect now or from a
 *       later day, and only the remit-to address and the contact change from a later day; the past is not rewritten.
 *       Terms may be given as days ("30"): the standard terms {@code NET30} are used, and created when missing. The
 *       1099 form and box are checked together: 1099-NEC box 1, or a 1099-MISC box of {@link ApEntities#BOXES};
 *       a form without a box is box 1; an empty form clears both.</li>
 *   <li>{@code FIN_VENDOR_TAX_SAVE}: a vendor's W-9: TIN type and number (kept in written form, {@link TaxIds}),
 *       document, date, the TIN-matching result and backup withholding. The clerk who records it sees the TIN masked
 *       afterwards like everyone without {@code fin.tax.data.read} (FIN-AP-002); a masked value is never taken as a
 *       new TIN.</li>
 * </ul>
 */
public final class VendorProcesses {

    public static final String SAVE = "FIN_VENDOR_SAVE";
    public static final String TAX_SAVE = "FIN_VENDOR_TAX_SAVE";

    public static final String MISSING = "FIN_VENDOR_MISSING_VALUE";
    public static final String UNKNOWN_VENDOR = "FIN_VENDOR_UNKNOWN";
    public static final String UNKNOWN_CURRENCY = "FIN_VENDOR_UNKNOWN_CURRENCY";
    public static final String UNKNOWN_TERMS = "FIN_VENDOR_UNKNOWN_TERMS";
    public static final String UNKNOWN_ACCOUNT = "FIN_VENDOR_UNKNOWN_ACCOUNT";
    public static final String INVALID_VALUE = "FIN_VENDOR_INVALID_VALUE";
    public static final String INVALID_BOX = "FIN_VENDOR_1099_BOX";
    public static final String PAST_DATE = "FIN_VENDOR_PAST_DATE";
    public static final String SCHEDULED_FIELD = "FIN_VENDOR_SCHEDULED_FIELD";
    public static final String INVALID_TIN = "FIN_VENDOR_TIN_INVALID";

    public record Address(@Size(max = 200) String street, @Size(max = 100) String city, @Size(max = 20) String state,
        @Size(max = 20) String postalCode, @Size(max = 60) String country) {}

    /**
     * Only what is given changes an existing vendor (an empty text clears an optional one); a new one needs its name,
     * currency, terms and entity type, and takes effect now.
     *
     * @param termsCode      payment terms by code, or
     * @param termsDays      net days: the standard terms {@code NET<days>}
     * @param expenseAccount the account a bill line without one is coded to
     * @param paymentMethod  one of {@link ApEntities#PAYMENT_METHOD_VALUES}: the default of its payments
     * @param entityType     one of {@link ApEntities#ENTITY_TYPE_VALUES}
     * @param form1099       {@code NEC} or {@code MISC}; empty when the vendor is not reportable
     * @param box1099        the box of the form; box 1 when a form is given without one
     * @param w9OnFile       whether a W-9 was collected
     * @param effectiveDate  the day the change takes effect, today or later; now when absent or today
     */
    public record VendorInput(@NotBlank @Size(max = 20) String vendorCode, @Size(max = 200) String legalName,
        @Size(max = 200) String dbaName, @Valid Address remit, @Size(max = 100) String contactName,
        @Size(max = 200) String contactEmail, @Size(max = 40) String contactPhone, @Size(max = 3) String currency,
        @Size(max = 20) String termsCode, @Min(0) @Max(365) Integer termsDays, @Size(max = 20) String expenseAccount,
        @Size(max = 10) String paymentMethod, @Size(max = 20) String entityType, @Size(max = 10) String form1099,
        @Size(max = 2) String box1099, Boolean w9OnFile, Boolean active, LocalDate effectiveDate) {}

    /**
     * @param created whether the vendor is new
     * @param changed whether anything was written
     */
    public record VendorOutput(String vendorId, String vendorCode, boolean created, boolean changed) {}

    /**
     * @param tinType           {@code SSN}, {@code EIN} or {@code ITIN}; with the number
     * @param tin               the number, with or without hyphens
     * @param w9FileId          the uploaded W-9 (file policy {@code fin.w9})
     * @param tinStatus         the IRS TIN-matching result; {@code UNVERIFIED} for a new number
     * @param backupWithholding whether backup withholding applies (26 U.S.C. 3406)
     */
    public record TaxInput(@NotBlank @Size(max = 20) String vendorCode, @Size(max = 10) String tinType,
        @Sensitive @Size(max = 20) String tin, UUID w9FileId, LocalDate w9Date, @Size(max = 20) String tinStatus,
        Boolean backupWithholding) {

        @Override
        public String toString() {
            return "TaxInput[vendorCode=" + vendorCode + ", tinType=" + tinType + ", tin=***, w9FileId=" + w9FileId
                + ", w9Date=" + w9Date + ", tinStatus=" + tinStatus + ", backupWithholding=" + backupWithholding + "]";
        }
    }

    public record TaxOutput(String taxInfoId, String vendorCode, boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String VENDORS = "vendors";
    static final String TERMS = "terms";
    static final String CURRENCIES = "currencies";
    static final String ACCOUNTS = "accounts";
    static final String TAX_INFOS = "taxInfos";

    public static ProcessDefinition<VendorInput, VendorOutput, ProcessContext> saveProcess(BookingTime booking) {
        return ProcessDefinition.define(SAVE, 1, VendorInput.class, VendorOutput.class, ProcessContext.class,
            pb -> pb
                .description("Creates or changes a vendor, from a given day if asked.")
                .permissions(FinancePermissions.VENDOR_MAINTAIN)
                .contextFactory(VendorProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, VendorOutput.class))
                .step("Load the vendor", QueryEntities.of(ApEntities.VENDOR_DATASET,
                    ctx -> byCode(vendorInput(ctx).vendorCode()), VENDORS))
                .step("Load the terms", QueryEntities.of(ArEntities.PAYMENT_TERMS_DATASET,
                    ctx -> eq("termsCode", termsCode(vendorInput(ctx))), TERMS))
                .step("Load the currency", QueryEntities.of(GlEntities.CURRENCY_DATASET,
                    ctx -> eq("currencyCode", code(vendorInput(ctx).currency())), CURRENCIES))
                .step("Load the expense account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                    ctx -> eq("accountCode", trim(vendorInput(ctx).expenseAccount())), ACCOUNTS))
                .compute("Save the vendor", (metadata, ctx) -> save(ctx, booking)));
    }

    public static final ProcessDefinition<TaxInput, TaxOutput, ProcessContext> TAX_PROCESS =
        ProcessDefinition.define(TAX_SAVE, 1, TaxInput.class, TaxOutput.class, ProcessContext.class, pb -> pb
            .description("Records a vendor's W-9: taxpayer identification number, document and status.")
            .permissions(FinancePermissions.VENDOR_MAINTAIN)
            .contextFactory(VendorProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, TaxOutput.class))
            .step("Load the vendor", QueryEntities.of(ApEntities.VENDOR_DATASET,
                ctx -> byCode(ctx.get(INPUT, TaxInput.class).vendorCode()), VENDORS))
            .step("Load its tax information", QueryEntities.of(ApEntities.TAX_INFO_DATASET,
                ctx -> byCode(ctx.get(INPUT, TaxInput.class).vendorCode()), TAX_INFOS))
            .compute("Save the tax information", (metadata, ctx) -> saveTax(ctx)));

    static void save(ProcessContext ctx, BookingTime booking) {
        VendorInput input = vendorInput(ctx);
        String vendorCode = code(input.vendorCode());
        EntityInstance current = list(ctx, VENDORS).isEmpty() ? null : list(ctx, VENDORS).getFirst();
        if (current == null) {
            require(ctx, input.legalName(), "legalName");
            require(ctx, input.currency(), "currency");
            require(ctx, input.entityType(), "entityType");
            if (blank(input.termsCode()) && input.termsDays() == null) {
                require(ctx, null, "termsCode");
            }
        }
        String currency = code(input.currency());
        if (currency != null && list(ctx, CURRENCIES).stream().noneMatch(c -> Boolean.TRUE.equals(c.get("active")))) {
            ctx.reject(new Violation("currency", UNKNOWN_CURRENCY, "There is no active currency " + currency,
                Map.of("currency", currency)));
        }
        String terms = termsCode(input);
        EntityInstance foundTerms = list(ctx, TERMS).stream().filter(t -> Objects.equals(terms, t.get("termsCode")))
            .findFirst().orElse(null);
        boolean standardTerms = blank(input.termsCode()) && input.termsDays() != null;
        if (terms != null && foundTerms == null && !standardTerms) {
            ctx.reject(new Violation("termsCode", UNKNOWN_TERMS, "There are no payment terms " + terms,
                Map.of("termsCode", terms)));
        } else if (foundTerms != null && !Boolean.TRUE.equals(foundTerms.get("active"))) {
            ctx.reject(new Violation("termsCode", UNKNOWN_TERMS, "The payment terms " + terms + " are inactive",
                Map.of("termsCode", terms)));
        }
        String account = trim(input.expenseAccount());
        if (account != null) {
            EntityInstance found = list(ctx, ACCOUNTS).isEmpty() ? null : list(ctx, ACCOUNTS).getFirst();
            if (found == null || found.get("controlClass") != null) {
                // A control account is posted to by its subledger only: bills credit payables, never debit them.
                ctx.reject(new Violation("expenseAccount", UNKNOWN_ACCOUNT, "The default expense account must exist "
                    + "and be no control account; " + account + " is not", Map.of("accountCode", account)));
            }
        }
        String method = choice(ctx, "paymentMethod", input.paymentMethod(), ApEntities.PAYMENT_METHOD_VALUES);
        String entityType = choice(ctx, "entityType", input.entityType(), ApEntities.ENTITY_TYPE_VALUES);
        String form = input.form1099() == null ? null : code(input.form1099());
        String box = trim(input.box1099());
        if (input.form1099() == null && box != null && current != null && current.get("form1099") != null) {
            // The box alone changes within the vendor's form.
            form = current.get("form1099");
        }
        if (input.form1099() != null && form == null) {
            box = null;
        } else if (form != null) {
            box = box == null ? "1" : box;
            if (!ApEntities.BOXES.containsKey(form) || !ApEntities.BOXES.get(form).contains(box)) {
                ctx.reject(new Violation("box1099", INVALID_BOX, "1099-" + form + " box " + box + " is not a box "
                    + "vendors are reported in; use " + ApEntities.BOXES, Map.of("form", form, "box", box)));
            }
        } else if (box != null && input.form1099() == null) {
            ctx.reject(new Violation("box1099", INVALID_BOX, "A 1099 box needs its form", Map.of("form", "",
                "box", box)));
        }
        LocalDate today = booking.dateOf(ctx.opTime());
        if (input.effectiveDate() != null && input.effectiveDate().isBefore(today)) {
            ctx.reject(new Violation("effectiveDate", PAST_DATE, "A change takes effect today or later, not on "
                + input.effectiveDate(), Map.of("date", input.effectiveDate().toString())));
        }
        if (input.effectiveDate() != null && input.effectiveDate().isAfter(today) && (input.legalName() != null
            || input.dbaName() != null || input.currency() != null || terms != null || account != null
            || method != null || entityType != null || input.form1099() != null || box != null
            || input.w9OnFile() != null || input.active() != null)) {
            ctx.reject(new Violation("effectiveDate", SCHEDULED_FIELD, "Only the remit-to address and the contact "
                + "change from a later day; the rest changes now", Map.of()));
        }
        if (ctx.hasViolations()) {
            return;
        }
        if (terms != null && foundTerms == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("termsCode", terms);
            values.put("description", "Net " + input.termsDays() + " days");
            values.put("netDays", BigDecimal.valueOf(input.termsDays()));
            values.put("endOfMonth", false);
            values.put("active", true);
            ctx.changes().insert(ArEntities.PAYMENT_TERMS, values);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "legalName", trim(input.legalName()));
        clearable(values, "dbaName", input.dbaName());
        if (input.remit() != null) {
            clearable(values, "remitStreet", input.remit().street());
            clearable(values, "remitCity", input.remit().city());
            clearable(values, "remitState", input.remit().state());
            clearable(values, "remitPostalCode", input.remit().postalCode());
            clearable(values, "remitCountry", input.remit().country());
        }
        clearable(values, "contactName", input.contactName());
        clearable(values, "contactEmail", input.contactEmail());
        clearable(values, "contactPhone", input.contactPhone());
        put(values, "currency", currency);
        put(values, "termsCode", terms);
        if (input.expenseAccount() != null) {
            values.put("expenseAccount", account);
        }
        if (input.paymentMethod() != null) {
            values.put("paymentMethod", method);
        }
        put(values, "entityType", entityType);
        if (input.form1099() != null || box != null) {
            values.put("form1099", form);
            values.put("box1099", box);
        }
        if (input.w9OnFile() != null || current == null) {
            values.put("w9OnFile", Boolean.TRUE.equals(input.w9OnFile()));
        }
        if (input.active() != null || current == null) {
            values.put("status", Boolean.FALSE.equals(input.active()) ? "INACTIVE" : "ACTIVE");
        }
        var target = input.effectiveDate() == null || !input.effectiveDate().isAfter(today) ? null
            : ctx.changes().effectiveAt(booking.of(input.effectiveDate()));
        if (current == null) {
            values.put("vendorCode", vendorCode);
            Object id = target == null ? ctx.changes().insert(ApEntities.VENDOR, values)
                : target.insert(ApEntities.VENDOR, values);
            ctx.put(OUTPUT, new VendorOutput(String.valueOf(id), vendorCode, true, true));
            return;
        }
        // A later day's change is written as given: the version it lands on may differ from today's.
        Map<String, Object> changes = target == null ? differences(current, values) : values;
        if (!changes.isEmpty()) {
            if (target == null) {
                ctx.changes().update(ApEntities.VENDOR, current.id(), current.version(), changes);
            } else {
                target.update(ApEntities.VENDOR, current.id(), current.version(), changes);
            }
        }
        ctx.put(OUTPUT, new VendorOutput(String.valueOf(current.id()), vendorCode, false, !changes.isEmpty()));
    }

    static void saveTax(ProcessContext ctx) {
        TaxInput input = ctx.get(INPUT, TaxInput.class);
        String vendorCode = code(input.vendorCode());
        if (list(ctx, VENDORS).isEmpty()) {
            ctx.reject(new Violation("vendorCode", UNKNOWN_VENDOR, "There is no vendor " + vendorCode,
                Map.of("vendorCode", vendorCode)));
            return;
        }
        EntityInstance current = list(ctx, TAX_INFOS).isEmpty() ? null : list(ctx, TAX_INFOS).getFirst();
        Map<String, Object> values = new LinkedHashMap<>();
        if (input.tin() != null || input.tinType() != null) {
            String type = code(input.tinType());
            if (input.tin() == null) {
                // A type alone would leave the number unchecked for it; a form that does not show the number
                // (it is masked) sends neither.
                ctx.reject(new Violation("tin", INVALID_TIN, "A TIN type is given with its number", Map.of()));
            } else if (input.tin().isBlank()) {
                // Clearing the number clears its type: a type without a number says nothing.
                values.put("tinType", null);
                values.put("tin", null);
            } else if (MaskStyle.looksMasked(input.tin().trim())) {
                ctx.reject(new Violation("tin", INVALID_TIN, "A masked number is not a new one: enter the whole "
                    + "number", Map.of()));
            } else if (type == null || !ApEntities.TIN_TYPE_VALUES.contains(type)) {
                ctx.reject(new Violation("tinType", INVALID_VALUE, "tinType must be one of "
                    + ApEntities.TIN_TYPE_VALUES, Map.of("field", "tinType", "value", String.valueOf(input.tinType()))));
            } else {
                var written = TaxIds.normalize(type, input.tin().trim());
                if (written.isEmpty()) {
                    // The value is never repeated: it is the TIN itself.
                    ctx.reject(new Violation("tin", INVALID_TIN, "The number is not a valid " + type, Map.of()));
                } else {
                    values.put("tinType", type);
                    values.put("tin", written.get());
                    if (current == null || !written.get().equals(current.get("tin"))) {
                        // A new number has not been matched with the IRS yet.
                        values.put("tinStatus", "UNVERIFIED");
                    }
                }
            }
        }
        if (input.tinStatus() != null) {
            values.put("tinStatus", choice(ctx, "tinStatus", input.tinStatus(), ApEntities.TIN_STATUS_VALUES));
        }
        if (input.w9FileId() != null) {
            values.put("w9FileId", input.w9FileId());
        }
        if (input.w9Date() != null) {
            values.put("w9Date", input.w9Date());
        }
        if (input.backupWithholding() != null) {
            values.put("backupWithholding", input.backupWithholding());
        }
        if (ctx.hasViolations()) {
            return;
        }
        if (current == null) {
            values.putIfAbsent("tinStatus", "UNVERIFIED");
            values.putIfAbsent("backupWithholding", false);
            values.put("vendorCode", vendorCode);
            Object id = ctx.changes().insert(ApEntities.TAX_INFO, values);
            ctx.put(OUTPUT, new TaxOutput(String.valueOf(id), vendorCode, true));
            return;
        }
        Map<String, Object> changes = differences(current, values);
        if (!changes.isEmpty()) {
            ctx.changes().update(ApEntities.TAX_INFO, current.id(), current.version(), changes);
        }
        ctx.put(OUTPUT, new TaxOutput(String.valueOf(current.id()), vendorCode, !changes.isEmpty()));
    }

    private static String choice(ProcessContext ctx, String field, String value, List<String> allowed) {
        if (blank(value)) {
            return null;
        }
        String chosen = code(value);
        if (!allowed.contains(chosen)) {
            ctx.reject(new Violation(field, INVALID_VALUE, field + " must be one of " + allowed,
                Map.of("field", field, "value", value)));
        }
        return chosen;
    }

    /** A text given changes the field, an empty one clears it; one not given leaves it. */
    private static void clearable(Map<String, Object> values, String field, String value) {
        if (value != null) {
            values.put(field, trim(value));
        }
    }

    /** The fields of {@code values} that differ from the stored entity. */
    static Map<String, Object> differences(EntityInstance current, Map<String, Object> values) {
        Map<String, Object> changes = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            Object stored = current.get(field);
            boolean same = stored instanceof BigDecimal a && value instanceof BigDecimal b ? a.compareTo(b) == 0
                : Objects.equals(stored == null ? null : stored.toString(), value == null ? null : value.toString());
            if (!same) {
                changes.put(field, value);
            }
        });
        return changes;
    }

    private static void require(ProcessContext ctx, String value, String field) {
        if (blank(value)) {
            ctx.reject(new Violation(field, MISSING, "A new vendor needs " + field, Map.of("field", field)));
        }
    }

    private static void put(Map<String, Object> values, String field, Object value) {
        if (value != null) {
            values.put(field, value);
        }
    }

    private static String termsCode(VendorInput input) {
        if (!blank(input.termsCode())) {
            return code(input.termsCode());
        }
        return input.termsDays() == null ? null : "NET" + input.termsDays();
    }

    public static EntityQuery byCode(String vendorCode) {
        return eq("vendorCode", code(vendorCode));
    }

    static EntityQuery eq(String field, String value) {
        if (value == null) {
            return EntityQuery.builder().where(new QueryPredicate.In(field, List.of())).limit(1).build();
        }
        return EntityQuery.builder().where(new QueryPredicate.Eq(field, value)).limit(1).build();
    }

    static String code(String value) {
        return blank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    static String trim(String value) {
        return blank(value) ? null : value.trim();
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static VendorInput vendorInput(ProcessContext ctx) {
        return ctx.get(INPUT, VendorInput.class);
    }

    static ProcessContext withInput(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private VendorProcesses() {}
}
