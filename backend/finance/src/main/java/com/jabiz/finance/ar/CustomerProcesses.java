package com.jabiz.finance.ar;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.migration.MigrationEntities;
import com.jabiz.finance.tax.TaxEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Customers, their exemption certificates and payment terms (FIN-AR-001, 002, FIN-TX-004), written only here, by hand
 * and by the imports alike:
 * <ul>
 *   <li>{@code FIN_CUSTOMER_SAVE}: a customer, new or changed. A change takes effect now or from a later day (an
 *       address that changes on the first of next month); the past is not rewritten, so a document issued earlier
 *       keeps the address it was issued with. Only addresses and the contact change from a later day; the rest
 *       changes now, where it is seen. A tax code that charges no tax and a certificate need {@code fin.customer.tax},
 *       a credit limit {@code fin.customer.credit}: keeping customers alone does not make sales tax-free or raise
 *       credit (FIN-CT-001). Terms
 *       may be given as days ("30"): the standard terms {@code NET30} are used, and created when missing. A customer
 *       code that the migration merged into another ({@code CUSTOMER} decision, FIN-DI-003) is not created: the
 *       output names the customer it was merged into.</li>
 *   <li>{@code FIN_EXEMPTION_CERTIFICATE_SAVE}: a customer's certificate for a state, new or changed.</li>
 *   <li>{@code FIN_PAYMENT_TERMS_SAVE}: payment terms, new or changed (FIN-AR-002).</li>
 * </ul>
 */
public final class CustomerProcesses {

    public static final String SAVE = "FIN_CUSTOMER_SAVE";
    public static final String CERTIFICATE_SAVE = "FIN_EXEMPTION_CERTIFICATE_SAVE";
    public static final String TERMS_SAVE = "FIN_PAYMENT_TERMS_SAVE";

    public static final String MISSING = "FIN_CUSTOMER_MISSING_VALUE";
    public static final String UNKNOWN_CUSTOMER = "FIN_CUSTOMER_UNKNOWN";
    public static final String UNKNOWN_CURRENCY = "FIN_CUSTOMER_UNKNOWN_CURRENCY";
    public static final String UNKNOWN_TERMS = "FIN_CUSTOMER_UNKNOWN_TERMS";
    public static final String UNKNOWN_TAX_CODE = "FIN_CUSTOMER_UNKNOWN_TAX_CODE";
    public static final String INVALID_VALUE = "FIN_CUSTOMER_INVALID_VALUE";
    public static final String PAST_DATE = "FIN_CUSTOMER_PAST_DATE";
    public static final String TAX_RESTRICTED = "FIN_CUSTOMER_TAX_RESTRICTED";
    public static final String CREDIT_RESTRICTED = "FIN_CUSTOMER_CREDIT_RESTRICTED";
    public static final String SCHEDULED_FIELD = "FIN_CUSTOMER_SCHEDULED_FIELD";
    public static final String MERGED = "FIN_CUSTOMER_MERGED";

    public record Address(@Size(max = 200) String street, @Size(max = 100) String city, @Size(max = 20) String state,
        @Size(max = 20) String postalCode, @Size(max = 60) String country) {}

    /**
     * @param certificateType one of {@link ArEntities#CERTIFICATE_TYPE_VALUES}
     * @param fileId          the uploaded document (file policy {@code fin.certificate}), if any
     */
    public record CertificateInput(@NotBlank @Size(max = 2) String state, @NotBlank @Size(max = 40) String certificateNo,
        @NotBlank String certificateType, @Size(max = 200) String description, UUID fileId, LocalDate issueDate,
        LocalDate expiryDate, Boolean active) {}

    /**
     * Only what is given changes an existing customer (an empty text clears an address part or the contact); a new one
     * needs its name, currency, terms and tax code and takes effect now.
     *
     * @param termsCode     payment terms by code, or
     * @param termsDays     net days: the standard terms {@code NET<days>}
     * @param creditLimit   in US dollars; none is no limit
     * @param taxCode       the tax code of the ship-to jurisdiction
     * @param effectiveDate the day the change takes effect, today or later; now when absent or today
     * @param certificate   a certificate to record with the customer
     */
    public record CustomerInput(@NotBlank @Size(max = 20) String customerCode, @Size(max = 200) String legalName,
        @Valid Address billing, @Valid Address shipping, @Size(max = 100) String contactName,
        @Size(max = 200) String contactEmail, @Size(max = 40) String contactPhone, @Size(max = 3) String currency,
        @Size(max = 20) String termsCode, @Min(0) @Max(365) Integer termsDays,
        @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal creditLimit, @Size(max = 20) String taxCode,
        Boolean active, LocalDate effectiveDate, @Valid CertificateInput certificate) {}

