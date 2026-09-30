package com.jabiz.runtime.imports;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.imports.ImportMapping;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved mappings (docs/design/20-imports.md section 4): the temporal platform entity {@code SysImportMapping}, one
 * per import and name, and the internal processes that save and remove them. The import endpoints check the
 * import's mapping permission before running them.
 */
@Configuration
public class ImportMappings {

    public static final String ENTITY = "SysImportMapping";
    public static final String DATASET = "urn:jabiz:dataset:platform:SysImportMapping";
    public static final String SAVE = "IMPORT_MAPPING_SAVE";
    public static final String REMOVE = "IMPORT_MAPPING_REMOVE";

    static final String IMPORT_ID = "importId";
    static final String NAME = "name";
    static final String MAPPING = "mapping";
    /** Most mappings an import may have; they are listed whole on the import page. */
    static final int MAX_MAPPINGS = 200;

    public static final EntityDefinition SYS_IMPORT_MAPPING = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("sys_import_mapping_version");
        eb.primaryKey("mappingId");
        eb.field("mappingId", f -> f.physicalColumn("mapping_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:import-mapping"));
        eb.field(IMPORT_ID, f -> f.physicalColumn("import_id").immutable(true).required(true).asText(100));
        eb.field(NAME, f -> f.physicalColumn("mapping_name").immutable(true).required(true).asText(100));
        eb.field(MAPPING, f -> f.physicalColumn("mapping").required(true).asText(8000, true));
        eb.unique("uk_sys_import_mapping_name", IMPORT_ID, NAME);
        eb.temporal(t -> { });
        eb.listView("default", lv -> lv
            .columns(IMPORT_ID, NAME, "effectStartTime")
            .filters(IMPORT_ID, NAME)
            .sorts(IMPORT_ID, NAME)
            .defaultSort(NAME, true));
    });

    public record SaveInput(@NotBlank @Size(max = 100) String importId, @NotBlank @Size(max = 100) String name,
        @NotNull ImportMapping mapping) {}

    public record RemoveInput(@NotBlank String importId, @NotBlank String name) {}

    public record MappingOutput(String mappingId, String importId, String name) {}

    static final String INPUT = "input";
    static final String FOUND = "found";
    static final String OUTPUT = "output";

    @Bean
    EntityDefinition sysImportMappingEntity() {
        return SYS_IMPORT_MAPPING;
    }

    @Bean
    DatasetDefinition sysImportMappingDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(ENTITY)
            .asDefault()
            .permissions(ImportPermissions.MAPPING_READ, ImportPermissions.MAPPING_WRITE)
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(MAX_MAPPINGS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    ProcessDefinition<SaveInput, MappingOutput, ProcessContext> importMappingSaveProcess(JsonMapper json) {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, MappingOutput.class, ProcessContext.class, pb -> pb
            .description("Saves a mapping of an import under a name, replacing the one of that name.")
            .permissions(ImportPermissions.MAPPING_WRITE)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, MappingOutput.class))
            .step("Load the mapping of that name", QueryEntities.of(DATASET, ctx -> byName(
                ctx.get(INPUT, SaveInput.class).importId(), ctx.get(INPUT, SaveInput.class).name()), FOUND))
            .compute("Register the mapping", (metadata, ctx) -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                String text = json.writeValueAsString(input.mapping());
                List<?> found = ctx.get(FOUND, List.class);
                Object id;
                if (found.isEmpty()) {
                    Map<String, Object> mapping = new LinkedHashMap<>();
                    mapping.put(IMPORT_ID, input.importId());
                    mapping.put(NAME, input.name());
                    mapping.put(MAPPING, text);
                    id = ctx.changes().insert(ENTITY, mapping);
                } else {
                    EntityInstance current = (EntityInstance) found.getFirst();
                    id = current.id();
                    ctx.changes().update(ENTITY, id, current.version(), Map.of(MAPPING, text));
                }
                ctx.put(OUTPUT, new MappingOutput(String.valueOf(id), input.importId(), input.name()));
            }));
    }

    @Bean
    ProcessDefinition<RemoveInput, MappingOutput, ProcessContext> importMappingRemoveProcess() {
        return ProcessDefinition.define(REMOVE, 1, RemoveInput.class, MappingOutput.class, ProcessContext.class,
            pb -> pb
                .description("Removes a saved mapping of an import.")
                .permissions(ImportPermissions.MAPPING_WRITE)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, MappingOutput.class))
                .step("Load the mapping", QueryEntities.of(DATASET, ctx -> byName(
                    ctx.get(INPUT, RemoveInput.class).importId(), ctx.get(INPUT, RemoveInput.class).name()), FOUND))
                .compute("Remove it", (metadata, ctx) -> {
                    RemoveInput input = ctx.get(INPUT, RemoveInput.class);
                    List<?> found = ctx.get(FOUND, List.class);
                    if (found.isEmpty()) {
                        throw new EntityNotFoundException("Import " + input.importId() + " has no mapping "
                            + input.name());
                    }
                    EntityInstance current = (EntityInstance) found.getFirst();
                    ctx.changes().delete(ENTITY, current.id(), current.version());
                    ctx.put(OUTPUT, new MappingOutput(String.valueOf(current.id()), input.importId(), input.name()));
                }));
    }

    static EntityQuery byName(String importId, String name) {
        return EntityQuery.builder()
            .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq(IMPORT_ID, importId),
                new QueryPredicate.Eq(NAME, name))))
            .limit(1)
            .build();
    }

    static EntityQuery ofImport(String importId) {
        return EntityQuery.builder().where(new QueryPredicate.Eq(IMPORT_ID, importId)).limit(MAX_MAPPINGS).build();
    }
}
