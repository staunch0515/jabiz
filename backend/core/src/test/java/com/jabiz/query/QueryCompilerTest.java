package com.jabiz.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.testkinds.TestCellKind;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryCompilerTest {

    private static final EntityDefinition ITEM = EntityDefinition.define("Item", eb -> {
        eb.physicalTable("t_item");
        eb.primaryKey("itemId");
        eb.field("itemId", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:test:item"));
        eb.field("price", f -> f.physicalColumn("f_price").asMonetary("JPY", 0));
        eb.field("status", f -> f.physicalColumn("f_status").asCode("urn:test:dict:status", "OPEN", "DONE"));
        eb.field("region", f -> f.physicalColumn("f_region").asCode("urn:test:dict:region", "JP", "US"));
        eb.field("cell", f -> f.physicalColumn("f_cell").kind(TestCellKind.of(8)));
        eb.field("createdAt", f -> f.physicalColumn("f_created").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("name", f -> f.physicalColumn("f_name").asText(100));
        eb.field("weight", f -> f.physicalColumn("f_weight").asNumeric(9, 3));
        eb.field("active", f -> f.physicalColumn("f_active").asBool());
        eb.field("deleted", f -> f.physicalColumn("is_deleted").asBool());
        eb.field("parentId", f -> f.physicalColumn("f_parent").asReference("Item"));
        eb.field("legacy", f -> f.physicalColumn("f_legacy"));
    });

    private static final EntityDefinition OTHER = EntityDefinition.define("Other", eb -> {
        eb.physicalTable("t_other");
        eb.primaryKey("otherId");
        eb.field("otherId", f -> f.physicalColumn("f_other_id"));
        eb.field("region", f -> f.physicalColumn("f_region"));
    });

    private final QueryCompiler compiler = new QueryCompiler();

    private static DatasetDefinition dataset(Consumer<DatasetDefinition.Builder> extra) {
        return DatasetDefinition.define("urn:jabiz:dataset:test:Item", d -> {
            d.targetEntityType("Item").storage(s -> s.connectionPoolRef("default"));
            extra.accept(d);
        });
    }

    private static final DatasetDefinition PLAIN = dataset(d -> {});

    private PhysicalQueryPlan compile(QueryPredicate predicate) {
        return compiler.compile(PLAIN, ITEM, EntityQuery.builder().where(predicate).build(), Map.of());
    }

    private static void assertRejected(Runnable call, String field, String ruleCode) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .extracting(Violation::field, Violation::ruleCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(field, ruleCode)));
    }

    private static Object param(PhysicalQueryPlan plan, String name) {
        return plan.bindParams().get(name).value();
    }

    @Test
    void comparisonOperatorsBindCoercedValues() {
        record Case(QueryPredicate predicate, String sql) {}
        List<Case> cases = List.of(
            new Case(new QueryPredicate.Eq("price", "100"), "f_price = :p0"),
            new Case(new QueryPredicate.Ne("price", "100"), "f_price <> :p0"),
            new Case(new QueryPredicate.Gt("price", "100"), "f_price > :p0"),
            new Case(new QueryPredicate.Gte("price", "100"), "f_price >= :p0"),
            new Case(new QueryPredicate.Lt("price", "100"), "f_price < :p0"),
            new Case(new QueryPredicate.Lte("price", "100"), "f_price <= :p0"));
        for (Case c : cases) {
            PhysicalQueryPlan plan = compile(c.predicate());
            assertThat(plan.whereClause()).isEqualTo(c.sql());
            assertThat(plan.bindParams().get("p0")).isEqualTo(BoundValue.of(new BigDecimal("100")));
        }
    }

    @Test
    void rangeConditionOnTimestamps() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.And(List.of(
            new QueryPredicate.Gte("createdAt", "2026-01-01T00:00:00Z"),
            new QueryPredicate.Lt("createdAt", "2026-02-01T00:00:00Z"))));

        assertThat(plan.whereClause()).isEqualTo("(f_created >= :p0 AND f_created < :p1)");
        assertThat(param(plan, "p0")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(param(plan, "p1")).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void equalityWithNullBecomesIsNull() {
        assertThat(compile(new QueryPredicate.Eq("price", null)).whereClause()).isEqualTo("f_price IS NULL");
        assertThat(compile(new QueryPredicate.Ne("price", null)).whereClause()).isEqualTo("f_price IS NOT NULL");
        assertThat(compile(new QueryPredicate.Eq("price", null)).bindParams()).isEmpty();
    }

    /** Paging by key: after the last key, whatever operators the key's kind allows filters. */
    @Test
    void keyAfterComparesThePrimaryKey() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.KeyAfter("I-7"));
        assertThat(plan.whereClause()).matches("\\w+ > :p0");
        assertThat(param(plan, "p0")).isEqualTo("I-7");
        assertThatThrownBy(() -> compile(new QueryPredicate.KeyAfter(null)))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void rangeComparisonWithNullIsRejected() {
        assertRejected(() -> compile(new QueryPredicate.Gt("price", null)), "price", "INVALID_VALUE");
    }

    /** Phase 3 acceptance: ">" on a Code field is rejected. */
    @Test
    void rangeComparisonOnCodeIsRejected() {
        assertRejected(() -> compile(new QueryPredicate.Gt("status", "OPEN")), "status", "OPERATOR_NOT_ALLOWED");
        assertRejected(() -> compile(new QueryPredicate.Between("status", "A", "Z")), "status", "OPERATOR_NOT_ALLOWED");
        assertRejected(() -> compile(new QueryPredicate.Like("status", "O%")), "status", "OPERATOR_NOT_ALLOWED");
        assertThat(compile(new QueryPredicate.Ne("status", "OPEN")).whereClause()).isEqualTo("f_status <> :p0");
    }

    @Test
    void operatorViolationNamesTheOperator() {
        assertThatThrownBy(() -> compile(new QueryPredicate.Gt("status", "OPEN")))
            .isInstanceOfSatisfying(ValidationException.class, e ->
                assertThat(e.violations().getFirst().params()).containsEntry("operator", "GT"));
    }

    /** Phase 3 acceptance: LIKE on a Text field works. */
    @Test
    void likeOnTextBindsThePatternAsIs() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.Like("name", "Tok%"));

        assertThat(plan.whereClause()).isEqualTo("f_name LIKE :p0");
        assertThat(param(plan, "p0")).isEqualTo("Tok%");
        assertRejected(() -> compile(new QueryPredicate.Gt("name", "a")), "name", "OPERATOR_NOT_ALLOWED");
        assertRejected(() -> compile(new QueryPredicate.Like("name", null)), "name", "INVALID_VALUE");
    }

    @Test
    void likeIsRejectedOnNonTextKinds() {
        assertRejected(() -> compile(new QueryPredicate.Like("price", "1%")), "price", "OPERATOR_NOT_ALLOWED");
        assertRejected(() -> compile(new QueryPredicate.Like("itemId", "A%")), "itemId", "OPERATOR_NOT_ALLOWED");
    }

    @Test
    void isNullAndIsNotNull() {
        assertThat(compile(new QueryPredicate.IsNull("status")).whereClause()).isEqualTo("f_status IS NULL");
        assertThat(compile(new QueryPredicate.IsNotNull("name")).whereClause()).isEqualTo("f_name IS NOT NULL");
        assertThat(compile(new QueryPredicate.IsNull("active")).bindParams()).isEmpty();
        assertRejected(() -> compile(new QueryPredicate.IsNull("cell")), "cell", "OPERATOR_NOT_ALLOWED");
    }

    @Test
    void betweenBindsBothCoercedBounds() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.Between("weight", "1.5", 10));

        assertThat(plan.whereClause()).isEqualTo("f_weight BETWEEN :p0 AND :p1");
        assertThat(param(plan, "p0")).isEqualTo(new BigDecimal("1.5"));
        assertThat(param(plan, "p1")).isEqualTo(new BigDecimal("10"));
        assertRejected(() -> compile(new QueryPredicate.Between("weight", null, 1)), "weight", "INVALID_VALUE");
        assertThat(compile(new QueryPredicate.Between("createdAt", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"))
            .whereClause()).isEqualTo("f_created BETWEEN :p0 AND :p1");
    }

    @Test
    void boolAllowsEqualityOnly() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.Eq("active", "true"));
        assertThat(param(plan, "p0")).isEqualTo(Boolean.TRUE);
        assertRejected(() -> compile(new QueryPredicate.Gt("active", true)), "active", "OPERATOR_NOT_ALLOWED");
        assertRejected(() -> compile(new QueryPredicate.In("active", List.of(true))), "active", "OPERATOR_NOT_ALLOWED");
    }

    @Test
    void referencesAndIdentitiesAreUnordered() {
        assertThat(compile(new QueryPredicate.Eq("parentId", "A")).whereClause()).isEqualTo("f_parent = :p0");
        assertRejected(() -> compile(new QueryPredicate.Gte("parentId", "A")), "parentId", "OPERATOR_NOT_ALLOWED");
        assertRejected(() -> compile(new QueryPredicate.Lt("itemId", "A")), "itemId", "OPERATOR_NOT_ALLOWED");
    }

    @Test
    void customKindsDeclareTheirOperators() {
        assertThat(param(compile(new QueryPredicate.Eq("cell", "0x10")), "p0")).isEqualTo(16L);
        assertRejected(() -> compile(new QueryPredicate.Lte("cell", 1L)), "cell", "OPERATOR_NOT_ALLOWED");
    }

    @Test
    void undeclaredKindKeepsEveryOperator() {
        assertThat(compile(new QueryPredicate.Gt("legacy", "x")).whereClause()).isEqualTo("f_legacy > :p0");
        assertThat(compile(new QueryPredicate.Like("legacy", "x%")).whereClause()).isEqualTo("f_legacy LIKE :p0");
    }

    @Test
    void queryValuesAreNotCheckedAgainstTheDictionary() {
        // Queries may look for retired codes that still exist in stored data.
        assertThat(param(compile(new QueryPredicate.Eq("status", "ARCHIVED")), "p0")).isEqualTo("ARCHIVED");
    }

    @Test
    void invalidValueIsRejected() {
        assertRejected(() -> compile(new QueryPredicate.Eq("price", "cheap")), "price", "INVALID_VALUE");
    }

    @Test
    void inBindsCoercedList() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.In("price", List.<Object>of("1", 2, 3L)));

        assertThat(plan.whereClause()).isEqualTo("f_price IN (:p0)");
        assertThat(param(plan, "p0")).isEqualTo(List.of(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3")));
    }

    @Test
    void inWithEmptyListMatchesNothing() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.In("price", List.<Object>of()));

        assertThat(plan.whereClause()).isEqualTo("1 = 0");
        assertThat(plan.bindParams()).isEmpty();
    }

    @Test
    void inWithNullElementIsRejected() {
        assertRejected(() -> compile(new QueryPredicate.In("price", Arrays.<Object>asList(1, null))),
            "price", "INVALID_VALUE");
    }

    @Test
    void andSkipsAlwaysTrueChildren() {
        assertThat(compile(new QueryPredicate.And(List.of())).whereClause()).isEmpty();
        assertThat(compile(new QueryPredicate.And(List.of(
            new QueryPredicate.And(List.of()),
            new QueryPredicate.Eq("status", "OPEN")))).whereClause())
            .isEqualTo("(f_status = :p0)");
    }

    @Test
    void emptyOrMatchesNothing() {
        assertThat(compile(new QueryPredicate.Or(List.of())).whereClause()).isEqualTo("1 = 0");
    }

    @Test
    void orCombinesChildren() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.Or(List.of(
            new QueryPredicate.Eq("status", "OPEN"),
            new QueryPredicate.In("region", List.<Object>of("JP", "US")))));

        assertThat(plan.whereClause()).isEqualTo("(f_status = :p0 OR f_region IN (:p1))");
    }

    @Test
    void orWithAlwaysTrueChildIsAlwaysTrue() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.Or(List.of(
            new QueryPredicate.Eq("status", "OPEN"),
            new QueryPredicate.And(List.of()))));

        assertThat(plan.whereClause()).isEmpty();
    }

    @Test
    @Disabled("Known bug 1 (phase-1 PR): parameters of discarded OR branches remain bound")
    void orShortCircuitBindsNoUnusedParameters() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.Or(List.of(
            new QueryPredicate.Eq("status", "OPEN"),
            new QueryPredicate.And(List.of()))));

        assertThat(plan.bindParams()).isEmpty();
    }

    @Test
    void nestedPredicatesUseUniqueParameterNames() {
        PhysicalQueryPlan plan = compile(new QueryPredicate.And(List.of(
            new QueryPredicate.Or(List.of(
                new QueryPredicate.Eq("status", "OPEN"),
                new QueryPredicate.Eq("status", "DONE"))),
            new QueryPredicate.Gt("price", 10))));

        assertThat(plan.whereClause()).isEqualTo("((f_status = :p0 OR f_status = :p1) AND f_price > :p2)");
        assertThat(plan.bindParams()).containsOnlyKeys("p0", "p1", "p2");
    }

    @Test
    void unknownFieldIsRejected() {
        assertRejected(() -> compile(new QueryPredicate.Eq("colour", "red")), "colour", "UNKNOWN_FIELD");
        assertRejected(() -> compiler.compile(PLAIN, ITEM,
            EntityQuery.builder().orderBy("colour", true).build(), Map.of()), "colour", "UNKNOWN_FIELD");
    }

    @Test
    void defaultOrderIsPrimaryKeyAscending() {
        PhysicalQueryPlan plan = compiler.compile(PLAIN, ITEM, EntityQuery.builder().build(), Map.of());

        assertThat(plan.whereClause()).isEmpty();
        assertThat(plan.sorts()).containsExactly(new PhysicalQueryPlan.PhysicalSort("f_id", true));
        assertThat(plan.source()).isEqualTo("t_item");
    }

    @Test
    void explicitSortsKeepTheirOrderAndEndWithThePrimaryKey() {
        PhysicalQueryPlan plan = compiler.compile(PLAIN, ITEM,
            EntityQuery.builder().orderBy("createdAt", false).orderBy("price", true).build(), Map.of());

        assertThat(plan.sorts()).containsExactly(
            new PhysicalQueryPlan.PhysicalSort("f_created", false),
            new PhysicalQueryPlan.PhysicalSort("f_price", true),
            new PhysicalQueryPlan.PhysicalSort("f_id", true));
    }

    @Test
    void primaryKeyIsNotAppendedTwice() {
        PhysicalQueryPlan plan = compiler.compile(PLAIN, ITEM,
            EntityQuery.builder().orderBy("itemId", false).build(), Map.of());

        assertThat(plan.sorts()).containsExactly(new PhysicalQueryPlan.PhysicalSort("f_id", false));
    }

    @Test
    void limitIsCappedByDatasetAndOffsetAndTimeoutArePassedThrough() {
        DatasetDefinition capped = dataset(d -> d.policy(p -> p.maxQueryBatchSize(20).queryTimeout(Duration.ofSeconds(2))));

        PhysicalQueryPlan big = compiler.compile(capped, ITEM, EntityQuery.builder().offset(40).limit(500).build(), Map.of());
        PhysicalQueryPlan small = compiler.compile(capped, ITEM, EntityQuery.builder().limit(5).build(), Map.of());

        assertThat(big.limit()).isEqualTo(20);
        assertThat(big.offset()).isEqualTo(40);
        assertThat(big.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(small.limit()).isEqualTo(5);
    }

    @Test
    void entityQueryRejectsInvalidPaging() {
        assertThatThrownBy(() -> EntityQuery.builder().offset(-1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityQuery.builder().limit(0).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scopeIsAppliedBeforeTheQueryPredicate() {
        DatasetDefinition scoped = dataset(d -> d
            .scope(s -> s.fixed("region", "JP"))
            .policy(p -> p.softDelete("deleted")));

        PhysicalQueryPlan plan = compiler.compile(scoped, ITEM,
            EntityQuery.builder().where(new QueryPredicate.Eq("status", "OPEN")).build(), Map.of("region", "JP"));

        assertThat(plan.whereClause()).isEqualTo("f_region = :p0 AND is_deleted IS NOT TRUE AND f_status = :p1");
        assertThat(param(plan, "p0")).isEqualTo("JP");
    }

    @Test
    void scopeCannotBeWidenedByAnOrPredicate() {
        DatasetDefinition scoped = dataset(d -> d.scope(s -> s.fixed("region", "JP")));

        PhysicalQueryPlan plan = compiler.compile(scoped, ITEM, EntityQuery.builder()
            .where(new QueryPredicate.Or(List.of(new QueryPredicate.Eq("region", "US"), new QueryPredicate.And(List.of()))))
            .build(), Map.of("region", "JP"));

        assertThat(plan.whereClause()).isEqualTo("f_region = :p0");
    }

    @Test
    void datasetRulesApplyToTheTargetEntityOnly() {
        DatasetDefinition scoped = dataset(d -> d
            .scope(s -> s.fixed("region", "JP"))
            .policy(p -> p.softDelete("deleted"))
            .storage(s -> s.connectionPoolRef("default").physicalTableOverride("archive.t_item_2025")));

        assertThat(compiler.resolveTable(scoped, ITEM)).isEqualTo("archive.t_item_2025");
        assertThat(compiler.resolveTable(scoped, OTHER)).isEqualTo("t_other");
        assertThat(compiler.scopeCondition(scoped, OTHER, Map.of("region", "JP"), new QueryCompiler.Binder("s")))
            .isEmpty();

        PhysicalQueryPlan other = compiler.compile(scoped, OTHER, EntityQuery.builder().build(), Map.of("region", "JP"));
        assertThat(other.source()).isEqualTo("t_other");
        assertThat(other.whereClause()).isEmpty();
        assertThat(other.sorts()).containsExactly(new PhysicalQueryPlan.PhysicalSort("f_other_id", true));
    }

    @Test
    void scopeValuesComeFromTheCallerAndAreCoerced() {
        DatasetDefinition member = dataset(d -> d.scope(s -> s.fromContext("parentId", r -> r.actorId())));

        PhysicalQueryPlan alice = compiler.compile(member, ITEM, EntityQuery.builder().build(), Map.of("parentId", "alice"));
        PhysicalQueryPlan unscoped = compiler.compile(dataset(d -> {}), ITEM, EntityQuery.builder().build(),
            Map.of());

        assertThat(alice.whereClause()).isEqualTo("f_parent = :p0");
        assertThat(param(alice, "p0")).isEqualTo("alice");
        assertThat(unscoped.whereClause()).isEmpty();
    }

    @Test
    void blankTableOverrideIsIgnored() {
        DatasetDefinition blank = dataset(d -> d.storage(s -> s.connectionPoolRef("default").physicalTableOverride(" ")));
        assertThat(compiler.resolveTable(blank, ITEM)).isEqualTo("t_item");
    }

    @Test
    void illegalIdentifiersFromMetadataAreRejected() {
        DatasetDefinition injected = dataset(d -> d.storage(s -> s.connectionPoolRef("default")
            .physicalTableOverride("t_item; DROP TABLE t_item")));
        assertThatThrownBy(() -> compiler.compile(injected, ITEM, EntityQuery.builder().build(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Illegal SQL identifier");

        EntityDefinition badColumn = EntityDefinition.define("Bad", eb -> {
            eb.physicalTable("t_bad");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("id"));
            eb.field("name", f -> f.physicalColumn("name or 1=1"));
        });
        assertThatThrownBy(() -> compiler.compile(PLAIN, badColumn,
            EntityQuery.builder().where(new QueryPredicate.Eq("name", "x")).build(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Illegal SQL identifier");
    }

    @Test
    void binderIssuesSequentialNames() {
        QueryCompiler.Binder binder = new QueryCompiler.Binder("scope_");
        assertThat(binder.bind(BoundValue.of(1))).isEqualTo("scope_0");
        assertThat(binder.bind(BoundValue.nullOf(String.class))).isEqualTo("scope_1");
        assertThat(binder.params()).containsOnlyKeys("scope_0", "scope_1");
    }

    @Test
    void boundValueRequiresType() {
        assertThatThrownBy(() -> BoundValue.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BoundValue(1, null)).isInstanceOf(NullPointerException.class);
        assertThat(BoundValue.nullOf(Long.class).value()).isNull();
    }
}
