package com.jabiz.app.it.fixture;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.core.Binding;
import io.r2dbc.proxy.core.BoundValue;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Parameter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Records every SQL statement the application sends through R2DBC, for tests that assert what was (not) executed.
 * Active only in contexts started with {@code it.sql-log.enabled=true}.
 *
 * <p>Besides the plain list {@link #STATEMENTS}, {@link #ENTRIES} keeps each statement with its connection and the
 * transaction boundaries ({@code BEGIN}, {@code COMMIT}, {@code ROLLBACK}), so that a test can count the statements of
 * one transaction ({@link #transactions()}; the statement budget, ROADMAP phase 16p).
 */
@Configuration
@ConditionalOnProperty(name = "it.sql-log.enabled", havingValue = "true")
public class SqlStatementLog {

    public static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    /** A statement, or a transaction boundary, on a connection. */
    public record Entry(String connection, String sql) {}

    public static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    /**
     * A statement with what was bound to it, in the order of its parameters ({@code $1} first), so that a test can
     * explain a statement as it ran, binding the same (ROADMAP 17zz18): each as the driver got it (Spring's typed
     * {@link Parameter}, or a {@link NullValue}).
     */
    public record Execution(String sql, List<Object> bound) {

        /** The values bound, unwrapped: null for a bound null. */
        public List<Object> values() {
            List<Object> values = new ArrayList<>();
            for (Object value : bound) {
                values.add(value instanceof Parameter parameter ? parameter.getValue()
                    : value instanceof NullValue ? null : value);
            }
            return Collections.unmodifiableList(values);
        }
    }

    /** A null bound with {@code bindNull}, of this type. */
    public record NullValue(Class<?> type) {}

    public static final List<Execution> EXECUTIONS = new CopyOnWriteArrayList<>();

    public static final String BEGIN = "BEGIN";
    public static final String COMMIT = "COMMIT";
    public static final String ROLLBACK = "ROLLBACK";

    /** Forgets everything recorded so far. */
    public static void clear() {
        STATEMENTS.clear();
        ENTRIES.clear();
        EXECUTIONS.clear();
    }

    /**
     * The transactions recorded since the last {@link #clear()}, each the statements from {@code BEGIN} to its end,
     * the final {@code COMMIT} or {@code ROLLBACK} included (the {@code BEGIN} is not: it is no round trip of its own
     * in the counts of the plan). A transaction not ended yet is left out.
     */
    public static List<List<String>> transactions() {
        Map<String, List<String>> open = new LinkedHashMap<>();
        List<List<String>> done = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            switch (entry.sql()) {
                case BEGIN -> open.put(entry.connection(), new ArrayList<>());
                case COMMIT, ROLLBACK -> {
                    List<String> statements = open.remove(entry.connection());
                    if (statements != null) {
                        statements.add(entry.sql());
                        done.add(List.copyOf(statements));
                    }
                }
                default -> {
                    List<String> statements = open.get(entry.connection());
                    if (statements != null) {
                        statements.add(entry.sql());
                    }
                }
            }
        }
        return done;
    }

    /** Statements sent outside any transaction since the last {@link #clear()}. */
    public static List<String> outsideTransactions() {
        Map<String, Boolean> inside = new LinkedHashMap<>();
        List<String> outside = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            switch (entry.sql()) {
                case BEGIN -> inside.put(entry.connection(), true);
                case COMMIT, ROLLBACK -> inside.remove(entry.connection());
                default -> {
                    if (!inside.containsKey(entry.connection())) {
                        outside.add(entry.sql());
                    }
                }
            }
        }
        return outside;
    }

    /** An UPDATE of a table (not {@code FOR UPDATE}, not {@code ON CONFLICT … DO UPDATE}), a DELETE or a TRUNCATE. */
    private static final List<Pattern> MUTATIONS = List.of(
        Pattern.compile("(?is)\\bUPDATE\\s+(?:ONLY\\s+)?\"?([a-z_][a-z0-9_]*)\"?\\s+(?:AS\\s+)?(?:[a-z_][a-z0-9_]*\\s+)?SET\\b"),
        Pattern.compile("(?is)\\bDELETE\\s+FROM\\s+(?:ONLY\\s+)?\"?([a-z_][a-z0-9_]*)"),
        Pattern.compile("(?is)\\bTRUNCATE\\s+(?:TABLE\\s+)?(?:ONLY\\s+)?\"?([a-z_][a-z0-9_]*)"));

    /**
     * The tables a statement updates, deletes from or truncates, in lower case, wherever in the statement: also in a
     * data-modifying {@code WITH} part (one statement writes several tables since phase 16p, decision D46).
     */
    public static List<String> mutatedTables(String sql) {
        List<String> tables = new ArrayList<>();
        for (Pattern mutation : MUTATIONS) {
            var matcher = mutation.matcher(sql);
            while (matcher.find()) {
                tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private static final Pattern INSERT = Pattern.compile("(?is)INSERT\\s+INTO\\s+\"?([a-z_][a-z0-9_]*)");

    /** The tables a statement inserts into, in lower case, the parts of a {@code WITH} included. */
    public static List<String> insertedTables(String sql) {
        List<String> tables = new ArrayList<>();
        var matcher = INSERT.matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return tables;
    }

    /**
     * The statements recorded since the last {@link #clear()} that write {@code table} (lower case): insert into it,
     * update it, delete from it or truncate it, in any part of the statement.
     */
    public static List<String> writing(String table) {
        return STATEMENTS.stream()
            .filter(sql -> insertedTables(sql).contains(table) || mutatedTables(sql).contains(table))
            .toList();
    }

    @Bean
    static BeanPostProcessor recordingConnectionFactory() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ConnectionFactory factory) {
                    return ProxyConnectionFactory.builder(factory)
                        .onAfterQuery(execution -> execution.getQueries().forEach(query -> {
                            String sql = query.getQuery();
                            STATEMENTS.add(sql);
                            ENTRIES.add(new Entry(connection(execution.getConnectionInfo()), sql));
                            executions(query).forEach(EXECUTIONS::add);
                        }))
                        .onAfterMethod(method -> {
                            String boundary = switch (method.getMethod().getName()) {
                                case "beginTransaction" -> BEGIN;
                                case "commitTransaction" -> COMMIT;
                                case "rollbackTransaction" -> ROLLBACK;
                                default -> null;
                            };
                            if (boundary != null && method.getConnectionInfo() != null) {
                                ENTRIES.add(new Entry(connection(method.getConnectionInfo()), boundary));
                            }
                        })
                        .build();
                }
                return bean;
            }
        };
    }

    /** One execution per set of bindings (a batch has several), or one without values. */
    private static List<Execution> executions(QueryInfo query) {
        if (query.getBindingsList().isEmpty()) {
            return List.of(new Execution(query.getQuery(), List.of()));
        }
        return query.getBindingsList().stream().map(bindings -> {
            List<Object> values = new ArrayList<>();
            for (Binding binding : bindings.getIndexBindings()) {
                BoundValue bound = binding.getBoundValue();
                values.add(bound.isNull() ? new NullValue(bound.getNullType()) : bound.getValue());
            }
            return new Execution(query.getQuery(), Collections.unmodifiableList(values));
        }).toList();
    }

    private static String connection(io.r2dbc.proxy.core.ConnectionInfo info) {
        return info == null ? "?" : info.getConnectionId();
    }

    /**
     * The plan of {@code ran} as the platform sent it, explained again with what it bound, as PostgreSQL's JSON (the
     * top node, "Plan"): {@code options} go into {@code EXPLAIN (…)} besides {@code FORMAT JSON}; {@code settings} are
     * set for it alone ({@code SET LOCAL} in a transaction that is always rolled back, so the pooled connection keeps
     * its settings). Run through {@code connections}, the statement is recorded here too.
     */
    public static String explain(ConnectionFactory connections, Execution ran, String options,
        Map<String, String> settings) {
        java.time.Duration timeout = java.time.Duration.ofSeconds(60);
        io.r2dbc.spi.Connection connection = reactor.core.publisher.Mono.from(connections.create()).block(timeout);
        try {
            reactor.core.publisher.Mono.from(connection.beginTransaction()).block(timeout);
            try {
                for (Map.Entry<String, String> setting : settings.entrySet()) {
                    reactor.core.publisher.Flux.from(connection.createStatement("SET LOCAL " + setting.getKey() + " = "
                        + setting.getValue()).execute()).flatMap(io.r2dbc.spi.Result::getRowsUpdated).blockLast(timeout);
                }
                io.r2dbc.spi.Statement explain = connection.createStatement("EXPLAIN ("
                    + (options.isBlank() ? "" : options + ", ") + "FORMAT JSON) " + ran.sql());
                for (int i = 0; i < ran.bound().size(); i++) {
                    Object bound = ran.bound().get(i);
                    if (bound instanceof NullValue nothing) {
                        explain.bindNull(i, nothing.type());
                    } else {
                        explain.bind(i, bound);
                    }
                }
                return reactor.core.publisher.Flux.from(explain.execute())
                    .flatMap(result -> result.map((row, meta) -> row.get(0, String.class))).blockFirst(timeout);
            } finally {
                reactor.core.publisher.Mono.from(connection.rollbackTransaction()).block(timeout);
            }
        } finally {
            reactor.core.publisher.Mono.from(connection.close()).block(timeout);
        }
    }
}
