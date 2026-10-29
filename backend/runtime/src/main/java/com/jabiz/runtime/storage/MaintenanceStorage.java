package com.jabiz.runtime.storage;

import com.jabiz.query.BoundValue;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The maintenance connection of the controlled purge (decision D5 item 2, decision D62 item 3): another database login,
 * a member of the role {@code jabiz_maintenance}, which the append-only guard lets update rows while the transaction
 * says {@code jabiz.maintenance_mode = purge}. The application's own login is never a member (D5 item 4), so only the
 * purge, through this connection, can overwrite append-only rows; each of its transactions is independent of the
 * operation that runs it and starts from an empty context (the operation's held writes are not this connection's).
 */
public final class MaintenanceStorage {

    private final StorageEngine engine;

    /**
     * What is wrong with a maintenance connection's URL, or null when nothing is: it is an R2DBC URL
     * ({@code r2dbc:postgresql://host:port/database}, as {@code spring.r2dbc.url}), not the JDBC one Flyway takes. Only
     * the scheme is named, never the URL, which may carry credentials.
     */
    public static String urlProblem(String url) {
        String value = url == null ? "" : url.strip();
        if (value.startsWith("r2dbc:")) {
            return null;
        }
        String given = value.startsWith("jdbc:") ? "a JDBC URL (jdbc:…)" : value.isEmpty() ? "empty"
            : "not an R2DBC URL";
        return "must be an R2DBC URL like r2dbc:postgresql://host:5432/database (as spring.r2dbc.url,"
            + " SPRING_R2DBC_URL), but is " + given;
    }

    /**
     * @param url      R2DBC URL of the application's database and schema (as {@code spring.r2dbc.url}); see
     *                 {@link #urlProblem}
     * @param username the maintenance login
     * @param password its password
     */
    public static MaintenanceStorage connect(String url, String username, String password) {
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(Objects.requireNonNull(url, "url"))
            .mutate()
            .option(ConnectionFactoryOptions.USER, Objects.requireNonNull(username, "username"))
            .option(ConnectionFactoryOptions.PASSWORD, Objects.requireNonNull(password, "password"))
            .build();
        ConnectionFactory connections = ConnectionFactories.get(options);
        R2dbcTransactionManager manager = new R2dbcTransactionManager(connections);
        return new MaintenanceStorage(new R2dbcStorageEngine(DatabaseClient.create(connections),
            TransactionalOperator.create(manager), TransactionalOperator.create(manager,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW))));
    }

    MaintenanceStorage(StorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Runs {@code work} in a transaction of its own on the maintenance connection, in purge mode: the append-only guard
     * lets this transaction update and delete. Commits when {@code work} completes, rolls back when it fails.
     */
    public <T> Mono<T> inPurgeTransaction(Function<StorageEngine, Mono<T>> work) {
        Mono<T> body = engine.select("SELECT set_config('jabiz.maintenance_mode', 'purge', true) AS mode",
                Map.<String, BoundValue>of())
            .then(Mono.defer(() -> work.apply(engine)));
        return engine.inTransaction(body).contextWrite(context -> Context.empty());
    }
}
