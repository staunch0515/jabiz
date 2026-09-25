package com.jabiz.process;

import reactor.core.publisher.Mono;

/**
 * Source of process sequence identifiers (one per process execution). Deployments with several
 * application instances should provide a bean backed by a shared source, such as a database
 * sequence, to guarantee global uniqueness.
 */
public interface ProcessSequence {
    Mono<Long> next();
}
