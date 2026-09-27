package com.jabiz.runtime.dataset;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.runtime.check.PlatformCheckRunner;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.StorageEngineBinding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Proxy;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startup checks of datasets (docs/design/03-dataset.md section 4); all problems are reported together. */
class DatasetRegistryTest {

    private static final EntityDefinition ORDER = EntityDefinition.define("Order", eb -> {
        eb.physicalTable("t_order");
        eb.primaryKey("orderId");
        eb.field("orderId", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:order"));
        eb.field("ownerId", f -> f.physicalColumn("f_owner").asText(64));
        eb.field("note", f -> f.physicalColumn("f_note").asText(64));
        eb.field("express", f -> f.physicalColumn("f_express").asBool());
        eb.field("amount", f -> f.physicalColumn("f_amount").asMonetary("JPY", 0));
        eb.field("deletedAt", f -> f.physicalColumn("f_deleted_at").asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("placedAt", f -> f.physicalColumn("f_placed_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.listView("default", lv -> lv.columns("orderId"));
    });

    private static final EntityDefinition OTHER = EntityDefinition.define("Other", eb -> {
        eb.physicalTable("t_other");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("f_id"));
    });

    private static DatasetDefinition dataset(String id, Consumer<DatasetDefinition.Builder> extra) {
        return DatasetDefinition.define(id, d -> {
            d.targetEntityType("Order").permissions("order.read", "order.write")
                .storage(s -> s.connectionPoolRef("default"));
            extra.accept(d);
        });
    }

    private static DatasetRegistry registry(String profile, EntityDefinition[] entities, DatasetDefinition... datasets) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (EntityDefinition entity : entities) {
            beans.registerSingleton("entity" + entity.name, entity);
        }
        for (int i = 0; i < datasets.length; i++) {
            beans.registerSingleton("dataset" + i, datasets[i]);
        }
        StorageEngine engine = (StorageEngine) Proxy.newProxyInstance(StorageEngine.class.getClassLoader(),
            new Class<?>[] {StorageEngine.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException();
            });
        beans.registerSingleton("binding", new StorageEngineBinding("default", engine));
        MockEnvironment environment = new MockEnvironment();
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        DatasetRegistry registry = new DatasetRegistry(beans.getBeanProvider(DatasetDefinition.class),
            new EntityDefinitionRegistry(beans.getBeanProvider(EntityDefinition.class)),
            new StorageAdapterRegistry(beans.getBeanProvider(StorageEngineBinding.class)), environment);
        // Problems are reported by the startup check run, as the application would.
        PlatformCheckRunner.verify(registry);
        return registry;
    }

    private static DatasetRegistry registry(DatasetDefinition... datasets) {
        return registry(null, new EntityDefinition[] {ORDER}, datasets);
    }

    @Test
    void severalDatasetsPerEntityWithOneDefault() {
        DatasetDefinition admin = dataset("urn:ds:admin:Order", DatasetDefinition.Builder::asDefault);
        DatasetDefinition member = dataset("urn:ds:member:Order",
            d -> d.scope(s -> s.fromContext("ownerId", RequestContext::actorId)));

        DatasetRegistry registry = registry(admin, member);

        assertThat(registry.findForEntity("Order")).contains(admin);
        assertThat(registry.findById("urn:ds:member:Order")).contains(member);
        assertThat(registry.all()).containsExactly(admin, member);
        assertThat(registry.findForEntity("Nope")).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        assertThatThrownBy(() -> registry(null, new EntityDefinition[] {ORDER, OTHER},
            dataset("urn:ds:a", d -> d.asDefault().scope(s -> s.fixed("missing", "x").fixed("express", true))),
            dataset("urn:ds:b", d -> d.asDefault().policy(p -> p.softDelete("note").softDeleteTimeField("placedAt"))),
            dataset("urn:ds:c", d -> d.listView("compact").permissions(null, null)),
            dataset("urn:ds:d", d -> d.targetEntityType("Ghost")),
            dataset("urn:ds:e", d -> d.storage(s -> s.connectionPoolRef("elsewhere").readReplicaRef("replica")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Dataset urn:ds:a | scope field missing does not exist on Order")
            .hasMessageContaining("Dataset urn:ds:b | entity type Order already has default dataset urn:ds:a")
            .hasMessageContaining("Dataset urn:ds:b | soft-delete field note must be Bool")
            .hasMessageContaining("Dataset urn:ds:b | soft-delete time field placedAt must be Temporal(SYSTEM_RECORDED)")
            .hasMessageContaining("Dataset urn:ds:c | list view compact does not exist on Order")
            .hasMessageContaining("Dataset urn:ds:c | read and write permissions are not declared")
            .hasMessageContaining("Dataset urn:ds:d | targets unregistered entity type Ghost")
            .hasMessageContaining("Dataset urn:ds:e | refers to unregistered storage engine elsewhere")
            .hasMessageContaining("Dataset urn:ds:e | refers to unregistered storage engine replica")
            .hasMessageContaining("Entity Other has no default dataset")
            // A Bool can be compared for equality, so it may scope a dataset.
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("scope field express"));
    }

    @Test
    void scopeFieldsMustSupportEquality() {
        EntityDefinition noted = EntityDefinition.define("Order", eb -> {
            eb.physicalTable("t_order");
            eb.primaryKey("orderId");
            eb.field("orderId", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:order"));
            eb.field("custom", f -> f.physicalColumn("f_custom").asCustom("unregistered.kind", java.util.Map.of()));
        });
        // Every kind the core knows supports equality; unregistered custom kinds are left to SemanticKindChecker.
        assertThatCode(() -> registry(null, new EntityDefinition[] {noted},
            dataset("urn:ds", d -> d.asDefault().scope(s -> s.fixed("custom", 1))))).doesNotThrowAnyException();
    }

    @Test
    void duplicateResourceIdsAreRejected() {
        assertThatThrownBy(() -> registry(
            dataset("urn:ds", DatasetDefinition.Builder::asDefault), dataset("urn:ds", d -> {})))
            .hasMessageContaining("Dataset urn:ds | duplicate resourceId");
    }

    @Test
    void undeclaredPermissionsAreOnlyAWarningInDevelopment() {
        DatasetDefinition open = dataset("urn:ds", d -> d.asDefault().permissions(null, null));

        assertThatThrownBy(() -> registry(open)).hasMessageContaining("permissions are not declared");
        assertThatCode(() -> registry("dev", new EntityDefinition[] {ORDER}, open)).doesNotThrowAnyException();
    }

    /** Temporal targets: no soft delete (deletion writes a tombstone) and no override table (reverts need one table). */
    @Test
    void temporalTargetsHaveNoSoftDeleteAndNoOverrideTable() {
        EntityDefinition price = EntityDefinition.define("Price", eb -> {
            eb.physicalTable("t_price");
            eb.primaryKey("priceId");
            eb.field("priceId", f -> f.physicalColumn("price_id").asSemanticIdentity("urn:price"));
            eb.field("hidden", f -> f.physicalColumn("hidden").asBool());
            eb.temporal();
        });
        DatasetDefinition soft = DatasetDefinition.define("urn:ds:soft:Price", d -> d.targetEntityType("Price")
            .asDefault().permissions("p.read", "p.write").storage(s -> s.connectionPoolRef("default"))
            .policy(p -> p.softDelete("hidden")));
        DatasetDefinition archive = DatasetDefinition.define("urn:ds:archive:Price", d -> d.targetEntityType("Price")
            .permissions("p.read", "p.write")
            .storage(s -> s.connectionPoolRef("default").physicalTableOverride("t_price_archive")));

        assertThatThrownBy(() -> registry(null, new EntityDefinition[] {price}, soft, archive))
            .hasMessageContaining("urn:ds:soft:Price | soft delete is not available for temporal entity Price")
            .hasMessageContaining("urn:ds:archive:Price | temporal entity Price cannot be stored in an override table");
    }

    /** Process-only fields cannot be sent through a dataset, so a required one must be filled on insert. */
    private static final EntityDefinition STORY = EntityDefinition.define("Story", eb -> {
        eb.physicalTable("t_story");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:story"));
        eb.field("region", f -> f.physicalColumn("f_region").asText(8).required(true).processOnly());
        eb.field("status", f -> f.physicalColumn("f_status").asCode("urn:status", "DRAFT", "DONE").required(true)
            .processOnly());
        eb.field("reviewer", f -> f.physicalColumn("f_reviewer").asText(8).required(true).processOnly());
        eb.field("comment", f -> f.physicalColumn("f_comment").asText(8).processOnly());
        eb.stateTransitions("status", st -> st.from("DRAFT").to("DONE"));
    });

    private static DatasetDefinition story(String id, Consumer<DatasetDefinition.Builder> extra) {
        return DatasetDefinition.define(id, d -> {
            d.targetEntityType("Story").permissions("story.read", "story.write")
                .storage(s -> s.connectionPoolRef("default"));
            extra.accept(d);
        });
    }

    @Test
    void requiredProcessOnlyFieldsMustBeFilledOnInsert() {
        assertThatThrownBy(() -> registry(null, new EntityDefinition[] {STORY},
            story("urn:ds:story", d -> d.asDefault().scope(s -> s.fixed("region", "JP"))),
            story("urn:ds:story:all", d -> { })))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Dataset urn:ds:story | required process-only field reviewer of Story")
            .hasMessageContaining("Dataset urn:ds:story:all | required process-only field region of Story")
            .hasMessageContaining("Dataset urn:ds:story:all | required process-only field reviewer of Story")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("field status").doesNotContain("field comment")
                .doesNotContain("urn:ds:story | required process-only field region"));
    }

    @Test
    void aProcessOnlyLifecycleNeedsOneInitialState() {
        EntityDefinition flow = EntityDefinition.define("Flow", eb -> {
            eb.physicalTable("t_flow");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:flow"));
            eb.field("status", f -> f.physicalColumn("f_status").asCode("urn:flow", "A", "B", "C").processOnly());
            eb.stateTransitions("status", st -> st.from("A").to("C").from("B").to("C"));
        });
        assertThatThrownBy(() -> registry(null, new EntityDefinition[] {flow}, DatasetDefinition.define("urn:ds:flow",
            d -> d.targetEntityType("Flow").asDefault().permissions("r", "w").storage(s -> s.connectionPoolRef("default")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Dataset urn:ds:flow | process-only lifecycle field status of Flow has several initial"
                + " states [A, B]");
    }

    @Test
    void datasetsThatCannotInsertDoNotNeedThem() {
        assertThatCode(() -> registry(null, new EntityDefinition[] {STORY},
            story("urn:ds:story:ro", d -> d.asDefault().policy(p -> p.readOnly(true))),
            story("urn:ds:story:process", d -> d.policy(p -> p.processOnlyWrites()))))
            .doesNotThrowAnyException();
    }
}
