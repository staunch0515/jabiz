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

    /** Temporal, so identified by UUIDs. */
    static final EntityDefinition LOCATION = EntityDefinition.define("Location", eb -> {
        eb.physicalTable("location");
        eb.primaryKey("locationId");
        eb.field("locationId", f -> f.physicalColumn("location_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:location"));
        eb.field("code", f -> f.physicalColumn("code").immutable(true).required(true).asText(10));
        eb.field("label", f -> f.physicalColumn("label").asText(50));
        eb.field("size", f -> f.physicalColumn("size").immutable(true).asNumeric(5, 0));
        eb.field("regionId", f -> f.physicalColumn("region_id").immutable(true).asReference("Region"));
        eb.field("tagId", f -> f.physicalColumn("tag_id").immutable(true).asReference("Tag"));
        eb.field("managerId", f -> f.physicalColumn("manager_id").asReference("Location"));
        eb.field("externalId", f -> f.physicalColumn("external_id").immutable(true)
            .asSemanticIdentity("urn:external"));
        eb.temporal();
    });

    static final EntityDefinition REGION = EntityDefinition.define("Region", eb -> {
        eb.physicalTable("region");
        eb.primaryKey("regionId");
        eb.field("regionId", f -> f.physicalColumn("region_id").immutable(true).required(true)
            .asSemanticIdentity("urn:region"));
        eb.temporal();
    });

    /** Not temporal: its ids are whatever its table holds, not necessarily UUIDs. */
    static final EntityDefinition TAG = EntityDefinition.define("Tag", eb -> {
        eb.physicalTable("tag");
        eb.primaryKey("tagId");
        eb.field("tagId", f -> f.physicalColumn("tag_id").required(true).asSemanticIdentity("urn:tag"));
        eb.field("name", f -> f.physicalColumn("name").asText(50));
    });

    static EntityDefinitionRegistry entities() {
        EntityDefinitionRegistry entities = mock(EntityDefinitionRegistry.class);
        when(entities.find("Location")).thenReturn(Optional.of(LOCATION));
        when(entities.find("Region")).thenReturn(Optional.of(REGION));
        when(entities.find("Tag")).thenReturn(Optional.of(TAG));
        when(entities.find("Nowhere")).thenReturn(Optional.empty());
        return entities;
    }

    static LedgerDimensionRegistry registry(LedgerDimension... dimensions) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < dimensions.length; i++) {
            beans.addBean("dimension" + i, dimensions[i]);
        }
        return new LedgerDimensionRegistry(beans.getBeanProvider(LedgerDimension.class), entities());
    }

    private static LedgerChecks checks(boolean dataset, LedgerDimension... dimensions) {
        DatasetRegistry datasets = mock(DatasetRegistry.class);
        for (String entity : new String[] {"Location", "Tag"}) {
            when(datasets.findForEntity(entity)).thenReturn(dataset ? Optional.of(mock(DatasetDefinition.class))
                : Optional.empty());
        }
        return new LedgerChecks(registry(dimensions), entities(), datasets);
    }

    private static Object[] problems(LedgerDimension... dimensions) {
        return checks(true, dimensions).check().stream()
            .map(problem -> problem.severity() + " " + problem.message()).toArray();
    }

    @Test
    void wellDeclaredDimensionsPass() {
        assertThat(checks(true, LedgerDimension.define(1, "department", d -> d.dictionary("urn:dept")),
            LedgerDimension.define(2, "location", d -> d.entity("Location", "code"))).check()).isEmpty();
        // A field of an entity without history may change: its current values are all there is.
        assertThat(checks(true, LedgerDimension.define(1, "tag", d -> d.entity("Tag", "name"))).check()).isEmpty();
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
    void theIdentityOfATemporalSourceAndReferencesToATemporalEntityAreSourcesToo() {
        // Values are UUIDs, looked up through the primary key's index or by an immutable column.
        assertThat(problems(LedgerDimension.define(1, "location", d -> d.entity("Location", "locationId")),
            LedgerDimension.define(2, "region", d -> d.entity("Location", "regionId")))).isEmpty();
    }

    @Test
    void idsThatAreNotUuidsOrNotThePrimaryKeyAreRefused() {
        assertThat(problems(LedgerDimension.define(1, "tag", d -> d.entity("Tag", "tagId")),
            LedgerDimension.define(2, "locationTag", d -> d.entity("Location", "tagId")),
            LedgerDimension.define(3, "external", d -> d.entity("Location", "externalId"))))
            .containsExactly("ERROR Tag.tagId does not hold UUIDs: Tag is not temporal",
                "ERROR Location.tagId does not hold UUIDs: Tag is not temporal",
                "ERROR Location.externalId is an identity field but not the primary key of Location");
    }

    @Test
    void aFieldOfATemporalSourceThatMayChangeIsWarnedWhateverItsKind() {
        assertThat(problems(LedgerDimension.define(1, "label", d -> d.entity("Location", "label")),
            LedgerDimension.define(2, "manager", d -> d.entity("Location", "managerId"))))
            .containsExactly("WARNING Location.label may change, so its values are looked up among all current"
                    + " instances of Location; make it immutable",
                "WARNING Location.managerId may change, so its values are looked up among all current instances"
                    + " of Location; make it immutable");
    }

    @Test
    void theDimensionsWhoseValuesAreIdsAreKnownFromTheStart() {
        assertThat(registry(LedgerDimension.define(1, "department", d -> d.dictionary("urn:dept")),
            LedgerDimension.define(2, "location", d -> d.entity("Location", "locationId")),
            LedgerDimension.define(3, "region", d -> d.entity("Location", "regionId")),
            LedgerDimension.define(4, "code", d -> d.entity("Location", "code"))).idValued())
            .containsExactlyInAnyOrder("location", "region");
    }
}
