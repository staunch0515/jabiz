package com.jabiz.quizbuks.region;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * How many starts the rule on foreign videos refused (F-S11): only a count by UTC day, never who or from which address.
 * A refused start writes nothing (its transaction is rolled back), so the count is kept in memory and written by the
 * job {@value #JOB} through {@value #FLUSH}, which adds it to the day's row of {@link QbRegion#REFUSAL_DAY} under the
 * lock {@value #LOCK} (each instance writes its own). What an instance counted and had not written yet is lost when it
 * stops: the figure is a count for headquarters, not a record.
 */
@Component
public class RegionRefusals {

    public static final String FLUSH = "QB_REGION_REFUSALS_FLUSH";
    public static final String JOB = "qb.region-refusals";
    /** Every hour at minute 7, UTC; a six-field cron. */
    public static final String CRON = "0 7 * * * *";
    static final String LOCK = "qb.region-refusals";

    /** @param counts refused starts by UTC day ({@code yyyy-mm-dd}) */
    public record FlushInput(@NotNull Instant scheduledTime, Map<String, Long> counts) {
        public FlushInput {
            counts = counts == null ? Map.of() : Map.copyOf(counts);
        }
    }

    /** @param refused how many refusals were written */
    public record FlushOutput(long refused) {}

    static final String INPUT = "input";
    static final String ROWS = "rows";

    private final Map<LocalDate, LongAdder> counts = new ConcurrentHashMap<>();

    /** One refused start at {@code time}. */
    public void count(Instant time) {
        counts.computeIfAbsent(RegionRules.today(time), day -> new LongAdder()).increment();
    }

    /** The counts so far, by day, taken out of memory (the job writes them). */
    public Map<String, Long> drain() {
        Map<String, Long> taken = new TreeMap<>();
        for (LocalDate day : List.copyOf(counts.keySet())) {
            LongAdder adder = counts.get(day);
            long n = adder == null ? 0 : adder.sumThenReset();
            if (n > 0) {
                taken.put(day.toString(), n);
            }
            if (adder != null && adder.sum() == 0) {
                counts.remove(day, adder);
            }
        }
        return taken;
    }

    public static ProcessDefinition<FlushInput, FlushOutput, ProcessContext> flush() {
        return ProcessDefinition.define(FLUSH, 1, FlushInput.class, FlushOutput.class, ProcessContext.class, pb -> pb
            .description("Writes how many starts the rule on foreign videos refused, by day.")
            .permissions(QbPermissions.ADMIN_REGION_READ)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new FlushOutput(input(ctx).counts().values().stream().mapToLong(Long::longValue)
                .sum()))
            .step("Lock the counts", HoldLock.<ProcessContext>exclusive(ctx -> input(ctx).counts().isEmpty() ? null
                : LOCK))
            .step("Load the days", QueryEntities.<ProcessContext>of(QbRegion.defaultDataset(QbRegion.REFUSAL_DAY),
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("day", new ArrayList<>(input(ctx).counts()
                    .keySet().stream().map(LocalDate::parse).toList()))).limit(Integer.MAX_VALUE).build(), ROWS))
            .compute("Add the counts", (metadata, ctx) -> add(ctx)));
    }

    private static FlushInput input(ProcessContext ctx) {
        return ctx.get(INPUT, FlushInput.class);
    }

    @SuppressWarnings("unchecked")
    static void add(ProcessContext ctx) {
        Map<LocalDate, EntityInstance> rows = new LinkedHashMap<>();
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(ROWS);
        for (EntityInstance row : found == null ? List.<EntityInstance>of() : found) {
            Object day = row.get("day");
            rows.put(day instanceof LocalDate date ? date : LocalDate.parse(String.valueOf(day)), row);
        }
        input(ctx).counts().forEach((text, n) -> {
            LocalDate day = LocalDate.parse(text);
            EntityInstance row = rows.get(day);
            if (row == null) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("day", day);
                values.put("refused", BigDecimal.valueOf(n));
                ctx.changes().insert(QbRegion.REFUSAL_DAY, values);
            } else {
                BigDecimal before = row.get("refused") instanceof BigDecimal b ? b
                    : new BigDecimal(String.valueOf((Object) row.get("refused")));
                ctx.changes().update(QbRegion.REFUSAL_DAY, row.id(), row.version(),
                    Map.of("refused", before.add(BigDecimal.valueOf(n))));
            }
        });
    }
}
