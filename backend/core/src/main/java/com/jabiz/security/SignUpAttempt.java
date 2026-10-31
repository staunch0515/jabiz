package com.jabiz.security;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link SignUpGuard} decides about (decision D66): an account that is to get the registration roles of
 * {@code entry}.
 *
 * @param entry         the sign-in entry whose registration roles the account is to get
 * @param path          how ({@link Path}); a guard may concern itself with some paths only
 * @param userId        the existing user's id ({@link Path#MAIL_JOIN}, {@link Path#SIGNED_IN_JOIN}); null for a new
 *                      account, and always for a {@link Path#REGISTER registration}, even of an address that belongs
 *                      to an account: the answer to a registration tells nothing about the address
 * @param email         the account's address (for a registration: the address registered); null when there is none
 *                      (an account opened by another platform's token)
 * @param emailVerified whether the address is proven by now (a mailed link, a provider that vouches for it, a verified
 *                      session); never for a registration
 * @param time          when (the operation time)
 * @param data          the rows of the guards' {@link SignUpGuard#loads()}, by load name; empty lists without a user
 */
public record SignUpAttempt(String entry, Path path, String userId, String email, boolean emailVerified,
    Instant time, Map<String, List<Map<String, Object>>> data) {

    /** The way an account gets an entry's registration roles. */
    public enum Path {
        /** {@code POST /api/auth/register}: asked before the address is looked up, alike for every address. */
        REGISTER,
        /** The link of the "you already have an account" mail ({@code POST /api/auth/join}). */
        MAIL_JOIN,
        /** A signed-in user joining another entry ({@code POST /api/auth/account/entries/{entry}/join}). */
        SIGNED_IN_JOIN,
        /** A mailed sign-in link to an address nobody has (decision D54). */
        EMAIL_LINK,
        /** An identity provider's sign-in of a subject nobody linked (decision D39). */
        OIDC,
        /** Another platform's token for a person nobody linked (decision D59). */
        EXTERNAL_TOKEN
    }

    public SignUpAttempt {
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(time, "time must not be null");
        Map<String, List<Map<String, Object>>> copy = new LinkedHashMap<>();
        if (data != null) {
            data.forEach((name, rows) -> copy.put(name, rows == null ? List.of() : List.copyOf(rows)));
        }
        data = Collections.unmodifiableMap(copy);
    }

    /** Whether the account exists already (a join); false for a new account and for a registration. */
    public boolean existingUser() {
        return userId != null;
    }

    /** The rows of the load {@code name}; empty when it found none (always for a new account). */
    public List<Map<String, Object>> rows(String name) {
        return data.getOrDefault(name, List.of());
    }
}
