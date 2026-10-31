package com.jabiz.runtime.publicread;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.PublicCounter;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup check of the public counters (category {@code PUBLIC}; docs/design/15-public-access.md section 10, decision
 * D65): each name once, the keys from a whitelisted text, code or id field of a public dataset, and settings that
 * make sense. It runs whether or not {@code jabiz.public.enabled} is set.
 */
@Component
public class PublicCounterCheck implements PlatformCheck {

    private final ObjectProvider<PublicCounter> declared;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final PublicProperties properties;

    public PublicCounterCheck(ObjectProvider<PublicCounter> declared, DatasetRegistry datasets,
        EntityDefinitionRegistry entities, PublicProperties properties) {
        this.declared = declared;
        this.datasets = datasets;
        this.entities = entities;
        this.properties = properties;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        PublicProperties.Counters settings = properties.counters();
        if (settings.flushInterval().isNegative() || settings.maxVisitors() < 1 || settings.maxPending() < 1) {
            problems.add(CheckProblem.error(PublicReadCheck.CATEGORY, "jabiz.public.counters",
                "flush-interval must not be negative, max-visitors and max-pending must be at least 1"));
        }
        Set<String> names = new HashSet<>();
        declared.orderedStream().forEach(counter -> {
            String location = "PublicCounter " + counter.name();
            if (!names.add(counter.name())) {
                problems.add(CheckProblem.error(PublicReadCheck.CATEGORY, location, "declared more than once"));
            }
            DatasetDefinition dataset = datasets.findById(counter.dataset()).orElse(null);
            var entity = dataset == null ? null : entities.find(dataset.targetEntityType()).orElse(null);
            counter.problems(dataset, entity).forEach(message ->
                problems.add(CheckProblem.error(PublicReadCheck.CATEGORY, location, message)));
        });
        return problems;
    }
}
