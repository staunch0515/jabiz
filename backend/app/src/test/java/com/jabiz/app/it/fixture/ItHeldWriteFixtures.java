package com.jabiz.app.it.fixture;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.SaveChanges;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes of the writes a transaction holds back (ROADMAP phase 16p-2, T9; decision D46 rule 1): a price written
 * with {@code SaveChanges.now} and held, then something that comes after it in the process (a lock another
 * transaction holds, a failure that is not the database's, a blocking step, an event, a sub-process).
 */
public final class ItHeldWriteFixtures {

    public static final String PERMISSION = "it.held";
    public static final String EVENT = "it.held-noted";

    /** Lock waits of these processes: short, so that a lock another transaction holds fails them quickly. */
    public static final Duration WAIT = Duration.ofMillis(300);

    /**
     * @param priceId the price to insert (may exist already)
     * @param then    what follows the save: {@code lock} (the exclusive lock {@code lockName}, waiting {@link #WAIT}),
     *                {@code fail} (an error of the process, not the database's), {@code blocking} (a blocking step,
     *                counted in {@link #BLOCKING_RUNS}), {@code event} (an event, then success), {@code event-fail}
     *                (an event, then the failure), {@code child} (a sub-process inserting another price), or null
     */
    public record HeldInput(UUID priceId, String sku, String then, String lockName) {}

    public record HeldOutput(String priceId) {}

    /** How often the blocking step of {@link #HELD} ran. */
    public static final AtomicInteger BLOCKING_RUNS = new AtomicInteger();

    public static Map<String, Object> price(UUID id, String sku) {
        Map<String, Object> price = new LinkedHashMap<>();
        price.put("priceId", id);
        price.put("sku", sku);
        price.put("region", "JP");
        price.put("amount", 100);
        price.put("status", "DRAFT");
        return price;
    }

    private static boolean then(ProcessContext ctx, String what) {
        return what.equals(ctx.get("input", HeldInput.class).then());
    }

    public static final ProcessDefinition<HeldInput, HeldOutput, ProcessContext> HELD =
        ProcessDefinition.define("IT_HELD", 1, HeldInput.class, HeldOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new HeldOutput(String.valueOf(ctx.get("input", HeldInput.class).priceId())))
            .compute("Insert the price", (metadata, ctx) -> {
                HeldInput input = ctx.get("input", HeldInput.class);
                ctx.changes().insert("ItPrice", price(input.priceId(), input.sku()));
            })
            .step("Save", SaveChanges.now())
            .step("Lock", HoldLock.<ProcessContext>exclusive(ctx -> then(ctx, "lock")
                ? ctx.get("input", HeldInput.class).lockName() : null, WAIT))
            .step("Publish", PublishEvent.<ProcessContext>when(ctx -> then(ctx, "event") || then(ctx, "event-fail"),
                EVENT, ctx -> Map.of("priceId", String.valueOf(ctx.get("input", HeldInput.class).priceId()))))
            .step("Block", Blocking.class, NoMetadata.INSTANCE)
            .step("Child", CallProcess.<ProcessContext>when(ctx -> then(ctx, "child"), "IT_HELD_INSERT", 1,
                ctx -> new HeldInput(UUID.nameUUIDFromBytes((ctx.get("input", HeldInput.class).priceId() + "-child")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)), ctx.get("input", HeldInput.class).sku() + "-c",
                    null, null),
                null))
            .compute("Fail when asked", (metadata, ctx) -> {
                if (then(ctx, "fail") || then(ctx, "event-fail")) {
                    throw new IllegalStateException("the step after the save failed");
                }
            }));

    /** Inserts a price: the sub-process of {@link #HELD}. */
    public static final ProcessDefinition<HeldInput, HeldOutput, ProcessContext> INSERT =
        ProcessDefinition.define("IT_HELD_INSERT", 1, HeldInput.class, HeldOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new HeldOutput(String.valueOf(ctx.get("input", HeldInput.class).priceId())))
            .compute("Insert the price", (metadata, ctx) -> {
                HeldInput input = ctx.get("input", HeldInput.class);
                ctx.changes().insert("ItPrice", price(input.priceId(), input.sku()));
            }));

    /** @param ticketId a ticket another transaction changes after it is loaded; {@code code} a code to insert */
    public record TouchThenInsert(String ticketId, String code) {}

    /**
     * Loads a ticket, lets another transaction change it, then changes it at the version it loaded (an update that
     * changes no row, held) and inserts an {@code ItUnique} of {@code code} after it (which may conflict).
     */
    public static final ProcessDefinition<TouchThenInsert, HeldOutput, ProcessContext> TOUCH_THEN_INSERT =
        ProcessDefinition.define("IT_HELD_TOUCH_THEN_INSERT", 1, TouchThenInsert.class, HeldOutput.class,
            ProcessContext.class, pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    // As ItBudgetFixtures.Meanwhile reads it: another transaction updates the loaded ticket.
                    ctx.put("input", new ItBudgetFixtures.TouchInput(input.ticketId(), Map.of(), "update"));
                    ctx.put("id", input.ticketId());
                    ctx.put("code", input.code());
                    return ctx;
                })
                .outputMapper(ctx -> new HeldOutput(String.valueOf(ctx.get("id"))))
                .step("Load the ticket", com.jabiz.runtime.process.steps.LoadEntity.by(ItFixtures.TICKET_DATASET, "id",
                    "ticket"))
                .step("Meanwhile", ItBudgetFixtures.Meanwhile.class, NoMetadata.INSTANCE)
                .compute("Change it, then insert", (metadata, ctx) -> {
                    com.jabiz.runtime.EntityInstance ticket = ctx.get("ticket", com.jabiz.runtime.EntityInstance.class);
                    ctx.changes().update("ItTicket", ticket.id(), ticket.version(), Map.of("title", "late"));
                    ctx.changes().insert("ItUnique", Map.of("uniqueId", "held-" + ctx.get("code"), "code",
                        ctx.get("code")));
                }));

    /** The output of {@link #SEALED}: what the seal in the same transaction sealed. */
    public record SealedOutput(String priceId, long rowCount) {}

    /** Inserts a price (held), then seals the append-only tables in the same transaction. */
    public static final ProcessDefinition<HeldInput, SealedOutput, ProcessContext> SEALED =
        ProcessDefinition.define("IT_HELD_THEN_SEAL", 1, HeldInput.class, SealedOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new SealedOutput(String.valueOf(ctx.get("input", HeldInput.class).priceId()),
                    ctx.get("sealed", com.jabiz.runtime.integrity.IntegrityProcesses.SealOutput.class).rowCount()))
                .compute("Insert the price", (metadata, ctx) -> {
                    HeldInput input = ctx.get("input", HeldInput.class);
                    ctx.changes().insert("ItPrice", price(input.priceId(), input.sku()));
                })
                .step("Save", SaveChanges.now())
                .step("Seal", CallProcess.of(com.jabiz.runtime.integrity.IntegrityProcesses.SEAL, 1,
                    ctx -> new com.jabiz.runtime.integrity.IntegrityProcesses.SealInput(null), "sealed")));

    /** @param skus the prices the sub-processes insert: all but the last through a {@code forEach}, the last alone */
    public record ManyInput(java.util.List<String> skus) {}

    /** Calls {@link #INSERT} once per SKU but the last through a {@code forEach}, then once more for the last. */
    public static final ProcessDefinition<ManyInput, HeldOutput, ProcessContext> MANY =
        ProcessDefinition.define("IT_HELD_MANY", 1, ManyInput.class, HeldOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new HeldOutput("many"))
            .step("Each", CallProcess.<ProcessContext>forEach("IT_HELD_INSERT", 1, ctx -> {
                java.util.List<String> skus = ctx.get("input", ManyInput.class).skus();
                return skus.subList(0, skus.size() - 1).stream().map(ItHeldWriteFixtures::insertOf).toList();
            }, null))
            .step("Last", CallProcess.of("IT_HELD_INSERT", 1,
                ctx -> insertOf(ctx.get("input", ManyInput.class).skus().getLast()), null)));

    private static HeldInput insertOf(String sku) {
        return new HeldInput(UUID.nameUUIDFromBytes(sku.getBytes(java.nio.charset.StandardCharsets.UTF_8)), sku, null,
            null);
    }

    /**
     * Inserts an {@code ItUnique} of {@code code} (held; its index may refuse it), then collects a violation, then reads
     * the uniques of the code, which writes what is held first: a conflict there is the insert's alone.
     */
    public static final ProcessDefinition<TouchThenInsert, HeldOutput, ProcessContext> UNIQUE_THEN_REJECT =
        ProcessDefinition.define("IT_HELD_UNIQUE_THEN_REJECT", 1, TouchThenInsert.class, HeldOutput.class,
            ProcessContext.class, pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("code", input.code());
                    return ctx;
                })
                .outputMapper(ctx -> new HeldOutput(String.valueOf(ctx.get("code"))))
                .compute("Insert", (metadata, ctx) -> ctx.changes().insert("ItUnique", Map.of("uniqueId",
                    "held-" + ctx.get("code"), "code", ctx.get("code"))))
                .step("Save", SaveChanges.now())
                .compute("Reject", (metadata, ctx) -> ctx.reject(new com.jabiz.entity.Violation(null,
                    "IT_HELD_LATER", "collected after the insert")))
                .step("Read", com.jabiz.runtime.process.steps.QueryEntities.of(ItFixtures.UNIQUE_DATASET,
                    ctx -> com.jabiz.query.EntityQuery.builder().where(new com.jabiz.query.QueryPredicate.Eq("code",
                        ctx.get("code"))).limit(5).build(), "found")));

    /** Counts its runs when the input asks for {@code blocking}; nothing otherwise. */
    @Component
    public static class Blocking implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {
            if ("blocking".equals(ctx.get("input", HeldInput.class).then())) {
                BLOCKING_RUNS.incrementAndGet();
            }
        }
    }

    private ItHeldWriteFixtures() {}

    @Configuration
    static class Beans {
        @Bean
        ProcessDefinition<HeldInput, HeldOutput, ProcessContext> itHeld() {
            return HELD;
        }

        @Bean
        ProcessDefinition<TouchThenInsert, HeldOutput, ProcessContext> itHeldTouchThenInsert() {
            return TOUCH_THEN_INSERT;
        }

        @Bean
        ProcessDefinition<HeldInput, SealedOutput, ProcessContext> itHeldThenSeal() {
            return SEALED;
        }

        @Bean
        ProcessDefinition<TouchThenInsert, HeldOutput, ProcessContext> itHeldUniqueThenReject() {
            return UNIQUE_THEN_REJECT;
        }

        @Bean
        ProcessDefinition<ManyInput, HeldOutput, ProcessContext> itHeldMany() {
            return MANY;
        }

        @Bean
        ProcessDefinition<HeldInput, HeldOutput, ProcessContext> itHeldInsert() {
            return INSERT;
        }
    }
}
