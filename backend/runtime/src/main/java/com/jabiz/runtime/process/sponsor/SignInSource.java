package com.jabiz.runtime.process.sponsor;

import com.jabiz.context.RequestContext;

/**
 * Where a sign-in attempt comes from (docs/design/10-security.md section 15; decision D36 items 1 and 7): the entry it
 * is for and the client's address and user agent, as kept in its login record.
 *
 * @param entry     the sign-in entry; null for the administration
 * @param clientIp  the client's address as {@code ClientAddresses} determined it, or null
 * @param userAgent the client's user agent: control characters removed, at most {@value #MAX_USER_AGENT} characters
 */
public record SignInSource(String entry, String clientIp, String userAgent) {

    public static final int MAX_USER_AGENT = 256;
    public static final int MAX_CLIENT_IP = 45;

    /** No source: an attempt not made over HTTP (tests, the platform itself), for the administration. */
    public static final SignInSource NONE = new SignInSource(null, null, null);

    public SignInSource {
        userAgent = userAgent(userAgent);
        clientIp = clientIp == null || clientIp.isBlank() ? null
            : clientIp.length() > MAX_CLIENT_IP ? clientIp.substring(0, MAX_CLIENT_IP) : clientIp;
    }

    /** The entry, the administration's when none was named. */
    public String entryOrDefault() {
        return entry == null || entry.isBlank() ? RequestContext.DEFAULT_ENTRY : entry;
    }

    /** This source for another entry. */
    public SignInSource withEntry(String entry) {
        return new SignInSource(entry, clientIp, userAgent);
    }

    /** A user agent as stored: without control characters (they could forge log lines), cut to the column. */
    static String userAgent(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_USER_AGENT));
        for (int i = 0; i < value.length() && clean.length() < MAX_USER_AGENT; ) {
            int point = value.codePointAt(i);
            i += Character.charCount(point);
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT) {
                continue;
            }
            if (clean.length() + Character.charCount(point) > MAX_USER_AGENT) {
                break;
            }
            clean.appendCodePoint(point);
        }
        String result = clean.toString().strip();
        return result.isEmpty() ? null : result;
    }
}
