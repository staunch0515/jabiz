package com.jabiz.job;

import com.jabiz.process.ProcessDefinition;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A scheduled job (docs/design/11-ledger-events-jobs.md section 4): at each time of its cron expression, one
 * instance of the application runs it as the system actor. The job itself does nothing but run processes, with their
 * transactions, operation records and checks. Declared as a bean. Two kinds:
 * <ul>
 *   <li>{@link #cron} ({@link Kind#SINGLE}): each run executes one process. Runs are identified by their scheduled
 *       time: whichever instance fires, a scheduled time runs its process at most once (decision D14).</li>
 *   <li>{@link #forEach} ({@link Kind#PER_ITEM}; decision D42): each run selects items with an SQL template and runs the
 *       item process once for each, as a root process with a transaction of its own: one failing item rolls back that
 *       item only, and an item holds only its own locks. An item runs at most once per scheduled time; across
 *       scheduled times the item's process reads again under its lock and leaves an item that is no longer due. Item
 *       processes are {@code internal()}: their input comes from the selection, not from a person.</li>
 * </ul>
 * Immutable: the {@code maxItems}, {@code parallelism} ... methods return a changed copy, checked when made.
 *
 * @param <I> the input of the process (of the item process, for a per-item job)
 */
public final class JobDefinition<I> {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    public static final Duration DEFAULT_LOCK_AT_MOST_FOR = Duration.ofMinutes(10);

    public static final int DEFAULT_MAX_ITEMS = 500;
    public static final int MAX_MAX_ITEMS = 10_000;
    public static final int DEFAULT_PARALLELISM = 1;
    public static final int MAX_PARALLELISM = 8;
    public static final Duration DEFAULT_ITEM_LOCK_WAIT = Duration.ofSeconds(2);
    public static final ItemRetry DEFAULT_ITEM_RETRY = new ItemRetry(Duration.ofMinutes(1), Duration.ofHours(1));

    /** The idempotency keys of the platform's executions are at most this long (op_process). */
    public static final int MAX_IDEMPOTENCY_KEY = 128;

    /** Whether a run executes one process or one per selected item. */
    public enum Kind { SINGLE, PER_ITEM }

    /**
     * How a failing item is retried by later runs (decision D42, rule 4): after its first failure the next run may
     * retry it; after the n-th consecutive failure (n &ge; 2) it waits {@code min(first * 2^(n-2), max)}. Never given
     * up.
     */
    public record ItemRetry(Duration first, Duration max) {
        public ItemRetry {
            Objects.requireNonNull(first, "first must not be null");
            Objects.requireNonNull(max, "max must not be null");
            if (first.isNegative() || first.isZero()) {
                throw new IllegalArgumentException("The first retry delay must be positive");
            }
            if (max.compareTo(first) < 0) {
                throw new IllegalArgumentException("The longest retry delay must not be shorter than the first");
            }
        }

        /** How long an item that failed {@code failures} times in a row waits before it is retried. */
        public Duration delayAfter(int failures) {
            if (failures <= 1) {
                return Duration.ZERO;
            }
            int doublings = failures - 2;
            if (doublings >= 62) {
                return max;
            }
            long millis = first.toMillis();
            long factor = 1L << doublings;
            if (millis > max.toMillis() / factor) {
                return max;
            }
            Duration delay = Duration.ofMillis(millis * factor);
            return delay.compareTo(max) > 0 ? max : delay;
        }
    }

    private final Kind kind;
    private final String name;
    private final String cron;
    private final ZoneId zone;
    private final ProcessDefinition<I, ?, ?> process;
    private final Function<Instant, I> input;
    private final JobItems items;
    private final BiFunction<Instant, Map<String, Object>, I> itemInput;
    private final Duration lockAtMostFor;
    private final int maxItems;
    private final int parallelism;
    private final Duration itemLockWait;
    private final ItemRetry itemRetry;
    private final Duration maxRunTime;

    private JobDefinition(Kind kind, String name, String cron, ZoneId zone, ProcessDefinition<I, ?, ?> process,
        Function<Instant, I> input, JobItems items, BiFunction<Instant, Map<String, Object>, I> itemInput,
        Duration lockAtMostFor, int maxItems, int parallelism, Duration itemLockWait, ItemRetry itemRetry,
        Duration maxRunTime) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Job name '" + name + "' must match " + NAME.pattern());
        }
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("Job " + name + ": cron must not be blank");
        }
        Objects.requireNonNull(zone, "zone must not be null");
        Objects.requireNonNull(process, "process must not be null");
        Objects.requireNonNull(lockAtMostFor, "lockAtMostFor must not be null");
        if (lockAtMostFor.isNegative() || lockAtMostFor.isZero()) {
            throw new IllegalArgumentException("Job " + name + ": lockAtMostFor must be positive");
        }
        if (kind == Kind.SINGLE) {
            Objects.requireNonNull(input, "input must not be null");
        } else {
            Objects.requireNonNull(items, "items must not be null");
            Objects.requireNonNull(itemInput, "input must not be null");
            if (maxItems < 1 || maxItems > MAX_MAX_ITEMS) {
                throw new IllegalArgumentException("Job " + name + ": maxItems must be 1 to " + MAX_MAX_ITEMS);
            }
            if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
                throw new IllegalArgumentException("Job " + name + ": parallelism must be 1 to " + MAX_PARALLELISM);
            }
            Objects.requireNonNull(itemLockWait, "itemLockWait must not be null");
            if (itemLockWait.isNegative() || itemLockWait.isZero()) {
                throw new IllegalArgumentException("Job " + name + ": itemLockWait must be positive");
            }
            Objects.requireNonNull(itemRetry, "itemRetry must not be null");
            if (maxRunTime != null && (maxRunTime.isNegative() || maxRunTime.isZero())) {
                throw new IllegalArgumentException("Job " + name + ": maxRunTime must be positive");
            }
            // The lock must outlast the run: new items stop at maxRunTime, those running end well within the lock.
            if (maxRunTime != null && maxRunTime.compareTo(lockAtMostFor) >= 0) {
                throw new IllegalArgumentException("Job " + name + ": maxRunTime (" + maxRunTime
                    + ") must be shorter than lockAtMostFor (" + lockAtMostFor
                    + "); declare a longer lockAtMostFor first");
            }
            String longest = itemIdempotencyKey(name, Instant.ofEpochMilli(9_999_999_999_999L), "x".repeat(36));
            if (longest.length() > MAX_IDEMPOTENCY_KEY) {
                throw new IllegalStateException("Job " + name + ": item idempotency keys would be too long");
            }
        }
        this.kind = kind;
        this.name = name;
        this.cron = cron;
        this.zone = zone;
        this.process = process;
        this.input = input;
        this.items = items;
        this.itemInput = itemInput;
        this.lockAtMostFor = lockAtMostFor;
        this.maxItems = maxItems;
        this.parallelism = parallelism;
        this.itemLockWait = itemLockWait;
        this.itemRetry = itemRetry;
        this.maxRunTime = maxRunTime;
    }

    /**
     * A job that runs {@code process} once per scheduled time.
     *
     * @param name    unique name, 1 to 64 letters, digits, {@code . _ -} (also the name of its cluster lock)
     * @param cron    Spring cron expression with six fields (second minute hour day month weekday), checked at startup
     * @param zone    time zone of the cron expression
     * @param process the process each run executes
     * @param input   builds the process input from the scheduled time of the run; synchronous and free of I/O
     */
    public static <I> JobDefinition<I> cron(String name, String cron, ZoneId zone, ProcessDefinition<I, ?, ?> process,
        Function<Instant, I> input) {
        return new JobDefinition<>(Kind.SINGLE, name, cron, zone, process, input, null, null,
            DEFAULT_LOCK_AT_MOST_FOR, 0, 0, null, null, null);
    }

    /**
     * A job that, at each scheduled time, selects items and runs {@code process} for each in a transaction of its own
     * (decision D42).
     *
     * @param items   the selection
     * @param process the item process; it must be {@code internal()} (startup check {@code JOB})
     * @param input   builds the item process's input from the scheduled time and the item's row; synchronous and
     *                free of I/O
     */
    public static <I> JobDefinition<I> forEach(String name, String cron, ZoneId zone, JobItems items,
        ProcessDefinition<I, ?, ?> process, BiFunction<Instant, Map<String, Object>, I> input) {
        return new JobDefinition<>(Kind.PER_ITEM, name, cron, zone, process, null, items, input,
            DEFAULT_LOCK_AT_MOST_FOR, DEFAULT_MAX_ITEMS, DEFAULT_PARALLELISM, DEFAULT_ITEM_LOCK_WAIT,
            DEFAULT_ITEM_RETRY, null);
    }

    /**
     * The idempotency key of an item's process: {@code job:<name>:<scheduled time in epoch milliseconds>:<item key>}.
     * An item runs at most once per scheduled time.
     */
    public static String itemIdempotencyKey(String jobName, Instant scheduledTime, String itemKey) {
        return "job:" + jobName + ":" + scheduledTime.toEpochMilli() + ":" + itemKey;
    }

    private JobDefinition<I> with(Duration lockAtMostFor, int maxItems, int parallelism, Duration itemLockWait,
        ItemRetry itemRetry, Duration maxRunTime) {
        return new JobDefinition<>(kind, name, cron, zone, process, input, items, itemInput, lockAtMostFor, maxItems,
            parallelism, itemLockWait, itemRetry, maxRunTime);
    }

    private void requirePerItem(String setting) {
        if (kind != Kind.PER_ITEM) {
            throw new IllegalStateException("Job " + name + ": " + setting + " is a setting of per-item jobs");
        }
    }

    /**
     * How long a run holds the cluster lock at most, should the instance die while running; longer than any run
     * takes (and than {@link #maxRunTime()}).
     */
    public JobDefinition<I> lockAtMostFor(Duration duration) {
        return with(duration, maxItems, parallelism, itemLockWait, itemRetry, maxRunTime);
    }

    /** At most this many items per run (default {@value #DEFAULT_MAX_ITEMS}, at most {@value #MAX_MAX_ITEMS}). */
    public JobDefinition<I> maxItems(int count) {
        requirePerItem("maxItems");
        return with(lockAtMostFor, count, parallelism, itemLockWait, itemRetry, maxRunTime);
    }

    /**
     * Items run side by side within the instance (default {@value #DEFAULT_PARALLELISM}, at most
     * {@value #MAX_PARALLELISM}); each takes a database connection. Two items of one run wanting the same lock: one
     * may be busy and wait for the next run.
     */
    public JobDefinition<I> parallelism(int count) {
        requirePerItem("parallelism");
        return with(lockAtMostFor, maxItems, count, itemLockWait, itemRetry, maxRunTime);
    }

    /**
     * The longest an item's {@code HoldLock} steps wait (default 2 seconds, at most {@code jabiz.process.lock-timeout});
     * an item that waits longer is busy and left to the next run.
     */
    public JobDefinition<I> itemLockWait(Duration wait) {
        requirePerItem("itemLockWait");
        return with(lockAtMostFor, maxItems, parallelism, wait, itemRetry, maxRunTime);
    }

    /** The backoff of failing items (default 1 minute, doubling up to 1 hour). */
    public JobDefinition<I> itemRetry(Duration first, Duration max) {
        requirePerItem("itemRetry");
        return with(lockAtMostFor, maxItems, parallelism, itemLockWait, new ItemRetry(first, max), maxRunTime);
    }

    /**
     * After this long a run starts no new item (they count as deferred); default half of {@link #lockAtMostFor()},
     * shorter than it.
     */
    public JobDefinition<I> maxRunTime(Duration duration) {
        requirePerItem("maxRunTime");
        return with(lockAtMostFor, maxItems, parallelism, itemLockWait, itemRetry,
            Objects.requireNonNull(duration, "maxRunTime must not be null"));
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public String cron() {
        return cron;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The process each run executes; for a per-item job, the process of each item. */
    public ProcessDefinition<I, ?, ?> process() {
        return process;
    }

    /** The process input from the scheduled time; null for a per-item job. */
    public Function<Instant, I> input() {
        return input;
    }

    /** The selection of a per-item job; null for a single-process job. */
    public JobItems items() {
        return items;
    }

    /** The item process's input from the scheduled time and the item's row; null for a single-process job. */
    public BiFunction<Instant, Map<String, Object>, I> itemInput() {
        return itemInput;
    }

    public Duration lockAtMostFor() {
        return lockAtMostFor;
    }

    public int maxItems() {
        return maxItems;
    }

    public int parallelism() {
        return parallelism;
    }

    public Duration itemLockWait() {
        return itemLockWait;
    }

    public ItemRetry itemRetry() {
        return itemRetry;
    }

    /** When a per-item run stops starting items: as declared, or half of {@link #lockAtMostFor()}. */
    public Duration maxRunTime() {
        if (kind != Kind.PER_ITEM) {
            return null;
        }
        return maxRunTime != null ? maxRunTime : lockAtMostFor.dividedBy(2);
    }

    @Override
    public String toString() {
        return "JobDefinition[" + kind + " " + name + " " + cron + " " + zone + " -> " + process.name() + " v"
            + process.version() + "]";
    }
}
