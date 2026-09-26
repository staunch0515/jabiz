package com.jabiz.runtime.process.steps;

import reactor.core.publisher.Mono;

/**
 * Where {@link PublishEvent} steps hand their events: the platform's outbox ({@code OutboxEventPublisher}), written
 * in the process's transaction and delivered after the commit. Without an implementation, processes using
 * {@code PublishEvent} fail the startup check.
 */
public interface EventPublisher {

    /** Publishes within the running process's transaction. */
    Mono<Void> publish(String eventType, Object payload, long processSeqId);
}
