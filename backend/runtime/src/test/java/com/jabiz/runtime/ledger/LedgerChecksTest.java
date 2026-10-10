package com.jabiz.runtime.ledger;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.ledger.LedgerDimension;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Startup checks of the ledger's analysis dimensions (docs/design/11-ledger-events-jobs.md section 1.5). */
class LedgerChecksTest {

    static final EntityDefinition LOCATION = EntityDefinition.define("Location", eb -> {
        eb.physicalTable("location");
        eb.primaryKey("locationId");
        eb.field("locationId", f -> f.physicalColumn("location_id").required(true).generated(true)
            .asSemanticIdentity("urn:location"));
        eb.field("code", f -> f.physicalColumn("code").required(true).asText(10));
        eb.field("size", f -> f.physicalColumn("size").asNumeric(5, 0));
        eb.field("regionId", f -> f.physicalColumn("region_id").immutable(true).asReference("Region"));
        eb.field("managerId", f -> f.physicalColumn("manager_id").asReference("SecUser"));
        eb.field("externalId", f -> f.physicalColumn("external_id").asSemanticIdentity("urn:external"));
    });

    private static LedgerChecks checks(boolean dataset, LedgerDimension... dimensions) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < dimensions.length; i++) {
            beans.addBean("dimension" + i, dimensions[i]);
        }
        EntityDefinitionRegistry entities = mock(EntityDefinitionRegistry.class);
        when(entities.find("Location")).thenReturn(Optional.of(LOCATION));
        when(entities.find("Nowhere")).thenReturn(Optional.empty());
        DatasetRegistry datasets = mock(DatasetRegistry.class);
        when(datasets.findForEntity("Location")).thenReturn(dataset ? Optional.of(mock(DatasetDefinition.class))
            : Optional.empty());
        return new LedgerChecks(new LedgerDimensionRegistry(beans.getBeanProvider(LedgerDimension.class)), entities,
            datasets);
    }

    @Test
    void wellDeclaredDimensionsPass() {
        assertThat(checks(true, LedgerDimension.define(1, "department", d -> d.dictionary("urn:dept")),
            LedgerDimension.define(2, "location", d -> d.entity("Location", "code"))).check()).isEmpty();
    }

    @Test
    void everyProblemIsReported() {
        assertThat(checks(false,
            LedgerDimension.define(1, "department", d -> d.dictionary("urn:dept")),
            LedgerDimension.define(1, "department", d -> d.dictionary("urn:dept")),
            LedgerDimension.define(2, "size", d -> d.entity("Location", "size")),
            LedgerDimension.define(3, "place", d -> d.entity("Nowhere", "code"))).check())
            .extracting(CheckProblem::message)
            .containsExactly("position 1 is declared by more than one dimension", "declared more than once",
                "Location.size is not a text, code, identity or reference field",
                "entity Location has no default dataset", "entity Nowhere is not declared");
    }

    @Test
    void theIdentityOfTheSourceAndItsReferencesAreSourcesToo() {
        // Values are instance ids, looked up through the primary key's index or by an immutable column.
        assertThat(checks(true, LedgerDimension.define(1, "location", d -> d.entity("Location", "locationId")),
            LedgerDimension.define(2, "region", d -> d.entity("Location", "regionId"))).check()).isEmpty();
    }

    @Test
    void anIdentityOtherThanThePrimaryKeyIsRefusedAndAMutableReferenceWarned() {
        assertThat(checks(true, LedgerDimension.define(1, "external", d -> d.entity("Location", "externalId")),
            LedgerDimension.define(2, "manager", d -> d.entity("Location", "managerId"))).check())
            .extracting(problem -> problem.severity() + " " + problem.message())
            .containsExactly("ERROR Location.externalId is an identity field but not the primary key of Location",
                "WARNING Location.managerId is a reference that may change, so its values are looked up among all"
                    + " current instances of Location; make it immutable");
    }
}
