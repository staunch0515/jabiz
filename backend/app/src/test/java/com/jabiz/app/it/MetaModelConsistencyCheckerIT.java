package com.jabiz.app.it;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.entity.MetaModelConsistencyChecker.MetaModelInconsistencyException;
import com.jabiz.runtime.entity.MetaModelConsistencyChecker;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startup check that the metamodel matches the database schema. */
class MetaModelConsistencyCheckerIT extends PostgresIntegrationTest {

    @Autowired
    DatabaseClient databaseClient;

    @Autowired
    EntityDefinitionRegistry applicationRegistry;

    private static EntityDefinitionRegistry registryOf(EntityDefinition... definitions) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (EntityDefinition def : definitions) {
            beans.registerSingleton("entity" + def.name, def);
        }
        return new EntityDefinitionRegistry(beans.getBeanProvider(EntityDefinition.class));
    }

    private static EntityDefinition drift() {
        return EntityDefinition.define("Drift", eb -> {
            eb.physicalTable("it_drift");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id"));
            eb.field("name", f -> f.physicalColumn("F_NAME"));
            eb.field("amount", f -> f.physicalColumn("f_amount"));
            eb.field("note", f -> f.physicalColumn("f_note"));
        });
    }

    @Test
    void applicationMetamodelMatchesTheMigratedSchema() {
        // The context started, so the checker already ran once; run it again explicitly.
        assertThatCode(() -> new MetaModelConsistencyChecker(databaseClient, applicationRegistry)
            .afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    @Test
    void reportsEveryMissingColumnAndMissingTableAtOnce() {
        execute("CREATE TABLE it_drift (f_id varchar(10), f_name text)");
        EntityDefinition ghost = EntityDefinition.define("Ghost", eb -> {
            eb.physicalTable("it_ghost");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id"));
        });

        MetaModelConsistencyChecker checker =
            new MetaModelConsistencyChecker(databaseClient, registryOf(drift(), ghost));

        assertThatThrownBy(checker::afterSingletonsInstantiated)
            .isInstanceOf(MetaModelInconsistencyException.class)
            .hasMessageContaining("Entity Drift: field amount -> missing column f_amount in table it_drift")
            .hasMessageContaining("Entity Drift: field note -> missing column f_note in table it_drift")
            .hasMessageContaining("Entity Ghost: table it_ghost was not found or has no columns")
            // Column names are compared case-insensitively.
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("field name"));
    }

    @Test
    void schemaQualifiedTablesAreChecked() {
        execute("CREATE TABLE it_qualified (f_id varchar(10))");
        String schema = (String) query("SELECT current_schema() AS s").get(0).get("s");
        EntityDefinition qualified = EntityDefinition.define("Qualified", eb -> {
            eb.physicalTable(schema + ".it_qualified");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id"));
            eb.field("extra", f -> f.physicalColumn("f_extra"));
        });

        assertThatThrownBy(new MetaModelConsistencyChecker(databaseClient, registryOf(qualified))::afterSingletonsInstantiated)
            .isInstanceOf(MetaModelInconsistencyException.class)
            .hasMessageContaining("field extra -> missing column f_extra");
    }

    @Test
    void uniqueConstraintsNeedAMatchingUniqueIndex() {
        execute("CREATE TABLE it_uniq (f_id varchar(10), f_code varchar(10), f_region varchar(10))");
        execute("CREATE UNIQUE INDEX uk_uniq_wrong ON it_uniq (f_code, f_region)");
        execute("CREATE INDEX uk_uniq_plain ON it_uniq (f_code)");
        execute("CREATE UNIQUE INDEX uk_uniq_ok ON it_uniq (f_region, f_code)");
        EntityDefinition uniq = EntityDefinition.define("Uniq", eb -> {
            eb.physicalTable("it_uniq");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id"));
            eb.field("code", f -> f.physicalColumn("f_code"));
            eb.field("region", f -> f.physicalColumn("f_region"));
            eb.unique("uk_uniq_missing", "code");
            eb.unique("uk_uniq_wrong", "code");
            eb.unique("uk_uniq_plain", "code");
            eb.unique("uk_uniq_ok", "code", "region");
        });

        assertThatThrownBy(new MetaModelConsistencyChecker(databaseClient, registryOf(uniq))::afterSingletonsInstantiated)
            .hasMessageContaining("unique constraint uk_uniq_missing -> no unique index named uk_uniq_missing")
            .hasMessageContaining("unique constraint uk_uniq_wrong -> index covers")
            .hasMessageContaining("unique constraint uk_uniq_plain -> no unique index named uk_uniq_plain")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("uk_uniq_ok"));
    }
}
