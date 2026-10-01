package com.jabiz.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Temporal reads at volume (decision D29): conditions on immutable fields also restrict the versions read, and a
 * write-once entity's versions are read without picking the latest. The scope stays outside (decision D3).
 */
class TemporalPushdownCompilerTest {

    private static final Instant AS_OF = Instant.parse("2026-03-01T00:00:00Z");

    private static final EntityDefinition LINE = EntityDefinition.define("Line", eb -> {
        eb.physicalTable("t_line");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).asSemanticIdentity("urn:test:line"));
        eb.field("orderId", f -> f.physicalColumn("order_id").immutable(true).asText(36));
        eb.field("region", f -> f.physicalColumn("region").immutable(true).asText(8));
        eb.field("memo", f -> f.physicalColumn("memo").asText(100));
        eb.temporal(t -> t.allowScheduled(false));
    });

    private static final EntityDefinition ENTRY = EntityDefinition.define("Entry", eb -> {
        eb.physicalTable("t_entry");
        eb.primaryKey("entryId");
        eb.field("entryId", f -> f.physicalColumn("entry_id").immutable(true).asSemanticIdentity("urn:test:entry"));
        eb.field("account", f -> f.physicalColumn("account").immutable(true).asText(20));
        eb.field("memo", f -> f.physicalColumn("memo").asText(100));
        eb.display("account");
        eb.temporal(t -> t.writeOnce());
    });

    private static final DatasetDefinition LINES = DatasetDefinition.define("urn:jabiz:dataset:test:Line", d -> d
        .targetEntityType("Line")
        .scope(s -> s.fixed("region", "JP"))
        .storage(s -> s.connectionPoolRef("default")));

    private static final DatasetDefinition ENTRIES = DatasetDefinition.define("urn:jabiz:dataset:test:Entry", d -> d
        .targetEntityType("Entry").storage(s -> s.connectionPoolRef("default")));

    private final QueryCompiler compiler = new QueryCompiler();

    private PhysicalQueryPlan lines(QueryPredicate where) {
        return compiler.compile(LINES, LINE, EntityQuery.builder().where(where).build(), Map.of("region", "JP"),
            TimeSlice.asOf(AS_OF));
    }

    @Test
    void conditionsOnImmutableFieldsAlsoRestrictTheVersionsRead() {
        PhysicalQueryPlan plan = lines(new QueryPredicate.Eq("orderId", "O-1"));
        assertThat(plan.source()).isEqualTo("(SELECT DISTINCT ON (line_id) * FROM t_line"
            + " WHERE effect_start_time <= :__asOf AND order_id = :p0"
            + " ORDER BY line_id, effect_start_time DESC, version_no DESC) v");
        // The full condition still applies outside, after the scope (decision D3).
        assertThat(plan.whereClause()).isEqualTo("NOT is_deleted AND region = :p1 AND order_id = :p2");
        assertThat(plan.bindParams().get("p0").value()).isEqualTo("O-1");
    }

    @Test
    void conditionsOnChangeableFieldsStayOutside() {
        PhysicalQueryPlan plan = lines(new QueryPredicate.Eq("memo", "x"));
        assertThat(plan.source()).doesNotContain("memo");
        assertThat(plan.whereClause()).endsWith("memo = :p1");
    }

    @Test
    void anAndPushesItsImmutableConjunctsAndAnOrOnlyWhenWhollyImmutable() {
        PhysicalQueryPlan and = lines(new QueryPredicate.And(List.of(new QueryPredicate.In("orderId",
            List.of("O-1", "O-2")), new QueryPredicate.Like("memo", "a%"))));
        assertThat(and.source()).contains("AND order_id IN (:p0)").doesNotContain("memo");

        PhysicalQueryPlan mixedOr = lines(new QueryPredicate.Or(List.of(new QueryPredicate.Eq("orderId", "O-1"),
            new QueryPredicate.Eq("memo", "x"))));
        assertThat(mixedOr.source()).doesNotContain("order_id").doesNotContain("memo");

        PhysicalQueryPlan immutableOr = lines(new QueryPredicate.Or(List.of(new QueryPredicate.Eq("orderId", "O-1"),
            new QueryPredicate.Eq("region", "KR"))));
        assertThat(immutableOr.source()).contains("(order_id = :p0 OR region = :p1)");
    }

    @Test
    void theKeyIsImmutable() {
        UUID id = UUID.randomUUID();
        PhysicalQueryPlan plan = lines(new QueryPredicate.Eq("lineId", id.toString()));
        assertThat(plan.source()).contains("AND line_id = :p0 ORDER BY");
        PhysicalQueryPlan after = lines(new QueryPredicate.KeyAfter(id.toString()));
        assertThat(after.source()).contains("AND line_id > :p0 ORDER BY");
    }

    @Test
    void theScopeIsNeverPushedDown() {
        PhysicalQueryPlan plan = lines(null);
        assertThat(plan.source()).doesNotContain("region");
        assertThat(plan.whereClause()).isEqualTo("NOT is_deleted AND region = :p0");
    }

    @Test
    void aWriteOnceEntityIsReadWithoutPickingTheLatestVersion() {
        PhysicalQueryPlan plan = compiler.compile(ENTRIES, ENTRY, EntityQuery.builder()
            .where(new QueryPredicate.Eq("account", "1010")).build(), Map.of(), new TimeSlice(AS_OF, AS_OF));
        assertThat(plan.source()).isEqualTo("(SELECT * FROM t_entry WHERE effect_start_time <= :__asOf"
            + " AND created_time <= :__knownAt AND account = :p0) v");
        assertThat(plan.whereClause()).isEqualTo("NOT is_deleted AND account = :p1");

        QueryCompiler.Binder binder = new QueryCompiler.Binder("s");
        assertThat(compiler.templateExpression(ENTRIES, ENTRY, Map.of(), TimeSlice.asOf(AS_OF), binder))
            .isEqualTo("(SELECT * FROM (SELECT * FROM t_entry WHERE effect_start_time <= :__asOf) v"
                + " WHERE NOT is_deleted)");
    }

    @Test
    void labelsReadOnlyTheVersionsOfTheirKeys() {
        UUID id = UUID.randomUUID();
        RawQueryPlan plan = compiler.compileLabels(ENTRIES, ENTRY, List.of(id), Map.of(), TimeSlice.asOf(AS_OF));
        assertThat(plan.sql()).contains("FROM t_entry WHERE effect_start_time <= :__asOf AND entry_id IN (:p0)) v");
        assertThat(plan.sql()).contains("v.entry_id IN (:p0)");
    }
}
