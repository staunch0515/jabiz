package com.jabiz.runtime.process;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * {@link ProcessSequence} backed by the database sequence {@code op_process_seq}, so identifiers are
 * unique across every application instance sharing the database (docs/design/04-temporal-append-only.md
 * section 2.2). Values are not gap-free: a rolled-back execution consumes its number.
 */
public final class DatabaseProcessSequence implements ProcessSequence {

    private final DatabaseClient databaseClient;

    public DatabaseProcessSequence(DatabaseClient databaseClient) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient must not be null");
    }

    @Override
    public Mono<Long> next() {
        return databaseClient.sql("SELECT nextval('op_process_seq')")
            .map(row -> row.get(0, Long.class))
            .one()
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("op_process_seq returned no value")));
    }
}
