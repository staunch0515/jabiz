package com.jabiz.context;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The country a request comes from, by its client address (decision D74; docs/design/10-security.md section 18): an
 * ISO 3166-1 alpha-2 code from the platform's local address database, or unknown (no database, an address it does not
 * list, a private or local address, a request that came over no connection, such as an event or a job). Never saved,
 * never in an operation's input summary or an observation's tags: it is a property of this one request.
 *
 * <p>Mainland China is {@code CN}; Hong Kong ({@code HK}), Macao ({@code MO}) and Taiwan ({@code TW}) have codes of
 * their own, as in the databases.
 *
 * @param code the upper-case two-letter code, or null when unknown
 */
public record ClientCountry(String code) {

    private static final Pattern CODE = Pattern.compile("[A-Z]{2}");

    /** The country of a request whose address says nothing. */
    public static final ClientCountry UNKNOWN = new ClientCountry(null);

    public ClientCountry {
        if (code != null) {
            code = code.trim().toUpperCase(Locale.ROOT);
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("A country code is two letters, not " + code);
            }
        }
    }

    /** The country of {@code code}; unknown for null, blank or a database's placeholder such as {@code ZZ}. */
    public static ClientCountry of(String code) {
        if (code == null || code.isBlank() || "ZZ".equalsIgnoreCase(code.trim())) {
            return UNKNOWN;
        }
        return new ClientCountry(code);
    }

    public boolean known() {
        return code != null;
    }

    /** Whether the request comes from mainland China ({@code CN}); false when unknown. */
    public boolean mainlandChina() {
        return "CN".equals(code);
    }

    /** Whether it is {@code code}; false when unknown. */
    public boolean is(String code) {
        return this.code != null && this.code.equalsIgnoreCase(code);
    }
}
