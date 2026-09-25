package com.jabiz.dataset;

import com.jabiz.context.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetDefinitionTest {

    private static RequestContext request(String actor, String tenant) {
        return new RequestContext(actor, tenant, Locale.ENGLISH, "r-1", Set.of(), Set.of());
    }

    private static DatasetDefinition.Builder base(DatasetDefinition.Builder d) {
        return d.targetEntityType("Order").storage(s -> s.connectionPoolRef("default"));
    }

    @Test
    void defaultsAreSafe() {
        DatasetDefinition dataset = DatasetDefinition.define("urn:ds:Order", DatasetDefinitionTest::base);

        assertThat(dataset.isDefault()).isFalse();
        assertThat(dataset.scope().isEmpty()).isTrue();
        assertThat(dataset.permissions().isDeclared()).isFalse();
        assertThat(dataset.listView()).isEqualTo("default");
        assertThat(dataset.isTarget("Order")).isTrue();
        assertThat(dataset.isTarget("Other")).isFalse();
        assertThat(dataset.policy().maxQueryBatchSize()).isEqualTo(100);
    }

    @Test
    void builderSetsEveryAttribute() {
        DatasetDefinition dataset = DatasetDefinition.define("urn:ds:member:Order", d -> base(d)
            .asDefault()
            .permissions("order.read", "order.write")
            .listView("compact")
            .scope(s -> s.fixed("region", "JP").fromContext("ownerId", RequestContext::actorId))
            .policy(p -> p.readOnly(true).softDelete("deleted").softDeleteTimeField("deletedAt")
                .maxQueryBatchSize(10).maxWriteBatchSize(2).queryTimeout(Duration.ofSeconds(1)))
            .storage(s -> s.driver("r2dbc").connectionPoolRef("main").readReplicaRef("replica")
                .physicalTableOverride("t_archive")));

        assertThat(dataset.isDefault()).isTrue();
        assertThat(dataset.permissions()).isEqualTo(new DatasetPermissions("order.read", "order.write"));
        assertThat(dataset.permissions().isDeclared()).isTrue();
        assertThat(dataset.listView()).isEqualTo("compact");
        assertThat(dataset.scope().isDynamic()).isTrue();
        assertThat(dataset.policy().softDeleteField()).isEqualTo("deleted");
        assertThat(dataset.storage().readReplicaRef()).isEqualTo("replica");
    }

    @Test
    void scopeResolvesFixedAndContextValuesInOrder() {
        DatasetScope scope = DatasetDefinition.define("urn:ds:Order", d -> base(d)
            .scope(s -> s.fixed("region", "JP").fromContext("tenantId", RequestContext::tenantId))).scope();

        assertThat(scope.resolve(request("alice", "t1"))).containsExactly(
            Map.entry("region", "JP"), Map.entry("tenantId", "t1"));
        assertThat(scope.entries()).hasSize(2);
        assertThat(DatasetScope.NONE.resolve(null)).isEmpty();
    }

    /** Phase 3 acceptance: a missing context value rejects the request instead of dropping the filter. */
    @Test
    void missingContextValueIsRejected() {
        DatasetScope scope = DatasetDefinition.define("urn:ds:Order", d -> base(d)
            .scope(s -> s.fromContext("tenantId", "tenant", RequestContext::tenantId))).scope();

        assertThatThrownBy(() -> scope.resolve(request("alice", null)))
            .isInstanceOfSatisfying(ScopeUnavailableException.class, e -> {
                assertThat(e.field()).isEqualTo("tenantId");
                assertThat(e.source()).isEqualTo("tenant");
            });
        assertThatThrownBy(() -> scope.resolve(request("alice", " "))).isInstanceOf(ScopeUnavailableException.class);
        assertThatThrownBy(() -> scope.resolve(null)).isInstanceOf(ScopeUnavailableException.class);
    }

    @Test
    void invalidDeclarationsAreRejected() {
        assertThatThrownBy(() -> DatasetDefinition.define("urn:ds", d -> base(d)
            .scope(s -> s.fixed("region", "JP").fixed("region", "US"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declared twice");
        assertThatThrownBy(() -> new DatasetScope.Fixed("region", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DatasetDefinition.define("urn:ds", d -> base(d).policy(p -> p.softDeleteTimeField("x"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires softDelete");
        assertThatThrownBy(() -> DatasetDefinition.define("urn:ds", d -> base(d).policy(p -> p.softDelete(" "))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatasetDefinition.define("urn:ds", d -> base(d).policy(p -> p.maxQueryBatchSize(0))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatasetDefinition.define("urn:ds", d -> base(d).policy(p -> p.maxWriteBatchSize(0))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatasetDefinition.define(" ", DatasetDefinitionTest::base))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatasetDefinition.define("urn:ds", d -> d.targetEntityType("Order")))
            .isInstanceOf(NullPointerException.class);
        assertThat(new DatasetPermissions(" ", "w").isDeclared()).isFalse();
    }
}
