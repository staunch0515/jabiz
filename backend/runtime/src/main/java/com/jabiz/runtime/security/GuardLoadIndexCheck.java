package com.jabiz.runtime.security;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.security.EntryScopeResolver;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import com.jabiz.security.SignUpGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decision D80 item 5: a guard's load is read at every sign-in and refresh (or every sign-up), so it should be an
 * indexed equality. Warns of a load none of whose compared columns (the user field, the conditions' fields) leads an
 * index of its table: the read would scan the table. A warning, not an error: a small table reads fast without one.
 */
@Component
public class GuardLoadIndexCheck implements PlatformCheck {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final List<SignInLoad> loads = new ArrayList<>();
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry definitions;
    private final DatabaseClient db;

    public GuardLoadIndexCheck(ObjectProvider<SignInGuard> signIn, ObjectProvider<SignUpGuard> signUp,
        ObjectProvider<EntryScopeResolver> resolvers, DatasetRegistry datasets, EntityDefinitionRegistry definitions,
        DatabaseClient db) {
        signIn.orderedStream().forEach(guard -> collect(guard::loads));
        signUp.orderedStream().forEach(guard -> collect(guard::loads));
        resolvers.orderedStream().forEach(resolver -> collect(resolver::loads));
        this.datasets = datasets;
        this.definitions = definitions;
        this.db = db;
    }

    private void collect(java.util.function.Supplier<List<SignInLoad>> declared) {
        try {
            loads.addAll(declared.get());
        } catch (RuntimeException e) {
            // Reported by the guards' own check (SignInGuards, SignUpGuards).
        }
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Map<String, Set<String>> leading = new LinkedHashMap<>();
        Set<String> checked = new LinkedHashSet<>();
        for (SignInLoad load : loads) {
            if (!checked.add(load.name())) {
                continue;
            }
            Optional<DatasetDefinition> dataset = datasets.findById(load.dataset());
            Optional<EntityDefinition> def = dataset.flatMap(d -> definitions.find(d.targetEntityType()));
            if (def.isEmpty()) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            if (!load.global()) {
                fields.add(load.userField());
            }
            load.conditions().forEach(condition -> fields.add(condition.field()));
            Set<String> columns = new LinkedHashSet<>();
            for (String field : fields) {
                def.get().findField(field).ifPresent(f -> columns.add(f.physicalColumn().toLowerCase(Locale.ROOT)));
            }
            if (columns.isEmpty()) {
                continue;
            }
            String table = def.get().physicalTable;
            Set<String> first = leading.computeIfAbsent(table, this::leadingColumns);
            if (columns.stream().noneMatch(first::contains)) {
                problems.add(CheckProblem.warning(SessionChecks.CATEGORY, "load " + load.name(), "none of "
                    + columns + " leads an index of " + table + ": the guard's read scans the table at every "
                    + "sign-in (decision D80)"));
            }
        }
        return problems;
    }

    /** The first key column of each index of the table, lower case. */
    private Set<String> leadingColumns(String table) {
        List<String> found = db.sql("""
                select a.attname as column_name
                from pg_index i
                join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[0]
                where i.indrelid = to_regclass(:tableName)
                """)
            .bind("tableName", table)
            .map((row, meta) -> String.valueOf(row.get("column_name", String.class)).toLowerCase(Locale.ROOT))
            .all().collectList().block(TIMEOUT);
        return found == null ? Set.of() : new LinkedHashSet<>(found);
    }
}
