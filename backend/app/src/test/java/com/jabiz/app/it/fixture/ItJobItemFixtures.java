package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixtures of the per-item job tests (ROADMAP phase 16k; decision D42). Each {@code ItJobItem} row says how its item
 * process {@value #ITEM_PROCESS} behaves: the locks it takes (in order), how long it works, the gate it waits at while
 * holding them, and whether it fails (422). A committed run counts itself in {@code runs} and leaves the item no longer
 * due, unless it stays due. The selection template is {@code it.job_items} (one batch, in {@code seq} order).
 *
 * <p>{@value #ONLINE_PROCESS} stands for a request of a person taking two locks in order, and {@value #BATCH_PROCESS}
 * for the old way of a batch: one root process calling the item process for every item with
 * {@code CallProcess.forEach}, in one transaction.
 */
public final class ItJobItemFixtures extends BaseEntityDefinitions {

    public static final String ENTITY = "ItJobItem";
    public static final String DATASET = "urn:jabiz:dataset:it:ItJobItem";
    public static final String TEMPLATE = "it.job_items";
    public static final String ITEM_PROCESS = "IT_JOB_ITEM";
    public static final String ONLINE_PROCESS = "IT_JOB_ONLINE";
    public static final String BATCH_PROCESS = "IT_JOB_BATCH";
    static final String PERMISSION = "it.job";
    /** The declared per-item job (for the jobs API) and the batch it selects. */
    public static final String BEAN_JOB = "it.items";
    public static final String BEAN_BATCH = "it-items-bean";

    /** Gates closed by the tests; an item reaching its gate waits there, holding its locks, until it is opened. */
    public static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();
    /** How many processes reached each gate. */
    public static final Map<String, AtomicInteger> ARRIVED = new ConcurrentHashMap<>();

    public static int arrived(String gate) {
        AtomicInteger count = ARRIVED.get(gate);
        return count == null ? 0 : count.get();
    }

    public static final EntityDefinition ITEM = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("it_job_item");
        eb.primaryKey("itemId");
        eb.field("itemId", semanticIdentity("f_id", "urn:jabiz:entity:it:job-item"));
        eb.field("batch", f -> f.physicalColumn("f_batch").required(true).asText(36));
        eb.field("seq", f -> f.physicalColumn("f_seq").required(true).asNumeric(9, 0));
        eb.field("due", f -> f.physicalColumn("f_due").asBool());
        eb.field("stayDue", f -> f.physicalColumn("f_stay_due").asBool());
        eb.field("fail", f -> f.physicalColumn("f_fail").asBool());
        eb.field("sleepMs", f -> f.physicalColumn("f_sleep_ms").asNumeric(9, 0));
        eb.field("gate", f -> f.physicalColumn("f_gate").asText(100));
        eb.field("lock1", f -> f.physicalColumn("f_lock1").asText(100));
        eb.field("lock2", f -> f.physicalColumn("f_lock2").asText(100));
        eb.field("runs", f -> f.physicalColumn("f_runs").asNumeric(9, 0));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    public record ItemInput(@NotBlank String itemId) {}

    public record ItemOutput(String itemId, int runs) {}

    /** @param gate where to wait holding both locks; null for nowhere */
    public record OnlineInput(String lock1, String lock2, String gate) {}

    public record BatchInput(List<String> itemIds) {}

    private static final String ID = "itemId";
    private static final String LOADED = "item";

    private static EntityInstance item(ProcessContext ctx) {
        return ctx.get(LOADED, EntityInstance.class);
    }

    public static final ProcessDefinition<ItemInput, ItemOutput, ProcessContext> ITEM_RUN =
        ProcessDefinition.define(ITEM_PROCESS, 1, ItemInput.class, ItemOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(ID, input.itemId());
                return ctx;
            })
            .outputMapper(ctx -> new ItemOutput(item(ctx).get("itemId"),
                item(ctx).<BigDecimal>get("runs").intValue() + 1))
            .step("Load the item", LoadEntity.by(DATASET, ID, LOADED))
            .step("Take the first lock", HoldLock.<ProcessContext>exclusive(ctx -> item(ctx).get("lock1")))
            .step("Take the second lock", HoldLock.<ProcessContext>exclusive(ctx -> item(ctx).get("lock2")))
            .step("Work", Work.class, NoMetadata.INSTANCE)
            .compute("Count the run", (metadata, ctx) -> {
                EntityInstance item = item(ctx);
                if (Boolean.TRUE.equals(item.get("fail"))) {
                    ctx.reject(new Violation(null, "IT_ITEM_FAILED", "item " + item.get("itemId") + " fails"));
                    return;
                }
                ctx.changes().update(ENTITY, item.id(), item.version(), Map.of(
                    "runs", item.<BigDecimal>get("runs").add(BigDecimal.ONE),
                    "due", Boolean.TRUE.equals(item.get("stayDue"))));
            }));

    public static final ProcessDefinition<OnlineInput, OnlineInput, ProcessContext> ONLINE =
        ProcessDefinition.define(ONLINE_PROCESS, 1, OnlineInput.class, OnlineInput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get("input", OnlineInput.class))
                .step("Take the first lock", HoldLock.<ProcessContext>exclusive(
                    ctx -> ctx.get("input", OnlineInput.class).lock1()))
                .step("Take the second lock", HoldLock.<ProcessContext>exclusive(
                    ctx -> ctx.get("input", OnlineInput.class).lock2()))
                .step("Wait at the gate", OnlineGate.class, NoMetadata.INSTANCE));

    public static final ProcessDefinition<BatchInput, BatchInput, ProcessContext> BATCH =
        ProcessDefinition.define(BATCH_PROCESS, 1, BatchInput.class, BatchInput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get("input", BatchInput.class))
                .step("Every item in this transaction", CallProcess.<ProcessContext>forEach(ITEM_PROCESS, 1,
                    ctx -> ctx.get("input", BatchInput.class).itemIds().stream().map(ItemInput::new).toList(),
                    null)));

    /** A per-item job over one batch of items. */
    public static JobDefinition<ItemInput> job(String name, String batch) {
        return JobDefinition.forEach(name, "0 * * * * *", ZoneOffset.UTC,
            JobItems.template(TEMPLATE, at -> Map.of("batch", batch), "itemId"), ITEM_RUN,
            (at, row) -> new ItemInput((String) row.get("itemId")));
    }

    /** Counts the item in at its gate and waits there until the test opens it, or works {@code sleepMs}. */
    @Component
    public static class Work implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) throws InterruptedException {
            EntityInstance item = item(ctx);
            await(item.get("gate"));
            BigDecimal sleep = item.get("sleepMs");
            if (sleep != null && sleep.signum() > 0) {
                Thread.sleep(sleep.longValue());
            }
        }
    }

    @Component
    public static class OnlineGate implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) throws InterruptedException {
            await(ctx.get("input", OnlineInput.class).gate());
        }
    }

    static void await(String gate) throws InterruptedException {
        if (gate == null) {
            return;
        }
        ARRIVED.computeIfAbsent(gate, key -> new AtomicInteger()).incrementAndGet();
        CountDownLatch latch = GATES.get(gate);
        if (latch != null && !latch.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("gate " + gate + " was never opened");
        }
    }

    private ItJobItemFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        EntityDefinition itJobItemEntity() {
            return ITEM;
        }

        @Bean
        DatasetDefinition itJobItemDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(DATASET, d -> d.targetEntityType(ENTITY).asDefault()
                .permissions("it.read", "it.write").policy(p -> p.maxQueryBatchSize(10))
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        ProcessDefinition<ItemInput, ItemOutput, ProcessContext> itJobItem() {
            return ITEM_RUN;
        }

        @Bean
        ProcessDefinition<OnlineInput, OnlineInput, ProcessContext> itJobOnline() {
            return ONLINE;
        }

        @Bean
        ProcessDefinition<BatchInput, BatchInput, ProcessContext> itJobBatch() {
            return BATCH;
        }

        @Bean
        JobDefinition<ItemInput> itItemsJob() {
            return job(BEAN_JOB, BEAN_BATCH);
        }
    }
}
