package com.jabiz.event;

import com.jabiz.process.ProcessDefinition;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A consumer of one event type (docs/design/11-ledger-events-jobs.md section 2.3). A consumer only runs a process:
 * the platform delivers each event to it at least once and processes it once, recording the consumption in the
 * process's own transaction. Declared as a bean.
 *
 * @param consumer  unique name of the consumer; part of what identifies a consumption, so renaming it redelivers
 *                  every event
 * @param eventType the events it receives
 * @param process   run once per event, as the system actor
 * @param input     builds the process input from the event; synchronous and free of I/O
 */
public record EventSubscription<I>(String consumer, String eventType, ProcessDefinition<I, ?, ?> process,
    Function<DomainEvent, I> input) {

    /** Names of consumers and event types: letters, digits, {@code . _ : -}, at most 100 characters. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    public EventSubscription {
        requireName(consumer, "consumer");
        requireName(eventType, "eventType");
        Objects.requireNonNull(process, "process must not be null");
        Objects.requireNonNull(input, "input must not be null");
    }

    public static <I> EventSubscription<I> of(String consumer, String eventType, ProcessDefinition<I, ?, ?> process,
        Function<DomainEvent, I> input) {
        return new EventSubscription<>(consumer, eventType, process, input);
    }

    static void requireName(String value, String what) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " '" + value + "' must match " + NAME.pattern());
        }
    }
}
