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
import java.util.List;
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
        Set<String> seen = new HashSet<>();
        for (RetentionPolicy policy : policies.all()) {
            String location = "RetentionPolicy " + policy.entity();
            if (!seen.add(policy.entity())) {
                problems.add(CheckProblem.error(CATEGORY, location, "declared more than once"));
            }
            if (!policy.complete()) {
                problems.add(CheckProblem.error(CATEGORY, location, "needs keep(...) and from(...)"));
                continue;
            }
            EntityDefinition def = entities.find(policy.entity()).orElse(null);
            if (def == null) {
                problems.add(CheckProblem.error(CATEGORY, location, "entity " + policy.entity() + " is not declared"));
                continue;
            }
            FieldDefinition field = def.fields.get(policy.from());
            if (field == null) {
                problems.add(CheckProblem.error(CATEGORY, location, "field " + policy.from() + " is not declared"));
            } else if (!(field.kind() instanceof SemanticKind.Temporal)) {
                problems.add(CheckProblem.error(CATEGORY, location, "field " + policy.from() + " is not a time"));
            }
        }
        return List.copyOf(problems);
    }
}
