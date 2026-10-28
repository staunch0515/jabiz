package com.jabiz.security.erasure;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Something the user should know before asking for the deletion of the account (decision D62 item 7). The text is the
 * message {@code erasure.notice.<code>} of the application's resources, with {@code params} filled in, in the
 * language of the request.
 *
 * @param code   the notice's code, letters, digits, {@code . _ -}
 * @param params values for the message's placeholders
 */
public record ErasureNotice(String code, Map<String, Object> params) {

    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");

    public ErasureNotice {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid notice code: " + code);
        }
        params = Map.copyOf(Objects.requireNonNull(params, "params must not be null"));
    }

    public static ErasureNotice of(String code) {
        return new ErasureNotice(code, Map.of());
    }

    public static ErasureNotice of(String code, Map<String, Object> params) {
        return new ErasureNotice(code, params);
    }
}
