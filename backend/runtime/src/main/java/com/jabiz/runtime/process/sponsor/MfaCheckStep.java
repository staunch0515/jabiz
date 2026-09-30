package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ComputeStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.security.MfaCodes;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;
import com.jabiz.security.MfaSecretCipher;
import com.jabiz.security.RecoveryCodes;
import com.jabiz.security.Totp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Checks a second factor (docs/design/10-security.md section 9): a TOTP code of a step later than the last one
 * accepted, or an unused recovery code. A locked account is refused whatever the code; a wrong code counts towards
 * the lock like a wrong password. HMAC and AES are cheap, so this is a computation step.
 */
@Component
public class MfaCheckStep implements ComputeStep<NoMetadata, MfaContext> {

    private static final Logger log = LoggerFactory.getLogger(MfaCheckStep.class);

    private final MfaSecretCipher cipher;
    private final LoginAttemptPolicy policy;

    public MfaCheckStep(MfaSecretCipher cipher, LoginAttemptPolicy policy) {
        this.cipher = cipher;
        this.policy = policy;
    }

    @Override
    public void compute(NoMetadata metadata, MfaContext ctx) {
        String code = ctx.takePassword();
        Optional<EntityInstance> user = ctx.user();
        if (user.isEmpty()) {
            return;
        }
        if (ctx.challengeAttemptNo() != null && ctx.challengeReplaced()) {
            ctx.refuse(MfaContext.STALE);
            return;
        }
        Optional<EntityInstance> mfa = ctx.confirmedMfa();
        if (mfa.isEmpty()) {
            ctx.refuse(MfaContext.NOT_ENROLLED);
            return;
        }
        if (policy.isLocked(ctx.latestState(), ctx.opTime())) {
            ctx.setOutcome(LoginOutcome.LOCKED);
            return;
        }
        byte[] secret;
        try {
            secret = cipher.decrypt(mfa.get().get("secret"), String.valueOf(user.get().id()));
        } catch (MfaSecretCipher.UnreadableSecretException e) {
            // A changed JABIZ_MFA_KEY, or an altered row: not the user's fault, so it does not count as a failure.
            log.error("Second factor of user {} cannot be read: {}", user.get().id(), e.getMessage());
            ctx.refuse(MfaContext.UNREADABLE);
            return;
        }
        Object confirmedStep = mfa.get().get("confirmedStep");
        long lastUsed = Math.max(ctx.latestMfaStep(), confirmedStep == null ? -1
            : ((Number) confirmedStep).longValue());
        OptionalLong step = Totp.verify(secret, code, ctx.opTime(), lastUsed);
        if (step.isPresent()) {
            ctx.setFactor(SecurityEntities.FACTOR_TOTP);
            ctx.setAcceptedMfaStep(step.getAsLong());
        } else {
            ctx.setFactor(RecoveryCodes.looksLikeCode(code) ? SecurityEntities.FACTOR_RECOVERY_CODE
                : SecurityEntities.FACTOR_TOTP);
            Optional<String> recovery = RecoveryCodes.match(code, MfaCodes.hashes(mfa.get().get("recoveryCodes")));
            if (recovery.isEmpty()) {
                ctx.setOutcome(LoginOutcome.MFA_FAILED);
                return;
            }
            ctx.setUsedRecoveryCode(recovery.get());
        }
        // Provisional: the role check comes next.
        ctx.setOutcome(Rbac.enabled(user.get()) ? LoginOutcome.SUCCESS : LoginOutcome.DISABLED);
    }
}
