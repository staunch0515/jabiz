package com.jabiz.finance.fa;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.jabiz.finance.fa.FaSupport.INPUT;
import static com.jabiz.finance.fa.FaSupport.code;
import static com.jabiz.finance.fa.FaSupport.list;
import static com.jabiz.finance.fa.FaSupport.trim;

/**
 * {@code FIN_FA_OPENING}: the legacy register brought over at the cutover (F6 plan decision D9; FIN-SCN-01). Assets
 * placed in service on or before the cutover are registered with what they had accumulated, nothing posted: their
 * cost by account adds up to the cost accounts of the opening entry and their accumulated depreciation to its
 * accumulated depreciation accounts, or nothing is brought over. Each takes the class of its cost account and the
 * class's defaults where the file gives none, and is depreciated by this register from the month after the cutover.
 * An asset placed in service after the cutover is no part of the opening balances: it comes in by its bill or
 * acquisition. Where that asset is registered already, its row must be it, of the same account, cost and date, and
 * only gives it the terms it lacks; where it is not yet, the row is left out and named in the answer (the setup
 * imports the register before January's bills, FIN-SCN-01). Once.
 */
public final class AssetOpeningProcesses {

    public static final String OPENING = "FIN_FA_OPENING";

    public static final String OPENING_DONE = "FIN_FA_OPENING_DONE";
    public static final String OPENING_NONE = "FIN_FA_OPENING_NONE";
    public static final String OPENING_TOTAL = "FIN_FA_OPENING_TOTAL";
    public static final String OPENING_ITEM = "FIN_FA_OPENING_ITEM";
    public static final String OPENING_LATER = "FIN_FA_OPENING_LATER";

    /** Rows of a register file, and assets registered since the cutover a later row may be. */
    static final int MAX_ITEMS = 5000;

    /**
     * An asset of the legacy register.
     *
     * @param accumulated what it had accumulated at the cutover
     */
    public record Item(@NotBlank @Size(max = 20) String assetNo, @NotBlank @Size(max = 500) String description,
        @NotBlank @Size(max = 20) String costAccount,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal cost,
        @NotNull LocalDate inServiceDate, @Size(max = 30) String method, @Min(1) @Max(1200) Integer lifeMonths,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal accumulated,
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal salvage, @Size(max = 12) String convention,
        @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal totalUnits,
        @Size(max = 20) String location, @Size(max = 100) String custodian) {}

    public record OpeningInput(@NotNull @Size(min = 1, max = MAX_ITEMS) List<@Valid @NotNull Item> items) {}

    /**
     * @param registered assets brought over; {@code completed} the later ones given their terms; {@code deferred} the
     *                   later ones left to their bill or acquisition
     */
    public record OpeningOutput(int registered, int completed, List<String> deferred, BigDecimal cost,
        BigDecimal accumulated, String firstPeriod) {}

    static final String OUTPUT = "output";
    static final String CLASSES = "classes";
    static final String JOURNALS = "journals";
    static final String LINES = "lines";
    static final String ASSETS = "assets";
    static final String FOUND = "found";
    static final String LATER = "later";

