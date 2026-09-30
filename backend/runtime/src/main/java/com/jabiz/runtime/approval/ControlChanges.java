package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalCondition;
import com.jabiz.approval.ApprovalLevel;
import com.jabiz.approval.ApprovalSubject;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.security.SodRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Four-eyes changes of the controls themselves (docs/design/18-numbering-approvals-tasks.md section 3.5): approval
 * rules, approver limits and SoD rules change only through a {@code SysControlChange} that one person proposes and
 * another publishes.
 * <ul>
 *   <li>{@code CONTROL_CHANGE_PROPOSE}: a new instance ({@code targetId} empty), a change of some fields, or a
 *       deletion; optionally effective at a later time (scheduled). The values are checked at once: fields of the
 *       entity, declared subjects, conditions and levels that fit the subject's facts, valid SoD groups;</li>
 *   <li>{@code CONTROL_CHANGE_PUBLISH}: by anybody but the proposer ({@code CONTROL_SAME_PERSON}); the values are
 *       checked again against the target's current state and written with the proposed effective time;</li>
 *   <li>{@code CONTROL_CHANGE_WITHDRAW}: the proposer takes a proposal back.</li>
 * </ul>
 */
@Configuration
public class ControlChanges {

    public static final String PROPOSE = "CONTROL_CHANGE_PROPOSE";
    public static final String PUBLISH = "CONTROL_CHANGE_PUBLISH";
    public static final String WITHDRAW = "CONTROL_CHANGE_WITHDRAW";

    /** Actions of a change. */
    public static final String UPSERT = "UPSERT";
    public static final String DELETE = "DELETE";

    /** The fields a change may set, per target entity. */
    static final Map<String, Set<String>> WRITABLE = Map.of(
        ApprovalEntities.RULE, Set.of("ruleCode", "subject", "condition", "levels", "priority", "enabled",
            "description"),
        ApprovalEntities.LIMIT, Set.of("userId", "subject", "maxValue"),
        ApprovalEntities.SOD_RULE, Set.of("ruleCode", "leftPermissions", "rightPermissions", "enabled",
            "description"));

    /**
     * @param targetId      the instance to change or delete; empty to create one
     * @param delete        true to delete the target
     * @param values        the fields to set (for an approval rule, {@code condition} and {@code levels} as JSON
     *                      objects or their text)
     * @param effectiveTime when the change takes effect; empty: when it is published
     */
    public record ProposeInput(@NotBlank String targetEntity, UUID targetId, Boolean delete,
        Map<String, Object> values, Instant effectiveTime, @NotBlank @Size(max = 500) String reason) {}

    public record ChangeInput(@NotNull UUID changeId) {}

    public record ChangeOutput(String changeId, String status, String targetId) {}

    private static final String INPUT = "input";
    private static final String CHANGE_ID = "changeId";
    private static final String CHANGE = "change";
    private static final String TARGET = "target";
    private static final String OUTPUT = "output";

    /** The dataset of a target entity; null for any other entity. */
    static String datasetOf(String entity) {
        return switch (entity) {
            case ApprovalEntities.RULE -> ApprovalEntities.RULE_DATASET;
            case ApprovalEntities.LIMIT -> ApprovalEntities.LIMIT_DATASET;
            case ApprovalEntities.SOD_RULE -> ApprovalEntities.SOD_RULE_DATASET;
            case null, default -> null;
        };
    }

