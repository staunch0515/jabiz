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

    /** docs/design/04-temporal-append-only.md section 8: what a temporal table needs is checked at startup. */
    @Test
    void temporalTablesNeedTheirIndexesKeysAndGuard() {
        execute("""
            CREATE TABLE it_sloppy (
                row_id bigint, sloppy_id uuid, version_no integer, effect_start_time timestamptz,
                created_time timestamptz, process_seq_id bigint, is_deleted boolean, name text)""");
        execute("CREATE INDEX it_sloppy_wrong_order ON it_sloppy (sloppy_id, effect_start_time, version_no)");
        EntityDefinition sloppy = EntityDefinition.define("Sloppy", eb -> {
            eb.physicalTable("it_sloppy");
            eb.primaryKey("sloppyId");
            eb.field("sloppyId", f -> f.physicalColumn("sloppy_id").asSemanticIdentity("urn:test:sloppy"));
            eb.field("name", f -> f.physicalColumn("name"));
            eb.unique("uk_sloppy_name", "name");
            eb.temporal();
        });

        assertThatThrownBy(new MetaModelConsistencyChecker(databaseClient, registryOf(sloppy))::afterSingletonsInstantiated)
            .hasMessageContaining("Entity Sloppy (temporal) -> no unique index on (sloppy_id, version_no)")
            .hasMessageContaining("no index on (sloppy_id, effect_start_time DESC, version_no DESC)")
            .hasMessageContaining("no index on (process_seq_id)")
            .hasMessageContaining("no foreign key (process_seq_id) to op_process")
            .hasMessageContaining("no foreign key (sloppy_id) to entity_registry")
            .hasMessageContaining("table it_sloppy lacks the row trigger BEFORE UPDATE OR DELETE")
            .hasMessageContaining("table it_sloppy lacks the statement trigger BEFORE TRUNCATE")
            // Temporal uniqueness is enforced by locks, not by an index (decision D6).
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("uk_sloppy_name"));
    }

    @Test
    void theOperationTablesMustKeepTheirGuard() {
        execute("ALTER TABLE op_process_result DISABLE TRIGGER op_process_result_no_truncate");
        try {
            assertThatThrownBy(new MetaModelConsistencyChecker(databaseClient, registryOf())::afterSingletonsInstantiated)
                .hasMessageContaining("Operation table op_process_result -> table op_process_result lacks the "
                    + "statement trigger BEFORE TRUNCATE");
        } finally {
            execute("ALTER TABLE op_process_result ENABLE TRIGGER op_process_result_no_truncate");
        }
    }
}
