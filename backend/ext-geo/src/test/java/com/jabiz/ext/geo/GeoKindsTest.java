package com.jabiz.ext.geo;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.CustomKinds;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.entity.SemanticKinds;
import com.jabiz.entity.ValidationContext;
import com.jabiz.entity.Violation;
import com.jabiz.query.QueryOperator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoKindsTest {

    private static final long PORT = 0x882f516a23ffffL;
    private static final ValidationContext CTX =
        new ValidationContext(Clock.systemUTC(), RequestContext.system(Locale.ENGLISH, "t"));

    @Test
    void supportsAreDiscoveredThroughTheServiceLoader() {
        assertThat(CustomKinds.find(GeoKinds.QUANTITY)).containsInstanceOf(QuantityKindSupport.class);
        assertThat(CustomKinds.find(GeoKinds.H3)).containsInstanceOf(H3KindSupport.class);
    }

    @Test
    void h3CellsAreLongsComparedForEqualityOnly() {
        var h3 = GeoKinds.h3(8);

        assertThat(FieldValueCoercer.coerce(h3, "0x882f516a23ffff", true)).isEqualTo(PORT);
        assertThat(FieldValueCoercer.coerce(h3, 42, false)).isEqualTo(42L);
        assertThat(FieldValueCoercer.javaType(h3)).isEqualTo(Long.class);
        assertThat(SemanticKinds.allowedOperators(h3)).containsExactlyInAnyOrder(
            QueryOperator.EQ, QueryOperator.NE, QueryOperator.IN, QueryOperator.IS_NULL, QueryOperator.IS_NOT_NULL);
        assertThat(MetaModelExporter.kindToJson(h3)).containsEntry("kindId", "geo.h3").containsEntry("resolution", 8);
        assertThatThrownBy(() -> GeoKinds.h3(16)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantitiesAreOrderedDecimals() {
        var mass = GeoKinds.quantity(DimensionType.MASS, "urn:unit:si:kilogram");

        assertThat(FieldValueCoercer.coerce(mass, "12.5", true)).isEqualTo(new BigDecimal("12.5"));
        assertThat(FieldValueCoercer.javaType(mass)).isEqualTo(BigDecimal.class);
        assertThat(SemanticKinds.allows(mass, QueryOperator.BETWEEN)).isTrue();
        assertThat(SemanticKinds.allows(mass, QueryOperator.LIKE)).isFalse();
        assertThat(MetaModelExporter.kindToJson(mass)).containsEntry("dimension", "MASS")
            .containsEntry("unit", "urn:unit:si:kilogram");
    }

    @Test
    void fieldPatternsDeclareKindAndRangeRule() {
        EntityDefinition parcel = EntityDefinition.define("Parcel", eb -> {
            eb.physicalTable("t_parcel");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id"));
            eb.field("weight", GeoFields.rangeQuantity("f_weight", "WEIGHT_RANGE", DimensionType.MASS, "kg", 1, 10));
            eb.field("cell", GeoFields.h3Cell("f_cell", 8));
        });

        assertThat(parcel.field("cell").kind()).isEqualTo(GeoKinds.h3(8));
        var rule = parcel.field("weight").rules().getFirst();
        assertThat(rule.isSatisfiedBy(new BigDecimal("5"), CTX)).isTrue();
        assertThat(rule.isSatisfiedBy(new BigDecimal("11"), CTX)).isFalse();
        assertThat(parcel.field("weight").ruleSpecs().getFirst().params()).containsEntry("max", 10.0);
    }

    @Test
    void areaGuardChecksTheResultingLocation() {
        H3AreaGuard guard = H3AreaGuard.within("cell", Set.of(PORT));
        Map<String, Object> incoming = new HashMap<>();

        assertThat(guard.check("IN_TRANSIT", "CLEARED", Map.of("cell", PORT), incoming, CTX)).isEmpty();
        incoming.put("cell", 1L);
        assertThat(guard.check("IN_TRANSIT", "CLEARED", Map.of("cell", PORT), incoming, CTX))
            .extracting(Violation::ruleCode).containsExactly(H3AreaGuard.LOCATION_REJECTED);
        List<Violation> missing = guard.check(null, "CLEARED", Map.of(), Map.of(), CTX);
        assertThat(missing).singleElement().satisfies(v -> {
            assertThat(v.ruleCode()).isEqualTo(H3AreaGuard.LOCATION_MISSING);
            assertThat(v.field()).isEqualTo("cell");
            assertThat(v.params()).containsEntry("status", "CLEARED");
        });
        assertThatThrownBy(() -> H3AreaGuard.within("cell", Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
