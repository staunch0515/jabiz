package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalCondition;
import com.jabiz.approval.ApprovalLevel;
import com.jabiz.approval.ApprovalSubject;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.security.SodRule;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Approval rules, approver limits and SoD rules as targets of controlled changes (docs/design/18 section 3.5): a new
 * instance ({@code targetId} empty), a change of some fields, or a deletion. Besides the entity's own validation
 * when the change is written, the values are checked for writable fields, declared subjects, conditions and levels
 * that fit the subject's facts, and valid SoD groups, at the proposal and again at the publication.
 */
@Component
class ApprovalControlTarget implements ControlTarget {

    /** The fields a change may set, per target entity. */
    static final Map<String, Set<String>> WRITABLE = Map.of(
        ApprovalEntities.RULE, Set.of("ruleCode", "subject", "condition", "levels", "priority", "enabled",
            "description"),
        ApprovalEntities.LIMIT, Set.of("userId", "subject", "maxValue"),
        ApprovalEntities.SOD_RULE, Set.of("ruleCode", "leftPermissions", "rightPermissions", "enabled",
            "description"));

    private static final Map<String, String> DATASETS = Map.of(
        ApprovalEntities.RULE, ApprovalEntities.RULE_DATASET,
        ApprovalEntities.LIMIT, ApprovalEntities.LIMIT_DATASET,
        ApprovalEntities.SOD_RULE, ApprovalEntities.SOD_RULE_DATASET);

    private final ApprovalSubjectRegistry subjects;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final DatasetEntityManager entityManager;

    ApprovalControlTarget(ApprovalSubjectRegistry subjects, DatasetRegistry datasets,
        EntityDefinitionRegistry entities, DatasetEntityManager entityManager) {
        this.subjects = subjects;
        this.datasets = datasets;
        this.entities = entities;
        this.entityManager = entityManager;
    }

    @Override
    public Set<String> entities() {
        return DATASETS.keySet();
    }

    @Override
    public String dataset(String entity) {
        return DATASETS.get(entity);
    }

    /** The current state of the instance the change names. */
    @Override
    public Mono<Object> load(Request request, ProcessContext ctx) {
        return Mono.defer(() -> {
            if (request.targetId() == null) {
                return Mono.empty();
            }
            DatasetDefinition dataset = datasets.findById(dataset(request.entity())).orElseThrow();
            UUID id = UUID.fromString(request.targetId());
            return entityManager.findById(dataset, entities.getOrThrow(request.entity()), id)
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(request.entity() + " " + id
                    + " not found")))
                .map(Object.class::cast);
        });
    }

    @Override
    public Proposal propose(Request request, Object loaded, ProcessContext ctx) {
        if (request.delete()) {
            if (request.targetId() == null) {
                ctx.reject(ControlChanges.invalid("targetId", "a deletion names the instance to delete"));
                return null;
            }
            return new Proposal(request.targetId(), null);
        }
        Map<String, Object> values = normalize(request.entity(), request.values());
        check(request.entity(), values, (EntityInstance) loaded).forEach(problem ->
            ctx.reject(ControlChanges.invalid("values", problem)));
        return ctx.hasViolations() ? null : new Proposal(request.targetId(), values);
    }

    @Override
    public Object publish(Request request, Object loaded, ProcessContext ctx, ChangeSet.Target writes) {
        EntityInstance target = (EntityInstance) loaded;
        if (request.delete()) {
            writes.delete(request.entity(), target.id(), target.version());
            return target.id();
        }
        Map<String, Object> values = new LinkedHashMap<>(request.values());
        check(request.entity(), values, target).forEach(problem ->
            ctx.reject(ControlChanges.invalid(ControlChanges.CHANGE_ID, problem)));
        if (ctx.hasViolations()) {
            return null;
        }
        if (target == null) {
            return writes.insert(request.entity(), values);
        }
        writes.update(request.entity(), target.id(), target.version(), values);
        return target.id();
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
    List<String> check(String entity, Map<String, Object> values, EntityInstance target) {
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
                    Optional<ApprovalSubject> subject = subject(merged.apply("subject"), problems);
                    if (subject.isPresent()) {
                        parse(() -> ApprovalCondition.parse(ApprovalJson.read((String) merged.apply("condition")),
                            subject.get()), problems);
                        parse(() -> ApprovalLevel.parse(ApprovalJson.read((String) merged.apply("levels")),
                            subject.get()), problems);
                    }
                }
                case ApprovalEntities.LIMIT -> {
                    subject(merged.apply("subject"), problems);
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

    private Optional<ApprovalSubject> subject(Object name, List<String> problems) {
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
}
