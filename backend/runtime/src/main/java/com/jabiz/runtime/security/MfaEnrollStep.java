package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ComputeStep;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.security.Base32;
import com.jabiz.security.MfaSecretCipher;
import com.jabiz.security.RecoveryCodes;
import com.jabiz.security.Totp;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The two halves of setting up a second factor (docs/design/10-security.md section 9): registering a new secret
 * (encrypted, not yet confirmed), then confirming it with a code, which issues the recovery codes. A confirmed
 * second factor is never replaced here; an administrator resets it first.
 */
@Component
public class MfaEnrollStep implements ComputeStep<MfaEnrollStep.Phase, ProcessContext> {

    public enum Phase { BEGIN, CONFIRM }

    private final MfaSecretCipher cipher;
    private final MfaSettings settings;

    public MfaEnrollStep(MfaSecretCipher cipher, MfaSettings settings) {
        this.cipher = cipher;
        this.settings = settings;
    }

    @Override
    public void compute(Phase phase, ProcessContext ctx) {
        EntityInstance user = ctx.get(MfaProcesses.USER, EntityInstance.class);
        // A second factor is set up by its own user only, whoever else may run processes (an administrator resets).
        if (!String.valueOf(user.id()).equals(ctx.request().actorId())) {
            throw new com.jabiz.runtime.PermissionDeniedException(SecurityPermissions.MFA_ENROLL,
                "A second factor is set up by its own user only");
        }
        Optional<EntityInstance> existing = MfaProcesses.first(ctx);
        if (existing.isPresent() && Boolean.TRUE.equals(existing.get().get("confirmed"))) {
            ctx.reject(new Violation(null, PlatformErrorCodes.MFA_ALREADY_ENROLLED,
                "A second factor is set up already"));
            return;
        }
        byte[] random = ctx.get(MfaProcesses.RANDOM, byte[].class);
        ctx.put(MfaProcesses.RANDOM, null);
        switch (phase) {
            case BEGIN -> begin(ctx, user, existing, random);
            case CONFIRM -> confirm(ctx, user, existing, random);
        }
    }

    private void begin(ProcessContext ctx, EntityInstance user, Optional<EntityInstance> pending, byte[] random) {
        byte[] secret = Arrays.copyOfRange(random, 0, Totp.SECRET_BYTES);
        byte[] iv = Arrays.copyOfRange(random, Totp.SECRET_BYTES, random.length);
        String sealed = cipher.encrypt(secret, String.valueOf(user.id()), iv);
        if (pending.isPresent()) {
            // A new attempt replaces the unconfirmed secret: only the latest one shown can be confirmed.
            ctx.changes().update(SecurityEntities.USER_MFA, pending.get().id(), pending.get().version(),
                Map.of("secret", sealed));
        } else {
            ctx.changes().insert(SecurityEntities.USER_MFA, MfaProcesses.attributes(user.id(), sealed));
        }
        ctx.put(MfaProcesses.RESULT, new MfaProcesses.Enrollment(Base32.encode(secret),
            Totp.uri(settings.issuer(), user.get("userName"), secret)));
    }

    private void confirm(ProcessContext ctx, EntityInstance user, Optional<EntityInstance> pending, byte[] random) {
        String code = ctx.get(MfaProcesses.CODE, String.class);
        ctx.put(MfaProcesses.CODE, null);
        if (pending.isEmpty()) {
            ctx.reject(new Violation(null, PlatformErrorCodes.MFA_NOT_ENROLLED, "No second factor is being set up"));
            return;
        }
        byte[] secret;
        try {
            secret = cipher.decrypt(pending.get().get("secret"), String.valueOf(user.id()));
        } catch (MfaSecretCipher.UnreadableSecretException e) {
            // Sealed under an earlier key: the enrolment starts again.
            ctx.reject(new Violation(null, PlatformErrorCodes.MFA_NOT_ENROLLED, "Start setting up again"));
            return;
        }
        java.util.OptionalLong step = Totp.verify(secret, code, ctx.opTime(), -1);
        if (step.isEmpty()) {
            ctx.reject(new Violation("code", PlatformErrorCodes.MFA_CODE_INVALID, "The code does not match"));
            return;
        }
        List<String> codes = RecoveryCodes.fromRandom(random);
        ctx.changes().update(SecurityEntities.USER_MFA, pending.get().id(), pending.get().version(), Map.of(
            "confirmed", true,
            "confirmedTime", ctx.opTime(),
            // The confirming code must not work again at the next sign-in.
            "confirmedStep", step.getAsLong(),
            "recoveryCodes", MfaCodes.join(codes.stream().map(RecoveryCodes::hash).toList())));
        ctx.put(MfaProcesses.RESULT, new MfaProcesses.RecoveryCodeList(codes));
    }
}
