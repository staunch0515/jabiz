package com.jabiz.runtime.config;

import com.jabiz.query.QueryCompiler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of jabiz-runtime into a Spring Boot application. Business modules only scan their own
 * packages; the platform's components arrive through this auto-configuration
 * (docs/design/01-core-vs-runtime.md section 2).
 */
@AutoConfiguration
@ComponentScan("com.jabiz.runtime")
public class JabizRuntimeAutoConfiguration {

    /** jabiz-core is framework free, so its stateless services are published as beans here. */
    @Bean
    QueryCompiler queryCompiler() {
        return new QueryCompiler();
    }
}
