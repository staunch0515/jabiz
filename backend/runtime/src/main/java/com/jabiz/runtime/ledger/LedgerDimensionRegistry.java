package com.jabiz.runtime.ledger;

import com.jabiz.ledger.LedgerDimension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** The declared {@link LedgerDimension} beans, by position; problems are reported by {@link LedgerChecks}. */
@Component
public class LedgerDimensionRegistry {

    private final List<LedgerDimension> dimensions;

    public LedgerDimensionRegistry(ObjectProvider<LedgerDimension> declared) {
        this.dimensions = declared.orderedStream().sorted(Comparator.comparingInt(LedgerDimension::position))
            .toList();
    }

    public List<LedgerDimension> all() {
        return dimensions;
    }
}
