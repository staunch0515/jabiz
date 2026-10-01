package com.jabiz.finance.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A jurisdiction's rates as a time line (FIN-TX-001): each rate runs from its date to the day before the next one, the
 * last stays open. Setting a rate from a date inserts it into the line, or corrects the rate of that very date, and
 * says which neighbours' end dates move. Pure computation for {@code FIN_TAX_RATE_SET} and {@code FIN_TAX_CODE_SAVE}.
 */
public final class TaxRates {

    /** A stored rate: its identity and version, from, to (null: open) and percent. */
    public record Stored(Object id, long version, LocalDate from, LocalDate to, BigDecimal percent) {}

    /** A stored rate whose end date (and, for a correction, percent) changes. */
    public record Update(Object id, long version, LocalDate to, BigDecimal percent) {}

    /**
     * @param insertNew whether a new rate is stored; false for a correction of the rate of that date
     * @param insertTo  the new rate's last day, null while no later rate follows
     */
    public record Plan(boolean insertNew, LocalDate insertTo, List<Update> updates) {}

    private TaxRates() {}

    public static Plan plan(List<Stored> stored, LocalDate from, BigDecimal percent) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(percent, "percent must not be null");
        List<Stored> line = new ArrayList<>(stored);
        line.sort(Comparator.comparing(Stored::from));
        List<Update> updates = new ArrayList<>();
        for (Stored rate : line) {
            if (rate.from().equals(from)) {
                if (rate.percent().compareTo(percent) != 0) {
                    updates.add(new Update(rate.id(), rate.version(), rate.to(), percent));
                }
                return new Plan(false, null, List.copyOf(updates));
            }
        }
        Stored previous = null;
        Stored next = null;
        for (Stored rate : line) {
            if (rate.from().isBefore(from)) {
                previous = rate;
            } else if (next == null) {
                next = rate;
            }
        }
        if (previous != null && previous.percent().compareTo(percent) == 0
            && (previous.to() == null || !previous.to().isBefore(from))) {
            // The rate in effect on that day is this rate already.
            return new Plan(false, null, List.of());
        }
        LocalDate newTo = next == null ? null : next.from().minusDays(1);
        if (previous != null && !Objects.equals(previous.to(), from.minusDays(1))) {
            updates.add(new Update(previous.id(), previous.version(), from.minusDays(1), previous.percent()));
        }
        return new Plan(true, newTo, List.copyOf(updates));
    }
}