    public static final ProcessDefinition<OpeningInput, OpeningOutput, ProcessContext> OPENING_PROCESS =
        ProcessDefinition.define(OPENING, 1, OpeningInput.class, OpeningOutput.class, ProcessContext.class, pb -> pb
            .description("Brings over the legacy fixed asset register; it adds up to the opening entry's asset "
                + "accounts.")
            .permissions(FinancePermissions.MIGRATION)
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, OpeningOutput.class))
            .step("Load the classes", QueryEntities.of(AssetEntities.ASSET_CLASS_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("active", true)).limit(500).build(), CLASSES))
            .step("Load the opening entry", QueryEntities.of(JournalEntities.JOURNAL_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("source", JournalEntities.OPENING),
                    new QueryPredicate.Eq("status", "POSTED")))).limit(1).build(), JOURNALS))
            .step("Load its asset lines", QueryEntities.of(JournalEntities.LINE_DATASET, ctx -> {
                List<EntityInstance> journals = list(ctx, JOURNALS);
                Set<Object> accounts = new HashSet<>();
                for (EntityInstance c : list(ctx, CLASSES)) {
                    accounts.add(c.get("costAccount"));
                    accounts.add(c.get("accumulatedAccount"));
                }
                if (journals.isEmpty() || accounts.isEmpty()) {
                    return FaSupport.in("journalId", List.of(), 1);
                }
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("journalId", journals.getFirst().id()),
                    new QueryPredicate.In("accountCode", new ArrayList<>(accounts))))).limit(2000).build();
            }, LINES))
            .step("Load the assets of these numbers", QueryEntities.of(AssetEntities.ASSET_DATASET, ctx -> {
                List<Object> numbers = ctx.get(INPUT, OpeningInput.class).items().stream()
                    .map(i -> (Object) code(i.assetNo())).distinct().toList();
                return FaSupport.in("assetNo", numbers, numbers.size() + 1);
            }, ASSETS))
            // The assets bills and acquisitions registered: a later row is one of them by what it is, not by its
            // number, which the sequence gave in the order the bills came.
            .step("Load the assets acquired since", QueryEntities.of(AssetEntities.ASSET_DATASET, ctx -> {
                List<Object> accounts = ctx.get(INPUT, OpeningInput.class).items().stream()
                    .map(i -> (Object) trim(i.costAccount())).distinct().toList();
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("costAccount", new ArrayList<>(accounts)),
                    new QueryPredicate.In("source", List.of(AssetEntities.BILL, AssetEntities.ACQUISITION)),
                    new QueryPredicate.Eq("active", true)))).limit(MAX_ITEMS * 2).build();
            }, LATER))
            .step("Look for a register brought over", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> FaSupport.eq("source", AssetEntities.OPENING), FOUND))
            .compute("Bring the register over", (metadata, ctx) -> opening(ctx)));

    static void opening(ProcessContext ctx) {
        OpeningInput input = ctx.get(INPUT, OpeningInput.class);
        if (!list(ctx, FOUND).isEmpty()) {
            ctx.reject(new Violation("items", OPENING_DONE, "The asset register was brought over already", Map.of()));
            return;
        }
        List<EntityInstance> journals = list(ctx, JOURNALS);
        if (journals.isEmpty() || !"POSTED".equals(journals.getFirst().get("status"))) {
            ctx.reject(new Violation("items", OPENING_NONE, "The opening entry is not posted yet", Map.of()));
            return;
        }
        LocalDate cutover = journals.getFirst().get("postingDate");
        YearMonth firstPeriod = YearMonth.from(cutover).plusMonths(1);
        Map<String, EntityInstance> classes = new LinkedHashMap<>();
        list(ctx, CLASSES).forEach(c -> classes.put(c.get("costAccount"), c));
        Map<String, EntityInstance> existing = new LinkedHashMap<>();
        list(ctx, ASSETS).forEach(a -> existing.put(a.get("assetNo"), a));
        // What the register says each account holds, against what the opening entry says.
        Map<String, BigDecimal> register = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> inserts = new ArrayList<>();
        List<Object[]> completions = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        Set<Object> matched = new HashSet<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalAccumulated = BigDecimal.ZERO;
        for (int i = 0; i < input.items().size(); i++) {
            Item item = input.items().get(i);
            String field = "items[" + i + "]";
            String number = code(item.assetNo());
            if (!seen.add(number)) {
                ctx.reject(new Violation(field + ".assetNo", OPENING_ITEM, "Asset " + number + " is in the file twice",
                    Map.of("assetNo", number)));
                continue;
            }
            EntityInstance assetClass = classes.get(trim(item.costAccount()));
            if (assetClass == null) {
                ctx.reject(new Violation(field + ".costAccount", AssetProcesses.UNKNOWN_CLASS, "No active asset class "
                    + "has cost account " + trim(item.costAccount()), Map.of("accountCode", trim(item.costAccount()))));
                continue;
            }
            Map<String, Object> terms = new LinkedHashMap<>();
            AssetProcesses.TermsInput given = new AssetProcesses.TermsInput(method(item.method()), item.lifeMonths(),
                item.salvage(), item.convention(), item.totalUnits());
            if (!AssetProcesses.terms(ctx, field, assetClass, given, item.cost(), true, terms)) {
                continue;
            }
            EntityInstance asset = existing.get(number);
            if (item.inServiceDate().isAfter(cutover)) {
                // In service after the cutover: it comes by a bill or an acquisition, and was not depreciated before.
                if (item.accumulated().signum() != 0) {
                    ctx.reject(new Violation(field + ".accumulated", OPENING_LATER, "Asset " + number + " was placed in "
                        + "service after the cutover: nothing was accumulated before it", Map.of("assetNo", number)));
                    continue;
                }
                List<EntityInstance> same = list(ctx, LATER).stream()
                    .filter(a -> !matched.contains(a.id()) && Objects.equals(a.get("costAccount"),
                        trim(item.costAccount())) && a.<BigDecimal>get("cost").compareTo(item.cost()) == 0
                        && item.inServiceDate().equals(a.get("inServiceDate")))
                    .toList();
                if (same.size() > 1) {
                    // Of two alike, the one of the row's number; otherwise which is meant cannot be told.
                    same = same.stream().filter(a -> number.equals(a.get("assetNo"))).toList();
                }
                if (same.size() > 1 || same.isEmpty() && list(ctx, LATER).stream().anyMatch(a -> Objects.equals(
                    a.get("costAccount"), trim(item.costAccount())) && item.inServiceDate().equals(a.get("inServiceDate"))
                    && a.<BigDecimal>get("cost").compareTo(item.cost()) == 0)) {
                    ctx.reject(new Violation(field + ".assetNo", OPENING_LATER, "Asset " + number + " is one of several "
                        + "assets of the same account, cost and date: give it their number", Map.of("assetNo", number)));
                    continue;
                }
                if (same.isEmpty()) {
                    // Not registered yet: its bill will make it with its class's terms, so the row may not ask others.
                    if (!defaults(terms, assetClass)) {
                        ctx.reject(new Violation(field + ".assetNo", OPENING_LATER, "Asset " + number + " is not "
                            + "registered yet and its terms are not its class's: set them on it once its bill or "
                            + "acquisition registers it", Map.of("assetNo", number)));
                        continue;
                    }
                    deferred.add(number);
                    continue;
                }
                EntityInstance later = same.getFirst();
                matched.add(later.id());
                if (later.get("depreciatedThrough") == null) {
                    completions.add(new Object[] {later, terms});
                }
                continue;
            }
            if (asset != null) {
                ctx.reject(new Violation(field + ".assetNo", OPENING_ITEM, "There is an asset " + number + " already",
                    Map.of("assetNo", number)));
                continue;
            }
            BigDecimal depreciable = item.cost().subtract((BigDecimal) terms.get("salvage"));
            if (item.accumulated().compareTo(depreciable) > 0) {
                ctx.reject(new Violation(field + ".accumulated", OPENING_ITEM, "Asset " + number + " has accumulated "
                    + item.accumulated().toPlainString() + ", more than its cost less salvage",
                    Map.of("assetNo", number)));
                continue;
            }
            if (item.accumulated().compareTo(depreciable) < 0 && !AssetEntities.UOP.equals(terms.get("method"))
                && firstPeriod.isAfter(lastMonth(item, terms))) {
                // Its life is over by the cutover: what it did not take it would never take.
                ctx.reject(new Violation(field + ".accumulated", OPENING_ITEM, "Asset " + number + "'s useful life "
                    + "ended by the cutover, yet it is not fully depreciated", Map.of("assetNo", number)));
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>(terms);
            values.put("assetNo", number);
            values.put("description", item.description().trim());
            values.put("costAccount", trim(item.costAccount()));
            values.put("cost", item.cost().setScale(2));
            values.put("inServiceDate", item.inServiceDate());
            values.put("location", trim(item.location()));
            values.put("custodian", trim(item.custodian()));
            values.put("source", AssetEntities.OPENING);
            values.put("status", item.accumulated().compareTo(depreciable) == 0 ? AssetEntities.FULLY_DEPRECIATED
                : AssetEntities.IN_SERVICE);
            values.put("openingAccumulated", item.accumulated().setScale(2));
            values.put("openingPeriod", firstPeriod.toString());
            values.put("accumulated", item.accumulated().setScale(2));
            values.put("active", true);
            inserts.add(values);
            register.merge(trim(item.costAccount()), item.cost(), BigDecimal::add);
            register.merge(assetClass.get("accumulatedAccount"), item.accumulated().negate(), BigDecimal::add);
            totalCost = totalCost.add(item.cost());
            totalAccumulated = totalAccumulated.add(item.accumulated());
        }
        if (ctx.hasViolations()) {
            return;
        }
        // Every asset account of the opening entry, whether the file has assets on it or not.
        Map<String, BigDecimal> ledger = new TreeMap<>();
        for (EntityInstance c : classes.values()) {
            ledger.put(c.get("costAccount"), BigDecimal.ZERO);
            ledger.put(c.get("accumulatedAccount"), BigDecimal.ZERO);
        }
        for (EntityInstance line : list(ctx, LINES)) {
            BigDecimal debit = line.get("debit");
            BigDecimal credit = line.get("credit");
            ledger.merge(line.get("accountCode"), (debit == null ? BigDecimal.ZERO : debit)
                .subtract(credit == null ? BigDecimal.ZERO : credit), BigDecimal::add);
        }
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> account : ledger.entrySet()) {
            BigDecimal mine = register.getOrDefault(account.getKey(), BigDecimal.ZERO);
            if (mine.compareTo(account.getValue()) != 0) {
                differences.add(account.getKey() + ": register " + mine.toPlainString() + ", opening entry "
                    + account.getValue().toPlainString());
            }
        }
        if (!differences.isEmpty()) {
            ctx.reject(new Violation("items", OPENING_TOTAL, "The register does not add up to the opening entry: "
                + String.join("; ", differences), Map.of("differences", String.join("; ", differences))));
            return;
        }
        for (Map<String, Object> values : inserts) {
            ctx.changes().insert(AssetEntities.ASSET, values);
        }
        for (Object[] completion : completions) {
            EntityInstance asset = (EntityInstance) completion[0];
            @SuppressWarnings("unchecked")
            Map<String, Object> changes = AssetClassProcesses.changed(asset, (Map<String, Object>) completion[1]);
            if (!changes.isEmpty()) {
                ctx.changes().update(AssetEntities.ASSET, asset.id(), asset.version(), changes);
            }
        }
        ctx.put(OUTPUT, new OpeningOutput(inserts.size(), completions.size(), List.copyOf(deferred), totalCost.setScale(2),
            totalAccumulated.setScale(2), firstPeriod.toString()));
    }

    /** Whether the terms are its class's defaults, as a bill would give them. */
    private static boolean defaults(Map<String, Object> terms, EntityInstance assetClass) {
        return Objects.equals(terms.get("method"), assetClass.get("method"))
            && Objects.equals(terms.get("convention"), assetClass.get("convention"))
            && ((BigDecimal) terms.get("lifeMonths")).compareTo(assetClass.get("lifeMonths")) == 0
            && ((BigDecimal) terms.get("salvage")).signum() == 0 && terms.get("totalUnits") == null;
    }

    private static YearMonth lastMonth(Item item, Map<String, Object> terms) {
        return new com.jabiz.finance.calc.Depreciation.Terms(item.cost(), (BigDecimal) terms.get("salvage"),
            item.inServiceDate(), ((BigDecimal) terms.get("lifeMonths")).intValueExact(),
            com.jabiz.finance.calc.Depreciation.Method.valueOf((String) terms.get("method")),
            com.jabiz.finance.calc.Depreciation.Convention.valueOf((String) terms.get("convention"))).lastMonth();
    }

    /** The legacy file's names of the methods. */
    static String method(String text) {
        String method = code(text);
        if (method == null) {
            return null;
        }
        return switch (method.replace(" ", "").replace("-", "").replace("_", "")) {
            case "SL", "STRAIGHTLINE" -> AssetEntities.SL;
            case "DDB", "DOUBLEDECLINING", "200DB", "DB200" -> AssetEntities.DDB;
            case "DB150", "150DB", "150DECLINING" -> AssetEntities.DB150;
            case "UOP", "UNITSOFPRODUCTION" -> AssetEntities.UOP;
            default -> method;
        };
    }

    private AssetOpeningProcesses() {}
}
