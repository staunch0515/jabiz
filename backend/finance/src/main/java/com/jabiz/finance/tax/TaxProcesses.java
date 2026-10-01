package com.jabiz.finance.tax;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sales tax master data (FIN-TX-001, 002), written only here:
 * <ul>
 *   <li>{@code FIN_TAX_JURISDICTION_SAVE}: a jurisdiction, new or changed.</li>
 *   <li>{@code FIN_TAX_RATE_SET}: a jurisdiction's rate from a date; the rate before it ends the day before, and a
 *       rate set again for the same date corrects it (the earlier value stays in the history).</li>
 *   <li>{@code FIN_TAX_CODE_SAVE}: a tax code, new or changed; it creates the jurisdictions it names that do not
 *       exist yet and sets the rates given from {@code ratesFrom}, so a code and its rates can be set up, or imported,
 *       in one go. Existing jurisdictions are shared by codes and keep their names; a rate given here is the
 *       jurisdiction's for every code.</li>
 * </ul>
 */
public final class TaxProcesses {

    public static final String JURISDICTION_SAVE = "FIN_TAX_JURISDICTION_SAVE";
    public static final String RATE_SET = "FIN_TAX_RATE_SET";
    public static final String CODE_SAVE = "FIN_TAX_CODE_SAVE";

    public static final String UNKNOWN_JURISDICTION = "FIN_TAX_UNKNOWN_JURISDICTION";
    public static final String UNKNOWN_CHARGE_CODE = "FIN_TAX_UNKNOWN_CHARGE_CODE";
    public static final String RATES_FROM_REQUIRED = "FIN_TAX_RATES_FROM_REQUIRED";
    public static final String DUPLICATE_JURISDICTION = "FIN_TAX_DUPLICATE_JURISDICTION";

    public record JurisdictionInput(@NotBlank @Size(max = 20) String jurisdictionCode,
        @NotBlank @Size(max = 200) String jurisdictionName, @NotBlank String level, @NotBlank String state,
        Boolean active) {}

    public record JurisdictionOutput(String jurisdictionId, String jurisdictionCode, boolean changed) {}

    public record RateInput(@NotBlank @Size(max = 20) String jurisdictionCode, @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal ratePercent) {}

    /** @param changed false when the same rate stood from that date already */
    public record RateOutput(String jurisdictionCode, LocalDate effectiveFrom, BigDecimal ratePercent,
        boolean changed) {}

    /**
     * One jurisdiction of a code. With a name it is created or renamed; with a rate the rate is set from the code's
     * {@code ratesFrom}.
     */
    public record JurisdictionPart(@NotBlank @Size(max = 20) String jurisdictionCode,
        @Size(max = 200) String jurisdictionName, String level, String state,
        @DecimalMin("0") @DecimalMax("100") BigDecimal ratePercent) {}

    /**
     * @param kind                {@code TAXABLE}, {@code EXEMPT} or {@code NON_TAXABLE}
     * @param reason              why an exempt or non-taxable sale bears no tax
     * @param jurisdictions       a taxable code's jurisdictions, in order
     * @param certificateRequired whether the exemption holds only with the customer's certificate; false when absent
     * @param chargeCode          a taxable code charged instead when the certificate is missing (FIN-TX-004)
     * @param ratesFrom           the date the jurisdictions' rates given here take effect
     */
    public record TaxCodeInput(@NotBlank @Size(max = 20) String taxCode, @NotBlank @Size(max = 200) String description,
        @NotBlank String kind, String reason, String state,
        @Size(max = 10) List<@Valid @NotNull JurisdictionPart> jurisdictions, Boolean certificateRequired,
        String chargeCode, Boolean active, LocalDate ratesFrom) {}

