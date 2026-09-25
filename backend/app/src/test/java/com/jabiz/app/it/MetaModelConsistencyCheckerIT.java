package com.jabiz.it;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.EntityDefinitionRegistry;
import com.jabiz.entity.MetaModelConsistencyChecker;
import com.jabiz.entity.MetaModelConsistencyChecker.MetaModelInconsistencyException;
import com.jabiz.it.support.PostgresIntegrationTest;
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
}
