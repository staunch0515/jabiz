package com.jabiz.runtime.firstlaunch;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The platform's processes of the first launch (docs/design/23-first-launch.md section 4). All are run by the
 * platform's runner only: two {@code internal()} ones that write the run's record, and {@code FIRST_LAUNCH_STEP},
 * {@code entryOnly()}, which runs one step of the content phase (a process of the plan, as a sub-process) in a
 * transaction of its own.
 */
@Configuration
public class FirstLaunchProcesses {

    /** Declared by the first launch's processes, which only the platform's runner starts. */
    public static final String PERMISSION = "first-launch.run";

    public static final String START = "FIRST_LAUNCH_START";
    public static final String RECORD = "FIRST_LAUNCH_RECORD";
    public static final String STEP = "FIRST_LAUNCH_STEP";

    /** @param configHash the hash of the configuration the run begins with */
    public record StartInput(String configHash) {}

    /**
     * @param runId      the run, when this call began it ({@code null} otherwise)
     * @param usersEver  how many users the database has ever had (the run begins only when none)
     */
    public record StartOutput(String runId, long usersEver) {
        public boolean started() {
            return runId != null;
        }
    }

    /**
     * An event of the run.
     *
     * @param kind       {@code CONTENT_DONE}, {@code DONE}, {@code FAILED}, {@code EXPIRED} or {@code REVISED}
     * @param stepKey    the step concerned, if any
     * @param reasonCode an error code, if any
     * @param reason     the reason as the log said it, if any (no addresses, no names)
     * @param configHash the hash of the configuration the event happened under
     * @param detail     JSON recorded with the event (user ids and the configuration's keys only), or {@code null}
     */
    public record RecordInput(String runId, String kind, String stepKey, String reasonCode, String reason,
        String configHash, String detail) {}

    public record RecordOutput(String eventId) {}

    /**
     * One step of the content phase. The process input is not part of this operation's record: the step's
     * sub-process records it, once.
     */
    public record StepInput(String runId, String stepKey, String process, @JsonIgnore Object input) {}

    /** @param output what the step's process gave */
    public record StepOutput(@JsonIgnore Object output) {}

    static final String STARTED = "first_launch_started";
    static final String RECORDED = "first_launch_recorded";
    static final String OUTPUT = "first_launch_output";

    public static final ProcessDefinition<StartInput, StartOutput, ProcessContext> START_PROCESS =
        ProcessDefinition.define(START, 1, StartInput.class, StartOutput.class, ProcessContext.class, pb -> pb
            .description("Begins the first launch on a database that never had a user; run by the platform only.")
            .permissions(PERMISSION)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(FirstLaunchSteps.INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(STARTED, StartOutput.class))
            .step("Begin the run", FirstLaunchSteps.Begin.class, new FirstLaunchSteps.Begin.Metadata()));

    public static final ProcessDefinition<RecordInput, RecordOutput, ProcessContext> RECORD_PROCESS =
        ProcessDefinition.define(RECORD, 1, RecordInput.class, RecordOutput.class, ProcessContext.class, pb -> pb
            .description("Records what became of the first launch; run by the platform only.")
            .permissions(PERMISSION)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(FirstLaunchSteps.INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(RECORDED, RecordOutput.class))
            .step("Record the event", FirstLaunchSteps.Record.class, new FirstLaunchSteps.Record.Metadata()));

    public static final ProcessDefinition<StepInput, StepOutput, ProcessContext> STEP_PROCESS =
        ProcessDefinition.define(STEP, 1, StepInput.class, StepOutput.class, ProcessContext.class, pb -> pb
            .description("Runs one step of the first launch's content phase; started by the platform's runner only.")
            .permissions(PERMISSION)
            .entryOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(FirstLaunchSteps.INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new StepOutput(ctx.get(OUTPUT)))
            .step("Run the step's process", FirstLaunchSteps.RunStep.class, new FirstLaunchSteps.RunStep.Metadata()));

    @Bean
    ProcessDefinition<StartInput, StartOutput, ProcessContext> firstLaunchStartProcess() {
        return START_PROCESS;
    }

    @Bean
    ProcessDefinition<RecordInput, RecordOutput, ProcessContext> firstLaunchRecordProcess() {
        return RECORD_PROCESS;
    }

    @Bean
    ProcessDefinition<StepInput, StepOutput, ProcessContext> firstLaunchStepProcess() {
        return STEP_PROCESS;
    }
}
