package com.jabiz.quizbuks.stats;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The analytics' days are those of {@code jabiz.fiscal-year-zone} (docs/quizbuks/plans/Q9e-analytics.md, D-Q9e-3),
 * grouped in SQL with {@code AT TIME ZONE}. PostgreSQL reads an offset written as a time zone the POSIX way, east of
 * Greenwich negative ({@code '+09:00'} and {@code 'UTC+9'} are nine hours <em>behind</em>), the reverse of Java: only
 * a region ({@code Asia/Tokyo}) or {@code UTC} means the same in both, so anything else fails the startup (category
 * {@code QUIZBUKS}, at startup and in {@code platformCheck}). An invalid name is the platform's own error
 * ({@code RETENTION}).
 */
@Component
class AnalyticsZoneCheck implements PlatformCheck {

    static final String SETTING = "jabiz.fiscal-year-zone";

    /** A region: Area/Location (letters, digits, {@code _ + -} in the parts), as the tz database names them. */
    private static final Pattern REGION = Pattern.compile("[A-Za-z]+(/[A-Za-z0-9_+\\-]+)+");

    private final String zone;

    AnalyticsZoneCheck(@Value("${" + SETTING + ":UTC}") String zone) {
        this.zone = zone;
    }

    @Override
    public List<CheckProblem> check() {
        return problems(zone);
    }

    /** Why the zone cannot be the analytics' days; empty when it can. */
    static List<CheckProblem> problems(String zone) {
        ZoneId id;
        try {
            id = ZoneId.of(zone);
        } catch (DateTimeException e) {
            return List.of();
        }
        boolean region = "UTC".equals(zone) || (!(id instanceof ZoneOffset) && REGION.matcher(zone).matches()
            && !zone.startsWith("Etc/"));
        if (region) {
            return List.of();
        }
        return List.of(CheckProblem.error(CountryCatalog.CATEGORY, SETTING, SETTING + " '" + zone + "' is an offset, "
            + "not a region: PostgreSQL reads offsets the other way round, and the analytics' days would be wrong; "
            + "name a region such as Asia/Tokyo, or UTC"));
    }
}
