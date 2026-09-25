package com.jabiz.app;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Todo entity with two datasets: the default one (all entries, hard delete) and a member view
 * in which every actor sees and writes only their own entries (docs/design/03-dataset.md section 2.2).
 */
@Configuration
class TodoConfig {

    static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:Todo";
    static final String MEMBER_DATASET = "urn:jabiz:dataset:member:Todo";

    @Bean
    EntityDefinition todoEntityDefinition() {
        return TodoEntityDefinitions.TODO;
    }

    @Bean
    DatasetDefinition todoDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DEFAULT_DATASET, d -> d
            .targetEntityType("Todo")
            .asDefault()
            .permissions("todo.read", "todo.write")
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition memberTodoDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(MEMBER_DATASET, d -> d
            .targetEntityType("Todo")
            .permissions("todo.member.read", "todo.member.write")
            .scope(s -> s.fromContext("ownerId", "actorId", RequestContext::actorId))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
