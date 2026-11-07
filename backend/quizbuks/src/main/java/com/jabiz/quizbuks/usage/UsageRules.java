package com.jabiz.quizbuks.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of the daily snapshot (F-C2), pure: which rows a run writes, which it deletes past the retention, whether
 * the sponsor's storage just went above the free amount, and the job's time of day.
 */
public final class UsageRules {

    /** The time of day of the snapshot: {@code HH:mm}, 24 hours. */
    private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):([0-5][0-9])");

    /** A row of the day already written: its id, version and value. */
    public record Row(Object id, Long version, BigDecimal value) {
        public Row {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /** An existing row to set to a new value. */
    public record Update(Object id, Long version, BigDecimal value) {}

    /** An existing row past the retention, to delete. */
    public record Delete(Object id, Long version) {}

    /**
     * What one run writes for one sponsor and day.
     *
     * @param inserts the meters the day has no row of yet, with their values
     * @param updates the day's rows whose value changed
     * @param deletes the sponsor's rows older than the retention
     * @param crossed whether the storage went above the free amount with this run
     */
    public record Plan(Map<String, BigDecimal> inserts, List<Update> updates, List<Delete> deletes, boolean crossed) {
        public Plan {
            inserts = Map.copyOf(inserts);
            updates = List.copyOf(updates);
            deletes = List.copyOf(deletes);
        }
    }

    private UsageRules() {}

    /**
     * Plans a run: every meter measured is written to the day, a row there already is overwritten (only when its value
     * changed), a meter missing from the measure is left as it was.
     *
     * @param measured        the meters now, by code
     * @param today           the day's rows already written, by meter
     * @param previousStorage {@code storage.bytes} of the latest day before this one, or null when there is none
     * @param freeBytes       the free amount, or null when it is not set (then nothing is noticed)
     * @param old             the sponsor's rows past the retention
     */
    public static Plan plan(Map<String, BigDecimal> measured, Map<String, Row> today, BigDecimal previousStorage,
        BigDecimal freeBytes, List<Delete> old) {
        Map<String, BigDecimal> inserts = new LinkedHashMap<>();
        List<Update> updates = new ArrayList<>();
        for (String meter : UsageMeters.codes()) {
            BigDecimal value = measured.get(meter);
            if (value == null) {
                continue;
            }
            Row row = today.get(meter);
            if (row == null) {
                inserts.put(meter, value);
            } else if (row.value().compareTo(value) != 0) {
                updates.add(new Update(row.id(), row.version(), value));
            }
        }
        return new Plan(inserts, updates, old,
            crossed(previousStorage, measured.get(UsageMeters.STORAGE_BYTES), freeBytes));
    }

    /**
     * Whether the storage is above the free amount now and was not before: the previous snapshot at or below it, or
     * none. Staying above is the same crossing (no second notice); going below and above again is a new one.
     */
    public static boolean crossed(BigDecimal previous, BigDecimal now, BigDecimal freeBytes) {
        if (freeBytes == null || now == null || now.compareTo(freeBytes) <= 0) {
            return false;
        }
        return previous == null || previous.compareTo(freeBytes) <= 0;
    }

    /** Whether a storage value is above the free amount (not set: never). */
    public static boolean over(BigDecimal storage, BigDecimal freeBytes) {
        return freeBytes != null && storage != null && storage.compareTo(freeBytes) > 0;
    }

    /** The first day kept: rows of earlier days are deleted. */
    public static LocalDate firstKept(LocalDate day, int retentionDays) {
        return day.minusDays(Math.max(retentionDays, 1) - 1L);
    }

    /** The day a scheduled time is of, in the deployment's zone. */
    public static LocalDate dayOf(Instant at, ZoneId zone) {
        return at.atZone(zone).toLocalDate();
    }

    /** The month of a day ({@code YYYY-MM}), as {@code QbAiUsage.month} writes it. */
    public static String month(LocalDate day) {
        return day.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    /** Whether a setting is a time of day the job can run at ({@code HH:mm}). */
    public static boolean validTime(String time) {
        return time != null && TIME.matcher(time.strip()).matches();
    }

    /**
     * The six-field cron of a daily run at {@code time} ({@code HH:mm}).
     *
     * @throws IllegalArgumentException when it is not a time of day
     */
    public static String cron(String time) {
        Matcher matcher = TIME.matcher(time == null ? "" : time.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(QbUsage.SNAPSHOT_TIME_SETTING + " must be a time of day HH:mm (00:00 "
                + "to 23:59): " + time);
        }
        return "0 " + Integer.parseInt(matcher.group(2)) + " " + Integer.parseInt(matcher.group(1)) + " * * *";
    }

    /** Bytes as megabytes with one decimal (1 MB = 1,048,576 bytes), for the texts of the notice. */
    public static String megabytes(BigDecimal bytes) {
        if (bytes == null) {
            return "0.0";
        }
        return bytes.divide(BigDecimal.valueOf(1_048_576L), 1, RoundingMode.HALF_UP).toPlainString();
    }
}
