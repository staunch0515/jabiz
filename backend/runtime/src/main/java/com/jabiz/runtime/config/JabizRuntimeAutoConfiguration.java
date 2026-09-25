package com.jabiz.runtime.config;

import com.jabiz.query.QueryCompiler;
import com.jabiz.runtime.JabizApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import reactor.core.scheduler.Schedulers;

/**
 * Entry point of jabiz-runtime into a Spring Boot application. Business modules only scan their own
 * packages; the platform's components arrive through this auto-configuration
 * (docs/design/01-core-vs-runtime.md section 2).
 */
@AutoConfiguration
@ComponentScan("com.jabiz.runtime")
public class JabizRuntimeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JabizRuntimeAutoConfiguration.class);

    /** jabiz-core is framework free, so its stateless services are published as beans here. */
    @Bean
    QueryCompiler queryCompiler() {
        return new QueryCompiler();
    }

    /**
     * Blocking steps rely on boundedElastic running on virtual threads. The setting cannot be changed
     * once Reactor has loaded, so a missing property is reported rather than corrected.
     */
    @Bean
    SmartInitializingSingleton virtualThreadSchedulerCheck() {
        return () -> {
            if (!Schedulers.DEFAULT_BOUNDED_ELASTIC_ON_VIRTUAL_THREADS) {
                log.warn("boundedElastic does not run on virtual threads: start the application with "
                    + "JabizApplication.run or -D{}=true", JabizApplication.VIRTUAL_THREADS_PROPERTY);
            }
        };
    }
}
