package com.jabiz.runtime.process.steps;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;

import java.util.Map;

/**
 * The bound of the reads that take a whole result rather than a page (decision D32): a template or an entity query in a
 * process, a SQL dictionary. {@code jabiz.process.max-read-rows} (100,000 by default); more is refused (422
 * {@code PROCESS_READ_TOO_LARGE}), never cut short.
 */
public final class ProcessReads {

    /** Fails at start for a maximum that is not positive, or so large that one more row is not an int. */
    public static void checkMaximum(int maxRows) {
        if (maxRows < 1 || maxRows == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("jabiz.process.max-read-rows must be between 1 and "
                + (Integer.MAX_VALUE - 1));
        }
    }

    /** The refusal of a read of {@code source} (a template or a dataset, by name) beyond {@code maxRows}. */
    public static BusinessRuleViolationException tooLarge(String source, int maxRows) {
        return new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.PROCESS_READ_TOO_LARGE,
            "Reading " + source + " would give more than " + maxRows + " rows",
            Map.of("source", source, "limit", maxRows)));
    }

    private ProcessReads() {}
}
