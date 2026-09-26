package com.jabiz.runtime.dictionary;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup self-check (docs/design/07-quality.md section 1): every dictionary a {@code Code} field refers to has
 * a source: a provider, fixed values, an SQL dictionary, or at least one entry in {@code sys_dict_item}.
 * It blocks on the startup thread, never on the request path. Disabled together with the metamodel consistency
 * check, since both need the database schema.
 */
@Component
@ConditionalOnProperty(prefix = "jabiz.metamodel.consistency-check", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class DictionaryChecker implements PlatformCheck {

    private final EntityDefinitionRegistry entities;
    private final DictionaryRegistry dictionaries;

    public DictionaryChecker(EntityDefinitionRegistry entities, DictionaryRegistry dictionaries) {
        this.entities = entities;
        this.dictionaries = dictionaries;
    }

    @Override
    public List<CheckProblem> check() {
        List<String> stored = dictionaries.databaseDictionaries().collectList().block(Duration.ofSeconds(30));
        Set<String> inTable = new HashSet<>(stored == null ? List.of() : stored);
        List<CheckProblem> problems = new ArrayList<>();
        for (EntityDefinition entity : entities.all()) {
            for (String urn : entity.dictionaryUrns()) {
                if (dictionaries.declaredSource(urn).isEmpty() && !inTable.contains(urn)) {
                    problems.add(CheckProblem.error("DICTIONARY", "Entity " + entity.name,
                        "dictionary " + urn + " has no provider"));
                }
            }
        }
        return problems;
    }
}
