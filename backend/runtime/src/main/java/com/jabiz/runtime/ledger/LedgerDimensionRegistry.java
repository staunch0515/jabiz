package com.jabiz.runtime.ledger;

import com.jabiz.entity.SemanticKind;
import com.jabiz.ledger.LedgerDimension;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** The declared {@link LedgerDimension} beans, by position; problems are reported by {@link LedgerChecks}. */
@Component
public class LedgerDimensionRegistry {

    private final List<LedgerDimension> dimensions;
    private final Set<String> idValued;

    public LedgerDimensionRegistry(ObjectProvider<LedgerDimension> declared, EntityDefinitionRegistry entities) {
        this.dimensions = declared.orderedStream().sorted(Comparator.comparingInt(LedgerDimension::position))
            .toList();
        this.idValued = dimensions.stream().filter(dimension -> takesIds(dimension, entities))
            .map(LedgerDimension::name).collect(Collectors.toUnmodifiableSet());
    }

    public List<LedgerDimension> all() {
        return dimensions;
    }

    /**
     * Names of the dimensions whose values are ids: their source is an identity or reference field. Their values
     * are checked and stored in canonical form ({@link LedgerDimension#canonicalId}).
     */
    public Set<String> idValued() {
        return idValued;
    }

    private static boolean takesIds(LedgerDimension dimension, EntityDefinitionRegistry entities) {
        return dimension.source() instanceof LedgerDimension.EntitySource source
            && entities.find(source.entity()).map(def -> def.fields.get(source.field()))
                .map(field -> field.kind() instanceof SemanticKind.SemanticIdentity
                    || field.kind() instanceof SemanticKind.Reference)
                .orElse(false);
    }
}
