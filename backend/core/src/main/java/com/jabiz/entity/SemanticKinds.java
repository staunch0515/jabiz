package com.jabiz.entity;

import com.jabiz.query.QueryOperator;

import java.util.EnumSet;
import java.util.Set;

import static com.jabiz.query.QueryOperator.*;

/**
 * Query constraints of the semantic kinds (docs/design/02-metamodel.md section 1.3): which operators make
 * sense for a value of each kind. Codes and identifiers have no order, text is searched with LIKE, flags are
 * only compared for equality.
 */
public final class SemanticKinds {

    private static final Set<QueryOperator> EQUALITY = Set.copyOf(EnumSet.of(EQ, NE, IN, IS_NULL, IS_NOT_NULL));
    private static final Set<QueryOperator> TEXT = Set.copyOf(EnumSet.of(EQ, NE, IN, LIKE, IS_NULL, IS_NOT_NULL));
    private static final Set<QueryOperator> ORDERED = Set.copyOf(EnumSet.complementOf(EnumSet.of(LIKE)));
    private static final Set<QueryOperator> FLAG = Set.copyOf(EnumSet.of(EQ, NE, IS_NULL, IS_NOT_NULL));
    private static final Set<QueryOperator> ALL = Set.copyOf(EnumSet.allOf(QueryOperator.class));

    private SemanticKinds() {}

    public static Set<QueryOperator> allowedOperators(SemanticKind kind) {
        return switch (kind) {
            case SemanticKind.SemanticIdentity s -> EQUALITY;
            case SemanticKind.Reference r -> EQUALITY;
            case SemanticKind.Code c -> EQUALITY;
            case SemanticKind.Text t -> TEXT;
            case SemanticKind.Monetary m -> ORDERED;
            case SemanticKind.Numeric n -> ORDERED;
            case SemanticKind.Temporal t -> ORDERED;
            case SemanticKind.Date d -> ORDERED;
            case SemanticKind.Version v -> ORDERED;
            case SemanticKind.Bool b -> FLAG;
            case SemanticKind.Custom c -> Set.copyOf(CustomKinds.require(c.kindId()).allowedOperators(c.params()));
            case SemanticKind.None n -> ALL;
        };
    }

    public static boolean allows(SemanticKind kind, QueryOperator operator) {
        return allowedOperators(kind).contains(operator);
    }
}