    public static ProcessDefinition<ProposeInput, ChangeOutput, ProcessContext> propose(
        ApprovalSubjectRegistry subjects) {
        return ProcessDefinition.define(PROPOSE, 1, ProposeInput.class, ChangeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Proposes a change of an approval rule, approver limit or SoD rule.")
                .permissions(ApprovalPermissions.CONTROL_PROPOSE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ChangeOutput.class))
                .step("Load the target", LoadControlTarget.of(ctx -> {
                    ProposeInput input = ctx.get(INPUT, ProposeInput.class);
                    return new LoadControlTarget.Target(input.targetEntity(), input.targetId());
                }, TARGET))
                .compute("Record the proposal", (metadata, ctx) -> propose(ctx, subjects)));
    }

    public static ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> publish(
        ApprovalSubjectRegistry subjects) {
        return ProcessDefinition.define(PUBLISH, 1, ChangeInput.class, ChangeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Publishes a change of a control that another person proposed.")
                .permissions(ApprovalPermissions.CONTROL_PUBLISH)
                .requiresMfa(com.jabiz.security.MfaRequirement.ADMINISTRATION)
                .actsOn(ApprovalEntities.CONTROL_CHANGE, CHANGE_ID,
                    a -> a.whenField("status", ApprovalEntities.PROPOSED))
                .contextFactory(ControlChanges::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, ChangeOutput.class))
                .step("Load the change", LoadEntity.by(ApprovalEntities.CONTROL_CHANGE_DATASET, CHANGE_ID, CHANGE))
                .step("Load the target", LoadControlTarget.of(ctx -> {
                    EntityInstance change = ctx.get(CHANGE, EntityInstance.class);
                    Object id = change.get("targetId");
                    return new LoadControlTarget.Target(change.get("targetEntity"), id == null ? null
                        : UUID.fromString(String.valueOf(id)));
                }, TARGET))
                .compute("Publish", (metadata, ctx) -> publish(ctx, subjects)));
    }

    public static ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> withdraw() {
        return ProcessDefinition.define(WITHDRAW, 1, ChangeInput.class, ChangeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Withdraws one's own proposed change of a control.")
                .permissions(ApprovalPermissions.CONTROL_PROPOSE)
                .actsOn(ApprovalEntities.CONTROL_CHANGE, CHANGE_ID,
                    a -> a.whenField("status", ApprovalEntities.PROPOSED))
                .contextFactory(ControlChanges::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, ChangeOutput.class))
                .step("Load the change", LoadEntity.by(ApprovalEntities.CONTROL_CHANGE_DATASET, CHANGE_ID, CHANGE))
                .compute("Withdraw", (metadata, ctx) -> withdraw(ctx)));
    }

    private static ProcessContext start(com.jabiz.process.ProcessStart start, ChangeInput input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(CHANGE_ID, input.changeId());
        return ctx;
    }

    private static void propose(ProcessContext ctx, ApprovalSubjectRegistry subjects) {
        ProposeInput input = ctx.get(INPUT, ProposeInput.class);
        if (datasetOf(input.targetEntity()) == null) {
            ctx.reject(invalid("targetEntity", "changes are proposed for " + String.join(", ",
                new TreeSet<>(WRITABLE.keySet())) + ", not " + input.targetEntity()));
            return;
        }
        EntityInstance target = ctx.contains(TARGET) ? ctx.get(TARGET, EntityInstance.class) : null;
        Map<String, Object> values = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(input.delete())) {
            if (input.targetId() == null) {
                ctx.reject(invalid("targetId", "a deletion names the instance to delete"));
                return;
            }
        } else {
            values = normalize(input.targetEntity(), input.values() == null ? Map.of() : input.values());
            check(input.targetEntity(), values, target, subjects).forEach(problem ->
                ctx.reject(invalid("values", problem)));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("targetEntity", input.targetEntity());
        change.put("targetId", input.targetId() == null ? null : input.targetId().toString());
        change.put("changeAction", Boolean.TRUE.equals(input.delete()) ? DELETE : UPSERT);
        change.put("changeValues", Boolean.TRUE.equals(input.delete()) ? null : ApprovalJson.write(values));
        change.put("effectiveTime", input.effectiveTime());
        change.put("reason", input.reason().strip());
        change.put("status", ApprovalEntities.PROPOSED);
        change.put("proposedBy", ctx.request().actorId());
        Object id = ctx.changes().insert(ApprovalEntities.CONTROL_CHANGE, change);
        ctx.put(OUTPUT, new ChangeOutput(String.valueOf(id), ApprovalEntities.PROPOSED,
            input.targetId() == null ? null : input.targetId().toString()));
    }

    @SuppressWarnings("unchecked")
    private static void publish(ProcessContext ctx, ApprovalSubjectRegistry subjects) {
        EntityInstance change = ctx.get(CHANGE, EntityInstance.class);
        String changeId = String.valueOf(change.id());
        if (!ApprovalEntities.PROPOSED.equals(change.get("status"))) {
            ctx.reject(new Violation(CHANGE_ID, PlatformErrorCodes.CONTROL_CHANGE_NOT_PROPOSED,
                "Change " + changeId + " is " + change.get("status") + ", not proposed", Map.of("change", changeId)));
            return;
        }
        if (ctx.request().actorId().equals(change.get("proposedBy"))) {
            ctx.reject(new Violation(CHANGE_ID, PlatformErrorCodes.CONTROL_SAME_PERSON,
                "The proposer of change " + changeId + " cannot publish it", Map.of("change", changeId)));
            return;
        }
        String entity = change.get("targetEntity");
        EntityInstance target = ctx.contains(TARGET) ? ctx.get(TARGET, EntityInstance.class) : null;
        Instant effective = change.get("effectiveTime");
        ChangeSet.Target writes = ctx.changes().in(datasetOf(entity));
        if (effective != null) {
            writes = writes.effectiveAt(effective);
        }
        Object targetId;
        if (DELETE.equals(change.get("changeAction"))) {
            writes.delete(entity, target.id(), target.version());
            targetId = target.id();
        } else {
            Map<String, Object> values = new LinkedHashMap<>(
                (Map<String, Object>) ApprovalJson.read(change.get("changeValues")));
            check(entity, values, target, subjects).forEach(problem -> ctx.reject(invalid(CHANGE_ID, problem)));
            if (ctx.hasViolations()) {
                return;
            }
            if (target == null) {
                targetId = writes.insert(entity, values);
            } else {
                writes.update(entity, target.id(), target.version(), values);
                targetId = target.id();
            }
        }
        Map<String, Object> published = new LinkedHashMap<>();
        published.put("status", ApprovalEntities.PUBLISHED);
        published.put("publishedBy", ctx.request().actorId());
        ctx.changes().update(ApprovalEntities.CONTROL_CHANGE, change.id(), change.version(), published);
        ctx.put(OUTPUT, new ChangeOutput(changeId, ApprovalEntities.PUBLISHED, String.valueOf(targetId)));
    }

    private static void withdraw(ProcessContext ctx) {
        EntityInstance change = ctx.get(CHANGE, EntityInstance.class);
        String changeId = String.valueOf(change.id());
        if (!ApprovalEntities.PROPOSED.equals(change.get("status"))) {
            ctx.reject(new Violation(CHANGE_ID, PlatformErrorCodes.CONTROL_CHANGE_NOT_PROPOSED,
                "Change " + changeId + " is " + change.get("status") + ", not proposed", Map.of("change", changeId)));
            return;
        }
        if (!ctx.request().actorId().equals(change.get("proposedBy"))) {
            ctx.reject(invalid(CHANGE_ID, "only the proposer withdraws a proposal"));
            return;
        }
        ctx.changes().update(ApprovalEntities.CONTROL_CHANGE, change.id(), change.version(),
            Map.of("status", ApprovalEntities.WITHDRAWN));
        ctx.put(OUTPUT, new ChangeOutput(changeId, ApprovalEntities.WITHDRAWN, change.get("targetId")));
    }

    /** The values as stored: an approval rule's condition and levels as JSON text. */
    private static Map<String, Object> normalize(String entity, Map<String, Object> values) {
        Map<String, Object> normalized = new LinkedHashMap<>(values);
        if (entity.equals(ApprovalEntities.RULE)) {
            for (String json : List.of("condition", "levels")) {
                Object value = normalized.get(json);
                if (value != null && !(value instanceof String)) {
                    normalized.put(json, ApprovalJson.write(value));
                }
            }
        }
        return normalized;
    }

    /**
     * Problems of setting {@code values} on {@code target} (null: a new instance), as far as the entity's own
     * validation does not find them when the change is written: subjects, conditions, levels and SoD groups.
     */
    static List<String> check(String entity, Map<String, Object> values, EntityInstance target,
        ApprovalSubjectRegistry subjects) {
        List<String> problems = new ArrayList<>();
        values.keySet().stream().filter(field -> !WRITABLE.get(entity).contains(field)).sorted()
            .forEach(field -> problems.add("field '" + field + "' cannot be set"));
        if (values.isEmpty()) {
            problems.add("no values");
        }
        Function<String, Object> merged = field -> values.containsKey(field) ? values.get(field)
            : target == null ? null : target.get(field);
        try {
            switch (entity) {
                case ApprovalEntities.RULE -> {
                    Optional<ApprovalSubject> subject = subject(merged.apply("subject"), subjects, problems);
                    if (subject.isPresent()) {
                        parse(() -> ApprovalCondition.parse(ApprovalJson.read((String) merged.apply("condition")),
                            subject.get()), problems);
                        parse(() -> ApprovalLevel.parse(ApprovalJson.read((String) merged.apply("levels")),
                            subject.get()), problems);
                    }
                }
                case ApprovalEntities.LIMIT -> {
                    subject(merged.apply("subject"), subjects, problems);
                    Object max = merged.apply("maxValue");
                    if (max != null && new BigDecimal(String.valueOf(max)).signum() < 0) {
                        problems.add("maxValue must not be negative");
                    }
                }
                case ApprovalEntities.SOD_RULE -> parse(() -> SodRule.of(String.valueOf(merged.apply("ruleCode")),
                    (String) merged.apply("leftPermissions"), (String) merged.apply("rightPermissions")), problems);
                default -> throw new IllegalArgumentException("not a control: " + entity);
            }
        } catch (ClassCastException | NumberFormatException e) {
            problems.add("a value has the wrong type: " + e.getMessage());
        }
        return problems;
    }

    private static Optional<ApprovalSubject> subject(Object name, ApprovalSubjectRegistry subjects,
        List<String> problems) {
        Optional<ApprovalSubject> subject = name == null ? Optional.empty() : subjects.find(String.valueOf(name));
        if (subject.isEmpty()) {
            problems.add("approval subject '" + name + "' is not declared");
        }
        return subject;
    }

    private static void parse(Runnable parse, List<String> problems) {
        try {
            parse.run();
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
    }

    private static Violation invalid(String field, String detail) {
        return new Violation(field, PlatformErrorCodes.CONTROL_CHANGE_INVALID, "Invalid change: " + detail,
            Map.of("detail", detail));
    }

    @Bean
    ProcessDefinition<ProposeInput, ChangeOutput, ProcessContext> controlChangeProposeProcess(
        ApprovalSubjectRegistry subjects) {
        return propose(subjects);
    }

    @Bean
    ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> controlChangePublishProcess(
        ApprovalSubjectRegistry subjects) {
        return publish(subjects);
    }

    @Bean
    ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> controlChangeWithdrawProcess() {
        return withdraw();
    }
}