    public record TaxCodeOutput(String taxCodeId, String taxCode, boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String CODES = "codes";
    static final String JURISDICTIONS = "jurisdictions";
    static final String RATES = "rates";

    public static final ProcessDefinition<JurisdictionInput, JurisdictionOutput, ProcessContext> JURISDICTION_PROCESS =
        ProcessDefinition.define(JURISDICTION_SAVE, 1, JurisdictionInput.class, JurisdictionOutput.class,
            ProcessContext.class, pb -> pb
                .description("Creates or changes a sales tax jurisdiction.")
                .permissions(FinancePermissions.TAX_MAINTAIN)
                .contextFactory(TaxProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, JurisdictionOutput.class))
                .step("Load the jurisdiction", QueryEntities.of(TaxEntities.JURISDICTION_DATASET,
                    ctx -> jurisdictions(List.of(input(ctx, JurisdictionInput.class).jurisdictionCode())),
                    JURISDICTIONS))
                .compute("Save the jurisdiction", (metadata, ctx) -> {
                    JurisdictionInput input = input(ctx, JurisdictionInput.class);
                    Map<String, Object> state = new LinkedHashMap<>();
                    state.put("jurisdictionName", input.jurisdictionName().trim());
                    state.put("level", upper(input.level()));
                    state.put("state", upper(input.state()));
                    state.put("active", !Boolean.FALSE.equals(input.active()));
                    Saved saved = upsert(ctx, TaxEntities.JURISDICTION, list(ctx, JURISDICTIONS),
                        "jurisdictionCode", upper(input.jurisdictionCode()), state);
                    ctx.put(OUTPUT, new JurisdictionOutput(saved.id(), upper(input.jurisdictionCode()),
                        saved.changed()));
                }));

