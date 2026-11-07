package com.jabiz.quizbuks.usage;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of the daily snapshot (F-C2), pure: what a run writes to the day's one row and its stored form, which it deletes past the retention, whether
 * the sponsor's storage just went above the free amount, and the job's time of day.
 */
public final class UsageRules {

    /** The time of day of the snapshot: {@code HH:mm}, 24 hours. */
    private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):([0-5][0-9])");

    /** The day's row already written: its id, version and meters. */
    public record Row(Object id, Long version, Map<String, BigDecimal> meters) {
        public Row {
            meters = Map.copyOf(meters);
        }
    }

    /** A row past the retention, to delete. */
    public record Delete(Object id, Long version) {}

    /**
     * What one run writes for one sponsor and day.
     *
     * @param meters  every meter of the day, in the order of {@link UsageMeters#ALL}
     * @param write   whether the day's row is to be inserted (no row yet) or updated (its values changed)
     * @param deletes the sponsor's rows older than the retention
     * @param crossed whether the storage went above the free amount with this run
     */
    public record Plan(Map<String, BigDecimal> meters, boolean write, List<Delete> deletes, boolean crossed) {
        public Plan {
            meters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(meters));
            deletes = List.copyOf(deletes);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};

    private UsageRules() {}

    /**
     * Plans a run: the day's one row holds every meter measured; a row there already is overwritten only when a value
     * changed.
     *
     * @param measured        the meters now, by code: every meter of {@link UsageMeters#ALL}
     * @param today           the day's row already written, or null
     * @param previousStorage {@code storage.bytes} of the latest day before this one, or null when there is none
     * @param freeBytes       the free amount, or null when it is not set (then nothing is noticed)
     * @param old             the sponsor's rows past the retention
     * @throws IllegalStateException when the measure lacks a meter (the template and the list differ: the start
     *                               would have failed; the item fails and is retried)
     */
    public static Plan plan(Map<String, BigDecimal> measured, Row today, BigDecimal previousStorage,
        BigDecimal freeBytes, List<Delete> old) {
        Map<String, BigDecimal> meters = new LinkedHashMap<>();
        for (String meter : UsageMeters.codes()) {
            BigDecimal value = measured.get(meter);
            if (value == null) {
                throw new IllegalStateException("The usage measure gave no value of the meter " + meter);
            }
            meters.put(meter, value);
        }
        boolean write = today == null || !same(today.meters(), meters);
        return new Plan(meters, write, old, crossed(previousStorage, meters.get(UsageMeters.STORAGE_BYTES), freeBytes));
    }

    private static boolean same(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        return a.entrySet().stream().allMatch(e -> e.getValue().compareTo(b.get(e.getKey())) == 0);
    }

    /** The stored form of a day's meters: a JSON object {meter: value}, whole numbers, in the given order. */
    public static String json(Map<String, BigDecimal> meters) {
        Map<String, Object> out = new LinkedHashMap<>();
        meters.forEach((meter, value) -> out.put(meter, value.toBigInteger()));
        return JSON.writeValueAsString(out);
    }

    /** A stored day's meters (unknown keys kept, values not numbers left out); empty for nothing or bad JSON. */
    public static Map<String, BigDecimal> meters(Object stored) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (stored == null || String.valueOf(stored).isBlank()) {
            return out;
        }
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(String.valueOf(stored), OBJECT);
        } catch (RuntimeException e) {
            return out;
        }
        parsed.forEach((meter, value) -> {
            if (value instanceof Number number) {
                out.put(meter, new BigDecimal(number.toString()));
            }
        });
        return out;
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
