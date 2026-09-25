package com.jabiz.runtime.it;

import com.jabiz.runtime.process.DatabaseProcessSequence;
import com.jabiz.runtime.process.ProcessSequence;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ROADMAP phase 2 acceptance: two instances drawing process numbers concurrently never get the same one. */
class ProcessSequenceIT extends PostgresIntegrationTest {

    private static final int PER_INSTANCE = 500;

    @Autowired
    ProcessSequence applicationSequence;

    @Test
    void theApplicationUsesTheDatabaseSequence() {
        assertThat(applicationSequence).isInstanceOf(DatabaseProcessSequence.class);
        long first = applicationSequence.next().block();
        long second = applicationSequence.next().block();

        assertThat(second).isGreaterThan(first);
        assertThat(query("SELECT last_value FROM op_process_seq").get(0)).containsEntry("last_value", second);
    }

    @Test
    void twoInstancesConcurrentlyNeverShareANumber() {
        // Each "instance" has its own connection pool, as two application processes would.
        ConnectionPool poolA = pool();
        ConnectionPool poolB = pool();
        try {
            ProcessSequence instanceA = new DatabaseProcessSequence(DatabaseClient.create(poolA));
            ProcessSequence instanceB = new DatabaseProcessSequence(DatabaseClient.create(poolB));

            List<Long> numbers = Flux.merge(
                    Flux.range(0, PER_INSTANCE).flatMap(i -> instanceA.next(), 16),
                    Flux.range(0, PER_INSTANCE).flatMap(i -> instanceB.next(), 16))
                .collectList()
                .block();

            assertThat(numbers).hasSize(2 * PER_INSTANCE);
            assertThat(new HashSet<>(numbers)).hasSize(2 * PER_INSTANCE);
        } finally {
            poolA.dispose();
            poolB.dispose();
        }
    }

    private static ConnectionPool pool() {
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(DB.r2dbcUrl() + "?schema=" + schema())
            .mutate()
            .option(ConnectionFactoryOptions.USER, DB.username())
            .option(ConnectionFactoryOptions.PASSWORD, DB.password())
            .build();
        return new ConnectionPool(ConnectionPoolConfiguration.builder(ConnectionFactories.get(options))
            .maxSize(8)
            .build());
    }
}
