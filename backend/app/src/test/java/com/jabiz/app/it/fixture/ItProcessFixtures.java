package com.jabiz.app.it.fixture;

import com.jabiz.entity.Violation;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ComputeStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.RetryPolicy;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes used by the process engine integration tests (ROADMAP phase 6). They write ItTicket (plain) and ItPrice
 * (temporal) rows; every test uses its own ids. Static probes let the tests see what steps observed.
 */
public final class ItProcessFixtures {

    public static final String PERMISSION = "it.process";

    /** Executions of {@link #COUNTED}, to show an idempotent request runs once. */
    public static final AtomicInteger COUNTED_RUNS = new AtomicInteger();
    /** Failures {@link FlakyNotifier} still has to produce before it succeeds. */
    public static final AtomicInteger NOTIFIER_FAILURES_LEFT = new AtomicInteger();
    /** Ticket ids {@link FlakyNotifier} saw, with whether the ticket was committed at that point. */
    public static final List<String> NOTIFIED = new CopyOnWriteArrayList<>();

    public record TicketInput(@NotBlank String id, @NotBlank String title, @Positive Long amount) {}

    public record TicketOutput(String id, long version, long processSeqId) {}

    public record ChildInput(String id, boolean fail) {}

    public record ParentInput(String parentTicket, String childTicket, boolean childFails) {}

    public record ParentOutput(long processSeqId, TicketOutput child) {}

    public record ThreadsOutput(boolean blockingOnVirtualThread, boolean computeOnVirtualThread) {}

    public record ReadsInput(String id, String owner) {}

    public record ReadsOutput(String loadedTitle, int sameOwner, int softNames, long savedVersion) {}

    public record PriceInput(String sku, long amount, Instant scheduleAt, long scheduledAmount) {}

    public record PriceOutput(String priceId, long processSeqId) {}

    public record PriceFamilyInput(String parentSku, String childSku) {}

    public record PricesAtInput(String sku, Instant knownAt) {}

    public record PricesAtOutput(List<Object> amounts) {}

    private static Map<String, Object> ticket(String id, String title, Long amount) {
        return Map.of("ticketId", id, "title", title, "amount", amount == null ? 0L : amount, "status", "OPEN",
            "owner", "it-owner");
    }

    private static TicketOutput savedTicket(ProcessContext ctx) {
        ChangeSet.Saved saved = ctx.changes().saved().getLast();
        return new TicketOutput(String.valueOf(saved.id()), saved.version(), ctx.processSeqId());
    }

