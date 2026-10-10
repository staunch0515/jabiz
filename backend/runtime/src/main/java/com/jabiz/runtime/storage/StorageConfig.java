package com.jabiz.runtime.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * Registers the application's default R2DBC storage engine. Datasets refer to it through
 * {@code jabiz.storage.default-pool-ref} (default value "default"). Additional engines, such as a
 * read replica, are added by declaring further {@link StorageEngineBinding} beans.
 */
@Configuration
class StorageConfig {

    @Bean
    StorageEngineBinding defaultStorageEngineBinding(
        DatabaseClient databaseClient,
        ReactiveTransactionManager transactionManager,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef
    ) {
        StorageEngine engine = new R2dbcStorageEngine(databaseClient, TransactionalOperator.create(transactionManager),
            TransactionalOperator.create(transactionManager,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW)));
        return new StorageEngineBinding(poolRef, engine);
    }
}
