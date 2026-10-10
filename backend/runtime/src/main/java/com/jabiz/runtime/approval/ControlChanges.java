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
import com.jabiz.runtime.param.ControlledParamRegistry;
import com.jabiz.runtime.param.ParamControlChanges;
import com.jabiz.runtime.param.ParamEntities;
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
 * rules, approver limits, SoD rules and controlled business parameters (decision D40, {@link ParamControlChanges})
 * change only through a {@code SysControlChange} that one person proposes and another publishes.
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
            case ParamEntities.ENTITY -> ParamEntities.DATASET;
            case null, default -> null;
        };
    }

    public static ProcessDefinition<ProposeInput, ChangeOutput, ProcessContext> propose(
        ApprovalSubjectRegistry subjects, ControlledParamRegistry controlled) {
        return ProcessDefinition.define(PROPOSE, 1, ProposeInput.class, ChangeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Proposes a change of an approval rule, approver limit, SoD rule or controlled"
                    + " business parameter.")
                .permissions(ApprovalPermissions.CONTROL_PROPOSE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ChangeOutput.class))
                .step("Load the target", LoadControlTarget.of(ctx -> {
                    ProposeInput input = ctx.get(INPUT, ProposeInput.class);
                    if (ParamEntities.ENTITY.equals(input.targetEntity())) {
                        return new LoadControlTarget.Target(input.targetEntity(), null,
                            ParamControlChanges.key(input.values()), paramTime(input.effectiveTime(), ctx));
                    }
                    return new LoadControlTarget.Target(input.targetEntity(), input.targetId());
                }, TARGET))
                .compute("Record the proposal", (metadata, ctx) -> propose(ctx, subjects, controlled)));
    }

    public static ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> publish(
        ApprovalSubjectRegistry subjects, ControlledParamRegistry controlled) {
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
                    if (ParamEntities.ENTITY.equals(change.get("targetEntity"))) {
                        return new LoadControlTarget.Target(change.get("targetEntity"), null,
                            ParamControlChanges.key(storedValues(change)), paramTime(change.get("effectiveTime"), ctx));
                    }
                    Object id = change.get("targetId");
                    return new LoadControlTarget.Target(change.get("targetEntity"), id == null ? null
                        : UUID.fromString(String.valueOf(id)));
                }, TARGET))
                .compute("Publish", (metadata, ctx) -> publish(ctx, subjects, controlled)));
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

    /**
     * The time a change of a parameter is based on: its effective time, or now when it takes effect when published
     * (a time that is not later than now is refused by {@link ParamControlChanges#prepare}).
     */
    private static Instant paramTime(Instant effective, ProcessContext ctx) {
        return effective != null && effective.isAfter(ctx.opTime()) ? effective : ctx.opTime();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> storedValues(EntityInstance change) {
        Object json = change.get("changeValues");
        return json == null ? Map.of() : (Map<String, Object>) ApprovalJson.read((String) json);
    }

    private static void propose(ProcessContext ctx, ApprovalSubjectRegistry subjects,
        ControlledParamRegistry controlled) {
        ProposeInput input = ctx.get(INPUT, ProposeInput.class);
        if (datasetOf(input.targetEntity()) == null) {
            Set<String> targets = new TreeSet<>(WRITABLE.keySet());
            targets.add(ParamEntities.ENTITY);
            ctx.reject(invalid("targetEntity", "changes are proposed for " + String.join(", ", targets) + ", not "
                + input.targetEntity()));
            return;
        }
        EntityInstance target = ctx.contains(TARGET) ? ctx.get(TARGET, EntityInstance.class) : null;
        if (ParamEntities.ENTITY.equals(input.targetEntity())) {
            if (input.targetId() != null) {
                ctx.reject(invalid("targetId", "a parameter is named by values.paramKey, not by its id"));
                return;
            }
            ParamControlChanges.Prepared prepared = ParamControlChanges.prepare(input.values(), target,
                Boolean.TRUE.equals(input.delete()), input.effectiveTime(), ctx.opTime(), controlled);
            prepared.violations().forEach(ctx::reject);
            if (!ctx.hasViolations()) {
                record(ctx, input, target == null ? null : String.valueOf(target.id()), prepared.values());
            }
            return;
        }
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
        record(ctx, input, input.targetId() == null ? null : input.targetId().toString(),
            Boolean.TRUE.equals(input.delete()) ? null : values);
    }

    /** Records the proposal; {@code values} null stores none (the deletion of a control). */
    private static void record(ProcessContext ctx, ProposeInput input, String targetId, Map<String, Object> values) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("targetEntity", input.targetEntity());
        change.put("targetId", targetId);
        change.put("changeAction", Boolean.TRUE.equals(input.delete()) ? DELETE : UPSERT);
        change.put("changeValues", values == null ? null : ApprovalJson.write(values));
        change.put("effectiveTime", input.effectiveTime());
        change.put("reason", input.reason().strip());
        change.put("status", ApprovalEntities.PROPOSED);
        change.put("proposedBy", ctx.request().actorId());
        Object id = ctx.changes().insert(ApprovalEntities.CONTROL_CHANGE, change);
        ctx.put(OUTPUT, new ChangeOutput(String.valueOf(id), ApprovalEntities.PROPOSED, targetId));
    }

    @SuppressWarnings("unchecked")
    private static void publish(ProcessContext ctx, ApprovalSubjectRegistry subjects,
        ControlledParamRegistry controlled) {
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
        if (ParamEntities.ENTITY.equals(entity)) {
            targetId = publishParam(ctx, change, target, effective, writes, controlled);
            if (targetId == null) {
                return;
            }
        } else if (DELETE.equals(change.get("changeAction"))) {
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

    /**
     * Writes a change of a parameter after checking it again against the version it is based on now; the only write
     * of a controlled parameter the platform lets pass ({@code ControlledParamGuard}).
     *
     * @return the parameter's id; null after rejecting the change
     */
    private static Object publishParam(ProcessContext ctx, EntityInstance change, EntityInstance target,
        Instant effective, ChangeSet.Target writes, ControlledParamRegistry controlled) {
        boolean delete = DELETE.equals(change.get("changeAction"));
        ParamControlChanges.Prepared prepared = ParamControlChanges.prepare(storedValues(change), target, delete,
            effective, ctx.opTime(), controlled);
        prepared.violations().forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return null;
        }
        if (delete) {
            writes.cancelScheduled(ParamEntities.ENTITY, target.id(), target.version());
            return target.id();
        }
        if (target == null) {
            return writes.insert(ParamEntities.ENTITY, ParamControlChanges.writes(prepared.values(), true));
        }
        writes.update(ParamEntities.ENTITY, target.id(), target.version(),
            ParamControlChanges.writes(prepared.values(), false));
        return target.id();
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
        ApprovalSubjectRegistry subjects, ControlledParamRegistry controlled) {
        return propose(subjects, controlled);
    }

    @Bean
    ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> controlChangePublishProcess(
        ApprovalSubjectRegistry subjects, ControlledParamRegistry controlled) {
        return publish(subjects, controlled);
    }

    @Bean
    ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> controlChangeWithdrawProcess() {
        return withdraw();
    }
}
