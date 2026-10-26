package com.jabiz.runtime.integrity;

import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Startup check of the integrity seals (docs/design/21-audit-retention.md section 2.1): every append-only table can
 * be sealed - it has a primary key, and its name and key columns are plain identifiers - and every column a block
 * recorded is still there (decision D58). A sealed column a migration renamed or dropped makes verifying report every
 * row sealed with it as MODIFIED, a dropped table its rows as MISSING; the migration has run by now, so refusing to
 * start would mend nothing: a warning, which names the cause before the daily verification reports thousands of rows.
 * Only names are recorded, so a changed type is not seen here (the migrations' test, {@code SealedColumnsHistory},
 * sees it). Blocks on the reactive client on the startup thread, as the other schema checks do.
 */
@Component
public class IntegrityChecks implements PlatformCheck {

    public static final String CATEGORY = "INTEGRITY";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final IntegrityStore store;

    public IntegrityChecks(IntegrityStore store) {
        this.store = store;
    }

    @Override
    public List<CheckProblem> check() {
        List<IntegrityStore.Table> tables = store.appendOnlyTables(store.engine()).collectList().block(TIMEOUT);
        List<CheckProblem> problems = new ArrayList<>();
        for (IntegrityStore.Table table : tables == null ? List.<IntegrityStore.Table>of() : tables) {
            String location = "Table " + table.name();
            if (table.keyColumns().isEmpty()) {
                problems.add(CheckProblem.error(CATEGORY, location,
                    "is append-only but has no primary key, so its rows cannot be sealed"));
            }
            for (String name : concat(table.name(), table.keyColumns())) {
                try {
                    SqlIdentifiers.require(name);
                } catch (IllegalArgumentException e) {
                    problems.add(CheckProblem.error(CATEGORY, location, "'" + name + "' is not a plain identifier"));
                }
            }
        }
        problems.addAll(sealedColumnProblems());
        return List.copyOf(problems);
    }

    /** The columns the blocks recorded that the schema no longer has, as warnings by table. */
    private List<CheckProblem> sealedColumnProblems() {
        List<IntegrityStore.SealedColumn> sealed = store.sealedColumns(store.engine()).collectList().block(TIMEOUT);
        if (sealed == null || sealed.isEmpty()) {
            return List.of();
        }
        Map<String, List<IntegrityStore.SealedColumn>> byTable = new TreeMap<>();
        sealed.forEach(column -> byTable.computeIfAbsent(column.table(), t -> new ArrayList<>()).add(column));
        Map<String, Set<String>> current = store.currentColumns(store.engine(), byTable.keySet()).block(TIMEOUT);
        List<CheckProblem> problems = new ArrayList<>();
        byTable.forEach((table, columns) -> {
            Set<String> now = current == null ? null : current.get(table);
            long first = columns.stream().mapToLong(IntegrityStore.SealedColumn::firstSeal).min().orElseThrow();
            if (now == null) {
                problems.add(CheckProblem.warning(CATEGORY, "Table " + table, "is gone, but blocks from " + first
                    + " on sealed its rows: verifying reports them as MISSING"));
                return;
            }
            List<IntegrityStore.SealedColumn> gone = columns.stream().filter(c -> !now.contains(c.column())).toList();
            if (!gone.isEmpty()) {
                problems.add(CheckProblem.warning(CATEGORY, "Table " + table, "has lost sealed columns "
                    + gone.stream().map(c -> c.column() + " (sealed from block " + c.firstSeal() + ")")
                        .collect(Collectors.joining(", "))
                    + ": verifying reports every row sealed with them as MODIFIED. A migration renamed or dropped"
                    + " them; sealed columns may only be added (docs/design/21-audit-retention.md section 2.1), so a"
                    + " migration giving them back mends it"));
            }
        });
        return problems;
    }

    private static List<String> concat(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }
}
