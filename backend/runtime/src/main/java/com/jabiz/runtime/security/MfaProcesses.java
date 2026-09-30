package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.sponsor.MfaContext;
import com.jabiz.runtime.process.sponsor.SponsorMfaVerifyInput;
import com.jabiz.runtime.process.sponsor.SponsorMfaVerifyProcess;
import com.jabiz.runtime.process.sponsor.SponsorSignInOutput;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.MfaRequirement;
import com.jabiz.security.MfaSecretCipher;
import com.jabiz.security.RecoveryCodes;
import com.jabiz.security.Sensitive;
import com.jabiz.security.Totp;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Setting up and resetting a second factor (docs/design/10-security.md section 9; decision D28 item 1). Enrolment runs
 * only through {@code /api/auth/mfa/**} and {@code /api/auth/challenge/**}, as the user enrolling: its permission is
 * granted to no role. The secret is stored encrypted ({@link MfaSecretCipher}); the recovery codes are shown once and
 * stored as hashes.
 */
@Configuration
public class MfaProcesses {

    public record UserInput(@NotNull String userId) {}

    /**
     * @param mfaCode named apart from ordinary "code" properties: the names of sensitive components are masked
     *                wherever they appear in logged JSON (docs/design/10-security.md section 6)
     */
    public record ConfirmInput(@NotNull String userId, @Sensitive @NotBlank String mfaCode) {
        @Override
        public String toString() {
            return "ConfirmInput[userId=" + userId + ", mfaCode=***]";
        }
    }

    /** @param reason why the second factor is reset (for example: phone lost) */
    public record ResetInput(@NotNull String userId, @NotBlank String reason) {}

    /** The new secret, for the authenticator app: as text and as an {@code otpauth://} address. */
    public record Enrollment(@Sensitive String secret, @Sensitive String otpauthUri) {
        @Override
        public String toString() {
            return "Enrollment[***]";
        }
    }

    public record RecoveryCodeList(@Sensitive List<String> recoveryCodes) {
        public RecoveryCodeList {
            recoveryCodes = List.copyOf(recoveryCodes);
        }

        @Override
        public String toString() {
            return "RecoveryCodeList[***]";
        }
    }

    public record UserIdOutput(String userId) {}

    static final String USER_ID = "user_id";
    static final String USER = "user";
    static final String MFA = "mfa";
    static final String RANDOM = "random";
    static final String CODE = "code";
    static final String RESULT = "result";

    public static final ProcessDefinition<UserInput, Enrollment, ProcessContext> ENROLL_BEGIN =
        ProcessDefinition.define("SEC_MFA_ENROLL_BEGIN", 1, UserInput.class, Enrollment.class, ProcessContext.class,
            pb -> pb
                .description("Starts setting up a second factor: a new TOTP secret, to be confirmed with a code.")
                .permissions(SecurityPermissions.MFA_ENROLL)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(RESULT, Enrollment.class))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Load the second factor", QueryEntities.of(SecurityEntities.USER_MFA_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.Eq("userId", ctx.get(USER, EntityInstance.class).id()),
                        "userId"), MFA))
                .step("Draw the secret", RandomBytesStep.class,
                    new RandomBytesStep.Metadata(RANDOM, Totp.SECRET_BYTES + MfaSecretCipher.IV_BYTES))
                .step("Register the secret", MfaEnrollStep.class, MfaEnrollStep.Phase.BEGIN));

    public static final ProcessDefinition<ConfirmInput, RecoveryCodeList, ProcessContext> ENROLL_CONFIRM =
        ProcessDefinition.define("SEC_MFA_ENROLL_CONFIRM", 1, ConfirmInput.class, RecoveryCodeList.class,
            ProcessContext.class, pb -> pb
                .description("Completes setting up a second factor with a code; returns the recovery codes.")
                .permissions(SecurityPermissions.MFA_ENROLL)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(CODE, input.mfaCode());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(RESULT, RecoveryCodeList.class))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Load the second factor", QueryEntities.of(SecurityEntities.USER_MFA_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.Eq("userId", ctx.get(USER, EntityInstance.class).id()),
                        "userId"), MFA))
                .step("Draw the recovery codes", RandomBytesStep.class,
                    new RandomBytesStep.Metadata(RANDOM, RecoveryCodes.COUNT * RecoveryCodes.BYTES_PER_CODE))
                .step("Confirm the secret", MfaEnrollStep.class, MfaEnrollStep.Phase.CONFIRM));

    public static final ProcessDefinition<ResetInput, UserIdOutput, ProcessContext> RESET =
        ProcessDefinition.define("SEC_MFA_RESET", 1, ResetInput.class, UserIdOutput.class, ProcessContext.class,
            pb -> pb
                .description("Removes the second factor of a user, who sets up a new one at the next sign-in.")
                .permissions(SecurityPermissions.MFA_RESET)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    return ctx;
                })
                .outputMapper(ctx -> new UserIdOutput(String.valueOf(ctx.get(USER_ID))))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Load the second factor", QueryEntities.of(SecurityEntities.USER_MFA_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.Eq("userId", ctx.get(USER, EntityInstance.class).id()),
                        "userId"), MFA))
                .compute("Remove the second factor", (metadata, ctx) -> {
                    Optional<EntityInstance> mfa = first(ctx);
                    if (mfa.isEmpty()) {
                        ctx.reject(new Violation("userId", PlatformErrorCodes.MFA_NOT_ENROLLED,
                            "The user has no second factor"));
                        return;
                    }
                    ctx.changes().delete(SecurityEntities.USER_MFA, mfa.get().id(), mfa.get().version());
                }));

    /** The user's second factor, confirmed or pending, if any. */
    @SuppressWarnings("unchecked")
    static Optional<EntityInstance> first(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(MFA);
        return rows == null || rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    static Map<String, Object> attributes(Object userId, String secret) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("userId", userId);
        attributes.put("secret", secret);
        attributes.put("confirmed", false);
        attributes.put("confirmedTime", null);
        attributes.put("confirmedStep", null);
        attributes.put("recoveryCodes", null);
        return attributes;
    }

    @Bean
    ProcessDefinition<SponsorMfaVerifyInput, SponsorSignInOutput, MfaContext> sponsorMfaVerifyProcess() {
        return SponsorMfaVerifyProcess.DEFINITION;
    }

    @Bean
    ProcessDefinition<UserInput, Enrollment, ProcessContext> secMfaEnrollBeginProcess() {
        return ENROLL_BEGIN;
    }

    @Bean
    ProcessDefinition<ConfirmInput, RecoveryCodeList, ProcessContext> secMfaEnrollConfirmProcess() {
        return ENROLL_CONFIRM;
    }

    @Bean
    ProcessDefinition<ResetInput, UserIdOutput, ProcessContext> secMfaResetProcess() {
        return RESET;
    }
}
