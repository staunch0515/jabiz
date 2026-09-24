package com.jabiz.entity;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Startup check that every physicalColumn declared in an EntityDefinition exists in its
 * physicalTable. It prevents silent drift between the Java metamodel and the actual schema
 * (which other systems or manual DDL may change): without it, drift only surfaces when a
 * request happens to touch the affected field.
 *
 * The check runs after all singletons are created and before the web server starts
 * accepting traffic. It blocks on the reactive client, which is acceptable here because
 * it executes on the startup thread and never on the request path. All inconsistencies
 * are collected and reported together.
 *
 * Disable with {@code jabiz.metamodel.consistency-check.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "jabiz.metamodel.consistency-check", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class MetaModelConsistencyChecker implements SmartInitializingSingleton {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(30);

    private final DatabaseClient db;
    private final EntityDefinitionRegistry registry;

    public MetaModelConsistencyChecker(DatabaseClient db, EntityDefinitionRegistry registry) {
        this.db = db;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = Flux.fromIterable(registry.all())
            .concatMap(this::checkEntity)
            .collectList()
            .block(CHECK_TIMEOUT);

        if (problems != null && !problems.isEmpty()) {
            throw new MetaModelInconsistencyException(
                "Metamodel does not match the database schema:\n - " + String.join("\n - ", problems));
        }
    }

    private Flux<String> checkEntity(EntityDefinition def) {
        return fetchColumns(def.physicalTable).flatMapMany(actual -> {
            if (actual.isEmpty()) {
                return Flux.just("Entity " + def.name + ": table " + def.physicalTable
                                 + " was not found or has no columns");
            }
            return Flux.fromIterable(def.fields.values())
                .filter(field -> !actual.contains(field.physicalColumn().toLowerCase(Locale.ROOT)))
                .map(field -> "Entity " + def.name + ": field " + field.name()
                              + " -> missing column " + field.physicalColumn()
                              + " in table " + def.physicalTable);
        });
    }

    private Mono<Set<String>> fetchColumns(String qualifiedTable) {
        int dot = qualifiedTable.indexOf('.');
        DatabaseClient.GenericExecuteSpec spec;
        if (dot < 0) {
            spec = db.sql("""
                    select column_name
                    from information_schema.columns
                    where table_schema = current_schema()
                      and table_name = :tableName
                    """)
                .bind("tableName", qualifiedTable.toLowerCase(Locale.ROOT));
        } else {
            spec = db.sql("""
                    select column_name
                    from information_schema.columns
                    where table_schema = :schemaName
                      and table_name = :tableName
                    """)
                .bind("schemaName", qualifiedTable.substring(0, dot).toLowerCase(Locale.ROOT))
                .bind("tableName", qualifiedTable.substring(dot + 1).toLowerCase(Locale.ROOT));
        }
        return spec
            .map((row, meta) -> row.get("column_name", String.class))
            .all()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(HashSet::new, Set::add);
    }

    public static class MetaModelInconsistencyException extends IllegalStateException {
        public MetaModelInconsistencyException(String message) {
            super(message);
        }
    }
}
