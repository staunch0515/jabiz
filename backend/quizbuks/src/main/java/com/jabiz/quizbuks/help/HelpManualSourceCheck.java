package com.jabiz.quizbuks.help;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The usage manuals the repository has are the ones loaded into the drafts (F-C10b, startup check {@value #LOCATION},
 * a warning): a feature's pull request changes the repository's manual ({@link HelpManualDrafts}); after it is
 * deployed this warns until a content administrator loads it into the draft ({@code QB_HELP_MANUAL_LOAD}, or records
 * that the draft holds it already) and publishes it. An entry without a manual (before {@code QB_SETUP}) is warned of
 * too. Reads the database with Flyway's settings, as {@link HelpMaterialsCheck} does; without one it is skipped.
 */
@Component
public class HelpManualSourceCheck implements PlatformCheck {

    static final String LOCATION = "QB_HELP_MANUAL_SOURCE";

    private final Environment environment;

    public HelpManualSourceCheck(Environment environment) {
        this.environment = environment;
    }

    @Override
    public List<CheckProblem> check() {
        Map<String, String> loaded = new HashMap<>();
        try (Connection connection = HelpMaterialsCheck.connect(environment)) {
            if (connection == null) {
                return List.of();
            }
            String sql = "SELECT DISTINCT ON (manual_id) entry, source_hash, is_deleted FROM qb_help_manual_version "
                + "ORDER BY manual_id, effect_start_time DESC, version_no DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!rows.getBoolean(3)) {
                        loaded.put(rows.getString(1), rows.getString(2) == null ? "" : rows.getString(2));
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION,
                "the AI help's manuals could not be read: " + e.getMessage()));
        }
        Map<String, String> repository = new HashMap<>();
        for (String entry : QbHelp.ENTRIES) {
            repository.put(entry, HelpManualDrafts.hash(HelpManualDrafts.of(entry)));
        }
        return problems(loaded, repository);
    }

    /** A warning for each entry whose draft does not hold the repository's manual. */
    static List<CheckProblem> problems(Map<String, String> loaded, Map<String, String> repository) {
        List<CheckProblem> problems = new ArrayList<>();
        for (String entry : QbHelp.ENTRIES) {
            String draft = loaded.get(entry);
            if (draft == null) {
                problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION + " " + entry, "the AI help "
                    + "has no usage manual of " + entry + ": run QB_SETUP"));
            } else if (!draft.equals(repository.get(entry))) {
                problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION + " " + entry, "the repository's "
                    + "usage manual of " + entry + " is not the one last loaded into its draft: load it ("
                    + HelpMaterialsProcesses.LOAD + "), check it and publish it (" + HelpMaterialsProcesses.PUBLISH
                    + ")"));
            }
        }
        return List.copyOf(problems);
    }
}
