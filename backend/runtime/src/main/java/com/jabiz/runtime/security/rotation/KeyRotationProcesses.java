package com.jabiz.runtime.security.rotation;

import com.jabiz.job.JobDefinition;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.security.IdentityTokens;
import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Key rotation (decision D78): while a retired {@code JABIZ_MFA_KEY} or {@code JABIZ_SEAL_KEY} is configured
 * ({@code *_PREVIOUS}), the job {@code jabiz.key-rotation} stores the TOTP secrets and the declared sealed values
 * ({@link SealedFields}) still under it again under the current key, a batch at a time, through the ordinary write path
 * (a new version of a temporal row, audited with the value masked). Once a run finds nothing left, the retired key can
 * be removed. Without retired keys a run reads nothing.
 */
@Configuration
public class KeyRotationProcesses {

    public static final String NAME = "KEY_ROTATION_REKEY";
    public static final String JOB = "jabiz.key-rotation";
    /** Running the re-keying (system only: the process is internal, run from its job or by hand through it). */
    public static final String PERMISSION = "security.key-rotation";

    static final String INPUT = "input";
    static final String FOUND = "found";
    static final String OUTPUT = "output";

    /** @param scheduledTime when the job meant to run; null when run by hand */
    public record RekeyInput(Instant scheduledTime) {}

    /**
     * @param mfaSecrets   TOTP secrets stored again under the current key
     * @param sealedValues sealed values stored again under the current key
     * @param unreadable   values under a retired key that did not open (wrong context, altered): left as they are
     * @param more         whether a batch was full, so that more may be left
     * @param secondFactorsTakenBack second factors an approved re-enrolment of everyone took back ({@link MfaReenrollment})
     */
    public record RekeyOutput(int mfaSecrets, int sealedValues, int unreadable, boolean more,
        int secondFactorsTakenBack) {}

    public static final ProcessDefinition<RekeyInput, RekeyOutput, ProcessContext> REKEY =
        ProcessDefinition.define(NAME, 1, RekeyInput.class, RekeyOutput.class, ProcessContext.class, pb -> pb
            .description("Stores TOTP secrets and sealed values that are still under a retired key again under the "
                + "current key, and takes back second factors an approved re-enrolment of everyone asks for.")
            .permissions(PERMISSION)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, RekeyOutput.class))
            .step("Take back second factors of a re-enrolment", TakeBackSecondFactors.class, NoMetadata.INSTANCE)
            .step("Find values under a retired key", FindRetiredValues.class, NoMetadata.INSTANCE)
            .step("Store them under the current key", StoreUnderCurrentKey.class, NoMetadata.INSTANCE));

    @Bean
    ProcessDefinition<RekeyInput, RekeyOutput, ProcessContext> keyRotationRekeyProcess() {
        return REKEY;
    }

    /** Every ten minutes by default ({@code jabiz.key-rotation.cron}, UTC); administrators also run it by hand (D77). */
    @Bean
    JobDefinition<RekeyInput> keyRotationJob(@Value("${jabiz.key-rotation.cron:0 */10 * * * *}") String cron) {
        return JobDefinition.cron(JOB, cron, ZoneOffset.UTC, REKEY, RekeyInput::new).manualToo();
    }

    /** The platform's own sealed field: the provider tokens of links (Apple), sealed for their link. */
    @Bean
    SealedFields platformSealedFields() {
        return () -> List.of(SealedField.of(IdentityTokens.TOKEN, "sealedToken",
            row -> IdentityTokens.context(UUID.fromString(String.valueOf(row.get("identityId"))))));
    }
}
