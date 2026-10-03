package com.jabiz.finance.gl;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A period's balances (docs/finance/perf.md Q1; ROADMAP F9 decision D1): each account's debits and credits of the
 * period by department and location, counted from the entries recorded up to a time. The close and the year close keep
 * one with the artifact, counted to the close; {@value #SNAPSHOT} keeps one for a closed period that has none (books
 * closed before F9). A snapshot equals the entries of the period recorded by its count: the reports read a period as
 * its latest snapshot recorded by their {@code knownAt} plus the period's entries recorded after the count, which
 * only a reopening leaves. A period without a snapshot is read entry by entry, so leaving one out is never wrong.
 * Postings and the close are not serialized by period (ROADMAP F8a, F11): an entry recorded before a close began but
 * committed after it read the movements is missing from that snapshot until the period is closed again.
 * <p>
 * The movements are read and written {@value #BUCKETS} account buckets at a time by {@value #WRITE}: a template read
 * in a process returns at most {@value #CAP} rows (the ledger's datasets' page), and a bucket reaching that is refused
 * rather than cut short. Snapshots follow the data period (10 section 13.2): an actor limited to one keeps none (they
 * would count only what the actor sees), and a reader limited to one sees only the snapshots of periods wholly within
 * it, the others read entry by entry through the ledger's own scope.
 */
public final class PeriodBalances {

    public static final String BALANCE = "FinPeriodBalance";
    public static final String DATASET = "urn:jabiz:dataset:default:FinPeriodBalance";
    /** The period's movements by account, department and location, as recorded up to a time. */
    public static final String MOVEMENTS_TEMPLATE = "finance.gl.period_movements";

    public static final String SNAPSHOT = "FIN_PERIOD_BALANCE_SNAPSHOT";
    public static final String WRITE = "FIN_PERIOD_BALANCE_WRITE";
    public static final String NOT_CLOSED = "FIN_PERIOD_BALANCE_NOT_CLOSED";
    public static final String DATA_PERIOD = "FIN_PERIOD_BALANCE_DATA_PERIOD";
    public static final String TOO_MANY = "FIN_PERIOD_BALANCE_TOO_MANY";

    /** How many account buckets a snapshot is read and written in. */
    public static final int BUCKETS = 16;
    /** The most rows a template read in a process returns: the ledger's datasets' {@code maxQueryBatchSize}. */
    public static final int CAP = 500;

    public static final EntityDefinition ENTITY = EntityDefinition.define(BALANCE, eb -> {
        eb.physicalTable("fi_period_balance_version");
        eb.primaryKey("balanceId");
        eb.field("balanceId", f -> f.physicalColumn("balance_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:period-balance"));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        eb.field("fiscalYear", f -> f.physicalColumn("fiscal_year").immutable(true).required(true)
            .asNumeric(4, 0));
        eb.field("periodNo", f -> f.physicalColumn("period_no").immutable(true).required(true).asNumeric(2, 0));
        // The booking times of the period's first and last days: a reader limited to a data period sees the
        // snapshot only when both lie within it (the whole period does).
        eb.field("firstBooking", f -> f.physicalColumn("first_booking").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("lastBooking", f -> f.physicalColumn("last_booking").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("accountCode", f -> f.physicalColumn("account_code").immutable(true).required(true).asText(20));
        eb.field("department", f -> f.physicalColumn("department").immutable(true).asText(100));
        eb.field("location", f -> f.physicalColumn("location").immutable(true).asText(100));
        eb.field("debit", f -> f.physicalColumn("debit").immutable(true).required(true).asNumeric(17, 2));
        eb.field("credit", f -> f.physicalColumn("credit").immutable(true).required(true).asNumeric(17, 2));
        // The entries recorded up to this time are counted; the reports add those recorded after it.
        eb.field("countedTo", f -> f.physicalColumn("counted_to").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("periodKey", "accountCode", "department", "location", "debit", "credit", "countedTo")
            .filters("periodKey", "accountCode", "countedTo")
            .sorts("periodKey", "accountCode", "countedTo")
            .defaultSort("periodKey", true));
    });

    public record PeriodInput(@NotBlank String periodKey) {}

    /** @param rows the balance rows written, one per account, department and location with entries */
    public record SnapshotOutput(String periodKey, int rows, Instant countedTo) {}

    /** One bucket of a period's accounts, {@code 0} to {@value #BUCKETS} - 1. */
    public record WriteInput(@NotBlank String periodKey, @NotNull @Min(0) @Max(BUCKETS - 1) Integer bucket) {}

    public record WriteOutput(int rows) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String PERIODS = "periods";
    static final String MOVEMENTS = "movements";
    static final String READY = "ready";
    /** Where the processes keep what {@link #keep} wrote. */
    public static final String WRITTEN = "periodBalancesWritten";

    public static final ProcessDefinition<PeriodInput, SnapshotOutput, ProcessContext> SNAPSHOT_PROCESS =
        ProcessDefinition.define(SNAPSHOT, 1, PeriodInput.class, SnapshotOutput.class, ProcessContext.class,
            pb -> pb
                .description("Keeps a closed period's balances, counted from the entries recorded until now.")
                .permissions(FinancePermissions.PERIOD_CLOSE)
                .contextFactory(PeriodBalances::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, SnapshotOutput.class))
                .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> byPeriod(ctx.get(INPUT, PeriodInput.class).periodKey().trim()), PERIODS))
                .compute("Check the period", (metadata, ctx) -> checkSnapshot(ctx))
                .step("Keep its balances", keep(ctx -> ctx.get(INPUT, PeriodInput.class).periodKey().trim(),
                    ctx -> ctx.contains(READY)))
                .compute("Count them", (metadata, ctx) -> {
                    if (ctx.contains(READY)) {
                        ctx.put(OUTPUT, new SnapshotOutput(ctx.get(INPUT, PeriodInput.class).periodKey().trim(),
                            written(ctx), ctx.opTime()));
                    }
                }));

    /** {@value #WRITE}: one bucket of a period's balances, as recorded now; only ever called by {@link #keep}. */
    public static ProcessDefinition<WriteInput, WriteOutput, ProcessContext> writeProcess(BookingTime booking) {
        return ProcessDefinition.define(WRITE, 1, WriteInput.class, WriteOutput.class, ProcessContext.class,
            pb -> pb
                .description("Keeps one bucket of a period's balances, counted from the entries recorded until now.")
                .permissions(FinancePermissions.PERIOD_CLOSE)
                .contextFactory(PeriodBalances::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, WriteOutput.class))
                .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> byPeriod(ctx.get(INPUT, WriteInput.class).periodKey()), PERIODS))
                .step("Load the bucket's movements", RunTemplate.of(MOVEMENTS_TEMPLATE, ctx -> {
                    WriteInput input = ctx.get(INPUT, WriteInput.class);
                    return Map.of("periodKey", input.periodKey(), "knownAt", ctx.opTime(),
                        "bucket", BigDecimal.valueOf(input.bucket()), "buckets", BigDecimal.valueOf(BUCKETS));
                }, MOVEMENTS))
                .compute("Keep them", (metadata, ctx) -> write(ctx, booking)));
    }

    /**
     * The step keeping a period's balances counted to now, when {@code when} holds: every bucket by {@value #WRITE},
     * nothing for an actor limited to a data period (see the class comment).
     */
    public static <C extends ProcessContext> StepSpec<CallProcess.Metadata<C>, C> keep(Function<C, String> periodKey,
        Predicate<C> when) {
        return CallProcess.forEach(WRITE, 1, ctx -> {
            if (!when.test(ctx) || ctx.request().dataPeriod() != null) {
                return List.<WriteInput>of();
            }
            List<WriteInput> buckets = new ArrayList<>();
            for (int i = 0; i < BUCKETS; i++) {
                buckets.add(new WriteInput(periodKey.apply(ctx), i));
            }
            return buckets;
        }, WRITTEN);
    }

    /** How many rows {@link #keep} wrote. */
    public static int written(ProcessContext ctx) {
        Object outputs = ctx.get(WRITTEN);
        if (!(outputs instanceof List<?> list)) {
            return 0;
        }
        return list.stream().filter(WriteOutput.class::isInstance).mapToInt(o -> ((WriteOutput) o).rows()).sum();
    }

    static void checkSnapshot(ProcessContext ctx) {
        String periodKey = ctx.get(INPUT, PeriodInput.class).periodKey().trim();
        EntityInstance period = first(ctx);
        if (period == null) {
            ctx.reject(new Violation("periodKey", PeriodProcesses.PERIOD_NOT_FOUND, "There is no period " + periodKey,
                Map.of("periodKey", periodKey)));
            return;
        }
        // Open, it would only be read with the entries posted after it: the close keeps the snapshot that pays.
        if (!"CLOSED".equals(period.get("status"))) {
            ctx.reject(new Violation("periodKey", NOT_CLOSED, "Period " + periodKey + " is not closed",
                Map.of("periodKey", periodKey)));
            return;
        }
        if (ctx.request().dataPeriod() != null) {
            ctx.reject(new Violation("periodKey", DATA_PERIOD, "Limited to a data period, you see only part of the "
                + "books: a snapshot taken by you would count only that part", Map.of("periodKey", periodKey)));
            return;
        }
        ctx.put(READY, true);
    }

    @SuppressWarnings("unchecked")
    static void write(ProcessContext ctx, BookingTime booking) {
        WriteInput input = ctx.get(INPUT, WriteInput.class);
        EntityInstance period = first(ctx);
        List<Map<String, Object>> movements = (List<Map<String, Object>>) ctx.get(MOVEMENTS);
        movements = movements == null ? List.of() : movements;
        if (period == null) {
            ctx.reject(new Violation("periodKey", PeriodProcesses.PERIOD_NOT_FOUND, "There is no period "
                + input.periodKey(), Map.of("periodKey", input.periodKey())));
            return;
        }
        // At the cap the read may have been cut short: refused, never kept partial.
        if (movements.size() >= CAP) {
            ctx.reject(new Violation("periodKey", TOO_MANY, "Period " + input.periodKey() + " has " + CAP
                + " or more balances in account bucket " + input.bucket() + "; its balances cannot be kept",
                Map.of("periodKey", input.periodKey(), "bucket", String.valueOf(input.bucket()))));
            return;
        }
        Instant first = booking.of(period.<LocalDate>get("startDate"));
        Instant last = booking.of(period.<LocalDate>get("endDate"));
        for (Map<String, Object> m : movements) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("periodKey", period.get("periodKey"));
            row.put("fiscalYear", period.get("fiscalYear"));
            row.put("periodNo", period.get("periodNo"));
            row.put("firstBooking", first);
            row.put("lastBooking", last);
            row.put("accountCode", m.get("accountCode"));
            row.put("department", m.get("department"));
            row.put("location", m.get("location"));
            row.put("debit", money(m.get("debit")));
            row.put("credit", money(m.get("credit")));
            row.put("countedTo", ctx.opTime());
            ctx.changes().insert(BALANCE, row);
        }
        ctx.put(OUTPUT, new WriteOutput(movements.size()));
    }

    private static EntityQuery byPeriod(String periodKey) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("periodKey", periodKey)).limit(1).build();
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx) {
        List<EntityInstance> periods = (List<EntityInstance>) ctx.get(PERIODS);
        return periods == null || periods.isEmpty() ? null : periods.getFirst();
    }

    private static ProcessContext withInput(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static BigDecimal money(Object value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private PeriodBalances() {}
}
