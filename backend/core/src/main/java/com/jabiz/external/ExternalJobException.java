package com.jabiz.external;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A failed attempt of an external job that the handler explains (decision D38 item 3): its code and message are kept
 * with the attempt and, once the job has failed for good, handed to the failure process. Never put a secret, a request
 * or an answer in the message. A {@linkplain #permanent permanent} failure ends the job at once; a
 * {@linkplain #retryable retryable} one is tried again while attempts are left, as any other exception is.
 */
public class ExternalJobException extends RuntimeException {

    static final String CODE_PATTERN = "[A-Z][A-Z0-9_]{0,99}";
    /** Error codes: upper case, digits and underscores. */
    public static final Pattern CODE = Pattern.compile(CODE_PATTERN);

    private final String code;
    private final boolean permanent;

    protected ExternalJobException(String code, String message, boolean permanent) {
        super(message);
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Error code '" + code + "' must match " + CODE_PATTERN);
        }
        this.code = code;
        this.permanent = permanent;
    }

    /** A failure trying again would not change (the request is refused, the input cannot be processed). */
    public static ExternalJobException permanent(String code, String message) {
        return new ExternalJobException(code, Objects.requireNonNullElse(message, code), true);
    }

    /** A failure that may pass (the provider is busy). */
    public static ExternalJobException retryable(String code, String message) {
        return new ExternalJobException(code, Objects.requireNonNullElse(message, code), false);
    }

    public String code() {
        return code;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
