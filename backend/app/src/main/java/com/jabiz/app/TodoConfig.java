package com.jabiz.app;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the Todo entity and its default dataset (hard delete, default storage engine). */
@Configuration
class TodoConfig {

    @Bean
    EntityDefinition todoEntityDefinition() {
        return TodoEntityDefinitions.TODO;
    }

    @Bean
    DatasetDefinition todoDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define("urn:jabiz:dataset:default:Todo", d -> d
            .targetEntityType("Todo")
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
