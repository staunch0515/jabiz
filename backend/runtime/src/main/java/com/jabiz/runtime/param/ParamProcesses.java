package com.jabiz.runtime.param;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.param.ParamKinds;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The processes that maintain business parameters (docs/design/04-temporal-append-only.md section 9, ROADMAP
 * phase 8): declare one, change its value now, schedule a change, cancel a scheduled change. Values are checked
 * against the parameter's kind and stored in canonical form; every change is a version of {@code SysParam} with its
 * operation record, so earlier values stay readable at their times.
 */
@Configuration
public class ParamProcesses {

    /**
     * @param valueKind the kind in the notation of SQL template headers, e.g. {@code {type: numeric, precision: 5,
     *                  scale: 4}}; cannot change later
     */
    public record CreateInput(@NotBlank String key, @NotEmpty Map<String, Object> valueKind, @NotNull Object value,
        String description) {}

    public record SetInput(@NotBlank String key, @NotNull Object value) {}

    /** @param effectiveTime when the new value takes effect; later than the time of the operation */
    public record ScheduleInput(@NotBlank String key, @NotNull Object value, @NotNull Instant effectiveTime) {}

    /** @param effectiveTime the effective time of the scheduled change to cancel */
    public record CancelInput(@NotBlank String key, @NotNull Instant effectiveTime) {}

    /**
     * @param value         canonical text of the value
     * @param effectiveTime when the value takes (or took) effect
     */
    public record ParamOutput(String key, String value, Instant effectiveTime) {}

    public record CancelOutput(String key, Instant effectiveTime) {}

    static final String KEY = "param_key";
    static final String PARAM = "param";
    static final String VALUE = "param_value";
    static final String EFFECTIVE = "effective_time";

    public static final ProcessDefinition<CreateInput, ParamOutput, ProcessContext> CREATE =
        ProcessDefinition.define("PARAM_CREATE", 1, CreateInput.class, ParamOutput.class, ProcessContext.class,
            pb -> pb
                .description("Declares a business parameter with its kind and first value, effective now.")
                .permissions(ParamPermissions.WRITE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new ParamOutput(ctx.get(KEY, String.class), ctx.get(VALUE, String.class),
                    ctx.opTime()))
                .compute("Register the parameter", (metadata, ctx) -> {
                    CreateInput input = ctx.get("input", CreateInput.class);
                    SemanticKind kind;
                    try {
                        kind = ParamKinds.parse(input.valueKind());
                    } catch (IllegalArgumentException e) {
                        // Malformed input, as on the dataset path (400), not a business rule.
                        throw new ValidationException(List.of(new Violation("valueKind",
                            PlatformErrorCodes.INVALID_VALUE, e.getMessage())));
                    }
                    String value = canonical(ctx, kind, input.value(), String.valueOf(input.valueKind().get("type")));
                    if (value == null) {
                        return;
                    }
                    Map<String, Object> param = new LinkedHashMap<>();
                    param.put(ParamEntities.KEY, input.key());
                    param.put(ParamEntities.KIND, input.valueKind());
                    param.put(ParamEntities.VALUE, value);
                    param.put(ParamEntities.DESCRIPTION, input.description());
                    ctx.changes().insert(ParamEntities.ENTITY, param);
                    ctx.put(KEY, input.key());
                    ctx.put(VALUE, value);
                }));

