package com.jabiz.app.it.fixture;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Public datasets that break the rules of docs/design/15-public-access.md section 2, one way each; only the
 * {@code broken-public} profile of {@code PlatformCheckIT} loads them.
 */
@Configuration
@Profile("broken-public")
class ItBrokenPublicFixtures {

    @Bean
    DatasetDefinition itBrokenContextScope(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
        return DatasetDefinition.define("urn:jabiz:dataset:it:broken:context", d -> d
            .targetEntityType("ItAttachment")
            .scope(s -> s.fromContext("title", RequestContext::actorId))
            .publicRead(p -> p.fields("attachmentId"))
            .permissions("it.read", "it.write")
            .storage(s -> s.connectionPoolRef(pool)));
    }

    @Bean
    DatasetDefinition itBrokenUnscoped(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
        return DatasetDefinition.define("urn:jabiz:dataset:it:broken:unscoped", d -> d
            .targetEntityType("ItAttachment")
            .publicRead(p -> p.fields("attachmentId", "secretStuff"))
            .permissions("it.read", "it.write")
            .storage(s -> s.connectionPoolRef(pool)));
    }

    @Bean
    DatasetDefinition itBrokenTooLarge(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
        return DatasetDefinition.define("urn:jabiz:dataset:it:broken:large", d -> d
            .targetEntityType("ItAttachment")
            .scope(s -> s.fixed("title", "x"))
            .policy(p -> p.maxQueryBatchSize(500))
            .publicRead(p -> p.fields("attachmentId"))
            .permissions("it.read", "it.write")
            .storage(s -> s.connectionPoolRef(pool)));
    }
}
