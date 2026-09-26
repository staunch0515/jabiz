package com.jabiz.ext.geo;

import com.jabiz.entity.FieldBuilder;
import com.jabiz.entity.Rules;

import java.math.BigDecimal;
import java.util.function.Consumer;

/** Field patterns built on the geo kinds, for use with {@code eb.field(name, ...)}. */
public final class GeoFields {

    private GeoFields() {}

    /** Physical quantity of the given dimension constrained to the closed range [min, max]. */
    public static Consumer<FieldBuilder> rangeQuantity(String physicalColumn, String ruleCode,
        DimensionType dimension, String unitUrn, double min, double max) {
        BigDecimal lower = BigDecimal.valueOf(min);
        BigDecimal upper = BigDecimal.valueOf(max);
        return f -> f.physicalColumn(physicalColumn)
            .kind(GeoKinds.quantity(dimension, unitUrn))
            .apply(Rules.range(ruleCode, lower, upper));
    }

    /** H3 cell index at the given resolution. */
    public static Consumer<FieldBuilder> h3Cell(String physicalColumn, int resolution) {
        return f -> f.physicalColumn(physicalColumn).kind(GeoKinds.h3(resolution));
    }
}
