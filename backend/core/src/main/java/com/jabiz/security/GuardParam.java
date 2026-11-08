package com.jabiz.security;

import java.util.Objects;

/**
 * A business parameter a {@link SignInGuard} or {@link SignUpGuard} reads (docs/design/10-security.md section 15.17;
 * decision D79), such as a switch that closes the registration. The platform reads the parameters of every guard
 * before asking them, as they are in effect at the attempt's time, and hands them over as
 * {@link SignInAttempt#params()} / {@link SignUpAttempt#params()}.
 *
 * <p>A {@link #required required} key without a value in effect at that time refuses the attempt before any guard is
 * asked (fail closed); an {@link #optional} key may be unset, and the guard sees it absent
 * ({@link com.jabiz.param.ParamValues#find}). A key one guard requires and another reads optionally is required. A
 * malformed key is not refused here: the startup check reports all of them at once.
 *
 * @param key      the parameter's key
 * @param required whether it must have a value in effect
 */
public record GuardParam(String key, boolean required) {

    public GuardParam {
        Objects.requireNonNull(key, "key must not be null");
    }

    /** A parameter that must have a value in effect; without one the attempt is refused. */
    public static GuardParam required(String key) {
        return new GuardParam(key, true);
    }

    /** A parameter that may be unset; the guard then finds it absent and decides what that means. */
    public static GuardParam optional(String key) {
        return new GuardParam(key, false);
    }
}
