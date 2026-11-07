package com.jabiz.quizbuks.usage;

import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The meters are listed in {@link UsageMeters} only (F-C2): the snapshot's template must give exactly those, no more
 * and no fewer, or the start fails (category {@code QUIZBUKS}, at startup and in {@code platformCheck}). A new meter
 * is added to both at once.
 */
@Component
class UsageMetersCheck implements PlatformCheck {

    /** A meter's code as the template writes it: a quoted {@code area.name}. */
    private static final Pattern METER = Pattern.compile("'([a-z]+\\.[a-z][a-z-]*)'");

    private final SqlTemplateRegistry templates;

    UsageMetersCheck(SqlTemplateRegistry templates) {
        this.templates = templates;
    }

    @Override
    public List<CheckProblem> check() {
        return problems(templates.find(QbUsage.MEASURE_TEMPLATE).map(AdvancedQueryDefinition::sqlTemplate)
            .orElse(null));
    }

    /** What is wrong with the snapshot template's meters; empty when they are those of {@link UsageMeters#ALL}. */
    static List<CheckProblem> problems(String sql) {
        if (sql == null) {
            return List.of(CheckProblem.error(CountryCatalog.CATEGORY, QbUsage.MEASURE_TEMPLATE,
                "The template " + QbUsage.MEASURE_TEMPLATE + " of the usage snapshot is missing"));
        }
        Set<String> written = meters(sql);
        Set<String> listed = new LinkedHashSet<>(UsageMeters.codes());
        if (written.equals(listed)) {
            return List.of();
        }
        Set<String> missing = new LinkedHashSet<>(listed);
        missing.removeAll(written);
        Set<String> unknown = new LinkedHashSet<>(written);
        unknown.removeAll(listed);
        return List.of(CheckProblem.error(CountryCatalog.CATEGORY, QbUsage.MEASURE_TEMPLATE,
            "The meters of " + QbUsage.MEASURE_TEMPLATE + " differ from UsageMeters.ALL: missing " + missing
                + ", not listed " + unknown + "; change both together"));
    }

    /** The meter codes a template's SQL names (its quoted {@code area.name} literals), comments left out. */
    static Set<String> meters(String sql) {
        String code = sql.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--.*$", "");
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = METER.matcher(code);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
