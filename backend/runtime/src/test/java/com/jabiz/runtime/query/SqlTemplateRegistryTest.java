package com.jabiz.runtime.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.template.SqlTemplateException;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.dictionary.SqlDictionary;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.StorageEngineBinding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Registration checks of SQL templates that need datasets but no database (decision D10). */
class SqlTemplateRegistryTest {

    private static final EntityDefinition ORDER = EntityDefinition.define("Order", eb -> {
        eb.physicalTable("t_order");
        eb.primaryKey("orderId");
        eb.field("orderId", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:order"));
        eb.field("amount", f -> f.physicalColumn("f_amount").asMonetary("JPY", 0));
    });

    private static final EntityDefinition LINE = EntityDefinition.define("Line", eb -> {
        eb.physicalTable("t_line");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:line"));
    });

    private static DatasetDefinition dataset(String id, String entity, boolean isDefault, String pool) {
        return DatasetDefinition.define(id, d -> {
            d.targetEntityType(entity).permissions("r", "w").storage(s -> s.connectionPoolRef(pool))
                .policy(p -> p.queryTimeout(Duration.ofSeconds(2)));
            if (isDefault) {
                d.asDefault();
            }
        });
    }

    private static AdvancedQueryDefinition query(String id, Consumer<AdvancedQueryDefinition.Builder> extra) {
        return AdvancedQueryDefinition.define(id, q -> {
            q.fromEntities("Order").returnsFrom("amount", "Order", "amount")
                .sqlTemplate("SELECT o.{{Order.amount}} AS amount FROM {{Order}} o");
            extra.accept(q);
        });
    }

    private static SqlTemplateRegistry registry(String profile, Object... beans) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("order", ORDER);
        factory.registerSingleton("line", LINE);
        factory.registerSingleton("orders", dataset("urn:ds:Order", "Order", true, "default"));
        factory.registerSingleton("lines", dataset("urn:ds:Line", "Line", true, "other"));
        StorageEngine engine = (StorageEngine) Proxy.newProxyInstance(StorageEngine.class.getClassLoader(),
            new Class<?>[] {StorageEngine.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException();
            });
        factory.registerSingleton("default", new StorageEngineBinding("default", engine));
        factory.registerSingleton("other", new StorageEngineBinding("other", engine));
        for (int i = 0; i < beans.length; i++) {
            factory.registerSingleton("bean" + i, beans[i]);
        }
        MockEnvironment environment = new MockEnvironment()
            .withProperty(SqlTemplateLoader.LOCATIONS_PROPERTY, "classpath*:no-templates-here/**/*.sql");
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        EntityDefinitionRegistry entities = new EntityDefinitionRegistry(factory.getBeanProvider(EntityDefinition.class));
        DatasetRegistry datasets = new DatasetRegistry(factory.getBeanProvider(DatasetDefinition.class), entities,
            new StorageAdapterRegistry(factory.getBeanProvider(StorageEngineBinding.class)), environment);
        return new SqlTemplateRegistry(new SqlTemplateLoader(environment),
            factory.getBeanProvider(AdvancedQueryDefinition.class), factory.getBeanProvider(SqlDictionary.class),
            entities, datasets, environment);
    }

    private static List<String> problems(SqlTemplateRegistry registry) {
        return registry.check().stream().map(CheckProblem::format).toList();
    }

    @Test
    void aValidTemplateIsRegisteredWithItsInheritedKinds() {
        SqlTemplateRegistry registry = registry(null, query("q.ok", q -> q.permissions("p")));

        assertThat(registry.check()).isEmpty();
        assertThat(registry.find("q.ok").orElseThrow().result("amount").orElseThrow().kind())
            .isEqualTo(new SemanticKind.Monetary("JPY", 0));
        assertThat(registry.all()).hasSize(1);
        assertThat(registry.datasetsOf(registry.find("q.ok").orElseThrow())).containsOnlyKeys("Order");
    }

