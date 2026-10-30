package com.jabiz.query;

import com.jabiz.context.EntryScope;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetScope;
import com.jabiz.dataset.EntryScopeVia;
import com.jabiz.entity.EntityDefinition;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entry scopes in datasets (decision D64; docs/design/10-security.md section 13.4): a session of a scope sees the rows
 * of its value, directly, through a mapping entity or by the rows' own tag; sessions without the scope are not
 * limited.
 */
class EntryScopeCompilerTest {

    private static final EntityDefinition SHIPMENT = EntityDefinition.define("Shipment", eb -> {
        eb.physicalTable("t_shipment");
        eb.primaryKey("shipmentId");
        eb.field("shipmentId", f -> f.physicalColumn("shipment_id").asSemanticIdentity("urn:test:shipment"));
        eb.field("storeCode", f -> f.physicalColumn("store_code").asText(20));
        eb.field("customerId", f -> f.physicalColumn("customer_id").asText(36));
    });

    private static final EntityDefinition CUSTOMER_STORE = EntityDefinition.define("CustomerStore", eb -> {
        eb.physicalTable("t_customer_store");
        eb.primaryKey("customerId");
        eb.field("customerId", f -> f.physicalColumn("customer_id").asText(36));
        eb.field("storeCode", f -> f.physicalColumn("store_code").asText(20));
    });

    private static final EntityDefinition TASK = EntityDefinition.define("Task", eb -> {
        eb.physicalTable("t_task");
        eb.primaryKey("taskId");
        eb.field("taskId", f -> f.physicalColumn("task_id").asSemanticIdentity("urn:test:task"));
        eb.field("scopeName", f -> f.physicalColumn("scope_name").asText(64));
        eb.field("scopeValue", f -> f.physicalColumn("scope_value").asText(128));
    });

    private static final DatasetDefinition BY_STORE = DatasetDefinition.define("urn:test:ShipmentByStore", d -> d
        .targetEntityType("Shipment").scope(s -> s.withinEntryScope("store", "storeCode"))
        .storage(s -> s.connectionPoolRef("default")));
    private static final DatasetDefinition BY_CUSTOMER = DatasetDefinition.define("urn:test:ShipmentByCustomer", d -> d
        .targetEntityType("Shipment")
        .scope(s -> s.withinEntryScope("store", "customerId", EntryScopeVia.of("CustomerStore", "customerId",
            "storeCode")))
        .storage(s -> s.connectionPoolRef("default")));
    private static final DatasetDefinition TASKS = DatasetDefinition.define("urn:test:Task", d -> d
        .targetEntityType("Task").scope(s -> s.withinEntryScopeTag("scopeName", "scopeValue"))
        .storage(s -> s.connectionPoolRef("default")));

    private final QueryCompiler compiler = new QueryCompiler(name -> Optional.ofNullable(Map.of("Shipment", SHIPMENT,
        "CustomerStore", CUSTOMER_STORE, "Task", TASK).get(name)));

    private static RequestContext session(EntryScope scope) {
        return new RequestContext("u1", null, Locale.ENGLISH, "r1", Set.of(), Set.of(), null, null, "portal", true,
            scope);
    }

    @Test
    void sessionsWithoutTheScopeAreNotLimited() {
        RequestContext admin = session(null);
        assertThat(BY_STORE.scope().resolve(admin)).isEmpty();
        assertThat(BY_CUSTOMER.scope().resolve(admin)).isEmpty();
        assertThat(TASKS.scope().resolve(admin)).isEmpty();
        assertThat(BY_STORE.scope().resolve(null)).isEmpty();
        // A session of another scope is not limited by this one, except by rows that name their own scope.
        RequestContext other = session(new EntryScope("region", "north"));
        assertThat(BY_STORE.scope().resolve(other)).isEmpty();
        assertThat(TASKS.scope().resolve(other)).containsKey("scopeValue");
        assertThat(BY_STORE.scope().isDynamic()).isTrue();
    }

    @Test
    void aDirectScopeIsTheFieldsValue() {
        Map<String, Object> scope = BY_STORE.scope().resolve(session(new EntryScope("store", "S1")));
        assertThat(scope).containsExactly(Map.entry("storeCode", "S1"));
        QueryCompiler.Binder binder = new QueryCompiler.Binder("s");
        assertThat(compiler.scopeCondition(BY_STORE, SHIPMENT, scope, binder)).isEqualTo("store_code = :s0");
        assertThat(binder.params().get("s0").value()).isEqualTo("S1");
    }

    @Test
    void aScopeThroughAMappingIsASubquery() {
        Map<String, Object> scope = BY_CUSTOMER.scope().resolve(session(new EntryScope("store", "S1")));
        assertThat(scope.get("customerId")).isEqualTo(new DatasetScope.EntryScopeCondition("S1",
            EntryScopeVia.of("CustomerStore", "customerId", "storeCode")));
        QueryCompiler.Binder binder = new QueryCompiler.Binder("m");
        assertThat(compiler.scopeCondition(BY_CUSTOMER, SHIPMENT, compiler.scopeValues(BY_CUSTOMER, SHIPMENT, scope),
            binder)).isEqualTo("CAST(customer_id AS text) IN (SELECT CAST(customer_id AS text) FROM t_customer_store "
                + "WHERE store_code = :m0)");
        assertThat(binder.params().get("m0").value()).isEqualTo("S1");
        // In a template, the entity stands for its rows of the scope.
        assertThat(compiler.templateExpression(BY_CUSTOMER, SHIPMENT, scope, null, new QueryCompiler.Binder("t")))
            .isEqualTo("(SELECT * FROM t_shipment WHERE CAST(customer_id AS text) IN (SELECT CAST(customer_id AS text) "
                + "FROM t_customer_store WHERE store_code = :t0))");
    }

    @Test
    void aTaggedRowNamesItsScopeAndValue() {
        Map<String, Object> scope = TASKS.scope().resolve(session(new EntryScope("store", "S1")));
        QueryCompiler.Binder binder = new QueryCompiler.Binder("g");
        assertThat(compiler.scopeCondition(TASKS, TASK, scope, binder))
            .isEqualTo("(scope_name = :g0 AND scope_value = :g1)");
        assertThat(binder.params().get("g0").value()).isEqualTo("store");
        assertThat(binder.params().get("g1").value()).isEqualTo("S1");
    }

    @Test
    void scopesAreNamedAndValued() {
        assertThatThrownBy(() -> new EntryScope("Store", "S1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntryScope("store", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntryScope("store", "x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatasetScope.Builder().withinEntryScope("Bad Name", "taskId"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatasetScope.Builder().withinEntryScopeTag("scopeName", "scopeValue")
            .fixed("scopeName", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatasetScope.Builder().fixed("scopeValue", "x")
            .withinEntryScopeTag("scopeName", "scopeValue")).isInstanceOf(IllegalArgumentException.class);
        RequestContext session = session(new EntryScope("store", "S1"));
        assertThat(session.entryScopeValue("store")).isEqualTo("S1");
        assertThat(session.entryScopeValue("region")).isNull();
        assertThat(session(null).entryScopeValue("store")).isNull();
    }
}
