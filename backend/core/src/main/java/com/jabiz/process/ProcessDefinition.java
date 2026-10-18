package com.jabiz.process;

import com.jabiz.security.MfaRequirement;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Immutable description of a process: its identity, its typed input and output, how the
 * execution context is created from the input, how the output is derived from the final
 * context, its ordered steps and the permissions needed to execute it.
 *
 * Usage:
 * <pre>
 * ProcessDefinition.define("PRICE_ADJUST", 1, In.class, Out.class, Ctx.class, pb -&gt; pb
 *     .description("...")
 *     .permissions("price.adjust")
 *     .contextFactory((start, in) -&gt; new Ctx(start, in))
 *     .outputMapper(ctx -&gt; new Out(...))
 *     .step("Load price", LoadEntity.by(PRICES, "priceId", "price"))
 *     .step("Adjust", AdjustPrice.class, NoMetadata.INSTANCE));
 * </pre>
 *
 * @param <I> process input type
 * @param <O> process output type
 * @param <C> context type shared by all steps
 * @param permissions permission codes the caller needs, all of them (docs/design/06-process.md section 8)
 * @param deprecated  whether a newer version should be used instead; references to it are reported at startup
 * @param access      who may start the process (decision D41): anyone holding its permissions, the system only, the
 *                    platform's own entry point only, or only as a sub-process; all but {@link ProcessAccess#OPEN} are
 *                    left out of the process catalog. Its permissions still apply wherever it runs
 * @param actsOn      the entity the process acts on, offered as an action on its rows; null if none
 * @param mfa         whether callers need a recent second factor (docs/design/10-security.md section 10)
 * @param verifiedEmail whether callers need a verified e-mail address (docs/design/10-security.md section 15;
 *                    decision D36 item 3); checked at the entry points like the permissions
 * @param admission   where top-level runs queue before their transaction (decision D45); null for no queue
 * @param alternativePermissions another set of permission codes that also lets a caller start the process, all of
 *                    them, for callers lacking {@code permissions}; empty for none. The process itself decides what
 *                    each set lets it do (decision D51: {@code APPROVAL_DECIDE} for protected changes)
 */
