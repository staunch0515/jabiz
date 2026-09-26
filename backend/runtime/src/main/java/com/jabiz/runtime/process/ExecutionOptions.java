package com.jabiz.runtime.process;

import java.util.regex.Pattern;

/**
 * How a top-level execution is requested.
 *
 * @param idempotencyKey the caller's key for this request; a repeated request of the same actor with the same key
 *                       returns the first result without executing again (decision D4). Null for none.
 */
public record ExecutionOptions(String idempotencyKey) {

    public static final ExecutionOptions NONE = new ExecutionOptions(null);

    /** Keys a client may send: short, printable, no spaces. */
    public static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public static ExecutionOptions idempotent(String key) {
        return new ExecutionOptions(key);
    }
}
