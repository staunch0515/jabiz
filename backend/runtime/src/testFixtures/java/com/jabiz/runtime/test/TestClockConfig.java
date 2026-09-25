package com.jabiz.it.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock in integration tests; only present on the test classpath. */
@Configuration
class TestClockConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(PostgresIntegrationTest.START);
    }
}
