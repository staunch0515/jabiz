package com.jabiz.runtime.security;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.ProcessContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Checks a new password against the password rules and replaces it in the context by its BCrypt hash (blocking:
 * BCrypt is slow on purpose). The plain password does not stay in the context.
 */
@Component
public class HashPasswordStep implements BlockingStep<HashPasswordStep.Metadata, ProcessContext> {

    /**
     * @param passwordKey context key of the new password; removed by the step
     * @param hashKey     context key receiving the hash
     * @param field       field name reported with violations
     * @param optional    whether a missing password is allowed (the hash is then null: a user who signs in through
     *                    an identity provider only, docs/design/10-security.md section 12)
     */
    public record Metadata(String passwordKey, String hashKey, String field, boolean optional) {

        public Metadata(String passwordKey, String hashKey, String field) {
            this(passwordKey, hashKey, field, false);
        }

        public Metadata {
            Objects.requireNonNull(passwordKey, "passwordKey must not be null");
            Objects.requireNonNull(hashKey, "hashKey must not be null");
            Objects.requireNonNull(field, "field must not be null");
        }
    }

    private final PasswordHasher hasher;

    public HashPasswordStep(PasswordHasher hasher) {
        this.hasher = hasher;
    }

    @Override
    public void run(Metadata metadata, ProcessContext ctx) {
        String password = ctx.get(metadata.passwordKey(), String.class);
        ctx.put(metadata.passwordKey(), null);
        if (metadata.optional() && (password == null || password.isEmpty())) {
            ctx.put(metadata.hashKey(), null);
            return;
        }
        List<Violation> problems = hasher.check(metadata.field(), password);
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        ctx.put(metadata.hashKey(), hasher.hash(password));
    }
}
