package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.security.PasswordHasher;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Checks the submitted password against the loaded user's BCrypt hash (docs/design/10-security.md section 4). A
 * blocking step: BCrypt costs tens to hundreds of milliseconds of CPU, which must not stall an event loop.
 *
 * <p>The password is always compared, also for names of no user (against a dummy hash) and for locked accounts, so
 * that the response time tells nothing. A locked account is refused whatever the password.
 */
@Component
public class AuthenticationStep implements BlockingStep<NoMetadata, LoginContext> {

    private final PasswordHasher hasher;
    private final LoginAttemptPolicy policy;

    public AuthenticationStep(PasswordHasher hasher, LoginAttemptPolicy policy) {
        this.hasher = hasher;
        this.policy = policy;
    }

    @Override
    public void run(NoMetadata metadata, LoginContext ctx) {
        String password = ctx.takePassword();
        Optional<EntityInstance> user = ctx.user();
        boolean matches = hasher.matches(password, user.<String>map(u -> u.get("passwordHash")).orElse(null));
        // Names of no user, and users without a password (they sign in through an identity provider only,
        // docs/design/10-security.md section 12), leave no record: no password can be right, so wrong ones must not
        // let anybody lock such an account.
        if (user.isEmpty() || blank(user.get().get("passwordHash"))) {
            return;
        }
        if (policy.isLocked(ctx.latestState(), ctx.opTime())) {
            ctx.setOutcome(LoginOutcome.LOCKED);
        } else if (!matches) {
            ctx.setOutcome(LoginOutcome.BAD_CREDENTIALS);
        } else if (!Rbac.enabled(user.get())) {
            ctx.setOutcome(LoginOutcome.DISABLED);
        } else {
            // Provisional: the role check comes next.
            ctx.setOutcome(LoginOutcome.SUCCESS);
        }
    }

    private static boolean blank(Object hash) {
        return hash == null || String.valueOf(hash).isBlank();
    }
}