    public static final ProcessDefinition<RateInput, RateOutput, ProcessContext> RATE_PROCESS =
        ProcessDefinition.define(RATE_SET, 1, RateInput.class, RateOutput.class, ProcessContext.class, pb -> pb
            .description("Sets a jurisdiction's sales tax rate from a date.")
            .permissions(FinancePermissions.TAX_MAINTAIN)
            .contextFactory(TaxProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RateOutput.class))
            .step("Load the jurisdiction", QueryEntities.of(TaxEntities.JURISDICTION_DATASET,
                ctx -> jurisdictions(List.of(input(ctx, RateInput.class).jurisdictionCode())), JURISDICTIONS))
            .step("Load its rates", QueryEntities.of(TaxEntities.RATE_DATASET,
                ctx -> rates(List.of(input(ctx, RateInput.class).jurisdictionCode())), RATES))
            .compute("Set the rate", (metadata, ctx) -> {
                RateInput input = input(ctx, RateInput.class);
                String code = upper(input.jurisdictionCode());
                if (list(ctx, JURISDICTIONS).isEmpty()) {
                    ctx.reject(new Violation("jurisdictionCode", UNKNOWN_JURISDICTION, "There is no jurisdiction "
                        + code, Map.of("jurisdictionCode", code)));
                    return;
                }
                boolean changed = setRate(ctx, code, input.effectiveFrom(), input.ratePercent(), list(ctx, RATES));
                ctx.put(OUTPUT, new RateOutput(code, input.effectiveFrom(), input.ratePercent(), changed));
            }));

    public static final ProcessDefinition<TaxCodeInput, TaxCodeOutput, ProcessContext> CODE_PROCESS =
        ProcessDefinition.define(CODE_SAVE, 1, TaxCodeInput.class, TaxCodeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Creates or changes a sales tax code, with its jurisdictions and their rates.")
                .permissions(FinancePermissions.TAX_MAINTAIN)
                .contextFactory(TaxProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, TaxCodeOutput.class))
                .step("Load the code and its charge code", QueryEntities.of(TaxEntities.CODE_DATASET, ctx -> {
                    TaxCodeInput input = input(ctx, TaxCodeInput.class);
                    return codes(present(input.taxCode(), input.chargeCode()));
                }, CODES))
                .step("Load the jurisdictions", QueryEntities.of(TaxEntities.JURISDICTION_DATASET,
                    ctx -> jurisdictions(partCodes(input(ctx, TaxCodeInput.class))), JURISDICTIONS))
                .step("Load their rates", QueryEntities.of(TaxEntities.RATE_DATASET,
                    ctx -> rates(partCodes(input(ctx, TaxCodeInput.class))), RATES))
                .compute("Save the code", (metadata, ctx) -> saveCode(ctx)));

    static void saveCode(ProcessContext ctx) {
        TaxCodeInput input = input(ctx, TaxCodeInput.class);
        String taxCode = upper(input.taxCode());
        String kind = upper(input.kind());
        String state = blankToNull(upper(input.state()));
        List<JurisdictionPart> parts = input.jurisdictions() == null ? List.of() : input.jurisdictions();
        Map<String, EntityInstance> jurisdictions = byCode(list(ctx, JURISDICTIONS), "jurisdictionCode");
        boolean ratesGiven = parts.stream().anyMatch(p -> p.ratePercent() != null);
        if (ratesGiven && input.ratesFrom() == null) {
            ctx.reject(new Violation("ratesFrom", RATES_FROM_REQUIRED, "Rates take effect from a date",
                Map.of()));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < parts.size(); i++) {
            JurisdictionPart part = parts.get(i);
            String code = upper(part.jurisdictionCode());
            if (!seen.add(code)) {
                ctx.reject(new Violation("jurisdictions[" + i + "].jurisdictionCode", DUPLICATE_JURISDICTION,
                    "Jurisdiction " + code + " is named twice", Map.of("jurisdictionCode", code)));
            }
            if (!jurisdictions.containsKey(code) && (blank(part.jurisdictionName()) || blank(part.level())
                || (blank(part.state()) && state == null))) {
                ctx.reject(new Violation("jurisdictions[" + i + "].jurisdictionCode", UNKNOWN_JURISDICTION,
                    "There is no jurisdiction " + code + "; a new one needs its name, level and state",
                    Map.of("jurisdictionCode", code)));
            }
        }
        String chargeCode = blankToNull(upper(input.chargeCode()));
        if (chargeCode != null && list(ctx, CODES).stream().noneMatch(c -> chargeCode.equals(c.get("taxCode"))
            && "TAXABLE".equals(c.get("kind")))) {
            ctx.reject(new Violation("chargeCode", UNKNOWN_CHARGE_CODE, "The charge code " + chargeCode
                + " must be a taxable code", Map.of("taxCode", chargeCode)));
        }
        if (ctx.hasViolations()) {
            return;
        }
        boolean changed = false;
        for (JurisdictionPart part : parts) {
            String code = upper(part.jurisdictionCode());
            // An existing jurisdiction is shared by codes: it is changed through FIN_TAX_JURISDICTION_SAVE only.
            if (!jurisdictions.containsKey(code)) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("jurisdictionName", part.jurisdictionName().trim());
                values.put("level", upper(part.level()));
                values.put("state", upper(blank(part.state()) ? state : part.state()));
                values.put("active", true);
                changed |= upsert(ctx, TaxEntities.JURISDICTION, list(ctx, JURISDICTIONS), "jurisdictionCode", code,
                    values).changed();
            }
            if (part.ratePercent() != null) {
                List<EntityInstance> rates = list(ctx, RATES).stream()
                    .filter(r -> code.equals(r.get("jurisdictionCode"))).toList();
                changed |= setRate(ctx, code, input.ratesFrom(), part.ratePercent(), rates);
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("description", input.description().trim());
        values.put("kind", kind);
        values.put("reason", blankToNull(upper(input.reason())));
        values.put("state", state);
        values.put("jurisdictions", parts.isEmpty() ? null
            : String.join(",", parts.stream().map(p -> upper(p.jurisdictionCode())).toList()));
        values.put("certificateRequired", Boolean.TRUE.equals(input.certificateRequired()));
        values.put("chargeCode", chargeCode);
        values.put("active", !Boolean.FALSE.equals(input.active()));
        Saved saved = upsert(ctx, TaxEntities.CODE, list(ctx, CODES), "taxCode", taxCode, values);
        ctx.put(OUTPUT, new TaxCodeOutput(saved.id(), taxCode, changed || saved.changed()));
    }

    /** Sets a rate from {@code from} among the jurisdiction's {@code stored} rates; whether anything changed. */
    static boolean setRate(ProcessContext ctx, String jurisdiction, LocalDate from, BigDecimal percent,
        List<EntityInstance> stored) {
        List<TaxRates.Stored> line = stored.stream().map(r -> new TaxRates.Stored(r.id(), r.version(),
            r.get("effectiveFrom"), r.get("effectiveTo"), r.get("ratePercent"))).toList();
        TaxRates.Plan plan = TaxRates.plan(line, from, percent);
        for (TaxRates.Update update : plan.updates()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("effectiveTo", update.to());
            values.put("ratePercent", update.percent());
            ctx.changes().update(TaxEntities.RATE, update.id(), update.version(), values);
        }
        if (plan.insertNew()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("jurisdictionCode", jurisdiction);
            values.put("effectiveFrom", from);
            values.put("effectiveTo", plan.insertTo());
            values.put("ratePercent", percent);
            ctx.changes().insert(TaxEntities.RATE, values);
        }
        return plan.insertNew() || !plan.updates().isEmpty();
    }

    record Saved(String id, boolean changed) {}

    /** Inserts the row with {@code key} or updates the fields of it that differ. */
    static Saved upsert(ProcessContext ctx, String entity, List<EntityInstance> found, String keyField, String key,
        Map<String, Object> values) {
        EntityInstance current = found.stream().filter(e -> key.equals(e.get(keyField))).findFirst().orElse(null);
        if (current == null) {
            Map<String, Object> state = new LinkedHashMap<>(values);
            state.put(keyField, key);
            return new Saved(String.valueOf(ctx.changes().insert(entity, state)), true);
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (!same(current.get(field), value)) {
                changes.put(field, value);
            }
        });
        if (!changes.isEmpty()) {
            ctx.changes().update(entity, current.id(), current.version(), changes);
        }
        return new Saved(String.valueOf(current.id()), !changes.isEmpty());
    }

    static boolean same(Object stored, Object value) {
        if (stored instanceof BigDecimal a && value instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        if (stored instanceof Number a && value instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(stored, value);
    }

    public static EntityQuery codes(Collection<String> codes) {
        return byField("taxCode", codes);
    }

    public static EntityQuery jurisdictions(Collection<String> codes) {
        return byField("jurisdictionCode", codes);
    }

    public static EntityQuery rates(Collection<String> jurisdictionCodes) {
        List<Object> values = jurisdictionCodes.stream().map(TaxProcesses::upper).distinct()
            .map(c -> (Object) c).toList();
        return EntityQuery.builder().where(new QueryPredicate.In("jurisdictionCode", new ArrayList<>(values)))
            .limit(500).build();
    }

    private static EntityQuery byField(String field, Collection<String> values) {
        List<Object> present = values.stream().filter(v -> v != null && !v.isBlank()).map(TaxProcesses::upper)
            .distinct().map(v -> (Object) v).toList();
        return EntityQuery.builder().where(new QueryPredicate.In(field, new ArrayList<>(present)))
            .limit(present.size() + 1).build();
    }

    private static List<String> partCodes(TaxCodeInput input) {
        return input.jurisdictions() == null ? List.of()
            : input.jurisdictions().stream().map(JurisdictionPart::jurisdictionCode).toList();
    }

    static Map<String, EntityInstance> byCode(List<EntityInstance> found, String field) {
        Map<String, EntityInstance> map = new LinkedHashMap<>();
        for (EntityInstance e : found) {
            map.put(e.get(field), e);
        }
        return map;
    }

    private static List<String> present(String... values) {
        Set<String> present = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                present.add(v);
            }
        }
        return List.copyOf(present);
    }

    static String upper(Object value) {
        return value == null ? null : value.toString().trim().toUpperCase(Locale.ROOT);
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static String blankToNull(String value) {
        return blank(value) ? null : value;
    }

    static <T> T input(ProcessContext ctx, Class<T> type) {
        return ctx.get(INPUT, type);
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

    private TaxProcesses() {}
}
