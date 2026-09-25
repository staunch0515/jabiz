package com.jabiz.runtime.process;

import reactor.core.publisher.Mono;

/**
 * Source of process sequence identifiers ({@code process_seq_id}, one per process execution). The
 * default implementation is {@link DatabaseProcessSequence}, which is unique across instances.
 */
public interface ProcessSequence {
    Mono<Long> next();
}
