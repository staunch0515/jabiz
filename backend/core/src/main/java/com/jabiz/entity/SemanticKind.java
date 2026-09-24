package com.jabiz.entity;

import java.util.List;

public sealed interface SemanticKind {
    record None() implements SemanticKind {}
    record SemanticIdentity(String urn) implements SemanticKind {}
    record Monetary(String currency, int scale) implements SemanticKind {}
    record PhysicalQuantity(DimensionType dimension, String unitUrn) implements SemanticKind {}
    record Temporal(TemporalRole role) implements SemanticKind {}
    record SpatialH3(int resolution) implements SemanticKind {}
    record Code(String dictUrn, List<String> allowedValues) implements SemanticKind {}
    record Version() implements SemanticKind {}
}
