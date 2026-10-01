package com.jabiz.finance.io;

import com.jabiz.finance.ar.CustomerProcesses;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a customer file's free-text columns read (FIN-DI-001; ROADMAP F3a): the sample company's {@code customers.csv}
 * gives a location ("TX (Austin)", "Germany") and the exemption certificate in words ("TX 01-339 resale certificate
 * RC-3301, valid to 2027-12-31"). Explicit columns, where a file has them, take precedence. Pure parsing; a text that
 * does not read is the row's error.
 */
final class CustomerRows {

    /** A place: state and city for a US location ("TX (Austin)", "Austin, TX"), else the country as given. */
    record Location(String city, String state, String country) {}

    private static final Pattern STATE_CITY = Pattern.compile("^([A-Z]{2})\\s*\\((.+)\\)$");
    private static final Pattern CITY_STATE = Pattern.compile("^(.+),\\s*([A-Z]{2})$");
    private static final Pattern CERT_STATE = Pattern.compile("^([A-Z]{2})\\b");
    private static final Pattern CERT_NUMBER = Pattern.compile("certificate\\s+([A-Z0-9][A-Z0-9-]*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern VALID_TO = Pattern.compile("valid (?:to|until|through)\\s+(\\d{4}-\\d{2}-\\d{2})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUED = Pattern.compile("issued(?: on)?\\s+(\\d{4}-\\d{2}-\\d{2})",
        Pattern.CASE_INSENSITIVE);

    private CustomerRows() {}

    static Location location(String text) {
        if (text == null || text.isBlank()) {
            return new Location(null, null, null);
        }
        String value = text.trim();
        Matcher m = STATE_CITY.matcher(value);
        if (m.matches()) {
            return new Location(m.group(2).trim(), m.group(1), "US");
        }
        m = CITY_STATE.matcher(value);
        if (m.matches()) {
            return new Location(m.group(1).trim(), m.group(2), "US");
        }
        return new Location(null, null, value);
    }

    /** The certificate a sentence describes, or null for a blank one. */
    static CustomerProcesses.CertificateInput certificate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        Matcher state = CERT_STATE.matcher(value);
        Matcher number = CERT_NUMBER.matcher(value);
        if (!state.find() || !number.find()) {
            throw new IllegalArgumentException("The certificate \"" + value + "\" names no state or number; write it "
                + "as \"TX ... certificate RC-3301, valid to 2027-12-31\"");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String type = lower.contains("resale") ? "RESALE"
            : lower.contains("government") ? "GOVERNMENT"
            : lower.contains("exempt organization") || lower.contains("nonprofit") ? "EXEMPT_ORGANIZATION"
            : "OTHER";
        return new CustomerProcesses.CertificateInput(state.group(1), number.group(1).toUpperCase(Locale.ROOT), type,
            value.length() > 200 ? value.substring(0, 200) : value, null, date(ISSUED, value), date(VALID_TO, value),
            true);
    }

    private static LocalDate date(Pattern pattern, String value) {
        Matcher m = pattern.matcher(value);
        return m.find() ? LocalDate.parse(m.group(1)) : null;
    }
}
