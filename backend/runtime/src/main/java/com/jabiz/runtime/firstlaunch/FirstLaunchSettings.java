package com.jabiz.runtime.firstlaunch;

import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Objects;

/**
 * The settings of the first launch (docs/design/23-first-launch.md section 2), from the environment.
 *
 * @param config        {@code jabiz.first-launch.config} ({@code JABIZ_FIRST_LAUNCH_CONFIG}): the main file; blank
 *                      for no first launch
 * @param dryRun        {@code jabiz.first-launch.dry-run}: validate, print the steps and exit, writing nothing
 * @param contentTimeout {@code jabiz.first-launch.content-timeout}: how long the content phase may take, from the
 *                      run's start (a waiting step gives up then; another instance's run older than this has stopped)
 * @param awaitInterval {@code jabiz.first-launch.await-interval}: the pause between two tries of a waiting step
 * @param maxFileBytes  {@code jabiz.first-launch.max-file-bytes}: the largest file of the configuration's directory
 * @param maxTotalBytes {@code jabiz.first-launch.max-total-bytes}: all of them together
 * @param maxFiles      {@code jabiz.first-launch.max-files}: how many files the directory may hold
 */
public record FirstLaunchSettings(String config, boolean dryRun, Duration contentTimeout, Duration awaitInterval,
    long maxFileBytes, long maxTotalBytes, int maxFiles) {

    public static final String CONFIG = "jabiz.first-launch.config";

    public FirstLaunchSettings {
        config = config == null ? "" : config.trim();
        Objects.requireNonNull(contentTimeout, "contentTimeout must not be null");
        Objects.requireNonNull(awaitInterval, "awaitInterval must not be null");
    }

    static FirstLaunchSettings of(Environment environment) {
        return new FirstLaunchSettings(
            environment.getProperty(CONFIG, ""),
            environment.getProperty("jabiz.first-launch.dry-run", Boolean.class, false),
            environment.getProperty("jabiz.first-launch.content-timeout", Duration.class, Duration.ofMinutes(10)),
            environment.getProperty("jabiz.first-launch.await-interval", Duration.class, Duration.ofSeconds(1)),
            environment.getProperty("jabiz.first-launch.max-file-bytes", Long.class, 20L * 1024 * 1024),
            environment.getProperty("jabiz.first-launch.max-total-bytes", Long.class, 200L * 1024 * 1024),
            environment.getProperty("jabiz.first-launch.max-files", Integer.class, 2000));
    }

    /** Whether a first-launch configuration is set. */
    public boolean configured() {
        return !config.isEmpty();
    }
}
