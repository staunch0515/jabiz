package com.jabiz.runtime.process.steps;

import reactor.core.publisher.Mono;

/**
 * Where {@link PublishEvent} steps hand their events. The outbox implementation (written in the process's transaction,
 * delivered after the commit) arrives with ROADMAP phase 9; until an implementation is registered, processes using
 * {@code PublishEvent} fail the startup check.
 */
public interface EventPublisher {

    /** Publishes within the running process's transaction. */
    Mono<Void> publish(String eventType, Object payload, long processSeqId);
}
