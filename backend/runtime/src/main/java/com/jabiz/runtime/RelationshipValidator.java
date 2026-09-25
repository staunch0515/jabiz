package com.jabiz.runtime;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ReferenceDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Startup check of the relationships between entity definitions: every reference must point at
 * a registered entity, both sides must be served by a dataset (otherwise the constraint could
 * not be enforced), and the referencing field must be able to hold the target's primary key.
 * All problems are collected and reported together.
 */
@Component
public class RelationshipValidator implements SmartInitializingSingleton {

    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;

    public RelationshipValidator(EntityDefinitionRegistry entities, DatasetRegistry datasets) {
        this.entities = entities;
        this.datasets = datasets;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = new ArrayList<>();
        for (EntityDefinition source : entities.all()) {
            for (ReferenceDefinition reference : source.references) {
                check(source, reference, problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid entity relationships:\n - " + String.join("\n - ", problems));
        }
    }

    private void check(EntityDefinition source, ReferenceDefinition reference, List<String> problems) {
        String label = source.name + "." + reference.sourceField() + " -> " + reference.targetEntity();
        Optional<EntityDefinition> found = entities.find(reference.targetEntity());
        if (found.isEmpty()) {
            problems.add(label + ": target entity is not registered");
            return;
        }
        EntityDefinition target = found.get();
        if (datasets.findForEntity(source.name).isEmpty()) {
            problems.add(label + ": no dataset serves " + source.name);
        }
        if (datasets.findForEntity(target.name).isEmpty()) {
            problems.add(label + ": no dataset serves " + target.name);
        }
        SemanticKind sourceKind = source.field(reference.sourceField()).kind();
        SemanticKind targetKind = target.field(target.primaryKey).kind();
        if (!compatible(sourceKind, targetKind)) {
            problems.add(label + ": field kind " + sourceKind.getClass().getSimpleName()
                + " cannot hold the target primary key kind " + targetKind.getClass().getSimpleName());
        }
    }

    private static boolean compatible(SemanticKind a, SemanticKind b) {
        return (isPlain(a) && isPlain(b)) || a.getClass() == b.getClass();
    }

    private static boolean isPlain(SemanticKind kind) {
        return kind instanceof SemanticKind.None || kind instanceof SemanticKind.SemanticIdentity;
    }
}
