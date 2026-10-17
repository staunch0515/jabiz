package com.jabiz.runtime.retention;

import com.jabiz.retention.RetentionPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * The retention policies of the application (beans), the end of its fiscal year ({@code jabiz.fiscal-year-end}, the
 * month number; default 12) and the zone its days are counted in ({@code jabiz.fiscal-year-zone}; default UTC),
 * docs/design/21-audit-retention.md section 3 and decision D48 item 2. A time field's value falls on the day, and so
 * in the fiscal year, that it is in that zone; "today" is the day it is there now.
 */
@Component
public class RetentionPolicies {

    private final List<RetentionPolicy> policies;
    private final Month fiscalYearEnd;
    private final ZoneId zone;
    /** Why the configured zone is not usable (the startup check reports it); null when it is. */
    private final String zoneProblem;

    public RetentionPolicies(ObjectProvider<RetentionPolicy> policies,
        @Value("${jabiz.fiscal-year-end:12}") int fiscalYearEnd,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.policies = policies.orderedStream().toList();
        this.fiscalYearEnd = Month.of(fiscalYearEnd);
        ZoneId parsed;
        String problem = null;
        try {
            parsed = ZoneId.of(zone.strip());
        } catch (DateTimeException e) {
            // Counted in UTC until the startup check stops the application: never in a zone nobody chose.
            parsed = ZoneOffset.UTC;
            problem = "jabiz.fiscal-year-zone '" + zone + "' is not a time zone (" + e.getMessage() + ")";
        }
        this.zone = parsed;
        this.zoneProblem = problem;
    }

    public List<RetentionPolicy> all() {
        return policies;
    }

    /** The policy of the entity; the first when several are declared (the startup check reports that). */
    public Optional<RetentionPolicy> of(String entity) {
        return policies.stream().filter(policy -> policy.entity().equals(entity)).findFirst();
    }

    public Month fiscalYearEnd() {
        return fiscalYearEnd;
    }

    /** The zone the days of retention are counted in. */
    public ZoneId zone() {
        return zone;
    }

    /** Why {@code jabiz.fiscal-year-zone} cannot be used; empty when it can. */
    public Optional<String> zoneProblem() {
        return Optional.ofNullable(zoneProblem);
    }

    /** The day it is now in {@link #zone()}. */
    public LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /** When {@code day} begins in {@link #zone()}. */
    public Instant startOf(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    /**
     * The day of a date or time field's value in {@link #zone()}: a date as it is, an instant on the day it falls
     * on there; null when there is none (or it cannot be read).
     */
    public LocalDate dateOf(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate date -> date;
            case Instant instant -> LocalDate.ofInstant(instant, zone);
            case OffsetDateTime time -> LocalDate.ofInstant(time.toInstant(), zone);
            case ZonedDateTime time -> LocalDate.ofInstant(time.toInstant(), zone);
            case LocalDateTime time -> time.toLocalDate();
            default -> parse(String.valueOf(value));
        };
    }

    private LocalDate parse(String text) {
        try {
            return LocalDate.ofInstant(Instant.parse(text), zone);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
