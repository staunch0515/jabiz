package com.jabiz.runtime.firstlaunch;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@value #NAME}: registers the row of a file a first-launch file step has stored (decision D82, design 23 section 6),
 * as {@code FILE_REGISTER} does for an upload, uploaded by the step's account. Only as a sub-process of the run's
 * {@code FIRST_LAUNCH_STEP} (which locks the run and checks its content phase), and only after the same checks as the
 * other powers: the operation's lineage leads to this run's step, the account is one the run created and has no
 * credential, and the policy is one the application's handler declares.
 */
@Configuration
public class FirstLaunchFileRegister {

    public static final String NAME = "FIRST_LAUNCH_FILE_REGISTER";

    static final String INPUT = "first_launch_file";
    static final String OUTPUT = "first_launch_file_info";

    public static final ProcessDefinition<FileProcesses.RegisterInput, FileProcesses.FileInfo, ProcessContext>
        PROCESS = ProcessDefinition.define(NAME, 1, FileProcesses.RegisterInput.class, FileProcesses.FileInfo.class,
            ProcessContext.class, pb -> pb
                .description("Records a file a first-launch file step stored, as the step's account (D82).")
                .permissions(FirstLaunchProcesses.PERMISSION)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, FileProcesses.FileInfo.class))
                .step("Check the first launch's power", StepSpec.of(Check.class, new Check.Metadata()))
                .compute("Record the file", (metadata, ctx) ->
                    ctx.put(OUTPUT, FileProcesses.record(ctx, ctx.get(INPUT, FileProcesses.RegisterInput.class)))));

    @Bean
    ProcessDefinition<FileProcesses.RegisterInput, FileProcesses.FileInfo, ProcessContext> firstLaunchFileRegisterProcess() {
        return PROCESS;
    }

    /** The checks of a file step's registration. */
    @Component
    static class Check implements StepHandler<Check.Metadata, ProcessContext> {

        record Metadata() {}

        private final FirstLaunchPowers powers;
        private final FirstLaunchFiles files;

        Check(FirstLaunchPowers powers, FirstLaunchFiles files) {
            this.powers = powers;
            this.files = files;
        }

        @Override
        public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
            FileProcesses.RegisterInput input = ctx.get(INPUT, FileProcesses.RegisterInput.class);
            if (!files.policies().contains(input.policy())) {
                return Mono.error(new PermissionDeniedException(FirstLaunchProcesses.PERMISSION, "First launch: file"
                    + " policy " + input.policy() + " is not one the application's handler names"));
            }
            if (RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
                return Mono.error(new PermissionDeniedException(FirstLaunchProcesses.PERMISSION,
                    "First launch: a file step stores the file as an account"));
            }
            return powers.contentPhase(ctx.request(), ctx.processSeqId(), false)
                .flatMap(run -> powers.requireActable(run, ctx.request().actorId()));
        }
    }
}
