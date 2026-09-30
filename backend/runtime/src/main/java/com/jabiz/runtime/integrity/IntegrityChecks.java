package com.jabiz.runtime.integrity;

import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup check of the integrity seals (docs/design/21-audit-retention.md section 2.1): every append-only table can
 * be sealed - it has a primary key, and its name and key columns are plain identifiers. Blocks on the reactive client
 * on the startup thread, as the other schema checks do.
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
        return List.copyOf(problems);
    }

    private static List<String> concat(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }
}