    /**
     * @param created    whether the customer is new
     * @param changed    whether anything was written
     * @param mergedInto the customer a merged legacy code stands for; nothing is written then
     */
    public record CustomerOutput(String customerId, String customerCode, boolean created, boolean changed,
        String mergedInto) {}

    public record CertificateSave(@NotBlank @Size(max = 20) String customerCode,
        @NotNull @Valid CertificateInput certificate) {}

    public record CertificateOutput(String certificateId, String customerCode, String certificateNo,
        boolean changed) {}

    public record TermsInput(@NotBlank @Size(max = 20) String termsCode, @NotBlank @Size(max = 100) String description,
        @NotNull @Min(0) @Max(365) Integer netDays,
        @DecimalMin("0.01") @DecimalMax("99.99") @Digits(integer = 2, fraction = 2) BigDecimal discountPercent,
        @Min(0) @Max(365) Integer discountDays, Boolean endOfMonth, Boolean active) {}

    public record TermsOutput(String termsId, String termsCode, boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String CUSTOMERS = "customers";
    static final String DECISIONS = "decisions";
    static final String TERMS = "terms";
    static final String CURRENCIES = "currencies";
    static final String TAX_CODES = "taxCodes";
    static final String CERTIFICATES = "certificates";

    public static ProcessDefinition<CustomerInput, CustomerOutput, ProcessContext> saveProcess(BookingTime booking) {
        return ProcessDefinition.define(SAVE, 1, CustomerInput.class, CustomerOutput.class, ProcessContext.class,
            pb -> pb
                .description("Creates or changes a customer, from a given day if asked.")
                .permissions(FinancePermissions.CUSTOMER_MAINTAIN)
                .contextFactory(CustomerProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, CustomerOutput.class))
                .step("Load the customer", QueryEntities.of(ArEntities.CUSTOMER_DATASET,
                    ctx -> byCode(customerInput(ctx).customerCode()), CUSTOMERS))
                .step("Look for a merge decision", QueryEntities.of(MigrationEntities.DECISION_DATASET,
                    ctx -> customerDecisions(List.of(code(customerInput(ctx).customerCode()))), DECISIONS))
                .step("Load the terms", QueryEntities.of(ArEntities.PAYMENT_TERMS_DATASET,
                    ctx -> termsQuery(termsCode(customerInput(ctx))), TERMS))
                .step("Load the currency", QueryEntities.of(GlEntities.CURRENCY_DATASET,
                    ctx -> eq("currencyCode", code(customerInput(ctx).currency())), CURRENCIES))
                .step("Load the tax code", QueryEntities.of(TaxEntities.CODE_DATASET,
                    ctx -> eq("taxCode", code(customerInput(ctx).taxCode())), TAX_CODES))
                .step("Load the certificates", QueryEntities.of(ArEntities.CERTIFICATE_DATASET,
                    ctx -> certificatesOf(customerInput(ctx).customerCode()), CERTIFICATES))
                .compute("Save the customer", (metadata, ctx) -> save(ctx, booking)));
    }

