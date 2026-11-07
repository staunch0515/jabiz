package com.jabiz.runtime.approval;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * {@code APPROVAL_SCOPE_MOVE} (docs/design/18-numbering-approvals-tasks.md section 3.8, decision D76): moves the
 * open approval requests (pending, or approved and not yet used) of the given cases, the open tasks of those requests and other open tasks named by their
 * source keys to another value of their entry scope - when the thing they are about passes to another operator, the
 * new one sees and decides them, the old one no longer does. Only the scope's value changes; its name, the decisions
 * and the evaluations stay as recorded.
 *
 * <p>{@code subProcessOnly()}: an application calls it with {@code CallProcess.of} from its own reassignment process,
 * which checks its own permissions and scope; the caller needs {@value ApprovalPermissions#SCOPE_MOVE} as well
 * (checked by the step, as sub-processes are not checked at the entry). Its writes alone carry the grant that lets a
 * scope field change ({@link ApprovalScopeGuard}).
 */
@Configuration
public class ApprovalScopeMove {

    public static final String MOVE = "APPROVAL_SCOPE_MOVE";

    /** Most cases or source keys one call moves. */
    static final int MAX_ITEMS = 500;

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    /** One case of an approval subject: the subject and the id the case was opened with. */
    public record Case(@NotBlank @Size(max = 100) String subject, @NotBlank @Size(max = 100) String entityId) {}

    /**
     * @param scopeName      the entry scope's name; every request and task moved must carry it
     * @param toValue        the scope value they move to
     * @param requests       the cases whose open request (and its open tasks) moves
     * @param taskSourceKeys other open tasks to move, by their source key; optional, never an approval's task
     * @param reason         why (kept with the operation)
     */
    public record MoveInput(@NotBlank @Size(max = 64) String scopeName, @NotBlank @Size(max = 128) String toValue,
        @NotNull @Size(max = MAX_ITEMS) List<@NotNull @Valid Case> requests,
        @Size(max = MAX_ITEMS) List<@NotBlank @Size(max = 200) String> taskSourceKeys,
        @NotBlank @Size(max = 500) String reason) {}

    /**
     * A case not moved: {@code NOT_OPEN} (it has no pending request, nor an approved one not yet used) or
     * {@code ALREADY_THERE} (its request has the value already; its open tasks are moved all the same).
     */
    public record Skipped(String subject, String entityId, String why) {}

    /** @param requestIds the requests moved; {@code taskIds} the tasks moved */
    public record MoveOutput(List<String> requestIds, List<String> taskIds, List<Skipped> skipped) {}

    static final String NOT_OPEN = "NOT_OPEN";
    static final String ALREADY_THERE = "ALREADY_THERE";

    @Bean
    ProcessDefinition<MoveInput, MoveOutput, ProcessContext> approvalScopeMoveProcess() {
        return ProcessDefinition.define(MOVE, 1, MoveInput.class, MoveOutput.class, ProcessContext.class, pb -> pb
            .description("Moves open approval requests and their open tasks to another value of their entry scope.")
            .permissions(ApprovalPermissions.SCOPE_MOVE)
            .subProcessOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, MoveOutput.class))
            .step("Move the requests and tasks", MoveApprovalScope.class, NoMetadata.INSTANCE));
    }
}
