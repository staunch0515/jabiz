package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * BCrypt hashing of passwords (docs/design/10-security.md section 4). Hashing and matching take tens to hundreds of
 * milliseconds of CPU and draw random salt, so callers run them off the event loop (in a {@code BlockingStep}); at
 * most as many at once as the {@link PasswordHashingLimit} allows (decision D47), whose wait may end in
 * {@link com.jabiz.runtime.AuthBusyException}.
 */
public class PasswordHasher {

    /** BCrypt only looks at the first 72 bytes; longer passwords would silently collide. */
    public static final int MAX_BYTES = 72;

    private final BCryptPasswordEncoder encoder;
    private final int minLength;
    /** Compared against when there is no user, so that unknown names take as long as wrong passwords. */
    private final String dummyHash;
    private final PasswordHashingLimit limit;

    /** Without a limit on concurrent hashing (tests, tools). */
    public PasswordHasher(int strength, int minLength) {
        this(strength, minLength, PasswordHashingLimit.NONE);
    }

    public PasswordHasher(int strength, int minLength, PasswordHashingLimit limit) {
        this.limit = Objects.requireNonNull(limit, "limit");
        if (minLength < 1) {
            throw new IllegalArgumentException("The minimum password length must be positive");
        }
        this.encoder = new BCryptPasswordEncoder(strength);
        this.minLength = minLength;
        this.dummyHash = encoder.encode("jabiz-no-such-user");
    }

    /** What is wrong with a new password (empty when it is acceptable); {@code field} names it in violations. */
    public List<Violation> check(String field, String password) {
        if (password == null || password.codePointCount(0, password.length()) < minLength) {
            return List.of(new Violation(field, PlatformErrorCodes.PASSWORD_TOO_SHORT,
                "The password is shorter than " + minLength + " characters", Map.of("min", minLength)));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return List.of(new Violation(field, PlatformErrorCodes.PASSWORD_TOO_LONG,
                "The password is longer than " + MAX_BYTES + " bytes", Map.of("max", MAX_BYTES)));
        }
        return List.of();
    }

    /** Blocking, and waits for its turn ({@link PasswordHashingLimit}). */
    public String hash(String password) {
        return limit.compute(() -> encoder.encode(password));
    }

    /**
     * Blocking, and waits for its turn ({@link PasswordHashingLimit}). False for a missing hash, after the same amount
     * of work as a real comparison.
     */
    public boolean matches(String password, String hash) {
        return limit.compute(() -> {
            if (password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                encoder.matches("", dummyHash);
                return false;
            }
            if (hash == null || hash.isBlank()) {
                encoder.matches(password, dummyHash);
                return false;
            }
            return encoder.matches(password, hash);
        });
    }
}