    public static final ProcessDefinition<CertificateSave, CertificateOutput, ProcessContext> CERTIFICATE_PROCESS =
        ProcessDefinition.define(CERTIFICATE_SAVE, 1, CertificateSave.class, CertificateOutput.class,
            ProcessContext.class, pb -> pb
                .description("Records a customer's exemption or resale certificate for a state.")
                .permissions(FinancePermissions.CUSTOMER_MAINTAIN, FinancePermissions.CUSTOMER_TAX)
                .contextFactory(CustomerProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, CertificateOutput.class))
                .step("Load the customer", QueryEntities.of(ArEntities.CUSTOMER_DATASET,
                    ctx -> byCode(ctx.get(INPUT, CertificateSave.class).customerCode()), CUSTOMERS))
                .step("Load the certificates", QueryEntities.of(ArEntities.CERTIFICATE_DATASET,
                    ctx -> certificatesOf(ctx.get(INPUT, CertificateSave.class).customerCode()), CERTIFICATES))
                .compute("Save the certificate", (metadata, ctx) -> {
                    CertificateSave input = ctx.get(INPUT, CertificateSave.class);
                    String customer = code(input.customerCode());
                    if (list(ctx, CUSTOMERS).isEmpty()) {
                        ctx.reject(new Violation("customerCode", UNKNOWN_CUSTOMER, "There is no customer " + customer,
                            Map.of("customerCode", customer)));
                        return;
                    }
                    if (!validCertificate(ctx, input.certificate(), "certificate")) {
                        return;
                    }
                    ctx.put(OUTPUT, saveCertificate(ctx, customer, input.certificate()));
                }));

    public static final ProcessDefinition<TermsInput, TermsOutput, ProcessContext> TERMS_PROCESS =
        ProcessDefinition.define(TERMS_SAVE, 1, TermsInput.class, TermsOutput.class, ProcessContext.class, pb -> pb
            .description("Creates or changes payment terms.")
            .permissions(FinancePermissions.AR_SETTINGS)
            .contextFactory(CustomerProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, TermsOutput.class))
            .step("Load the terms", QueryEntities.of(ArEntities.PAYMENT_TERMS_DATASET,
                ctx -> termsQuery(ctx.get(INPUT, TermsInput.class).termsCode()), TERMS))
            .compute("Save the terms", (metadata, ctx) -> {
                TermsInput input = ctx.get(INPUT, TermsInput.class);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("description", input.description().trim());
                values.put("netDays", BigDecimal.valueOf(input.netDays()));
                values.put("discountPercent", input.discountPercent());
                values.put("discountDays", input.discountDays() == null ? null
                    : BigDecimal.valueOf(input.discountDays()));
                values.put("endOfMonth", Boolean.TRUE.equals(input.endOfMonth()));
                values.put("active", !Boolean.FALSE.equals(input.active()));
                String code = code(input.termsCode());
                EntityInstance current = list(ctx, TERMS).stream().filter(t -> code.equals(t.get("termsCode")))
                    .findFirst().orElse(null);
                if (current == null) {
                    values.put("termsCode", code);
                    Object id = ctx.changes().insert(ArEntities.PAYMENT_TERMS, values);
                    ctx.put(OUTPUT, new TermsOutput(String.valueOf(id), code, true));
                    return;
                }
                Map<String, Object> changes = differences(current, values);
                if (!changes.isEmpty()) {
                    ctx.changes().update(ArEntities.PAYMENT_TERMS, current.id(), current.version(), changes);
                }
                ctx.put(OUTPUT, new TermsOutput(String.valueOf(current.id()), code, !changes.isEmpty()));
            }));

    static void save(ProcessContext ctx, BookingTime booking) {
        CustomerInput input = customerInput(ctx);
        String customerCode = code(input.customerCode());
        if (!list(ctx, DECISIONS).isEmpty()) {
            String into = list(ctx, DECISIONS).getFirst().get("decidedValue");
            if (input.certificate() != null) {
                // The certificate would be lost with the merged code; it is recorded on the customer it went into.
                ctx.reject(new Violation("certificate", MERGED, customerCode + " was merged into " + into
                    + ": record its certificate on " + into, Map.of("customerCode", customerCode, "into", into)));
                return;
            }
            ctx.put(OUTPUT, new CustomerOutput(null, customerCode, false, false, into));
            return;
        }
        EntityInstance current = list(ctx, CUSTOMERS).isEmpty() ? null : list(ctx, CUSTOMERS).getFirst();
        if (current == null) {
            require(ctx, input.legalName(), "legalName");
            require(ctx, input.currency(), "currency");
            require(ctx, input.taxCode(), "taxCode");
            if (blank(input.termsCode()) && input.termsDays() == null) {
                require(ctx, null, "termsCode");
            }
        }
        String currency = code(input.currency());
        if (currency != null && list(ctx, CURRENCIES).stream().noneMatch(c -> Boolean.TRUE.equals(c.get("active")))) {
            ctx.reject(new Violation("currency", UNKNOWN_CURRENCY, "There is no active currency " + currency,
                Map.of("currency", currency)));
        }
        String taxCode = code(input.taxCode());
        if (taxCode != null && list(ctx, TAX_CODES).stream().noneMatch(c -> Boolean.TRUE.equals(c.get("active")))) {
            ctx.reject(new Violation("taxCode", UNKNOWN_TAX_CODE, "There is no active tax code " + taxCode,
                Map.of("taxCode", taxCode)));
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
        if (input.certificate() != null) {
            validCertificate(ctx, input.certificate(), "certificate");
        }
        LocalDate today = booking.dateOf(ctx.opTime());
        if (input.effectiveDate() != null && input.effectiveDate().isBefore(today)) {
            ctx.reject(new Violation("effectiveDate", PAST_DATE, "A change takes effect today or later, not on "
                + input.effectiveDate(), Map.of("date", input.effectiveDate().toString())));
        }
        if (input.effectiveDate() != null && input.effectiveDate().isAfter(today) && (input.legalName() != null
            || input.currency() != null || terms != null || input.creditLimit() != null || taxCode != null
            || input.active() != null || input.certificate() != null)) {
            ctx.reject(new Violation("effectiveDate", SCHEDULED_FIELD, "Only addresses and the contact change from a "
                + "later day; the rest changes now", Map.of()));
        }
        boolean taxPermitted = ctx.request().hasPermission(FinancePermissions.CUSTOMER_TAX);
        if (taxCode != null && !taxPermitted && (current == null || !taxCode.equals(current.get("taxCode")))
            && list(ctx, TAX_CODES).stream().anyMatch(c -> !"TAXABLE".equals(c.get("kind")))) {
            ctx.reject(new Violation("taxCode", TAX_RESTRICTED, "Tax code " + taxCode + " charges no tax: it needs "
                + "permission " + FinancePermissions.CUSTOMER_TAX, Map.of("taxCode", taxCode)));
        }
        if (input.certificate() != null && !taxPermitted) {
            ctx.reject(new Violation("certificate", TAX_RESTRICTED, "Recording a certificate needs permission "
                + FinancePermissions.CUSTOMER_TAX, Map.of("taxCode", "")));
        }
        if (input.creditLimit() != null && !ctx.request().hasPermission(FinancePermissions.CUSTOMER_CREDIT)
            && (current == null || !CustomerProcesses.same(current.get("creditLimit"), input.creditLimit()))) {
            ctx.reject(new Violation("creditLimit", CREDIT_RESTRICTED, "Setting a credit limit needs permission "
                + FinancePermissions.CUSTOMER_CREDIT, Map.of()));
        }
        if (ctx.hasViolations()) {
            return;
        }
        if (terms != null && foundTerms == null) {
            // Standard net terms named by their days, as a customer file gives them.
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
        address(values, "billing", input.billing());
        address(values, "shipping", input.shipping());
        clearable(values, "contactName", input.contactName());
        clearable(values, "contactEmail", input.contactEmail());
        clearable(values, "contactPhone", input.contactPhone());
        put(values, "currency", currency);
        put(values, "termsCode", terms);
        if (input.creditLimit() != null) {
            values.put("creditLimit", input.creditLimit());
        }
        put(values, "taxCode", taxCode);
        if (input.active() != null || current == null) {
            values.put("status", Boolean.FALSE.equals(input.active()) ? "INACTIVE" : "ACTIVE");
        }
        var target = input.effectiveDate() == null || !input.effectiveDate().isAfter(today) ? null
            : ctx.changes().effectiveAt(booking.of(input.effectiveDate()));
        String id;
        boolean changed;
        if (current == null) {
            values.put("customerCode", customerCode);
            id = String.valueOf(target == null ? ctx.changes().insert(ArEntities.CUSTOMER, values)
                : target.insert(ArEntities.CUSTOMER, values));
            changed = true;
        } else {
            // A later day's change is written as given: the version it lands on may differ from today's.
            Map<String, Object> changes = target == null ? differences(current, values) : values;
            if (!changes.isEmpty()) {
                if (target == null) {
                    ctx.changes().update(ArEntities.CUSTOMER, current.id(), current.version(), changes);
                } else {
                    target.update(ArEntities.CUSTOMER, current.id(), current.version(), changes);
                }
            }
            id = String.valueOf(current.id());
            changed = !changes.isEmpty();
        }
        if (input.certificate() != null) {
            changed |= saveCertificate(ctx, customerCode, input.certificate()).changed();
        }
        ctx.put(OUTPUT, new CustomerOutput(id, customerCode, current == null, changed, null));
    }

    private static boolean validCertificate(ProcessContext ctx, CertificateInput certificate, String field) {
        String type = code(certificate.certificateType());
        if (!ArEntities.CERTIFICATE_TYPE_VALUES.contains(type)) {
            ctx.reject(new Violation(field + ".certificateType", INVALID_VALUE, "certificateType must be one of "
                + ArEntities.CERTIFICATE_TYPE_VALUES, Map.of("value", certificate.certificateType())));
            return false;
        }
        return true;
    }

    static CertificateOutput saveCertificate(ProcessContext ctx, String customer, CertificateInput certificate) {
        String state = code(certificate.state());
        String number = certificate.certificateNo().trim();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("certificateType", code(certificate.certificateType()));
        values.put("description", trim(certificate.description()));
        values.put("fileId", certificate.fileId());
        values.put("issueDate", certificate.issueDate());
        values.put("expiryDate", certificate.expiryDate());
        values.put("active", !Boolean.FALSE.equals(certificate.active()));
        EntityInstance current = list(ctx, CERTIFICATES).stream()
            .filter(c -> state.equals(c.get("state")) && number.equals(c.get("certificateNo")))
            .findFirst().orElse(null);
        if (current == null) {
            values.put("customerCode", customer);
            values.put("state", state);
            values.put("certificateNo", number);
            Object id = ctx.changes().insert(ArEntities.CERTIFICATE, values);
            return new CertificateOutput(String.valueOf(id), customer, number, true);
        }
        Map<String, Object> changes = differences(current, values);
        if (!changes.isEmpty()) {
            ctx.changes().update(ArEntities.CERTIFICATE, current.id(), current.version(), changes);
        }
        return new CertificateOutput(String.valueOf(current.id()), customer, number, !changes.isEmpty());
    }

    private static void address(Map<String, Object> values, String prefix, Address address) {
        if (address == null) {
            return;
        }
        clearable(values, prefix + "Street", address.street());
        clearable(values, prefix + "City", address.city());
        clearable(values, prefix + "State", address.state());
        clearable(values, prefix + "PostalCode", address.postalCode());
        clearable(values, prefix + "Country", address.country());
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

    static boolean same(Object stored, BigDecimal value) {
        return stored != null && new BigDecimal(stored.toString()).compareTo(value) == 0;
    }

    private static void require(ProcessContext ctx, String value, String field) {
        if (blank(value)) {
            ctx.reject(new Violation(field, MISSING, "A new customer needs " + field, Map.of("field", field)));
        }
    }

    private static void put(Map<String, Object> values, String field, Object value) {
        if (value != null) {
            values.put(field, value);
        }
    }

    private static String termsCode(CustomerInput input) {
        if (!blank(input.termsCode())) {
            return code(input.termsCode());
        }
        return input.termsDays() == null ? null : "NET" + input.termsDays();
    }

    public static EntityQuery byCode(String customerCode) {
        return eq("customerCode", code(customerCode));
    }

    public static EntityQuery byCodes(List<String> codes) {
        List<Object> values = codes.stream().filter(c -> !blank(c)).map(CustomerProcesses::code).distinct()
            .map(c -> (Object) c).toList();
        return EntityQuery.builder().where(new QueryPredicate.In("customerCode", new ArrayList<>(values)))
            .limit(Math.max(1, values.size())).build();
    }

    static EntityQuery certificatesOf(String customerCode) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("customerCode", code(customerCode))).limit(100)
            .build();
    }

    /** The {@code CUSTOMER} merge decisions on these legacy codes. */
    public static EntityQuery customerDecisions(List<String> legacyCodes) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("kind", MigrationEntities.CUSTOMER),
                new QueryPredicate.In("legacyValue", new ArrayList<>(legacyCodes)))))
            .limit(Math.max(1, legacyCodes.size())).build();
    }

    private static EntityQuery termsQuery(String code) {
        return eq("termsCode", code(code));
    }

    private static EntityQuery eq(String field, String value) {
        if (value == null) {
            return EntityQuery.builder().where(new QueryPredicate.In(field, List.of())).limit(1).build();
        }
        return EntityQuery.builder().where(new QueryPredicate.Eq(field, value)).limit(1).build();
    }

    static String code(String value) {
        return blank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static CustomerInput customerInput(ProcessContext ctx) {
        return ctx.get(INPUT, CustomerInput.class);
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

    private CustomerProcesses() {}
}
