package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.runtime.firstlaunch.FirstLaunchConfigReader;
import com.jabiz.runtime.firstlaunch.FirstLaunchSettings;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The settings of the first launch's waiting phase (docs/design/23-first-launch.md section 8), from the environment:
 * <ul>
 *   <li>{@code jabiz.first-launch.deadline} (14 days): from the end of the content phase; then the run ends
 *       {@code EXPIRED} and what is left is done by people as usual;</li>
 *   <li>{@code jabiz.first-launch.remind-after} (3 days): an account that has not accepted by then is invited once
 *       more;</li>
 *   <li>{@code jabiz.first-launch.confirm-admins} ({@code JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS}): the fingerprints the
 *       operator confirmed, comma separated; an administrator's role is granted only to an account whose fingerprint is
 *       among them.</li>
 * </ul>
 */
@Component
public class WaitingSettings implements SmartInitializingSingleton {

    private final Environment environment;
    private volatile Optional<FirstLaunchConfig> config = Optional.empty();

    public WaitingSettings(Environment environment) {
        this.environment = environment;
    }

    public Duration deadline() {
        return environment.getProperty("jabiz.first-launch.deadline", Duration.class, Duration.ofDays(14));
    }

    public Duration remindAfter() {
        return environment.getProperty("jabiz.first-launch.remind-after", Duration.class, Duration.ofDays(3));
    }

    /** The fingerprints the operator confirmed (lower case). */
    public Set<String> confirmed() {
        String value = environment.getProperty("jabiz.first-launch.confirm-admins", "");
        return Arrays.stream(value.split(",")).map(String::strip).filter(text -> !text.isEmpty())
            .map(text -> text.toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /** Reads the configuration once, at startup: it is read from files, which the request path does not. */
    @Override
    public void afterSingletonsInstantiated() {
        config = read();
    }

    /** The configuration this instance was started with, when there is one and it read without problems. */
    public Optional<FirstLaunchConfig> config() {
        return config;
    }

    private Optional<FirstLaunchConfig> read() {
        FirstLaunchSettings settings = new FirstLaunchSettings(
            environment.getProperty(FirstLaunchSettings.CONFIG, ""), false,
            environment.getProperty("jabiz.first-launch.content-timeout", Duration.class, Duration.ofMinutes(10)),
            environment.getProperty("jabiz.first-launch.await-interval", Duration.class, Duration.ofSeconds(1)),
            environment.getProperty("jabiz.first-launch.max-file-bytes", Long.class, 20L * 1024 * 1024),
            environment.getProperty("jabiz.first-launch.max-total-bytes", Long.class, 200L * 1024 * 1024),
            environment.getProperty("jabiz.first-launch.max-files", Integer.class, 2000));
        if (!settings.configured()) {
            return Optional.empty();
        }
        FirstLaunchConfigReader.Read read = new FirstLaunchConfigReader(settings).read();
        return read.problems().isEmpty() ? Optional.of(read.config()) : Optional.empty();
    }
}
