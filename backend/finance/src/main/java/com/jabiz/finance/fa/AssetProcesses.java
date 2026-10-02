package com.jabiz.finance.fa;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.Depreciation;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.SubledgerPosting;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jabiz.finance.fa.FaSupport.INPUT;
import static com.jabiz.finance.fa.FaSupport.code;
import static com.jabiz.finance.fa.FaSupport.first;
import static com.jabiz.finance.fa.FaSupport.trim;

/**
 * The assets of the register (FIN-FA-002; ROADMAP F6a), numbered without gaps ({@code FA-004}):
 * <ul>
 *   <li>{@code FIN_ASSET_CREATE} (internal): an asset a bill line capitalizes, in the bill's own transaction
 *       (FIN-AP-007). It takes the class of its cost account and the class's defaults; a cost below the class's
 *       threshold refuses the bill, for the line to be an expense (F6 plan decision D2); without a class it is
 *       unclassified until one is given (D4).</li>
 *   <li>{@code FIN_FA_ACQUIRE}: an asset acquired otherwise than by a bill, its cost posted by the asset module
 *       (source FA) against the account given (D3): a manual entry never reaches a cost control account.</li>
 *   <li>{@code FIN_FA_ASSET_SAVE}: the description, location, custodian and department; and, until depreciation has
 *       taken anything (here or, for an asset brought over, before the cutover), the class and the depreciation terms
 *       (method, life, salvage, convention, units). After that only a change in estimate changes them (F6b).</li>
 * </ul>
 */
public final class AssetProcesses {

    public static final String CREATE = "FIN_ASSET_CREATE";
    public static final String ACQUIRE = "FIN_FA_ACQUIRE";
    public static final String SAVE = "FIN_FA_ASSET_SAVE";

    public static final String BELOW_THRESHOLD = "FIN_FA_BELOW_THRESHOLD";
    public static final String UNKNOWN_CLASS = "FIN_FA_UNKNOWN_CLASS";
    public static final String INACTIVE_CLASS = "FIN_FA_INACTIVE_CLASS";
    public static final String CLASS_ACCOUNT = "FIN_FA_CLASS_ACCOUNT_MISMATCH";
    public static final String WRONG_TERMS = AssetClassProcesses.WRONG_TERMS;
    public static final String TERMS_LOCKED = "FIN_FA_TERMS_LOCKED";
    public static final String NOT_FOUND = "FIN_FA_ASSET_NOT_FOUND";
    public static final String WRONG_OFFSET = "FIN_FA_OFFSET_ACCOUNT";

    public record AssetInput(@NotBlank @Size(max = 500) String description, @NotBlank String costAccount,
        @NotNull BigDecimal cost, @NotNull LocalDate inServiceDate, String department, String location,
        UUID sourceBillId, String sourceBillNo, String vendorCode, UUID transactionId) {}

    public record AssetOutput(String assetId, String assetNo) {}

    /** The depreciation terms an asset is given; what is not given comes from its class. */
    public record TermsInput(@Size(max = 6) String method, @Min(1) @Max(1200) Integer lifeMonths,
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal salvage, @Size(max = 12) String convention,
        @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal totalUnits) {}

    public record AcquireInput(@NotBlank @Size(max = 20) String classCode,
        @NotBlank @Size(max = 500) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal cost,
        @NotNull LocalDate inServiceDate, @NotBlank @Size(max = 20) String offsetAccount,
        @Size(max = 20) String department, @Size(max = 20) String location, @Size(max = 100) String custodian,
        TermsInput terms) {}

    public record AcquireOutput(String assetId, String assetNo, String glNo) {}

    public record SaveInput(@NotNull UUID assetId, @Size(max = 500) String description,
        @Size(max = 20) String department, @Size(max = 20) String location, @Size(max = 100) String custodian,
        @Size(max = 20) String classCode, TermsInput terms) {}

    public record SaveOutput(String assetId, String assetNo, boolean changed) {}

    static final String NUMBER = "number";
    static final String OUTPUT = "output";
    static final String CLASSES = "classes";
    static final String ACCOUNTS = "accounts";
    static final String ASSETS = "assets";
    static final String VALUES = "values";
    static final String NEW_ID = "newId";
    static final String SUB_INPUT = "subInput";
    static final String SUB_OUTPUT = "subOutput";

