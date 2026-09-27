package com.jabiz.culture;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.culture.Culture.*;

/**
 * Datasets (docs/culture/00-design.md section 5). Editors work through the default datasets. Correspondents see and
 * change only their own rows: the {@code own:} datasets are scoped by the actor and by the state in which their rows
 * may still be edited, so a submitted story (and its perspectives and photos, no longer editable) falls out of them
 * and cannot be changed until an editor returns it (docs/design/03-dataset.md section 2.2: a write cannot move a row
 * out of the scope). The {@code own-view:} datasets show all their rows, read-only.
 */
@Configuration
public class CultureDatasets {

    /** Processes read the children of a story in one query; editors' pages page as usual. */
    static final int MAX_QUERY = 1000;

    private final String pool;

    public CultureDatasets(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
        this.pool = pool;
    }

    private DatasetDefinition editors(String entity) {
        boolean consent = CONSENT.equals(entity);
        return DatasetDefinition.define(dataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(consent ? CONSENT_READ : CONTENT_READ, consent ? CONSENT_WRITE : CONTENT_WRITE)
            .policy(p -> p.maxQueryBatchSize(MAX_QUERY))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(pool)));
    }

    private DatasetDefinition own(String entity, String ownerField, String stateField, Object editableValue) {
        return DatasetDefinition.define(Culture.own(entity), d -> d
            .targetEntityType(entity)
            .permissions(OWN_READ, OWN_WRITE)
            .scope(s -> s.fromContext(ownerField, "actorId", RequestContext::actorId).fixed(stateField, editableValue))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(pool)));
    }

    private DatasetDefinition ownView(String entity, String ownerField) {
        return DatasetDefinition.define(Culture.ownView(entity), d -> d
            .targetEntityType(entity)
            // Read-only: the write permission is never used.
            .permissions(OWN_READ, OWN_WRITE)
            .policy(p -> p.readOnly(true))
            .scope(s -> s.fromContext(ownerField, "actorId", RequestContext::actorId))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(pool)));
    }

    @Bean DatasetDefinition cultureLocationDataset() { return editors(LOCATION); }
    @Bean DatasetDefinition cultureThemeDataset() { return editors(THEME); }
    @Bean DatasetDefinition cultureParticipantDataset() { return editors(PARTICIPANT); }
    @Bean DatasetDefinition cultureStoryDataset() { return editors(STORY); }
    @Bean DatasetDefinition cultureStoryThemeDataset() { return editors(STORY_THEME); }
    @Bean DatasetDefinition cultureContributionDataset() { return editors(CONTRIBUTION); }
    @Bean DatasetDefinition cultureMediaItemDataset() { return editors(MEDIA_ITEM); }
    @Bean DatasetDefinition cultureResourceDataset() { return editors(RESOURCE); }
    @Bean DatasetDefinition cultureResourceStoryDataset() { return editors(RESOURCE_STORY); }
    @Bean DatasetDefinition cultureSiteBlockDataset() { return editors(SITE_BLOCK); }
    @Bean DatasetDefinition cultureConsentDataset() { return editors(CONSENT); }

    @Bean DatasetDefinition ownStoryDataset() { return own(STORY, "ownerActorId", "status", DRAFT); }
    @Bean DatasetDefinition ownContributionDataset() { return own(CONTRIBUTION, "ownerActorId", "editable", true); }
    @Bean DatasetDefinition ownMediaItemDataset() { return own(MEDIA_ITEM, "ownerActorId", "editable", true); }

    /**
     * A correspondent sees their profile but does not edit it: it holds what decides their consent ({@code adult})
     * and editors' fields, which a dataset cannot keep them from writing. Editors keep the profile up to date.
     */
    @Bean DatasetDefinition ownViewParticipantDataset() { return ownView(PARTICIPANT, "accountActorId"); }
    @Bean DatasetDefinition ownViewStoryDataset() { return ownView(STORY, "ownerActorId"); }
    @Bean DatasetDefinition ownViewContributionDataset() { return ownView(CONTRIBUTION, "ownerActorId"); }
    @Bean DatasetDefinition ownViewMediaItemDataset() { return ownView(MEDIA_ITEM, "ownerActorId"); }
}