    /** Inserts a ticket. */
    public static final ProcessDefinition<TicketInput, TicketOutput, ProcessContext> CREATE_TICKET =
        ProcessDefinition.define("IT_CREATE_TICKET", 1, TicketInput.class, TicketOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, in) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", in);
                    return ctx;
                })
                .outputMapper(ItProcessFixtures::savedTicket)
                .compute("Register", (metadata, ctx) -> {
                    TicketInput in = ctx.get("in", TicketInput.class);
                    ctx.changes().insert("ItTicket", ticket(in.id(), in.title(), in.amount()));
                }));

    /** Version 2 of the same process: the title is prefixed, so tests can tell the versions apart. */
    public static final ProcessDefinition<TicketInput, TicketOutput, ProcessContext> CREATE_TICKET_V2 =
        ProcessDefinition.single("IT_CREATE_TICKET", 2, TicketInput.class, TicketOutput.class, (in, ctx) -> {
            ctx.changes().insert("ItTicket", ticket(in.id(), "v2 " + in.title(), in.amount()));
            return new TicketOutput(in.id(), 1L, ctx.processSeqId());
        }).withPermissions(PERMISSION);

    /** Inserts a ticket, or fails with a violation when asked to. */
    public static final ProcessDefinition<ChildInput, TicketOutput, ProcessContext> CHILD =
        ProcessDefinition.define("IT_CHILD", 1, ChildInput.class, TicketOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, in) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", in);
                return ctx;
            })
            .outputMapper(ItProcessFixtures::savedTicket)
            .compute("Register", (metadata, ctx) -> {
                ChildInput in = ctx.get("in", ChildInput.class);
                ctx.changes().insert("ItTicket", ticket(in.id(), "child", 1L));
                if (in.fail()) {
                    ctx.reject(new Violation(null, "IT_CHILD_REFUSED", "the child refuses"));
                }
            }));

    /** Inserts a ticket, then calls {@link #CHILD} in the same transaction. */
    public static final ProcessDefinition<ParentInput, ParentOutput, ProcessContext> PARENT =
        ProcessDefinition.define("IT_PARENT", 1, ParentInput.class, ParentOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, in) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", in);
                return ctx;
            })
            .outputMapper(ctx -> new ParentOutput(ctx.processSeqId(), ctx.get("child", TicketOutput.class)))
            .compute("Register", (metadata, ctx) -> {
                ParentInput in = ctx.get("in", ParentInput.class);
                ctx.changes().insert("ItTicket", ticket(in.parentTicket(), "parent", 1L));
            })
            .step("Save", SaveChanges.now())
            .step("Call child", CallProcess.of("IT_CHILD", 1, ctx -> {
                ParentInput in = ctx.get("in", ParentInput.class);
                return new ChildInput(in.childTicket(), in.childFails());
            }, "child")));

    public record FamilyInput(@NotBlank String parentTicket, java.util.List<String> childTickets, String failing) {}

    public record FamilyOutput(long processSeqId, java.util.List<TicketOutput> children) {}

    /** Inserts a ticket, then calls {@link #CHILD} once for each child ticket, in the same transaction. */
    @SuppressWarnings("unchecked")
    public static final ProcessDefinition<FamilyInput, FamilyOutput, ProcessContext> FAMILY =
        ProcessDefinition.define("IT_FAMILY", 1, FamilyInput.class, FamilyOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, in) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", in);
                return ctx;
            })
            .outputMapper(ctx -> new FamilyOutput(ctx.processSeqId(),
                ctx.contains("children") ? (java.util.List<TicketOutput>) ctx.get("children") : null))
            .compute("Register", (metadata, ctx) -> {
                FamilyInput in = ctx.get("in", FamilyInput.class);
                ctx.changes().insert("ItTicket", ticket(in.parentTicket(), "parent", 1L));
            })
            .step("Call the children", CallProcess.forEach("IT_CHILD", 1, ctx -> {
                FamilyInput in = ctx.get("in", FamilyInput.class);
                return in.childTickets().stream().map(id -> new ChildInput(id, id.equals(in.failing()))).toList();
            }, "children")));

    /** Rejects, then saves: the save refuses because of the violation, which is reported once. */
    public static final ProcessDefinition<TicketInput, TicketOutput, ProcessContext> REJECT_THEN_SAVE =
        ProcessDefinition.define("IT_REJECT_THEN_SAVE", 1, TicketInput.class, TicketOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, in) -> new ProcessContext(start))
                .outputMapper(ctx -> null)
                .compute("Reject", (metadata, ctx) -> ctx.reject(new Violation("title", "IT_REFUSED", "refused")))
                .step("Save", SaveChanges.now()));

    /** Three steps: two report violations, one registers a change that must never be written. */
    public static final ProcessDefinition<TicketInput, TicketOutput, ProcessContext> MULTI_VIOLATION =
        ProcessDefinition.define("IT_MULTI_VIOLATION", 1, TicketInput.class, TicketOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, in) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", in);
                    return ctx;
                })
                .outputMapper(ItProcessFixtures::savedTicket)
                .compute("Check title", (metadata, ctx) ->
                    ctx.reject(new Violation("title", "IT_TITLE_TAKEN", "title taken", Map.of())))
                .compute("Register", (metadata, ctx) -> {
                    TicketInput in = ctx.get("in", TicketInput.class);
                    ctx.changes().insert("ItTicket", ticket(in.id(), in.title(), in.amount()));
                })
                .compute("Check amount", (metadata, ctx) ->
                    ctx.reject(new Violation("amount", "IT_AMOUNT_TOO_HIGH", "amount too high", Map.of()))));

    /** Records on which kinds of thread a blocking and a computation step run. */
    public static final ProcessDefinition<String, ThreadsOutput, ProcessContext> THREADS =
        ProcessDefinition.define("IT_THREADS", 1, String.class, ThreadsOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, in) -> new ProcessContext(start))
            .outputMapper(ctx -> new ThreadsOutput(ctx.get("blockingVirtual", Boolean.class),
                ctx.get("computeVirtual", Boolean.class)))
            .step("Call slow service", SlowService.class, NoMetadata.INSTANCE)
            .compute("Compute", (metadata, ctx) -> ctx.put("computeVirtual", Thread.currentThread().isVirtual())));

    /** Counts its executions; a blocking step keeps the transaction open long enough for requests to overlap. */
    public static final ProcessDefinition<TicketInput, TicketOutput, ProcessContext> COUNTED =
        ProcessDefinition.define("IT_COUNTED", 1, TicketInput.class, TicketOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, in) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", in);
                return ctx;
            })
            .outputMapper(ItProcessFixtures::savedTicket)
            .compute("Count", (metadata, ctx) -> {
                COUNTED_RUNS.incrementAndGet();
                TicketInput in = ctx.get("in", TicketInput.class);
                ctx.changes().insert("ItTicket", ticket(in.id(), in.title(), in.amount()));
            })
            .step("Wait", SlowService.class, NoMetadata.INSTANCE));

    /** Inserts a ticket and notifies an unreliable service once the transaction has committed. */
    public static final ProcessDefinition<ChildInput, TicketOutput, ProcessContext> NOTIFY =
        ProcessDefinition.define("IT_NOTIFY", 1, ChildInput.class, TicketOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, in) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", in);
                ctx.put("ticketId", in.id());
                return ctx;
            })
            .outputMapper(ctx -> new TicketOutput(ctx.get("ticketId", String.class), 1L, ctx.processSeqId()))
            .compute("Register", (metadata, ctx) -> {
                ChildInput in = ctx.get("in", ChildInput.class);
                ctx.changes().insert("ItTicket", ticket(in.id(), "notified", 1L));
            })
            .afterCommit("Notify", FlakyNotifier.class, NoMetadata.INSTANCE, new RetryPolicy(3, Duration.ofMillis(10)))
            .compute("Maybe fail", (metadata, ctx) -> {
                if (ctx.get("in", ChildInput.class).fail()) {
                    throw new IllegalStateException("fails after the after-commit step was declared");
                }
            }));

    /** Loads, queries, runs a template and saves midway. */
    public static final ProcessDefinition<ReadsInput, ReadsOutput, ProcessContext> READS =
        ProcessDefinition.define("IT_READS", 1, ReadsInput.class, ReadsOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, in) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", in);
                ctx.put("id", in.id());
                return ctx;
            })
            .outputMapper(ctx -> new ReadsOutput(
                ctx.get("ticket", EntityInstance.class).get("title"),
                ctx.get("sameOwner", List.class).size(),
                ctx.get("softNames", List.class).size(),
                ctx.get("reloaded", EntityInstance.class).version()))
            .step("Load", LoadEntity.by(ItFixtures.TICKET_DATASET, "id", "ticket"))
            .step("Query", QueryEntities.of(ItFixtures.TICKET_DATASET,
                ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("owner", ctx.get("in", ReadsInput.class).owner()))
                    .limit(5).build(),
                "sameOwner"))
            .step("Template", RunTemplate.of("it.soft_names", ctx -> Map.of(), "softNames"))
            .compute("Rename", (metadata, ctx) -> {
                EntityInstance loaded = ctx.get("ticket", EntityInstance.class);
                ctx.changes().update("ItTicket", loaded.id(), loaded.version(), Map.of("title", "renamed"));
            })
            .step("Save", SaveChanges.now())
            .step("Reload", LoadEntity.by(ItFixtures.TICKET_DATASET, "id", "reloaded")));

    /** Runs a template as known at the given time (docs/design/19-reports.md section 2.1). */
    public static final ProcessDefinition<PricesAtInput, PricesAtOutput, ProcessContext> PRICES_AT =
        ProcessDefinition.define("IT_PRICES_AT", 1, PricesAtInput.class, PricesAtOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, in) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", in);
                    return ctx;
                })
                .outputMapper(ctx -> {
                    String sku = ctx.get("in", PricesAtInput.class).sku();
                    List<?> rows = ctx.get("prices", List.class);
                    return new PricesAtOutput(rows.stream().map(row -> (Map<?, ?>) row)
                        .filter(row -> sku.equals(row.get("sku"))).<Object>map(row -> row.get("amount")).toList());
                })
                .step("Template", RunTemplate.at("it.jp_prices", ctx -> Map.of(), ctx -> null,
                    ctx -> ctx.get("in", PricesAtInput.class).knownAt(), "prices")));

    /** Inserts a temporal price and schedules a change of its amount, in one operation. */
    public static final ProcessDefinition<PriceInput, PriceOutput, ProcessContext> PRICE =
        ProcessDefinition.define("IT_PRICE", 1, PriceInput.class, PriceOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, in) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", in);
                return ctx;
            })
            .outputMapper(ctx -> new PriceOutput(String.valueOf(ctx.get("priceId")), ctx.processSeqId()))
            .compute("Insert", (metadata, ctx) -> {
                PriceInput in = ctx.get("in", PriceInput.class);
                Object id = ctx.changes().insert("ItPrice",
                    Map.of("sku", in.sku(), "region", "JP", "amount", in.amount(), "status", "DRAFT"));
                ctx.put("priceId", id);
            })
            .step("Save", SaveChanges.now())
            .compute("Schedule", (metadata, ctx) -> {
                PriceInput in = ctx.get("in", PriceInput.class);
                if (in.scheduleAt() != null) {
                    ctx.changes().effectiveAt(in.scheduleAt())
                        .update("ItPrice", ctx.get("priceId"), 1L, Map.of("amount", in.scheduledAmount()));
                }
            }));

    /** Inserts a price and calls {@link #PRICE} for another one: a revert of it reverts both. */
    public static final ProcessDefinition<PriceFamilyInput, PriceOutput, ProcessContext> PRICE_FAMILY =
        ProcessDefinition.define("IT_PRICE_FAMILY", 1, PriceFamilyInput.class, PriceOutput.class,
            ProcessContext.class, pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, in) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", in);
                    return ctx;
                })
                .outputMapper(ctx -> new PriceOutput(String.valueOf(ctx.get("priceId")), ctx.processSeqId()))
                .compute("Insert", (metadata, ctx) -> ctx.put("priceId", ctx.changes().insert("ItPrice",
                    Map.of("sku", ctx.get("in", PriceFamilyInput.class).parentSku(), "region", "JP", "amount", 10L,
                        "status", "DRAFT"))))
                .step("Child", CallProcess.latest("IT_PRICE",
                    ctx -> new PriceInput(ctx.get("in", PriceFamilyInput.class).childSku(), 20L, null, 0L), null)));

    /** Wrongly registers a change after the commit: attempted once, since a retry cannot help. */
    public static final ProcessDefinition<ChildInput, TicketOutput, ProcessContext> LATE_CHANGE =
        ProcessDefinition.define("IT_LATE_CHANGE", 1, ChildInput.class, TicketOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, in) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("ticketId", in.id());
                    return ctx;
                })
                .outputMapper(ctx -> new TicketOutput(ctx.get("ticketId", String.class), 0L, ctx.processSeqId()))
                .compute("Nothing", (metadata, ctx) -> {})
                .afterCommit("Register late", LateRegistrar.class, NoMetadata.INSTANCE,
                    new RetryPolicy(3, Duration.ofMillis(10))));

    @Component
    public static class LateRegistrar implements ComputeStep<NoMetadata, ProcessContext> {
        @Override
        public void compute(NoMetadata metadata, ProcessContext ctx) {
            ctx.changes().insert("ItTicket", ticket(ctx.get("ticketId", String.class), "late", 1L));
        }
    }

    /** A service with a blocking client only: sleeps, and records whether it ran on a virtual thread. */
    @Component
    public static class SlowService implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) throws InterruptedException {
            ctx.put("blockingVirtual", Thread.currentThread().isVirtual());
            Thread.sleep(300);
        }
    }

    /**
     * Notifies after the commit; fails while {@link #NOTIFIER_FAILURES_LEFT} is positive. Records whether the ticket
     * it notifies about is already visible to another connection, i.e. committed.
     */
    @Component
    public static class FlakyNotifier implements BlockingStep<NoMetadata, ProcessContext> {

        /** Reads the database the way an external system would: through its own connection. */
        public static volatile java.util.function.Predicate<String> committed = id -> false;

        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {
            String id = ctx.get("ticketId", String.class);
            NOTIFIED.add(id + (committed.test(id) ? ":committed" : ":uncommitted"));
            if (NOTIFIER_FAILURES_LEFT.getAndDecrement() > 0) {
                throw new IllegalStateException("notification service unavailable");
            }
        }
    }

    private ItProcessFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        ProcessDefinition<TicketInput, TicketOutput, ProcessContext> itCreateTicket() {
            return CREATE_TICKET;
        }

        @Bean
        ProcessDefinition<TicketInput, TicketOutput, ProcessContext> itCreateTicketV2() {
            return CREATE_TICKET_V2;
        }

        @Bean
        ProcessDefinition<ChildInput, TicketOutput, ProcessContext> itChild() {
            return CHILD;
        }

        @Bean
        ProcessDefinition<ParentInput, ParentOutput, ProcessContext> itParent() {
            return PARENT;
        }

        @Bean
        ProcessDefinition<FamilyInput, FamilyOutput, ProcessContext> itFamily() {
            return FAMILY;
        }

        @Bean
        ProcessDefinition<TicketInput, TicketOutput, ProcessContext> itRejectThenSave() {
            return REJECT_THEN_SAVE;
        }

        @Bean
        ProcessDefinition<TicketInput, TicketOutput, ProcessContext> itMultiViolation() {
            return MULTI_VIOLATION;
        }

        @Bean
        ProcessDefinition<String, ThreadsOutput, ProcessContext> itThreads() {
            return THREADS;
        }

        @Bean
        ProcessDefinition<TicketInput, TicketOutput, ProcessContext> itCounted() {
            return COUNTED;
        }

        @Bean
        ProcessDefinition<ChildInput, TicketOutput, ProcessContext> itNotify() {
            return NOTIFY;
        }

        @Bean
        ProcessDefinition<ChildInput, TicketOutput, ProcessContext> itLateChange() {
            return LATE_CHANGE;
        }

        @Bean
        ProcessDefinition<ReadsInput, ReadsOutput, ProcessContext> itReads() {
            return READS;
        }

        @Bean
        ProcessDefinition<PricesAtInput, PricesAtOutput, ProcessContext> itPricesAt() {
            return PRICES_AT;
        }

        @Bean
        ProcessDefinition<PriceInput, PriceOutput, ProcessContext> itPrice() {
            return PRICE;
        }

        @Bean
        ProcessDefinition<PriceFamilyInput, PriceOutput, ProcessContext> itPriceFamily() {
            return PRICE_FAMILY;
        }
    }
}
