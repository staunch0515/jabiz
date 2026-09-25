package com.jabiz.app;

import com.jabiz.dataset.DatasetDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default datasets: one unrestricted, hard-delete dataset per entity type on the default storage engine.
 * Restricted views (scopes, soft delete, read replicas) are added by declaring further {@link DatasetDefinition}
 * beans.
 */
@Configuration
class DatasetsConfig {

    @Bean
    DatasetDefinition waybillDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return defaultDataset("urn:jabiz:dataset:default:WaybillTracking", "WaybillTracking", "logistics.waybill",
            poolRef);
    }

    @Bean
    DatasetDefinition customsDeclarationDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return defaultDataset("urn:jabiz:dataset:default:CustomsDeclaration", "CustomsDeclaration",
            "customs.declaration", poolRef);
    }

    private static DatasetDefinition defaultDataset(String resourceId, String entityType, String permission,
        String poolRef) {
        return DatasetDefinition.define(resourceId, d -> d
            .targetEntityType(entityType)
            .asDefault()
            .permissions(permission + ".read", permission + ".write")
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
