package com.jabiz.runtime.integrity;

import com.jabiz.job.JobDefinition;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The integrity seals (docs/design/21-audit-retention.md section 2): {@code INTEGRITY_SEAL} seals the rows of the
 * append-only tables that no block holds yet into the next block of the chain; {@code INTEGRITY_VERIFY} checks the
 * chain, each block's rows and the rows themselves, and keeps what it found. Both run as jobs too.
 */
@Configuration
public class IntegrityProcesses {

    public static final String SEAL = "INTEGRITY_SEAL";
    public static final String VERIFY = "INTEGRITY_VERIFY";
    public static final String SEAL_JOB = "jabiz.integrity-seal";
    public static final String VERIFY_JOB = "jabiz.integrity-verify";

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    /** @param scheduledTime when the job meant to run; null when run by hand */
    public record SealInput(Instant scheduledTime) {}

    /** @param sealNo the new block, or null when there was nothing to seal */
    public record SealOutput(Long sealNo, int rowCount, String sealHash) {}

    /**
     * @param fromSeal the first block to check (the one before it is trusted as stored); default the first
     * @param scheduledTime when the job meant to run; null when run by hand
     */
    public record VerifyInput(Long fromSeal, Instant scheduledTime) {}

    /**
     * @param intact        whether nothing was found
     * @param unsealedCount rows of append-only tables no block holds yet (the next seal takes them)
     */
    public record VerifyOutput(long checkNo, boolean intact, int problemCount, int sealCount, long rowCount,
        long unsealedCount) {}

    public static final ProcessDefinition<SealInput, SealOutput, ProcessContext> SEAL_PROCESS =
        ProcessDefinition.define(SEAL, 1, SealInput.class, SealOutput.class, ProcessContext.class, pb -> pb
            .description("Seals the rows of the append-only tables that no block holds yet into the next block.")
            .permissions(IntegrityPermissions.SEAL)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, SealOutput.class))
            .step("Seal the new rows", SealRows.class, NoMetadata.INSTANCE));

    public static final ProcessDefinition<VerifyInput, VerifyOutput, ProcessContext> VERIFY_PROCESS =
        ProcessDefinition.define(VERIFY, 1, VerifyInput.class, VerifyOutput.class, ProcessContext.class, pb -> pb
            .description("Checks the seal chain and every sealed row, and records what it found.")
            .permissions(IntegrityPermissions.VERIFY)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, VerifyOutput.class))
            .step("Verify the seals", VerifySeals.class, NoMetadata.INSTANCE));

    @Bean
    ProcessDefinition<SealInput, SealOutput, ProcessContext> integritySealProcess() {
        return SEAL_PROCESS;
    }

    @Bean
    ProcessDefinition<VerifyInput, VerifyOutput, ProcessContext> integrityVerifyProcess() {
        return VERIFY_PROCESS;
    }

    /** Every five minutes by default ({@code jabiz.integrity.seal.cron}, UTC). */
    @Bean
    JobDefinition<SealInput> integritySealJob(IntegritySettings settings) {
        return JobDefinition.cron(SEAL_JOB, settings.sealCron(), ZoneOffset.UTC, SEAL_PROCESS, SealInput::new);
    }

    /** Daily by default ({@code jabiz.integrity.verify.cron}, UTC). */
    @Bean
    JobDefinition<VerifyInput> integrityVerifyJob(IntegritySettings settings) {
        return JobDefinition.cron(VERIFY_JOB, settings.verifyCron(), ZoneOffset.UTC, VERIFY_PROCESS,
            time -> new VerifyInput(null, time));
    }
}
