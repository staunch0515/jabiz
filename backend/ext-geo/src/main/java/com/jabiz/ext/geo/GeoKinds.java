package com.jabiz.ext.geo;

import com.jabiz.entity.SemanticKind;

import java.util.Map;

/** Factories of the custom semantic kinds this module provides. */
public final class GeoKinds {

    /** Kind id of a physical quantity (BigDecimal) with dimension and unit. */
    public static final String QUANTITY = "geo.quantity";
    /** Kind id of an H3 cell index (Long) at a fixed resolution. */
    public static final String H3 = "geo.h3";

    private GeoKinds() {}

    public static SemanticKind.Custom quantity(DimensionType dimension, String unitUrn) {
        return new SemanticKind.Custom(QUANTITY, Map.of("dimension", dimension.name(), "unit", unitUrn));
    }

    public static SemanticKind.Custom h3(int resolution) {
        if (resolution < 0 || resolution > 15) {
            throw new IllegalArgumentException("H3 resolution must be between 0 and 15: " + resolution);
        }
        return new SemanticKind.Custom(H3, Map.of("resolution", resolution));
    }
}