public record ProcessDefinition<I, O, C extends ProcessContext>(
    String name,
    int version,
    String description,
    Class<I> inputType,
    Class<O> outputType,
    Class<C> contextType,
    ContextFactory<I, C> contextFactory,
    Function<C, O> outputMapper,
    List<StepDefinition<?, C>> steps,
    Set<String> permissions,
    boolean deprecated,
    ProcessAccess access,
    ActsOn actsOn,
    MfaRequirement mfa,
    boolean verifiedEmail,
    Admission<I> admission,
    Set<String> alternativePermissions
) {

    /** A definition without alternative permissions. */
    public ProcessDefinition(String name, int version, String description, Class<I> inputType, Class<O> outputType,
        Class<C> contextType, ContextFactory<I, C> contextFactory, Function<C, O> outputMapper,
        List<StepDefinition<?, C>> steps, Set<String> permissions, boolean deprecated, ProcessAccess access,
        ActsOn actsOn, MfaRequirement mfa, boolean verifiedEmail, Admission<I> admission) {
        this(name, version, description, inputType, outputType, contextType, contextFactory, outputMapper, steps,
            permissions, deprecated, access, actsOn, mfa, verifiedEmail, admission, Set.of());
    }

    /** A definition that does not queue before its transaction. */
    public ProcessDefinition(String name, int version, String description, Class<I> inputType, Class<O> outputType,
        Class<C> contextType, ContextFactory<I, C> contextFactory, Function<C, O> outputMapper,
        List<StepDefinition<?, C>> steps, Set<String> permissions, boolean deprecated, ProcessAccess access,
        ActsOn actsOn, MfaRequirement mfa, boolean verifiedEmail) {
        this(name, version, description, inputType, outputType, contextType, contextFactory, outputMapper, steps,
            permissions, deprecated, access, actsOn, mfa, verifiedEmail, null);
    }

    /** A definition whose callers need no verified e-mail address. */
    public ProcessDefinition(String name, int version, String description, Class<I> inputType, Class<O> outputType,
        Class<C> contextType, ContextFactory<I, C> contextFactory, Function<C, O> outputMapper,
        List<StepDefinition<?, C>> steps, Set<String> permissions, boolean deprecated, ProcessAccess access,
        ActsOn actsOn, MfaRequirement mfa) {
        this(name, version, description, inputType, outputType, contextType, contextFactory, outputMapper, steps,
            permissions, deprecated, access, actsOn, mfa, false, null);
    }

    /** Context key under which {@link #single} processes keep their input and output. */
    public static final String SINGLE_INPUT = "jabiz.single.input";
    public static final String SINGLE_OUTPUT = "jabiz.single.output";

    /** Creates the execution context for one run from the process input. */
    @FunctionalInterface
    public interface ContextFactory<I, C extends ProcessContext> {
        C create(ProcessStart start, I input);
    }

    public ProcessDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (version <= 0) {
            throw new IllegalArgumentException("Process " + name + ": version must be positive");
        }
        Objects.requireNonNull(inputType, "inputType must not be null");
        Objects.requireNonNull(outputType, "outputType must not be null");
        Objects.requireNonNull(contextType, "contextType must not be null");
        Objects.requireNonNull(contextFactory, "contextFactory must not be null");
        Objects.requireNonNull(outputMapper, "outputMapper must not be null");
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Process " + name + " needs at least one step");
        }
        steps = List.copyOf(steps);
        mfa = mfa == null ? MfaRequirement.NONE : mfa;
        access = access == null ? ProcessAccess.OPEN : access;
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        alternativePermissions = alternativePermissions == null ? Set.of() : Set.copyOf(alternativePermissions);
        for (String permission : permissions) {
            if (permission.isBlank()) {
                throw new IllegalArgumentException("Process " + name + ": permission codes must not be blank");
            }
        }
        for (String permission : alternativePermissions) {
            if (permission.isBlank()) {
                throw new IllegalArgumentException("Process " + name + ": permission codes must not be blank");
            }
        }
        if (!alternativePermissions.isEmpty() && permissions.isEmpty()) {
            throw new IllegalArgumentException("Process " + name + ": alternative permissions need permissions");
        }
    }

    /**
     * The permissions a caller holding what {@code holds} says starts the process with: {@link #permissions} when it
     * holds them all; otherwise {@link #alternativePermissions} when declared and all held; otherwise
     * {@link #permissions} (which the caller then lacks).
     */
    public Set<String> permissionsFor(java.util.function.Predicate<String> holds) {
        if (permissions.stream().allMatch(holds) || alternativePermissions.isEmpty()
            || !alternativePermissions.stream().allMatch(holds)) {
            return permissions;
        }
        return alternativePermissions;
    }

    public static <I, O, C extends ProcessContext> ProcessDefinition<I, O, C> define(
        String name,
        int version,
        Class<I> inputType,
        Class<O> outputType,
        Class<C> contextType,
        Consumer<ProcessDefinitionBuilder<I, O, C>> block
    ) {
        ProcessDefinitionBuilder<I, O, C> builder =
            new ProcessDefinitionBuilder<>(name, version, inputType, outputType, contextType);
        block.accept(builder);
        return builder.build();
    }

    /**
     * A process with a single computation step (docs/design/06-process.md section 7). It has the same transaction,
     * operation record and revert as any other process; declare its permissions with {@link #withPermissions}.
     *
     * <pre>
     * ProcessDefinition.single("TODO_COMPLETE", 1, In.class, Out.class, (in, ctx) -&gt; {
     *     ctx.changes().update("Todo", in.id(), in.version(), Map.of("done", true));
     *     return new Out(in.id());
     * }).withPermissions("todo.write");
     * </pre>
     *
     * The function runs as a {@link ComputeStep}: synchronously and without I/O. Its result is the process output,
     * returned once the registered changes have been committed.
     */
    public static <I, O> ProcessDefinition<I, O, ProcessContext> single(
        String name, int version, Class<I> inputType, Class<O> outputType, BiFunction<I, ProcessContext, O> body
    ) {
        Objects.requireNonNull(body, "body must not be null");
        return define(name, version, inputType, outputType, ProcessContext.class, pb -> pb
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(SINGLE_INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(SINGLE_OUTPUT, outputType))
            .compute(name, (metadata, ctx) -> ctx.put(SINGLE_OUTPUT, body.apply(ctx.get(SINGLE_INPUT, inputType), ctx))));
    }

    /** This definition requiring the given permissions instead of the declared ones. */
    public ProcessDefinition<I, O, C> withPermissions(String... codes) {
        return new ProcessDefinition<>(name, version, description, inputType, outputType, contextType,
            contextFactory, outputMapper, steps, new LinkedHashSet<>(Arrays.asList(codes)), deprecated, access,
            actsOn, mfa, verifiedEmail, admission, Set.of());
    }

    /** This definition acting on {@code entity}, whose primary key is the input component {@code input}. */
    public ProcessDefinition<I, O, C> actsOn(String entity, String input) {
        return actsOn(entity, input, null);
    }

    /**
     * This definition acting on {@code entity}, shown as an action only under the given condition (a display hint,
     * docs/design/16-content-authoring.md section 3).
     */
    public ProcessDefinition<I, O, C> actsOn(String entity, String input, Consumer<ActsOn.Builder> condition) {
        return new ProcessDefinition<>(name, version, description, inputType, outputType, contextType,
            contextFactory, outputMapper, steps, permissions, deprecated, access,
            ActsOn.of(entity, input, condition), mfa, verifiedEmail, admission, alternativePermissions);
    }

    /** This definition run as the system only (decision D41; {@link ProcessAccess#INTERNAL}). */
    public ProcessDefinition<I, O, C> asInternal() {
        return withAccess(ProcessAccess.INTERNAL);
    }

    /** This definition started only as the given kind allows (decision D41). */
    public ProcessDefinition<I, O, C> withAccess(ProcessAccess kind) {
        return new ProcessDefinition<>(name, version, description, inputType, outputType, contextType,
            contextFactory, outputMapper, steps, permissions, deprecated, kind, actsOn, mfa, verifiedEmail,
            admission, alternativePermissions);
    }

    /** Whether the process runs as the system only ({@link ProcessAccess#INTERNAL}, decision D41). */
    public boolean internal() {
        return access == ProcessAccess.INTERNAL;
    }

    /** Whether the process is listed in the process catalog and may be offered as an action (decision D41). */
    public boolean listed() {
        return access.listed();
    }

    /** This definition requiring a recent second factor of its callers as given. */
    public ProcessDefinition<I, O, C> withMfa(MfaRequirement requirement) {
        return new ProcessDefinition<>(name, version, description, inputType, outputType, contextType,
            contextFactory, outputMapper, steps, permissions, deprecated, access, actsOn, requirement, verifiedEmail,
            admission, alternativePermissions);
    }

    /** This definition requiring (or not) a verified e-mail address of its callers (decision D36 item 3). */
    public ProcessDefinition<I, O, C> withVerifiedEmail(boolean required) {
        return new ProcessDefinition<>(name, version, description, inputType, outputType, contextType,
            contextFactory, outputMapper, steps, permissions, deprecated, access, actsOn, mfa, required, admission,
            alternativePermissions);
    }

    /**
     * This definition queueing its top-level runs before their transaction under {@code family} and the key computed
     * from the input (decision D45; {@link ProcessDefinitionBuilder#admission}).
     */
    public ProcessDefinition<I, O, C> withAdmission(String family, Function<I, ?> key) {
        return new ProcessDefinition<>(name, version, description, inputType, outputType, contextType,
            contextFactory, outputMapper, steps, permissions, deprecated, access, actsOn, mfa, verifiedEmail,
            new Admission<>(family, key), alternativePermissions);
    }
}
