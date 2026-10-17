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
public final class ItBudgetFixtures extends com.jabiz.entity.BaseEntityDefinitions {

    public static final String PERMISSION = "it.budget";

    /** The database dictionary of {@link #AUDITED}'s kind (items in {@code sys_dict_item_version}). */
    public static final String KINDS = "urn:jabiz:dict:it_audited_kind";
    public static final String AUDITED_DATASET = "urn:jabiz:dataset:it:ItAudited";

    /**
     * A plain entity with a secret, an audit-masked and a database-dictionary field, publishing its changes: what an
     * update writes besides itself (audit record, event) is compared between one statement and one at a time.
     */
    public static final com.jabiz.entity.EntityDefinition AUDITED = com.jabiz.entity.EntityDefinition.define(
        "ItAudited", eb -> {
            eb.physicalTable("it_audited");
            eb.primaryKey("auditedId");
            eb.field("auditedId", semanticIdentity("f_id", "urn:jabiz:entity:it:audited"));
            eb.field("note", f -> f.physicalColumn("f_note").asText(200));
            eb.field("secret", f -> f.physicalColumn("f_secret").asText(200).sensitive());
            eb.field("fileName", f -> f.physicalColumn("f_file").asText(200).auditMasked());
            eb.field("kind", f -> f.physicalColumn("f_kind").asCode(KINDS));
            eb.field("rowVersion", rowVersion("f_version"));
            eb.publishChanges();
        });

    /** Loads an audited instance and changes it at the version read. */
    public static final ProcessDefinition<TouchInput, TouchOutput, ProcessContext> TOUCH_AUDITED =
        ProcessDefinition.define("IT_BUDGET_TOUCH_AUDITED", 1, TouchInput.class, TouchOutput.class,
            ProcessContext.class, pb -> pb
                .permissions(PERMISSION)
                .contextFactory(ItBudgetFixtures::touchContext)
                .outputMapper(ItBudgetFixtures::touched)
                .step("Load it", LoadEntity.by(AUDITED_DATASET, "id", "audited"))
                .compute("Change it", (metadata, ctx) -> {
                    EntityInstance audited = ctx.get("audited", EntityInstance.class);
                    ctx.changes().update("ItAudited", audited.id(), audited.version(),
                        ctx.get("input", TouchInput.class).changes());
                }));

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
        private final com.jabiz.runtime.storage.StorageAdapterRegistry storages;

        public Meanwhile(DatasetEntityManager entities, DatasetRegistry datasets,
            com.jabiz.runtime.storage.StorageAdapterRegistry storages) {
            this.entities = entities;
            this.datasets = datasets;
            this.storages = storages;
        }

        @Override
        public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
            String meanwhile = ctx.get("input", TouchInput.class).meanwhile();
            if (meanwhile == null) {
                return Mono.empty();
            }
            EntityInstance ticket = ctx.get("ticket", EntityInstance.class);
            if ("update-new-tx".equals(meanwhile)) {
                // A transaction of the platform's own, suspending this one (as a mail attempt is recorded).
                EntityChange change = new EntityChange(EntityAction.UPDATE, new EntityInstance(ticket.id(), "ItTicket",
                    ticket.version(), null, Map.of("title", "changed meanwhile")));
                // Outside this operation, which the other transaction cannot see.
                return storages.getEngine("default").inNewTransaction(entities.commitBatch(
                        datasets.findById(ItFixtures.TICKET_DATASET).orElseThrow(), List.of(change))
                    .contextWrite(context -> context.delete(com.jabiz.runtime.operation.Operation.class))).then();
            }
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

        @Bean
        ProcessDefinition<TouchInput, TouchOutput, ProcessContext> itBudgetTouchAudited() {
            return TOUCH_AUDITED;
        }

        @Bean
        com.jabiz.entity.EntityDefinition itAuditedEntity() {
            return AUDITED;
        }

        @Bean
        com.jabiz.dataset.DatasetDefinition itAuditedDataset(
            @org.springframework.beans.factory.annotation.Value("${jabiz.storage.default-pool-ref:default}")
            String pool) {
            return com.jabiz.dataset.DatasetDefinition.define(AUDITED_DATASET, d -> d.targetEntityType("ItAudited")
                .asDefault().permissions("it.read", "it.write").storage(st -> st.connectionPoolRef(pool)));
        }
    }
}
