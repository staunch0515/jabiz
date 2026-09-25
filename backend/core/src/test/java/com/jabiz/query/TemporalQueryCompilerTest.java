package com.jabiz.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reads of temporal entities (decision D3, docs/design/04-temporal-append-only.md section 5.1). */
class TemporalQueryCompilerTest {

    private static final Instant AS_OF = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant KNOWN_AT = Instant.parse("2026-02-01T00:00:00Z");

    private static final EntityDefinition PRICE = EntityDefinition.define("Price", eb -> {
        eb.physicalTable("t_price");
        eb.primaryKey("priceId");
        eb.field("priceId", f -> f.physicalColumn("price_id").asSemanticIdentity("urn:test:price"));
        eb.field("region", f -> f.physicalColumn("region").asText(8));
        eb.field("amount", f -> f.physicalColumn("amount").asMonetary("JPY", 0));
        eb.temporal(t -> t.column("deleted", "gone"));
    });

    private static final DatasetDefinition REGIONAL = DatasetDefinition.define("urn:jabiz:dataset:test:Price", d -> d
        .targetEntityType("Price")
        .scope(s -> s.fixed("region", "JP"))
        .storage(s -> s.connectionPoolRef("default")));

    private final QueryCompiler compiler = new QueryCompiler();

    @Test
    void readsTheVersionsInEffectAndFiltersOutside() {
        PhysicalQueryPlan plan = compiler.compile(REGIONAL, PRICE, EntityQuery.builder()
            .where(new QueryPredicate.Gt("amount", 100)).build(), Map.of("region", "JP"), TimeSlice.asOf(AS_OF));

        assertThat(plan.source()).isEqualTo("(SELECT DISTINCT ON (price_id) * FROM t_price"
            + " WHERE effect_start_time <= :__asOf"
            + " ORDER BY price_id, effect_start_time DESC, version_no DESC) v");
        // Tombstones first, then the scope, then the query's own conditions; none inside the sub-select.
        assertThat(plan.whereClause()).isEqualTo("NOT gone AND region = :p0 AND amount > :p1");
        assertThat(plan.bindParams().get("__asOf").value()).isEqualTo(AS_OF);
        assertThat(plan.bindParams()).doesNotContainKey("__knownAt");
        assertThat(plan.sorts()).extracting(PhysicalQueryPlan.PhysicalSort::physicalColumn).containsExactly("price_id");
    }

    @Test
    void knownAtLimitsTheRecordingTime() {
        PhysicalQueryPlan plan = compiler.compile(REGIONAL, PRICE, EntityQuery.builder().build(),
            Map.of("region", "JP"), new TimeSlice(AS_OF, KNOWN_AT));

        assertThat(plan.source()).contains("WHERE effect_start_time <= :__asOf AND created_time <= :__knownAt ORDER BY");
        assertThat(plan.bindParams().get("__knownAt").value()).isEqualTo(KNOWN_AT);
    }

    @Test
    void temporalReadsNeedATimeSlice() {
        assertThatThrownBy(() -> compiler.compile(REGIONAL, PRICE, EntityQuery.builder().build(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a time slice");
    }

    @Test
    void idsAreComparedAsUuids() {
        UUID id = UUID.randomUUID();
        PhysicalQueryPlan plan = compiler.compile(REGIONAL, PRICE, EntityQuery.builder()
            .where(new QueryPredicate.Eq("priceId", id.toString())).build(), Map.of(), TimeSlice.asOf(AS_OF));
        assertThat(plan.bindParams().get("p0").value()).isEqualTo(id);

        assertThatThrownBy(() -> compiler.compile(REGIONAL, PRICE, EntityQuery.builder()
            .where(new QueryPredicate.Eq("priceId", "P-1")).build(), Map.of(), TimeSlice.asOf(AS_OF)))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations()).singleElement()
                .satisfies(v -> assertThat(v.message()).contains("is not a UUID")));
    }

    @Test
    void templatesSeeTheScopedVersionsInEffect() {
        QueryCompiler.Binder binder = new QueryCompiler.Binder("scope_");
        String expression = compiler.templateExpression(REGIONAL, PRICE, Map.of("region", "JP"),
            TimeSlice.asOf(AS_OF), binder);

        assertThat(expression).isEqualTo("(SELECT * FROM (SELECT DISTINCT ON (price_id) * FROM t_price"
            + " WHERE effect_start_time <= :__asOf"
            + " ORDER BY price_id, effect_start_time DESC, version_no DESC) v WHERE NOT gone AND region = :scope_0)");
        assertThat(binder.params()).containsOnlyKeys("__asOf", "scope_0");

        // A second temporal entity in the same template shares the time.
        compiler.templateExpression(REGIONAL, PRICE, Map.of("region", "JP"), TimeSlice.asOf(AS_OF), binder);
        assertThatThrownBy(() -> compiler.templateExpression(REGIONAL, PRICE, Map.of(),
            TimeSlice.asOf(KNOWN_AT), binder)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ordinaryEntitiesAreUnchanged() {
        EntityDefinition plain = EntityDefinition.define("Plain", eb -> {
            eb.physicalTable("t_plain");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("id"));
        });
        DatasetDefinition dataset = DatasetDefinition.define("urn:jabiz:dataset:test:Plain", d -> d
            .targetEntityType("Plain").storage(s -> s.connectionPoolRef("default")));

        assertThat(compiler.templateExpression(dataset, plain, Map.of(), null, new QueryCompiler.Binder("s")))
            .isEqualTo("t_plain");
        assertThat(compiler.compile(dataset, plain, EntityQuery.builder().build(), Map.of(), TimeSlice.asOf(AS_OF))
            .source()).isEqualTo("t_plain");
    }

    @Test
    void referencesToTemporalEntitiesAreComparedAsUuids() {
        EntityDefinition note = EntityDefinition.define("Note", eb -> {
            eb.physicalTable("t_note");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("id"));
            eb.field("priceRef", f -> f.physicalColumn("price_ref").asReference("Price"));
        });
        DatasetDefinition notes = DatasetDefinition.define("urn:jabiz:dataset:test:Note", d -> d
            .targetEntityType("Note").storage(s -> s.connectionPoolRef("default")));
        UUID id = UUID.randomUUID();
        EntityQuery byPrice = EntityQuery.builder().where(new QueryPredicate.Eq("priceRef", id.toString())).build();

        QueryCompiler knowing = new QueryCompiler(name -> "Price".equals(name) ? Optional.of(PRICE) : Optional.empty());
        assertThat(knowing.compile(notes, note, byPrice, Map.of()).bindParams().get("p0").value()).isEqualTo(id);
        assertThat(compiler.compile(notes, note, byPrice, Map.of()).bindParams().get("p0").value())
            .isEqualTo(id.toString());
    }
}
