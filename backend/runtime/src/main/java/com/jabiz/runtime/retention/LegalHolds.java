package com.jabiz.runtime.retention;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Legal holds (docs/design/21-audit-retention.md section 3.3): the temporal platform entity {@code SysLegalHold} and
 * the only processes that write it. A hold names entries of one entity type by id, or all whose field has a value;
 * while it is in force none of them can be deleted. Releasing needs a reason; holds are never deleted.
 */
@Configuration
public class LegalHolds {

    public static final String ENTITY = "SysLegalHold";
    public static final String DATASET = "urn:jabiz:dataset:platform:SysLegalHold";
    public static final String PLACE = "LEGAL_HOLD_PLACE";
    public static final String RELEASE = "LEGAL_HOLD_RELEASE";
    public static final String ACTIVE = "ACTIVE";
    public static final String RELEASED = "RELEASED";

    /** Most ids one hold names; more belong in a field value. */
    static final int MAX_IDS = 500;

    static final String INPUT = "input";
    static final String FOUND = "found";
    static final String OUTPUT = "output";

    public static final EntityDefinition SYS_LEGAL_HOLD = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("sys_legal_hold_version");
        eb.primaryKey("holdId");
        eb.field("holdId", f -> f.physicalColumn("hold_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:legal-hold"));
        eb.field("name", f -> f.physicalColumn("hold_name").required(true).asText(200));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).asText(2000, true));
        eb.field("entityType", f -> f.physicalColumn("entity_type").immutable(true).required(true).asText(100));
        eb.field("entityIds", f -> f.physicalColumn("entity_ids").immutable(true).asText(20000, true));
        eb.field("matchField", f -> f.physicalColumn("match_field").immutable(true).asText(100));
        eb.field("matchValue", f -> f.physicalColumn("match_value").immutable(true).asText(500));
        eb.field("status", f -> f.physicalColumn("status").required(true).asText(20));
        eb.field("releaseReason", f -> f.physicalColumn("release_reason").asText(2000, true));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("name", "entityType", "matchField", "matchValue", "status", "effectStartTime")
            .filters("entityType", "status", "name")
            .sorts("name", "entityType", "effectStartTime")
            .defaultSort("effectStartTime", false));
    });

    /**
     * @param ids   the entries, by id; or else
     * @param field a field of the entity and its {@code value}
     */
    public record PlaceInput(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 2000) String reason,
        @NotBlank String entityType, List<String> ids, String field, @Size(max = 500) String value) {}

    public record ReleaseInput(@NotBlank String holdId, @NotBlank @Size(max = 2000) String reason) {}

    public record HoldOutput(String holdId, String status) {}

    /** Ids as stored: UUIDs in their canonical lower-case form, anything else trimmed. */
    static String normalizedId(String id) {
        String trimmed = id.strip();
        try {
            return UUID.fromString(trimmed).toString();
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    @Bean
    EntityDefinition sysLegalHoldEntity() {
        return SYS_LEGAL_HOLD;
    }

    @Bean
    DatasetDefinition sysLegalHoldDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(ENTITY)
            .asDefault()
            .permissions(RetentionPermissions.HOLD_READ, RetentionPermissions.HOLD_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    ProcessDefinition<PlaceInput, HoldOutput, ProcessContext> legalHoldPlaceProcess(EntityDefinitionRegistry entities,
        JsonMapper json) {
        return ProcessDefinition.define(PLACE, 1, PlaceInput.class, HoldOutput.class, ProcessContext.class, pb -> pb
            .description("Places a legal hold: the entries it names cannot be deleted until it is released.")
            .permissions(RetentionPermissions.HOLD_WRITE)
            .requiresMfa(com.jabiz.security.MfaRequirement.ADMINISTRATION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, HoldOutput.class))
            .compute("Check and register the hold", (metadata, ctx) -> {
                PlaceInput input = ctx.get(INPUT, PlaceInput.class);
                List<String> ids = input.ids() == null ? List.of()
                    : input.ids().stream().filter(id -> id != null && !id.isBlank()).map(LegalHolds::normalizedId)
                        .distinct().toList();
                List<Violation> problems = new ArrayList<>();
                EntityDefinition def = entities.find(input.entityType()).orElse(null);
                if (def == null) {
                    problems.add(new Violation("entityType", PlatformErrorCodes.INVALID_VALUE,
                        "Unknown entity " + input.entityType()));
                }
                boolean byField = input.field() != null && !input.field().isBlank();
                if (ids.isEmpty() == !byField) {
                    problems.add(new Violation("ids", PlatformErrorCodes.INVALID_VALUE,
                        "A hold names either ids or a field and its value"));
                }
                if (ids.size() > MAX_IDS) {
                    problems.add(new Violation("ids", PlatformErrorCodes.INVALID_VALUE,
                        "A hold names at most " + MAX_IDS + " ids; name a field instead"));
                }
                if (byField && (input.value() == null || input.value().isBlank())) {
                    problems.add(new Violation("value", PlatformErrorCodes.REQUIRED, "The field's value is missing"));
                }
                if (byField && def != null && !def.fields.containsKey(input.field())) {
                    problems.add(new Violation("field", PlatformErrorCodes.UNKNOWN_FIELD,
                        input.entityType() + " has no field " + input.field()));
                }
                if (!problems.isEmpty()) {
                    throw new ValidationException(problems);
                }
                Map<String, Object> hold = new LinkedHashMap<>();
                hold.put("name", input.name());
                hold.put("reason", input.reason());
                hold.put("entityType", input.entityType());
                hold.put("entityIds", ids.isEmpty() ? null : json.writeValueAsString(ids));
                hold.put("matchField", byField ? input.field() : null);
                hold.put("matchValue", byField ? input.value() : null);
                hold.put("status", ACTIVE);
                Object id = ctx.changes().insert(ENTITY, hold);
                ctx.put(OUTPUT, new HoldOutput(String.valueOf(id), ACTIVE));
            }));
    }

    @Bean
    ProcessDefinition<ReleaseInput, HoldOutput, ProcessContext> legalHoldReleaseProcess() {
        return ProcessDefinition.define(RELEASE, 1, ReleaseInput.class, HoldOutput.class, ProcessContext.class,
            pb -> pb
                .description("Releases a legal hold, with the reason; its entries follow their retention again.")
                .permissions(RetentionPermissions.HOLD_WRITE)
                .requiresMfa(com.jabiz.security.MfaRequirement.ADMINISTRATION)
            .requiresMfa(com.jabiz.security.MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, HoldOutput.class))
                .step("Load the hold", QueryEntities.of(DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("holdId", ctx.get(INPUT, ReleaseInput.class).holdId()))
                    .limit(1).build(), FOUND))
                .compute("Release it", (metadata, ctx) -> {
                    ReleaseInput input = ctx.get(INPUT, ReleaseInput.class);
                    List<?> found = ctx.get(FOUND, List.class);
                    if (found.isEmpty()) {
                        throw new EntityNotFoundException("Legal hold " + input.holdId() + " not found");
                    }
                    EntityInstance hold = (EntityInstance) found.getFirst();
                    if (!ACTIVE.equals(hold.attributes().get("status"))) {
                        throw new BusinessRuleViolationException(new Violation("holdId",
                            PlatformErrorCodes.LEGAL_HOLD_NOT_ACTIVE, "Legal hold " + input.holdId()
                                + " is released already"));
                    }
                    ctx.changes().update(ENTITY, hold.id(), hold.version(),
                        Map.of("status", RELEASED, "releaseReason", input.reason()));
                    ctx.put(OUTPUT, new HoldOutput(String.valueOf(hold.id()), RELEASED));
                }));
    }
}