    public static final ProcessDefinition<AssetInput, AssetOutput, ProcessContext> CREATE_PROCESS =
        ProcessDefinition.define(CREATE, 1, AssetInput.class, AssetOutput.class, ProcessContext.class, pb -> pb
            .description("Registers a fixed asset a bill capitalized.")
            .permissions(FinancePermissions.AP_INTERNAL)
            .internal()
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, AssetOutput.class))
            .step("Load the class of its account", QueryEntities.of(AssetEntities.ASSET_CLASS_DATASET,
                ctx -> FaSupport.eq("costAccount", ctx.get(INPUT, AssetInput.class).costAccount()), CLASSES))
            .compute("Check it", (metadata, ctx) -> {
                AssetInput input = ctx.get(INPUT, AssetInput.class);
                EntityInstance assetClass = first(ctx, CLASSES);
                if (assetClass != null && active(assetClass) == null) {
                    // The class no longer takes assets: the line is an expense or goes to another class's account.
                    ctx.reject(new Violation("lines", INACTIVE_CLASS, "Account " + input.costAccount() + " is of "
                        + "class " + assetClass.get("classCode") + ", which is no longer active",
                        Map.of("classCode", (Object) assetClass.get("classCode"))));
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                // A bill gives no units: an asset by units of production gets them before it is depreciated.
                if (assetClass != null && terms(ctx, "lines", assetClass, null, input.cost(), false, values)) {
                    threshold(ctx, "lines", assetClass, input.cost());
                }
                ctx.put(VALUES, values);
            })
            .step("Number the asset", AssignNumber.when(ctx -> !ctx.hasViolations(), AssetEntities.ASSET_NUMBERS,
                null, NUMBER))
            .compute("Register it", (metadata, ctx) -> {
                if (ctx.hasViolations()) {
                    return;
                }
                AssetInput input = ctx.get(INPUT, AssetInput.class);
                String number = ctx.get(NUMBER, String.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> values = new LinkedHashMap<>((Map<String, Object>) ctx.get(VALUES));
                values.put("assetNo", number);
                values.put("description", input.description());
                values.put("costAccount", input.costAccount());
                values.put("cost", input.cost());
                values.put("inServiceDate", input.inServiceDate());
                values.put("department", input.department());
                values.put("location", input.location());
                values.put("sourceBillId", input.sourceBillId());
                values.put("sourceBillNo", input.sourceBillNo());
                values.put("vendorCode", input.vendorCode());
                values.put("transactionId", input.transactionId());
                values.put("source", AssetEntities.BILL);
                values.put("status", AssetEntities.IN_SERVICE);
                values.put("accumulated", BigDecimal.ZERO.setScale(2));
                values.put("active", true);
                Object id = ctx.changes().insert(AssetEntities.ASSET, values);
                ctx.put(OUTPUT, new AssetOutput(String.valueOf(id), number));
            }));

    public static final ProcessDefinition<AcquireInput, AcquireOutput, ProcessContext> ACQUIRE_PROCESS =
        ProcessDefinition.define(ACQUIRE, 1, AcquireInput.class, AcquireOutput.class, ProcessContext.class, pb -> pb
            .description("Acquires a fixed asset otherwise than by a bill and posts its cost.")
            .permissions(FinancePermissions.FA_MAINTAIN)
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, AcquireOutput.class))
            .step("Load the class", QueryEntities.of(AssetEntities.ASSET_CLASS_DATASET,
                ctx -> FaSupport.eq("classCode", code(ctx.get(INPUT, AcquireInput.class).classCode())), CLASSES))
            .step("Load the offset account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> FaSupport.eq("accountCode", trim(ctx.get(INPUT, AcquireInput.class).offsetAccount())),
                ACCOUNTS))
            .compute("Check it", (metadata, ctx) -> checkAcquire(ctx))
            .step("Number the asset", AssignNumber.when(ctx -> ctx.contains(VALUES), AssetEntities.ASSET_NUMBERS,
                null, NUMBER))
            .compute("Register it", (metadata, ctx) -> registerAcquired(ctx))
            // The ledger checks the source document exists: the asset is saved before it is booked.
            .step("Save the asset", SaveChanges.now())
            .step("Book its cost", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Answer", (metadata, ctx) -> {
                if (ctx.contains(SUB_OUTPUT)) {
                    ctx.put(OUTPUT, new AcquireOutput(String.valueOf(ctx.get(NEW_ID)), ctx.get(NUMBER, String.class),
                        ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo()));
                }
            }));

    public static final ProcessDefinition<SaveInput, SaveOutput, ProcessContext> SAVE_PROCESS =
        ProcessDefinition.define(SAVE, 1, SaveInput.class, SaveOutput.class, ProcessContext.class, pb -> pb
            .description("Changes an asset's description, location, custodian and department, and its class and "
                + "depreciation terms before any depreciation.")
            .permissions(FinancePermissions.FA_MAINTAIN)
            .actsOn(AssetEntities.ASSET, "assetId")
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, SaveOutput.class))
            .step("Load the asset", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> FaSupport.in("assetId", List.of(ctx.get(INPUT, SaveInput.class).assetId()), 1), ASSETS))
            .step("Load the class", QueryEntities.of(AssetEntities.ASSET_CLASS_DATASET, ctx -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                EntityInstance asset = first(ctx, ASSETS);
                String classCode = code(input.classCode()) != null ? code(input.classCode())
                    : asset == null ? null : asset.get("classCode");
                return FaSupport.eq("classCode", classCode);
            }, CLASSES))
            .compute("Save it", (metadata, ctx) -> saveAsset(ctx)));

    /** Asset numbers {@code FA-001} on; a register brought over has its own numbers below the first given. */
    public static NumberSequence assetNumbers(long first) {
        return NumberSequence.define(AssetEntities.ASSET_NUMBERS, s -> s.format("FA-{n:3}").startAt(first));
    }

    static void checkAcquire(ProcessContext ctx) {
        AcquireInput input = ctx.get(INPUT, AcquireInput.class);
        EntityInstance assetClass = active(first(ctx, CLASSES));
        if (assetClass == null) {
            ctx.reject(new Violation("classCode", UNKNOWN_CLASS, "There is no active asset class "
                + code(input.classCode()), Map.of("classCode", String.valueOf(code(input.classCode())))));
            return;
        }
        EntityInstance offset = first(ctx, ACCOUNTS);
        // Paid from a bank account or owed elsewhere; never another subledger's control account.
        if (offset == null || offset.get("controlClass") != null && !"BANK".equals(offset.get("controlClass"))) {
            ctx.reject(new Violation("offsetAccount", WRONG_OFFSET, "An acquisition is paid from a bank account or an "
                + "account that is no control account; " + trim(input.offsetAccount()) + " is not",
                Map.of("accountCode", String.valueOf(trim(input.offsetAccount())))));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        if (terms(ctx, "terms", assetClass, input.terms(), input.cost(), true, values)) {
            threshold(ctx, "cost", assetClass, input.cost());
        }
        if (!ctx.hasViolations()) {
            ctx.put(VALUES, values);
        }
    }

    @SuppressWarnings("unchecked")
    static void registerAcquired(ProcessContext ctx) {
        if (!ctx.contains(VALUES)) {
            return;
        }
        AcquireInput input = ctx.get(INPUT, AcquireInput.class);
        EntityInstance assetClass = first(ctx, CLASSES);
        String number = ctx.get(NUMBER, String.class);
        Map<String, Object> values = new LinkedHashMap<>((Map<String, Object>) ctx.get(VALUES));
        values.put("assetNo", number);
        values.put("description", input.description().trim());
        values.put("costAccount", assetClass.get("costAccount"));
        values.put("cost", input.cost().setScale(2));
        values.put("inServiceDate", input.inServiceDate());
        values.put("department", trim(input.department()));
        values.put("location", trim(input.location()));
        values.put("custodian", trim(input.custodian()));
        values.put("source", AssetEntities.ACQUISITION);
        values.put("status", AssetEntities.IN_SERVICE);
        values.put("accumulated", BigDecimal.ZERO.setScale(2));
        values.put("active", true);
        Object id = ctx.changes().insert(AssetEntities.ASSET, values);
        ctx.put(NEW_ID, id);
        BigDecimal cost = input.cost().setScale(2);
        String memo = cut(number + " " + input.description().trim(), 200);
        List<JournalProcesses.LineInput> lines = List.of(
            // The department is the ledger's dimension; the asset's location is where it stands, not a dimension.
            new JournalProcesses.LineInput(assetClass.get("costAccount"), cost, null, memo, trim(input.department()),
                null),
            new JournalProcesses.LineInput(trim(input.offsetAccount()), null, cost, memo, null, null));
        List<String> controls = new ArrayList<>(List.of("FA_COST"));
        if ("BANK".equals(first(ctx, ACCOUNTS).get("controlClass"))) {
            controls.add("BANK");
        }
        ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("FA", input.inServiceDate(),
            cut("Acquisition of " + number + ": " + input.description().trim(), 500), number, AssetEntities.ASSET,
            String.valueOf(id), lines, List.copyOf(controls)));
    }

    static void saveAsset(ProcessContext ctx) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        EntityInstance asset = first(ctx, ASSETS);
        if (asset == null) {
            ctx.reject(new Violation("assetId", NOT_FOUND, "There is no asset " + input.assetId(), Map.of()));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        if (trim(input.description()) != null) {
            values.put("description", input.description().trim());
        }
        values.put("department", trim(input.department()) != null ? trim(input.department()) : asset.get("department"));
        values.put("location", trim(input.location()) != null ? trim(input.location()) : asset.get("location"));
        values.put("custodian", trim(input.custodian()) != null ? trim(input.custodian()) : asset.get("custodian"));
        boolean reclassified = code(input.classCode()) != null
            && !Objects.equals(code(input.classCode()), asset.get("classCode"));
        if (reclassified || input.terms() != null) {
            // Terms that something was depreciated by change only prospectively (FIN-FA-006, F6b).
            BigDecimal broughtOver = asset.get("openingAccumulated");
            if (asset.get("depreciatedThrough") != null || broughtOver != null && broughtOver.signum() > 0
                || !AssetEntities.IN_SERVICE.equals(asset.get("status")) || !Boolean.TRUE.equals(asset.get("active"))) {
                ctx.reject(new Violation("terms", TERMS_LOCKED, "Asset " + asset.get("assetNo") + " has been "
                    + "depreciated or is no longer in service: its terms change by a change in estimate", Map.of()));
                return;
            }
            EntityInstance assetClass = active(first(ctx, CLASSES));
            if (assetClass == null) {
                ctx.reject(new Violation("classCode", UNKNOWN_CLASS, reclassified || asset.get("classCode") != null
                    ? "There is no active asset class " + (reclassified ? code(input.classCode())
                        : asset.get("classCode"))
                    : "Asset " + asset.get("assetNo") + " has no class yet: give it one with its terms", Map.of()));
                return;
            }
            if (!Objects.equals(assetClass.get("costAccount"), asset.get("costAccount"))) {
                ctx.reject(new Violation("classCode", CLASS_ACCOUNT, "Class " + assetClass.get("classCode")
                    + " is of account " + assetClass.get("costAccount") + "; asset " + asset.get("assetNo")
                    + " is carried on " + asset.get("costAccount"), Map.of()));
                return;
            }
            Map<String, Object> terms = new LinkedHashMap<>();
            // Classified now, the class's defaults; otherwise what the asset has, changed where given.
            TermsInput given = input.terms();
            if (!reclassified) {
                given = merged(asset, given);
            }
            if (!terms(ctx, "terms", assetClass, given, asset.get("cost"), true, terms)) {
                return;
            }
            values.putAll(terms);
        }
        Map<String, Object> changes = AssetClassProcesses.changed(asset, values);
        if (!changes.isEmpty()) {
            ctx.changes().update(AssetEntities.ASSET, asset.id(), asset.version(), changes);
        }
        ctx.put(OUTPUT, new SaveOutput(String.valueOf(asset.id()), asset.get("assetNo"), !changes.isEmpty()));
    }

    /**
     * The terms of an asset of {@code assetClass}: the class's defaults where none are given. Refuses terms the
     * calculation would not take (a method or convention unknown, salvage above cost, units of production without
     * units). Puts the terms in {@code into}; whether they hold.
     */
    static boolean terms(ProcessContext ctx, String field, EntityInstance assetClass, TermsInput given, BigDecimal cost,
        boolean requireUnits, Map<String, Object> into) {
        String method = given != null && code(given.method()) != null ? code(given.method()) : assetClass.get("method");
        String convention = given != null && code(given.convention()) != null ? code(given.convention())
            : assetClass.get("convention");
        int life = given != null && given.lifeMonths() != null ? given.lifeMonths()
            : assetClass.<BigDecimal>get("lifeMonths").intValueExact();
        BigDecimal salvage = given != null && given.salvage() != null ? given.salvage().setScale(2)
            : BigDecimal.ZERO.setScale(2);
        BigDecimal units = given == null ? null : given.totalUnits();
        if (!AssetEntities.METHOD_VALUES.contains(method) || !AssetEntities.CONVENTION_VALUES.contains(convention)) {
            ctx.reject(new Violation(field, WRONG_TERMS, "A method is one of " + AssetEntities.METHOD_VALUES
                + " and a convention one of " + AssetEntities.CONVENTION_VALUES, Map.of()));
            return false;
        }
        if (AssetEntities.UOP.equals(method) && units == null && requireUnits) {
            ctx.reject(new Violation(field, WRONG_TERMS, "Units of production needs the units the asset is expected "
                + "to give", Map.of()));
            return false;
        }
        try {
            // The calculation's own bounds: salvage within cost, a life of 1 to 1,200 months.
            new Depreciation.Terms(cost, salvage, LocalDate.of(2000, 1, 1), life, Depreciation.Method.valueOf(method),
                Depreciation.Convention.valueOf(convention));
        } catch (IllegalArgumentException e) {
            ctx.reject(new Violation(field, WRONG_TERMS, e.getMessage(), Map.of()));
            return false;
        }
        into.put("classCode", assetClass.get("classCode"));
        into.put("method", method);
        into.put("lifeMonths", BigDecimal.valueOf(life));
        into.put("salvage", salvage);
        into.put("convention", convention);
        into.put("totalUnits", AssetEntities.UOP.equals(method) && units != null ? units.setScale(2) : null);
        return true;
    }

    /** Below its class's threshold a purchase is an expense (FIN-FA-001, D2). */
    static void threshold(ProcessContext ctx, String field, EntityInstance assetClass, BigDecimal cost) {
        BigDecimal threshold = assetClass.get("threshold");
        if (threshold != null && cost.compareTo(threshold) < 0) {
            ctx.reject(new Violation(field, BELOW_THRESHOLD, "A cost of " + cost.toPlainString() + " is below the "
                + "capitalization threshold of " + threshold.toPlainString() + " of class " + assetClass.get("classCode")
                + ": an expense, not an asset", Map.of("cost", cost, "threshold", threshold,
                "classCode", (Object) assetClass.get("classCode"))));
        }
    }

    /** What the asset has, with what is given over it. */
    private static TermsInput merged(EntityInstance asset, TermsInput given) {
        String method = given != null && given.method() != null ? given.method() : asset.get("method");
        Integer life = given != null && given.lifeMonths() != null ? given.lifeMonths()
            : asset.get("lifeMonths") == null ? null : asset.<BigDecimal>get("lifeMonths").intValueExact();
        BigDecimal salvage = given != null && given.salvage() != null ? given.salvage() : asset.get("salvage");
        String convention = given != null && given.convention() != null ? given.convention() : asset.get("convention");
        BigDecimal units = given != null && given.totalUnits() != null ? given.totalUnits() : asset.get("totalUnits");
        return new TermsInput(method, life, salvage, convention, units);
    }

    private static EntityInstance active(EntityInstance assetClass) {
        return assetClass != null && Boolean.TRUE.equals(assetClass.get("active")) ? assetClass : null;
    }

    private static String cut(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }

    private AssetProcesses() {}
}
