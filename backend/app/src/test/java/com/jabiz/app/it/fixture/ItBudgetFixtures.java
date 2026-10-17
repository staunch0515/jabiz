package com.jabiz.app.it.fixture;

import com.jabiz.context.RequestContext;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.SaveChanges;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Processes of the statement budget and of the guarantees behind it (ROADMAP phase 16p, decision D46):
 * {@code LEDGER_POST} as the sub-process of a process that does nothing else; a plain entity updated at the version
 * this transaction read, also while another transaction changes or deletes it; a reference to an instance read or
 * deleted earlier in the same transaction.
 */
public final class ItBudgetFixtures {

    public static final String PERMISSION = "it.budget";

    /** Posts {@code input} through {@code LEDGER_POST} as its sub-process (the shape of a business process's posting). */
    public static final ProcessDefinition<LedgerProcesses.PostInput, LedgerProcesses.PostOutput, ProcessContext>
        POST_AS_CHILD = ProcessDefinition.define("IT_BUDGET_POST", 1, LedgerProcesses.PostInput.class,
            LedgerProcesses.PostOutput.class, ProcessContext.class, pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get("posted", LedgerProcesses.PostOutput.class))
                .step("Post", CallProcess.of(LedgerProcesses.POST, 1, ctx -> ctx.get("input"), "posted")));

    /**
     * @param changes   the fields to change and their values
     * @param meanwhile what another transaction does to the ticket after it was loaded: {@code update} (its title),
     *                  {@code delete}, or null for nothing
     */
    public record TouchInput(String ticketId, Map<String, Object> changes, String meanwhile) {
        public TouchInput(String ticketId, Map<String, Object> changes) {
            this(ticketId, changes, null);
        }
    }

    public record TouchOutput(String ticketId, long version) {}

    /** Loads a ticket and changes it: an update at the version just read. */
    public static final ProcessDefinition<TouchInput, TouchOutput, ProcessContext> TOUCH =
        ProcessDefinition.define("IT_BUDGET_TOUCH", 1, TouchInput.class, TouchOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory(ItBudgetFixtures::touchContext)
                .outputMapper(ItBudgetFixtures::touched)
                .step("Load the ticket", LoadEntity.by(ItFixtures.TICKET_DATASET, "id", "ticket"))
                .step("Meanwhile", Meanwhile.class, NoMetadata.INSTANCE)
                .compute("Change it", (metadata, ctx) -> {
                    EntityInstance ticket = ctx.get("ticket", EntityInstance.class);
                    ctx.changes().update("ItTicket", ticket.id(), ticket.version(),
                        ctx.get("input", TouchInput.class).changes());
                }));

    /** Changes a ticket twice in one transaction: the second update cannot rely on the first read. */
    public static final ProcessDefinition<TouchInput, TouchOutput, ProcessContext> TOUCH_TWICE =
        ProcessDefinition.define("IT_BUDGET_TOUCH_TWICE", 1, TouchInput.class, TouchOutput.class,
            ProcessContext.class, pb -> pb
                .permissions(PERMISSION)
                .contextFactory(ItBudgetFixtures::touchContext)
                .outputMapper(ItBudgetFixtures::touched)
                .step("Load the ticket", LoadEntity.by(ItFixtures.TICKET_DATASET, "id", "ticket"))
                .compute("Change it", (metadata, ctx) -> {
                    EntityInstance ticket = ctx.get("ticket", EntityInstance.class);
                    ctx.changes().update("ItTicket", ticket.id(), ticket.version(), Map.of("title", "once"));
                })
                .step("Save", SaveChanges.now())
                .compute("Change it again", (metadata, ctx) -> {
                    EntityInstance ticket = ctx.get("ticket", EntityInstance.class);
                    ctx.changes().update("ItTicket", ticket.id(), ctx.changes().saved().getLast().version(),
                        ctx.get("input", TouchInput.class).changes());
                }));

    private static ProcessContext touchContext(com.jabiz.process.ProcessStart start, TouchInput input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put("input", input);
        ctx.put("id", input.ticketId());
        return ctx;
    }

    private static TouchOutput touched(ProcessContext ctx) {
        return new TouchOutput(ctx.get("input", TouchInput.class).ticketId(), ctx.changes().saved().getLast().version());
    }

    /** What another transaction does to the loaded ticket, committed before the process goes on. */
    @Component
    public static class Meanwhile implements StepHandler<NoMetadata, ProcessContext> {
        private final DatasetEntityManager entities;
        private final DatasetRegistry datasets;

        public Meanwhile(DatasetEntityManager entities, DatasetRegistry datasets) {
            this.entities = entities;
            this.datasets = datasets;
        }

        @Override
        public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
            String meanwhile = ctx.get("input", TouchInput.class).meanwhile();
            if (meanwhile == null) {
                return Mono.empty();
            }
            EntityInstance ticket = ctx.get("ticket", EntityInstance.class);
            EntityChange change = "delete".equals(meanwhile)
                ? new EntityChange(EntityAction.DELETE, new EntityInstance(ticket.id(), "ItTicket", ticket.version(),
                    null, Map.of()))
                : new EntityChange(EntityAction.UPDATE, new EntityInstance(ticket.id(), "ItTicket", ticket.version(),
                    null, Map.of("title", "changed meanwhile")));
            // A context of its own: another transaction, on another connection, committed before this one goes on.
            RequestContext other = new RequestContext("it-other", null, java.util.Locale.ENGLISH, "it-other",
                java.util.Set.of(), java.util.Set.of("*"));
            return entities.commitBatch(datasets.findById(ItFixtures.TICKET_DATASET).orElseThrow(), List.of(change))
                .contextWrite(view -> RequestContexts.put(Context.empty(), other))
                .then();
        }
    }

    public record NoteInput(String priceId, boolean deletePrice) {}

    public record NoteOutput(String noteId) {}

    private static ProcessDefinition<NoteInput, NoteOutput, ProcessContext> noteAfterRead(String name,
        String dataset) {
        return ProcessDefinition.define(name, 1, NoteInput.class, NoteOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                ctx.put("priceId", input.priceId());
                return ctx;
            })
            .outputMapper(ctx -> new NoteOutput(String.valueOf(ctx.get("note"))))
            .step("Load the price", LoadEntity.by(dataset, "priceId", "price"))
            .compute("Note it", (metadata, ctx) -> {
                EntityInstance price = ctx.get("price", EntityInstance.class);
                if (ctx.get("input", NoteInput.class).deletePrice()) {
                    ctx.changes().delete("ItPrice", price.id(), price.version());
                }
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("priceRef", price.id());
                note.put("body", "after a read");
                ctx.put("note", ctx.changes().insert("ItNote", note));
            }));
    }

    /** Reads a price through its default dataset, then notes it (deleting it first when asked). */
    public static final ProcessDefinition<NoteInput, NoteOutput, ProcessContext> NOTE_AFTER_DEFAULT_READ =
        noteAfterRead("IT_BUDGET_NOTE_DEFAULT", ItTemporalFixtures.PRICE_DATASET);

    /** Reads a price through the dataset of region JP, then notes it. */
    public static final ProcessDefinition<NoteInput, NoteOutput, ProcessContext> NOTE_AFTER_SCOPED_READ =
        noteAfterRead("IT_BUDGET_NOTE_JP", ItTemporalFixtures.PRICE_JP_DATASET);

    private ItBudgetFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        ProcessDefinition<LedgerProcesses.PostInput, LedgerProcesses.PostOutput, ProcessContext> itBudgetPost() {
            return POST_AS_CHILD;
        }

        @Bean
        ProcessDefinition<TouchInput, TouchOutput, ProcessContext> itBudgetTouch() {
            return TOUCH;
        }

        @Bean
        ProcessDefinition<TouchInput, TouchOutput, ProcessContext> itBudgetTouchTwice() {
            return TOUCH_TWICE;
        }

        @Bean
        ProcessDefinition<NoteInput, NoteOutput, ProcessContext> itBudgetNoteDefault() {
            return NOTE_AFTER_DEFAULT_READ;
        }

        @Bean
        ProcessDefinition<NoteInput, NoteOutput, ProcessContext> itBudgetNoteJp() {
            return NOTE_AFTER_SCOPED_READ;
        }
    }
}
