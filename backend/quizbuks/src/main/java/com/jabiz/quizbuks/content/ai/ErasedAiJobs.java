package com.jabiz.quizbuks.content.ai;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.CancelExternalJob;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F-M15c (docs/quizbuks/plans/impl/F-M15.md §5.5): an erased sponsor's AI jobs still open are cancelled. QuizBuks' own
 * job rows are deleted with the account, so the jobs are the platform's ({@value #TEMPLATE}, by who asked for them); a
 * job cancelled ends without its fee (its outcome process finds no row: what was not charged when the account was
 * erased is never charged, plan H3), and a job that would run after the purge, its input overwritten, never reaches
 * the provider. Run by {@code QB_SPONSOR_ERASED} for every erased account and by the wind-up of the fee account, which
 * waits while more jobs are left than one run reads.
 *
 * <ul>
 *   <li>{@value #CANCEL_ALL} ({@code subProcessOnly}): the sponsor's jobs that may be open, at most {@value #MAX_JOBS}
 *       a run, each by {@value #CANCEL_ONE};</li>
 *   <li>{@value #CANCEL_ONE} ({@code subProcessOnly}): the platform's step, for the jobs of these types that the sponsor
 *       asked for only, and nothing for one that has ended.</li>
 * </ul>
 */
public final class ErasedAiJobs {

    public static final String CANCEL_ALL = "QB_AI_JOBS_ERASED_CANCEL";
    public static final String CANCEL_ONE = "QB_AI_JOB_ERASED_CANCEL";
    public static final String TEMPLATE = "qb.system.erased-ai-jobs";

    /** The most jobs a run reads (the platform's datasets read at most a thousand rows at once). */
    public static final int MAX_JOBS = 1000;

    /**
     * The AI job types a sponsor asks for, with the attempts each may make (its {@code retry}): the questions, the
     * cover's (withdrawn, kept for the jobs queued before), the translations and the website's draft. The check of a
     * translation (qb.ai.translation-check) is the reviewer's, not the sponsor's.
     */
    static final Map<String, Integer> TYPES = Map.of(
        AiQuestionsJob.TYPE, AiQuestionsJob.ATTEMPTS,
        AiCoverJob.TYPE, AiQuestionsJob.ATTEMPTS,
        AiTranslateJob.TYPE, AiQuestionsJob.ATTEMPTS,
        SiteProfileJob.TYPE, SiteProfileJob.ATTEMPTS);

    static final String INPUT = "input";
    static final String JOBS = "jobs";
    static final String CANCELLED = "cancelled";

    private ErasedAiJobs() {}

    public record SponsorRef(@NotBlank @Size(max = 128) String sponsorId) {}

    /**
     * @param cancelled the jobs cancelled now
     * @param more      whether more jobs may be open than this run read (the wind-up waits for the next)
     */
    public record CancelOutput(int cancelled, boolean more) {}

    public record JobRef(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 36) String jobId) {}

    public record JobOutput(String jobId, boolean cancelled) {}

    public static ProcessDefinition<SponsorRef, CancelOutput, ProcessContext> cancelAll() {
        return ProcessDefinition.define(CANCEL_ALL, 1, SponsorRef.class, CancelOutput.class, ProcessContext.class,
            pb -> pb
                .description("Cancels an erased sponsor's AI jobs that may still be open.")
                .permissions(QbPermissions.AI_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ErasedAiJobs::cancelOutput)
                .step("Read the sponsor's AI jobs not ended", RunTemplate.<ProcessContext>of(TEMPLATE,
                    ctx -> Map.of("userId", sponsor(ctx), "types", List.copyOf(TYPES.keySet()), "limits", limits(),
                        "max", MAX_JOBS),
                    JOBS))
                .step("Cancel them", CallProcess.forEach(CANCEL_ONE, 1, ErasedAiJobs::jobs, CANCELLED)));
    }

    public static ProcessDefinition<JobRef, JobOutput, ProcessContext> cancelOne() {
        return ProcessDefinition.define(CANCEL_ONE, 1, JobRef.class, JobOutput.class, ProcessContext.class, pb -> pb
            .description("Cancels one of an erased sponsor's AI jobs, if it is still open.")
            .permissions(QbPermissions.AI_SYSTEM)
            .subProcessOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new JobOutput(ctx.get(INPUT, JobRef.class).jobId(),
                Boolean.TRUE.equals(ctx.get(CANCELLED))))
            .step("Cancel the platform's job", CancelExternalJob.<ProcessContext>of(List.copyOf(TYPES.keySet()),
                    ctx -> ctx.get(INPUT, JobRef.class).jobId())
                .requestedBy(ctx -> ctx.get(INPUT, JobRef.class).sponsorId()).ifOpen().outputKey(CANCELLED)));
    }

    /** Each type's attempts, as the template takes them ({@code type:attempts}). */
    static List<String> limits() {
        return TYPES.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).sorted().toList();
    }

    /** The jobs to cancel: every one the template read. */
    @SuppressWarnings("unchecked")
    static List<JobRef> jobs(ProcessContext ctx) {
        List<JobRef> jobs = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) ctx.get(JOBS)) {
            jobs.add(new JobRef(sponsor(ctx), String.valueOf(row.get("jobId"))));
        }
        return List.copyOf(jobs);
    }

    @SuppressWarnings("unchecked")
    static CancelOutput cancelOutput(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(JOBS);
        long total = rows.isEmpty() ? 0 : number(rows.getFirst().get("total"));
        int cancelled = ctx.get(CANCELLED) instanceof List<?> outputs
            ? (int) outputs.stream().filter(o -> ((JobOutput) o).cancelled()).count() : 0;
        return new CancelOutput(cancelled, total > rows.size());
    }

    /**
     * A job whose input the purge of the erased account overwrote ({@code {"erased": true}}, read leniently into an
     * input of nulls: no model) ends for good without calling the provider.
     */
    static void refuseErased(String model) {
        if (model == null || model.isBlank()) {
            throw com.jabiz.external.ExternalJobException.permanent(AiCodes.INPUT_ERASED,
                "The job's input was erased with the account that asked for it");
        }
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : new BigDecimal(String.valueOf(value)).longValue();
    }

    private static String sponsor(ProcessContext ctx) {
        return ctx.get(INPUT, SponsorRef.class).sponsorId();
    }
}
