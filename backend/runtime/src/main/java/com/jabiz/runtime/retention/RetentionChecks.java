package com.jabiz.runtime.retention;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.retention.RetentionPolicy;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Startup check of the retention policies (docs/design/21-audit-retention.md section 3.1): each complete, of a
 * declared entity, counted from one of its time fields, one per entity.
 */
@Component
public class RetentionChecks implements PlatformCheck {

    public static final String CATEGORY = "RETENTION";

    private final RetentionPolicies policies;
    private final EntityDefinitionRegistry entities;

    public RetentionChecks(RetentionPolicies policies, EntityDefinitionRegistry entities) {
        this.policies = policies;
        this.entities = entities;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        policies.zoneProblem().ifPresent(problem -> problems.add(CheckProblem.error(CATEGORY,
            "jabiz.fiscal-year-zone", problem)));
        Map<String, List<RetentionPolicy>> byEntity = new LinkedHashMap<>();
        for (RetentionPolicy policy : policies.all()) {
            byEntity.computeIfAbsent(policy.entity(), entity -> new ArrayList<>()).add(policy);
        }
        // Decision D70: several policies of an entity only when each covers some values of the same field, and no
        // value is covered twice.
        byEntity.forEach((entity, declared) -> {
            if (declared.size() < 2) {
                return;
            }
            String location = "RetentionPolicy " + entity;
            if (declared.stream().anyMatch(policy -> !policy.scoped())) {
                problems.add(CheckProblem.error(CATEGORY, location, "declared more than once: several policies of "
                    + "an entity must each cover some values with only(...)"));
                return;
            }
            if (declared.stream().map(RetentionPolicy::onlyField).distinct().count() > 1) {
                problems.add(CheckProblem.error(CATEGORY, location, "its policies cover values of different fields"));
                return;
            }
            Set<String> covered = new HashSet<>();
            for (RetentionPolicy policy : declared) {
                for (String value : policy.onlyValues()) {
                    if (!covered.add(value)) {
                        problems.add(CheckProblem.error(CATEGORY, location, "value " + value + " of "
                            + policy.onlyField() + " is covered by more than one policy"));
                    }
                }
            }
        });
        for (RetentionPolicy policy : policies.all()) {
            String location = "RetentionPolicy " + policy.entity()
                + (policy.scoped() ? " only " + policy.onlyField() + " " + policy.onlyValues() : "");
            if (!policy.complete()) {
                problems.add(CheckProblem.error(CATEGORY, location, "needs keep(...) and from(...)"));
                continue;
            }
            EntityDefinition def = entities.find(policy.entity()).orElse(null);
            if (def == null) {
                problems.add(CheckProblem.error(CATEGORY, location, "entity " + policy.entity() + " is not declared"));
                continue;
            }
            if (policy.scoped()) {
                FieldDefinition only = def.fields.get(policy.onlyField());
                if (only == null) {
                    problems.add(CheckProblem.error(CATEGORY, location, "field " + policy.onlyField()
                        + " is not declared"));
                } else if (!only.immutable()) {
                    // A changeable field would let an entry leave its retention by an update.
                    problems.add(CheckProblem.error(CATEGORY, location, "field " + policy.onlyField()
                        + " is not immutable"));
                }
            }
            FieldDefinition field = def.fields.get(policy.from());
            if (field == null) {
                problems.add(CheckProblem.error(CATEGORY, location, "field " + policy.from() + " is not declared"));
            } else if (!(field.kind() instanceof SemanticKind.Temporal)
                && !(field.kind() instanceof SemanticKind.Date)) {
                problems.add(CheckProblem.error(CATEGORY, location, "field " + policy.from()
                    + " is not a time or a date"));
            }
        }
        return List.copyOf(problems);
    }
}