    public static final ProcessDefinition<SetInput, ParamOutput, ProcessContext> SET =
        ProcessDefinition.define("PARAM_SET", 1, SetInput.class, ParamOutput.class, ProcessContext.class, pb -> pb
            .description("Changes the value of a business parameter, effective now.")
            .permissions(ParamPermissions.WRITE)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(KEY, input.key());
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new ParamOutput(ctx.get(KEY, String.class), ctx.get(VALUE, String.class),
                ctx.opTime()))
            .step("Load the parameter", LoadParamVersion.of(KEY, ProcessContext::opTime, PARAM))
            .compute("Register the new value", (metadata, ctx) -> {
                SetInput input = ctx.get("input", SetInput.class);
                change(ctx, ctx.changes().effectiveAt(ctx.opTime()), input.value());
            }));

    public static final ProcessDefinition<ScheduleInput, ParamOutput, ProcessContext> SCHEDULE =
        ProcessDefinition.define("PARAM_SCHEDULE", 1, ScheduleInput.class, ParamOutput.class, ProcessContext.class,
            pb -> pb
                .description("Schedules a new value of a business parameter to take effect later.")
                .permissions(ParamPermissions.WRITE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(KEY, input.key());
                    ctx.put(EFFECTIVE, input.effectiveTime());
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new ParamOutput(ctx.get(KEY, String.class), ctx.get(VALUE, String.class),
                    ctx.get(EFFECTIVE, Instant.class)))
                // The change is based on the version in effect when it takes effect (docs/design/04 section 3).
                .step("Load the parameter", LoadParamVersion.of(KEY, ParamProcesses::scheduledTime, PARAM))
                .compute("Register the scheduled value", (metadata, ctx) -> {
                    ScheduleInput input = ctx.get("input", ScheduleInput.class);
                    if (!input.effectiveTime().isAfter(ctx.opTime())) {
                        ctx.reject(notFuture(input.effectiveTime()));
                        return;
                    }
                    change(ctx, ctx.changes().effectiveAt(input.effectiveTime()), input.value());
                }));

    public static final ProcessDefinition<CancelInput, CancelOutput, ProcessContext> CANCEL_SCHEDULED =
        ProcessDefinition.define("PARAM_CANCEL_SCHEDULED", 1, CancelInput.class, CancelOutput.class,
            ProcessContext.class, pb -> pb
                .description("Cancels a scheduled change of a business parameter.")
                .permissions(ParamPermissions.WRITE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(KEY, input.key());
                    ctx.put(EFFECTIVE, input.effectiveTime());
                    return ctx;
                })
                .outputMapper(ctx -> new CancelOutput(ctx.get(KEY, String.class), ctx.get(EFFECTIVE, Instant.class)))
                .step("Load the scheduled version", LoadParamVersion.of(KEY, ParamProcesses::scheduledTime, PARAM))
                .compute("Register the cancellation", (metadata, ctx) -> {
                    // A time that is not later than now has nothing scheduled: the platform answers NOT_SCHEDULED.
                    EntityInstance param = ctx.get(PARAM, EntityInstance.class);
                    ctx.changes().effectiveAt(ctx.get(EFFECTIVE, Instant.class))
                        .cancelScheduled(ParamEntities.ENTITY, param.id(), param.version());
                }));

    /** The requested effective time, but never earlier than now: a past time is refused by the compute step. */
    private static Instant scheduledTime(ProcessContext ctx) {
        Instant effective = ctx.get(EFFECTIVE, Instant.class);
        return effective.isAfter(ctx.opTime()) ? effective : ctx.opTime();
    }

    private static void change(ProcessContext ctx, ChangeSet.Target target, Object raw) {
        EntityInstance param = ctx.get(PARAM, EntityInstance.class);
        Map<String, ?> spec = param.get(ParamEntities.KIND);
        String value = canonical(ctx, ParamKinds.parse(spec), raw, String.valueOf(spec.get("type")));
        if (value == null) {
            return;
        }
        target.update(ParamEntities.ENTITY, param.id(), param.version(), Map.of(ParamEntities.VALUE, value));
        ctx.put(VALUE, value);
    }

    /** The canonical text of {@code raw}, or null after rejecting it. */
    private static String canonical(ProcessContext ctx, SemanticKind kind, Object raw, String kindName) {
        try {
            return ParamKinds.canonical(kind, raw);
        } catch (IllegalArgumentException e) {
            ctx.reject(new Violation("value", PlatformErrorCodes.PARAM_VALUE_INVALID,
                "The value is not a " + kindName + ": " + e.getMessage(), Map.of("kind", kindName)));
            return null;
        }
    }

    private static Violation notFuture(Instant time) {
        return new Violation("effectiveTime", PlatformErrorCodes.EFFECTIVE_TIME_NOT_FUTURE,
            "The effective time " + time + " is not later than now", Map.of("time", time.toString()));
    }

    @Bean
    ProcessDefinition<CreateInput, ParamOutput, ProcessContext> paramCreateProcess() {
        return CREATE;
    }

    @Bean
    ProcessDefinition<SetInput, ParamOutput, ProcessContext> paramSetProcess() {
        return SET;
    }

    @Bean
    ProcessDefinition<ScheduleInput, ParamOutput, ProcessContext> paramScheduleProcess() {
        return SCHEDULE;
    }

    @Bean
    ProcessDefinition<CancelInput, CancelOutput, ProcessContext> paramCancelScheduledProcess() {
        return CANCEL_SCHEDULED;
    }
}