    @Test
    void datasetsMustExistServeTheEntityAndShareOneStorage() {
        assertThat(problems(registry(null,
            query("q.missing", q -> q.permissions("p").dataset("Order", "urn:ds:nope")),
            query("q.wrong", q -> q.permissions("p").dataset("Order", "urn:ds:Line")),
            query("q.split", q -> q.permissions("p").fromEntities("Line")
                .sqlTemplate("SELECT o.{{Order.amount}} AS amount FROM {{Order}} o, {{Line}} l")),
            query("q.slow", q -> q.permissions("p").timeout(Duration.ofSeconds(2))))))
            .containsExactly(
                "SQL_TEMPLATE | query q.missing | dataset urn:ds:nope of entity Order does not exist",
                "SQL_TEMPLATE | query q.wrong | dataset urn:ds:Line does not serve entity Order but Line",
                "SQL_TEMPLATE | query q.split | datasets urn:ds:Order and urn:ds:Line use different storage; one query "
                    + "runs on one connection",
                "SQL_TEMPLATE | query q.slow | timeoutMs (2000) must be shorter than the datasets' query timeout "
                    + "(2000 ms)");
    }

    @Test
    void permissionsAreRequiredOutsideTheDevProfile() {
        assertThat(problems(registry(null, query("q.open", q -> {}))))
            .containsExactly("SQL_TEMPLATE | query q.open | query q.open declares no permissions");
        assertThat(problems(registry("dev", query("q.open", q -> {}))))
            .containsExactly("WARNING SQL_TEMPLATE | query q.open | query q.open declares no permissions (allowed in "
                + "the dev profile only)");
    }

    @Test
    void idsAreUnique() {
        assertThat(problems(registry(null, query("q", q -> q.permissions("p")), query("q", q -> q.permissions("p")))))
            .containsExactly("SQL_TEMPLATE | query q | duplicate query id q");
    }

    @Test
    void sqlDictionariesAreCheckedToo() {
        SqlDictionary dictionary = new SqlDictionary("urn:dict", "urn:ds:nope", AdvancedQueryDefinition.define("d.q",
            q -> q.fromEntities("Order").returns("code", new SemanticKind.Text(null, false))
                .returns("label", new SemanticKind.Text(null, false))
                .sqlTemplate("SELECT {{Order.nope}} AS code, 'x' AS label FROM {{Order}} o")), null);
        SqlDictionary fine = new SqlDictionary("urn:dict2", "urn:ds:Order", AdvancedQueryDefinition.define("d.bad",
            q -> q.fromEntities("Order").returns("code", new SemanticKind.Text(null, false))
                .returns("label", new SemanticKind.Text(null, false))
                .sqlTemplate("SELECT {{Order.nope}} AS code, 'x' AS label FROM {{Order}} o")), null);

        SqlTemplateRegistry registry = registry(null, dictionary, fine);

        assertThat(problems(registry)).containsExactly(
            "SQL_TEMPLATE | query d.q | SQL dictionary urn:dict refers to unknown dataset urn:ds:nope",
            "SQL_TEMPLATE | query d.bad:1 | unknown field Order.nope");
        assertThat(registry.find("d.bad")).isEmpty();
        assertThat(registry.allForChecks()).extracting(AdvancedQueryDefinition::queryId).containsExactly("d.bad");
    }

    @Test
    void queriesBuiltInCodeArePreparedOnDemand() {
        SqlTemplateRegistry registry = registry(null, query("q.ok", q -> q.permissions("p")));
        AdvancedQueryDefinition adHoc = query("q.adhoc", q -> {});

        assertThat(registry.prepare(adHoc).result("amount").orElseThrow().kind())
            .isEqualTo(new SemanticKind.Monetary("JPY", 0));
        assertThat(registry.prepare(query("q.ok", q -> q.permissions("p"))))
            .isSameAs(registry.find("q.ok").orElseThrow());
        assertThatThrownBy(() -> registry.prepare(AdvancedQueryDefinition.define("q.bad", q -> q.fromEntities("Order")
            .returnsFrom("x", "Order", "nope").sqlTemplate("SELECT 1 AS x"))))
            .isInstanceOf(SqlTemplateException.class).hasMessageContaining("unknown field Order.nope");
        assertThatThrownBy(() -> registry.datasetsOf(AdvancedQueryDefinition.define("q.none", q -> q
            .fromEntities("Ghost").returns("x", new SemanticKind.Bool()).sqlTemplate("SELECT 1 AS x"))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no dataset for [Ghost]");
        assertThatThrownBy(() -> registry.datasetsOf(query("q.wrong", q -> q.dataset("Order", "urn:ds:Line"))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no dataset for [Order]");
    }
}
