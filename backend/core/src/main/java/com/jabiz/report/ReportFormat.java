package com.jabiz.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * How values read in a printed report (docs/design/19-reports.md section 4), following the application's region like
 * the back office does (docs/design/12-frontend.md section 10): amounts with grouping, a fixed number of decimals and
 * negatives in parentheses; times and dates in the region's form, or {@code 2026-01-31 14:05:09} and {@code 2026-01-31}
 * without a region.
 */
public final class ReportFormat {

    private static final DateTimeFormatter NEUTRAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter NEUTRAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Locale numbers;
    private final DateTimeFormatter times;
    private final DateTimeFormatter dates;
    private final ZoneId zone;

    /**
     * @param region the application's region such as {@code en-US}, or null
     * @param zone   the zone times are shown in
     */
    public ReportFormat(String region, ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
        Locale locale = region == null || region.isBlank() ? null : Locale.forLanguageTag(region.trim());
        this.numbers = locale == null ? Locale.ENGLISH : locale;
        this.times = locale == null ? NEUTRAL
            : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale);
        this.dates = locale == null ? NEUTRAL_DATE
            : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
    }

    public ZoneId zone() {
        return zone;
    }

    /** An amount such as {@code 1,234.50} or {@code (1,234.50)}, rounded half away from zero to {@code scale}. */
    public String amount(BigDecimal value, int scale) {
        DecimalFormat format = new DecimalFormat("#,##0" + (scale > 0 ? "." + "0".repeat(scale) : ""),
            DecimalFormatSymbols.getInstance(numbers));
        format.setRoundingMode(RoundingMode.HALF_UP);
        String text = format.format(value.abs());
        return value.signum() < 0 ? "(" + text + ")" : text;
    }

    public String dateTime(Instant time) {
        return times.format(time.atZone(zone));
    }

    /** A calendar date in the region's form, or {@code 2026-01-31} without a region; no zone applies. */
    public String date(LocalDate date) {
        return dates.format(date);
    }

    /** A cell's text. */
    public String value(ReportColumn column, Object value) {
        return switch (value) {
            case null -> "";
            case BigDecimal d when column.numeric() -> amount(d, column.scale());
            case Number n when column.numeric() -> amount(new BigDecimal(n.toString()), column.scale());
            case Instant i -> dateTime(i);
            case OffsetDateTime t -> dateTime(t.toInstant());
            case LocalDate d -> date(d);
            case Map<?, ?> m -> new TreeMap<>(m).toString();
            default -> String.valueOf(value);
        };
    }
}
