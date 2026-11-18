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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WaitingSettings.class);

    private final Environment environment;
    private final ConfigShape shapes;
    private volatile Optional<FirstLaunchConfig> config = Optional.empty();
    /** The shape, once worked out (empty inside: it could not be). */
    private volatile Optional<String> shape;

    public WaitingSettings(Environment environment, ConfigShape shapes) {
        this.environment = environment;
        this.shapes = shapes;
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

    /**
     * The shape of the configuration this instance was started with ({@link ConfigShape}): the application's steps
     * for a ready account run only while it is the shape the run recorded at its content phase's end. Worked out on
     * first use only - when the waiting phase needs it, not at every start of a system long past its first launch -
     * and once: it hashes the configuration's directory (blocking: call it off the request path). Empty when there is
     * no configuration or it cannot be hashed (logged).
     */
    public Optional<String> shape() {
        Optional<String> known = shape;
        if (known == null) {
            try {
                known = config().map(shapes::of);
            } catch (RuntimeException e) {
                log.warn("First launch: the configuration's directory could not be read to compare it with the run's;"
                    + " the application's steps for ready accounts do not run until a restart: {}", e.toString());
                known = Optional.empty();
            }
            shape = known;
        }
        return known;
    }

    /**
     * How many times in a row a ready account's item may fail before the run's end stops waiting for its steps
     * ({@code jabiz.first-launch.ready-attempts}, 5): a passing failure (a conflict, a busy lock) is tried again by
     * the job with its backoff; the run's deadline ends the wait in any case.
     */
    public int readyAttempts() {
        return Math.max(1, environment.getProperty("jabiz.first-launch.ready-attempts", Integer.class, 5));
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
        if (!read.problems().isEmpty()) {
            // The runner reports them only on a database without users; in the waiting phase nothing else would.
            StringBuilder report = new StringBuilder("First launch: the configuration has " + read.problems().size()
                + " problem(s); the waiting phase does not apply it (no address correction, no remaining items in"
                + " the report):");
            read.problems().forEach(problem -> report.append(System.lineSeparator()).append("  ")
                .append(problem.format()));
            log.warn(report.toString());
            return Optional.empty();
        }
        return Optional.of(read.config());
    }
}
