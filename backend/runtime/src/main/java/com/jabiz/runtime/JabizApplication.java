package com.jabiz.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts a jabiz application; use instead of {@link SpringApplication#run(Class, String...)}. */
public final class JabizApplication {

    /**
     * Reactor reads this once, when its {@code Schedulers} class loads, so it must be set before any
     * reactive code runs. With it, {@code boundedElastic} (used for blocking steps) runs on virtual threads.
     */
    public static final String VIRTUAL_THREADS_PROPERTY = "reactor.schedulers.defaultBoundedElasticOnVirtualThreads";

    private JabizApplication() {}

    public static ConfigurableApplicationContext run(Class<?> primarySource, String... args) {
        if (System.getProperty(VIRTUAL_THREADS_PROPERTY) == null) {
            System.setProperty(VIRTUAL_THREADS_PROPERTY, "true");
        }
        return SpringApplication.run(primarySource, args);
    }
}
