package com.jabiz.job;

import com.jabiz.process.ProcessDefinition;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A scheduled job (docs/design/11-ledger-events-jobs.md section 4): at each time of its cron expression, one
 * instance of the application runs the process as the system actor. The job itself does nothing else; what the run
 * does is the process, with its transaction, operation record and checks. Declared as a bean.
 *
 * <p>Runs are identified by their scheduled time: whichever instance fires, a scheduled time runs its process at
 * most once.
 *
 * @param name          unique name, 1 to 64 letters, digits, {@code . _ -} (also the name of its cluster lock)
 * @param cron          Spring cron expression with six fields (second minute hour day month weekday), checked at
 *                      startup
 * @param zone          time zone of the cron expression
 * @param process       the process each run executes
 * @param input         builds the process input from the scheduled time of the run; synchronous and free of I/O
 * @param lockAtMostFor how long a run holds the cluster lock at most, should the instance die while running; longer
 *                      than any run takes
 */
public record JobDefinition<I>(String name, String cron, ZoneId zone, ProcessDefinition<I, ?, ?> process,
    Function<Instant, I> input, Duration lockAtMostFor) {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    public static final Duration DEFAULT_LOCK_AT_MOST_FOR = Duration.ofMinutes(10);

    public JobDefinition {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Job name '" + name + "' must match " + NAME.pattern());
        }
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("Job " + name + ": cron must not be blank");
        }
        Objects.requireNonNull(zone, "zone must not be null");
        Objects.requireNonNull(process, "process must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(lockAtMostFor, "lockAtMostFor must not be null");
        if (lockAtMostFor.isNegative() || lockAtMostFor.isZero()) {
            throw new IllegalArgumentException("Job " + name + ": lockAtMostFor must be positive");
        }
    }

    public static <I> JobDefinition<I> cron(String name, String cron, ZoneId zone, ProcessDefinition<I, ?, ?> process,
        Function<Instant, I> input) {
        return new JobDefinition<>(name, cron, zone, process, input, DEFAULT_LOCK_AT_MOST_FOR);
    }

    public JobDefinition<I> lockAtMostFor(Duration duration) {
        return new JobDefinition<>(name, cron, zone, process, input, duration);
    }
}
