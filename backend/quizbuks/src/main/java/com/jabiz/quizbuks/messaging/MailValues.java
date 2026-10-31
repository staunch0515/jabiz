package com.jabiz.quizbuks.messaging;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Parameters of QuizBuks's mail as the mail shows them (platform decision D35: parameters are stored as text, the
 * process formats them): amounts as numbers whose unit the template writes in its language ("1,234 Kudos", "1,234 学分",
 * backend/quizbuks/CLAUDE.md section 4), counts with digit grouping, lists of names separated by commas, and times in the business's zone with its
 * short name written out ("2026-10-20 09:30 JST"). The zone is {@code jabiz.fiscal-year-zone} (Asia/Tokyo for
 * QuizBuks, a Japanese business), the one its dates are reckoned in elsewhere; its label is the zone's own short
 * name, never written here.
 */
public final class MailValues {

    private final DateTimeFormatter time;

    /** @param zone the zone mail shows times in */
    public MailValues(ZoneId zone) {
        // The zone's short name in English ("JST", "UTC"): the same in every language of the mail.
        this.time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz", Locale.ENGLISH)
            .withZone(Objects.requireNonNull(zone, "zone"));
    }

    /** An instant (as an {@link Instant} or its ISO text) in the mail's zone; empty when there is none. */
    public String time(Object value) {
        if (value == null) {
            return "";
        }
        try {
            Instant instant = value instanceof Instant i ? i : Instant.parse(value.toString());
            return time.format(instant);
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    /**
     * An amount of Kudos as a number: whole, grouped by thousands ("1,234"); a missing one is 0. The points' name is
     * the language's (Q61): a mail's template writes it after the number ("{amount} 学分"), an inbox message takes
     * {@link MessageTexts#kudos}.
     */
    public static String amount(Object amount) {
        return grouped(amount);
    }

    /** A count, grouped by thousands; a missing one is 0. */
    public static String count(Object value) {
        return grouped(value);
    }

    /**
     * Names in quotes, separated by commas, at most {@code max} of them and "…" when there are more (a mail's
     * parameter is text on one line: platform D35).
     */
    public static String names(List<Object> names, int max) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(max, names.size()); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append('"').append(names.get(i)).append('"');
        }
        if (names.size() > max) {
            text.append(", …");
        }
        return text.toString();
    }

    private static String grouped(Object value) {
        BigDecimal number = value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(number.setScale(0, RoundingMode.HALF_UP));
    }
}
