package com.jabiz.runtime.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
class SchemaMigrationConfig {

    @Bean
    @ConditionalOnMissingBean(FlywayMigrationStrategy.class)
    FlywayMigrationStrategy platformSchemaMigration(
        @Value("${" + PlatformSchemaMigration.LOCK_TIMEOUT_PROPERTY + ":0s}") Duration lockTimeout) {
        return new PlatformSchemaMigration(lockTimeout);
    }
}
