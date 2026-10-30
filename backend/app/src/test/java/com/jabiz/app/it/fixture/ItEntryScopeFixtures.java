package com.jabiz.app.it.fixture;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.EntryScopeVia;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RequireScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry scopes through a mapping on a process-only dataset (ROADMAP phase 17y-7, decision D64 item 2): notes of users,
 * a warehouse session seeing those of its warehouse's staff (through {@code WarehouseStaff}); a process writes them,
 * checking the owner with {@link RequireScope}. Only where a test asks for it ({@code it.entry-scope=true}).
 */
public final class ItEntryScopeFixtures extends BaseEntityDefinitions {

    public static final String NOTE = "ItScopedNote";
    public static final String NOTE_DATASET = "urn:jabiz:dataset:it:ItScopedNote";
    public static final String SAVE = "IT_SCOPED_NOTE_SAVE";

    /** @param noteId the note to change, or a new one's id */
    public record SaveInput(String noteId, String owner, String text) {}

    public record SaveOutput(String noteId) {}

    public static final EntityDefinition NOTE_ENTITY = EntityDefinition.define(NOTE, eb -> {
        eb.physicalTable("it_scoped_note");
        eb.primaryKey("noteId");
        eb.field("noteId", f -> f.physicalColumn("f_id").immutable(true).required(true).asText(64));
        eb.field("owner", f -> f.physicalColumn("f_owner").required(true).asText(36));
        eb.field("text", f -> f.physicalColumn("f_text").asText(200));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    private static final String INPUT = "input";
    private static final String FOUND = "found";
    private static final String STAFF = "staff";

    public static final ProcessDefinition<SaveInput, SaveOutput, ProcessContext> SAVE_PROCESS =
        ProcessDefinition.define(SAVE, 1, SaveInput.class, SaveOutput.class, ProcessContext.class, pb -> pb
            .permissions("it.note.write")
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new SaveOutput(ctx.get(INPUT, SaveInput.class).noteId()))
            .step("Load the note", QueryEntities.<ProcessContext>of(NOTE_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("noteId", ctx.get(INPUT, SaveInput.class).noteId())).limit(1).build(),
                FOUND))
            .step("Load the owner's warehouse", QueryEntities.<ProcessContext>of(
                CommerceEntities.WAREHOUSE_STAFF_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("userId", ctx.get(INPUT, SaveInput.class).owner())).limit(1).build(),
                STAFF))
            .step("The owner works in the caller's warehouse", RequireScope.<ProcessContext>of(
                CommerceEntities.WAREHOUSE_SCOPE, ctx -> {
                    List<EntityInstance> staff = rows(ctx, STAFF);
                    return staff.isEmpty() ? null : String.valueOf((Object) staff.getFirst().get("warehouseId"));
                }))
            .compute("Save", (metadata, ctx) -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                List<EntityInstance> found = rows(ctx, FOUND);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("owner", input.owner());
                values.put("text", input.text());
                if (found.isEmpty()) {
                    values.put("noteId", input.noteId());
                    ctx.changes().insert(NOTE, values);
                } else {
                    ctx.changes().update(NOTE, found.getFirst().id(), found.getFirst().version(), values);
                }
            }));

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> rows(ProcessContext ctx, String key) {
        return (List<EntityInstance>) ctx.get(key);
    }

    private ItEntryScopeFixtures() {}

    @Configuration
    @ConditionalOnProperty(name = "it.entry-scope", havingValue = "true")
    static class Beans {

        @Bean
        EntityDefinition itScopedNoteEntity() {
            return NOTE_ENTITY;
        }

        @Bean
        DatasetDefinition itScopedNoteDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(NOTE_DATASET, d -> d
                .targetEntityType(NOTE)
                .asDefault()
                .permissions("it.note.read", "it.note.write")
                .scope(s -> s.withinEntryScope(CommerceEntities.WAREHOUSE_SCOPE, "owner",
                    EntryScopeVia.of(CommerceEntities.WAREHOUSE_STAFF, "userId", "warehouseId")))
                .policy(p -> p.processOnlyWrites())
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        ProcessDefinition<SaveInput, SaveOutput, ProcessContext> itScopedNoteSave() {
            return SAVE_PROCESS;
        }
    }
}
