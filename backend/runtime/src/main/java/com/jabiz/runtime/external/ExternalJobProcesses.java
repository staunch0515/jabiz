package com.jabiz.runtime.external;

import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The processes of asynchronous external jobs (decision D38 items 3 and 5):
 * <ul>
 *   <li>{@value #COMPLETE}: run by the platform's consumer {@value #CONSUMER} for each
 *       {@value ExternalJobRecorder#FINISHED_EVENT}, as the system, once per job: it calls the job type's completion
 *       or failure process as a sub-process ({@link RunExternalJobCallback}); a cancelled job calls nothing. Its
 *       failures are retried as every consumer's are (D14).</li>
 *   <li>{@value #CANCEL}: cancels a job, for the requester or a holder of {@code external-job.cancel}
 *       ({@link CancelExternalJob}); {@code POST /api/external-jobs/{id}/cancel} runs it.</li>
 * </ul>
 * {@value #COMPLETE} is {@code internal()}; {@value #CANCEL} is {@code entryOnly()}: only its endpoint runs it, as the
 * real caller, and its step checks who may cancel (decision D41).
 */
@Configuration
public class ExternalJobProcesses {

    public static final String COMPLETE = "EXTERNAL_JOB_COMPLETE";
    public static final String CANCEL = "EXTERNAL_JOB_CANCEL";
    /** The platform's consumer of finished jobs. */
    public static final String CONSUMER = "jabiz.external-jobs";

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    /** @param jobId the finished job; the step reads everything else itself */
    public record CompleteInput(@NotBlank String jobId) {}

    /** @param called the process called, or null for a cancelled job */
    public record CompleteOutput(String jobId, String status, String called) {}

    public record CancelInput(@NotBlank String jobId) {}

    /**
     * @param status {@code CANCELLED}; a running attempt ends first, and its result is dropped
     */
    public record CancelOutput(String jobId, String status) {}

    static final ProcessDefinition<CompleteInput, CompleteOutput, ProcessContext> COMPLETE_PROCESS =
        ProcessDefinition.define(COMPLETE, 1, CompleteInput.class, CompleteOutput.class, ProcessContext.class, pb -> pb
            .description("Hands a finished external job to its completion or failure process.")
            .permissions(ExternalJobPermissions.COMPLETE)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, CompleteOutput.class))
            .step("Call the job's process", RunExternalJobCallback.of(INPUT, OUTPUT)));

    static final ProcessDefinition<CancelInput, CancelOutput, ProcessContext> CANCEL_PROCESS =
        ProcessDefinition.define(CANCEL, 1, CancelInput.class, CancelOutput.class, ProcessContext.class, pb -> pb
            .description("Cancels an external job.")
            .permissions(ExternalJobPermissions.CANCEL)
            .entryOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, CancelOutput.class))
            .step("Cancel the job", CancelExternalJob.of(INPUT, OUTPUT)));

    @Bean
    ProcessDefinition<CompleteInput, CompleteOutput, ProcessContext> externalJobCompleteProcess() {
        return COMPLETE_PROCESS;
    }

    @Bean
    ProcessDefinition<CancelInput, CancelOutput, ProcessContext> externalJobCancelProcess() {
        return CANCEL_PROCESS;
    }

    @Bean
    EventSubscription<CompleteInput> externalJobFinishedSubscription() {
        return EventSubscription.of(CONSUMER, ExternalJobRecorder.FINISHED_EVENT, COMPLETE_PROCESS,
            ExternalJobProcesses::input);
    }

    static CompleteInput input(DomainEvent event) {
        return new CompleteInput(String.valueOf(event.payload().get("jobId")));
    }
}
