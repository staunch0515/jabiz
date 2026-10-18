package com.jabiz.quizbuks.messaging;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Parameters of QuizBuks's mail as the mail shows them (platform decision D35: parameters are stored as text, the
 * process formats them): amounts as the three front ends show them ("1,234 Kudos", backend/quizbuks/CLAUDE.md section
 * 4), counts with digit grouping, times in UTC with the zone written out (the recipient's zone is not known).
 */
public final class MailValues {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
        .withZone(ZoneOffset.UTC);

    private MailValues() {}

    /** An amount in Kudos: whole, grouped by thousands ("1,234 Kudos"); a missing one is 0. */
    public static String kudos(Object amount) {
        return grouped(amount) + " Kudos";
    }

    /** A count, grouped by thousands; a missing one is 0. */
    public static String count(Object value) {
        return grouped(value);
    }

    /** An instant (as an {@link Instant} or its ISO text) in UTC; empty when there is none. */
    public static String time(Object value) {
        if (value == null) {
            return "";
        }
        try {
            Instant instant = value instanceof Instant i ? i : Instant.parse(value.toString());
            return TIME.format(instant);
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    private static String grouped(Object value) {
        BigDecimal number = value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(number.setScale(0, RoundingMode.HALF_UP));
    }
}
