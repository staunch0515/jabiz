package com.jabiz.runtime.entity;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.UniqueConstraint;
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
 * physicalTable, and that every declared unique constraint is backed by a unique index of the same name
 * over exactly its columns (docs/design/02-metamodel.md section 6). It prevents silent drift between the Java metamodel and the actual schema
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
            Flux<String> columns = Flux.fromIterable(def.fields.values())
                .filter(field -> !actual.contains(field.physicalColumn().toLowerCase(Locale.ROOT)))
                .map(field -> "Entity " + def.name + ": field " + field.name()
                              + " -> missing column " + field.physicalColumn()
                              + " in table " + def.physicalTable);
            return columns.concatWith(Flux.fromIterable(def.uniqueConstraints)
                .concatMap(unique -> checkUniqueIndex(def, unique)));
        });
    }

    private Mono<String> checkUniqueIndex(EntityDefinition def, UniqueConstraint unique) {
        Set<String> expected = new HashSet<>();
        unique.fields().forEach(f -> expected.add(def.physicalColumn(f).toLowerCase(Locale.ROOT)));
        String label = "Entity " + def.name + ": unique constraint " + unique.name();
        return fetchUniqueIndexColumns(def.physicalTable, unique.name().toLowerCase(Locale.ROOT))
            .flatMap(actual -> {
                if (actual.isEmpty()) {
                    return Mono.just(label + " -> no unique index named " + unique.name()
                                     + " on table " + def.physicalTable);
                }
                if (!actual.equals(expected)) {
                    return Mono.just(label + " -> index covers " + actual + " instead of " + expected);
                }
                return Mono.empty();
            });
    }

    /** Columns of a plain (non-partial, non-expression) unique index of the table; empty if there is none. */
    private Mono<Set<String>> fetchUniqueIndexColumns(String qualifiedTable, String indexName) {
        int dot = qualifiedTable.indexOf('.');
        String schemaCondition = dot < 0 ? "current_schema()" : ":schemaName";
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                select a.attname as column_name
                from pg_index i
                join pg_class ic on ic.oid = i.indexrelid
                join pg_class t on t.oid = i.indrelid
                join pg_namespace n on n.oid = t.relnamespace
                join pg_attribute a on a.attrelid = t.oid and a.attnum = any(i.indkey)
                where i.indisunique and i.indpred is null and not (0 = any(i.indkey))
                  and ic.relname = :indexName and t.relname = :tableName and n.nspname = """ + schemaCondition)
            .bind("indexName", indexName)
            .bind("tableName", (dot < 0 ? qualifiedTable : qualifiedTable.substring(dot + 1)).toLowerCase(Locale.ROOT));
        if (dot >= 0) {
            spec = spec.bind("schemaName", qualifiedTable.substring(0, dot).toLowerCase(Locale.ROOT));
        }
        return spec
            .map((row, meta) -> row.get("column_name", String.class))
            .all()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(HashSet::new, Set::add);
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
