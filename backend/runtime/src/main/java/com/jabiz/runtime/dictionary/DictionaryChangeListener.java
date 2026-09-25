package com.jabiz.runtime.dictionary;

import io.r2dbc.postgresql.api.PostgresqlConnection;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Evicts cached dictionaries when the database announces a change on {@value DictionaryRegistry#CHANNEL}
 * (docs/design/02-metamodel.md section 5), so every instance sees edits of {@code sys_dict_item} without
 * restarting. The table's trigger sends the URN of the changed dictionary; anything else may send a URN, or
 * {@code *} for all dictionaries.
 *
 * <p>The listener holds one connection of the pool for as long as the application runs, and reconnects with
 * back-off when the connection is lost. Everything is evicted after a reconnect, since notifications sent
 * meanwhile are lost. Disable with {@code jabiz.dictionary.listen.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "jabiz.dictionary.listen", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class DictionaryChangeListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DictionaryChangeListener.class);

    private final ConnectionFactory connections;
    private final DictionaryRegistry registry;
    private volatile Disposable subscription;

    public DictionaryChangeListener(ConnectionFactory connections, DictionaryRegistry registry) {
        this.connections = connections;
        this.registry = registry;
    }

    @Override
    public void start() {
        subscription = Flux.defer(this::listenOnce)
            .doOnError(e -> log.warn("Dictionary change listener lost its connection: {}", e.toString()))
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(500)).maxBackoff(Duration.ofSeconds(30)))
            .repeatWhen(completed -> completed.delayElements(Duration.ofMillis(500)))
            .subscribe(registry::invalidate);
    }

    /** Notification payloads of one connection; starts with {@code *} since earlier ones may be missed. */
    private Flux<String> listenOnce() {
        return Flux.usingWhen(
            Mono.from(connections.create()),
            connection -> {
                PostgresqlConnection postgres = unwrap(connection);
                return postgres.createStatement("LISTEN " + DictionaryRegistry.CHANNEL).execute()
                    .flatMap(result -> result.getRowsUpdated())
                    .thenMany(Flux.just("*").concatWith(postgres.getNotifications()
                        .map(n -> n.getParameter() == null ? "*" : n.getParameter())));
            },
            Connection::close);
    }

    private static PostgresqlConnection unwrap(Connection connection) {
        Object current = connection;
        while (!(current instanceof PostgresqlConnection) && current instanceof Wrapped<?> wrapped) {
            current = wrapped.unwrap();
        }
        if (current instanceof PostgresqlConnection postgres) {
            return postgres;
        }
        throw new IllegalStateException("Dictionary change notifications need a PostgreSQL R2DBC connection, got "
            + connection.getClass().getName());
    }

    @Override
    public void stop() {
        Disposable current = subscription;
        if (current != null) {
            current.dispose();
        }
        subscription = null;
    }

    @Override
    public boolean isRunning() {
        return subscription != null && !subscription.isDisposed();
    }
}
