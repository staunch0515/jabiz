package com.jabiz.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Fluent builder used through {@link ProcessDefinition#define}. */
public final class ProcessDefinitionBuilder<I, O, C extends ProcessContext> {

    private final String name;
    private final int version;
    private final Class<I> inputType;
    private final Class<O> outputType;
    private final Class<C> contextType;
    private String description;
    private ProcessDefinition.ContextFactory<I, C> contextFactory;
    private Function<C, O> outputMapper;
    private final List<StepDefinition<?, C>> steps = new ArrayList<>();
    private final Set<String> stepNames = new HashSet<>();
    private final Set<String> permissions = new LinkedHashSet<>();
    private final Set<String> alternativePermissions = new LinkedHashSet<>();
    private boolean deprecated;
    private ProcessAccess access = ProcessAccess.OPEN;
    private com.jabiz.security.MfaRequirement mfa = com.jabiz.security.MfaRequirement.NONE;
    private boolean verifiedEmail;
    private ActsOn actsOn;
    private Admission<I> admission;

    ProcessDefinitionBuilder(String name, int version, Class<I> inputType, Class<O> outputType, Class<C> contextType) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.version = version;
        this.inputType = inputType;
        this.outputType = outputType;
        this.contextType = contextType;
    }

    public ProcessDefinitionBuilder<I, O, C> description(String description) {
        this.description = description;
        return this;
    }

    public ProcessDefinitionBuilder<I, O, C> contextFactory(ProcessDefinition.ContextFactory<I, C> contextFactory) {
        this.contextFactory = contextFactory;
        return this;
    }

    public ProcessDefinitionBuilder<I, O, C> outputMapper(Function<C, O> outputMapper) {
        this.outputMapper = outputMapper;
        return this;
    }

    /** The entity the process acts on; see {@link ProcessDefinition#actsOn(String, String, java.util.function.Consumer)}. */
    public ProcessDefinitionBuilder<I, O, C> actsOn(String entity, String input,
        java.util.function.Consumer<ActsOn.Builder> condition) {
        this.actsOn = ActsOn.of(entity, input, condition);
        return this;
    }

    /** The entity the process acts on, whose primary key is the input component {@code input}. */
    public ProcessDefinitionBuilder<I, O, C> actsOn(String entity, String input) {
        return actsOn(entity, input, null);
    }

    /** Permission codes the caller needs, all of them. A process without any fails startup outside development. */
    public ProcessDefinitionBuilder<I, O, C> permissions(String... codes) {
        Collections.addAll(permissions, codes);
        return this;
    }

    /**
     * Another set of permission codes that also lets a caller start the process, all of them, when it lacks
     * {@link #permissions}. The process itself must then decide what each set allows: the platform uses it for
     * {@code APPROVAL_DECIDE}, whose protected changes are decided with their own permission (decision D51). Not
     * for business processes, which declare one set.
     */
    public ProcessDefinitionBuilder<I, O, C> orPermissions(String... codes) {
        Collections.addAll(alternativePermissions, codes);
        return this;
    }

    /**
     * Callers need a recent second factor (docs/design/10-security.md section 10); checked at the entry points like
     * the permissions.
     */
    public ProcessDefinitionBuilder<I, O, C> requiresMfa(com.jabiz.security.MfaRequirement requirement) {
        this.mfa = java.util.Objects.requireNonNull(requirement, "requirement must not be null");
        return this;
    }

    /**
     * Callers need a verified e-mail address (docs/design/10-security.md section 15; decision D36 item 3); checked at
     * the entry points like the permissions (403 {@code EMAIL_NOT_VERIFIED}).
     */
    public ProcessDefinitionBuilder<I, O, C> requiresVerifiedEmail() {
        this.verifiedEmail = true;
        return this;
    }

    /** Callers always need a recent second factor. */
    public ProcessDefinitionBuilder<I, O, C> requiresMfa() {
        return requiresMfa(com.jabiz.security.MfaRequirement.ALWAYS);
    }

    /**
     * Marks the process as a hand-off point run as the system only - event consumers, jobs, callbacks (decision D41;
     * {@link ProcessAccess#INTERNAL}): anyone else gets 403 {@code PROCESS_INTERNAL}, whatever their permissions, as
     * do sub-process calls under a process not run by the system. Left out of the process catalog.
     */
    public ProcessDefinitionBuilder<I, O, C> internal() {
        this.access = ProcessAccess.INTERNAL;
        return this;
    }

    /**
     * Marks the process as run only by the platform's own entry point for it, as the real caller (decision D41;
     * {@link ProcessAccess#ENTRY_ONLY}): the process API and sub-process calls get 403 {@code PROCESS_INTERNAL}.
     * Left out of the process catalog.
     */
    public ProcessDefinitionBuilder<I, O, C> entryOnly() {
        this.access = ProcessAccess.ENTRY_ONLY;
        return this;
    }

    /**
     * Marks the process as run only as a sub-process, under any top-level process (decision D41;
     * {@link ProcessAccess#SUB_PROCESS_ONLY}): started on its own it gets 403 {@code PROCESS_INTERNAL}. Left out of
     * the process catalog.
     */
    public ProcessDefinitionBuilder<I, O, C> subProcessOnly() {
        this.access = ProcessAccess.SUB_PROCESS_ONLY;
        return this;
    }

    /**
     * Top-level runs queue in the process before their transaction, at most a few of one key at a time per instance
     * (docs/design/06-process.md section 4.1; decision D45). Declare it for the process's outermost hot
     * {@code HoldLock}, with the same name: {@code family + ":" + key(input)}, built by the same helper as the lock's
     * name. The key comes from the input alone; null or blank does not queue. Sub-processes never queue. At most one
     * per process.
     *
     * <pre>
     * .admission("qb.pub", SubmitInput::publicationId)
     * .step("Lock the publication", HoldLock.exclusive(ctx -&gt; "qb.pub:" + publicationId(ctx)))
     * </pre>
     */
    public ProcessDefinitionBuilder<I, O, C> admission(String family, Function<I, ?> key) {
        if (admission != null) {
            throw new IllegalArgumentException("Process " + name + ": admission is declared once (decision D45)");
        }
        this.admission = new Admission<>(family, key);
        return this;
    }

    /** Marks this version as superseded: it still runs, but processes calling it are reported at startup. */
    public ProcessDefinitionBuilder<I, O, C> deprecated() {
        this.deprecated = true;
        return this;
    }

    /**
     * Appends an in-transaction step. The compiler checks that the handler accepts exactly the given metadata
     * type and runs against this process's context type.
     */
    public <M> ProcessDefinitionBuilder<I, O, C> step(
        String stepName, Class<? extends StepImplementation<M, C>> handlerClass, M metadata
    ) {
        return add(new StepDefinition<>(stepName, handlerClass, metadata));
    }

    /** Appends an in-transaction platform step, as returned by the step's factory (for example {@code LoadEntity.by}). */
    public <M> ProcessDefinitionBuilder<I, O, C> step(String stepName, StepSource<M, C> source) {
        StepSpec<M, C> spec = source.toStepSpec();
        return add(new StepDefinition<>(stepName, spec.handlerClass(), spec.metadata()));
    }

    /** Appends a step that runs once the transaction has committed, retried as {@link RetryPolicy#DEFAULT}. */
    public <M> ProcessDefinitionBuilder<I, O, C> afterCommit(
        String stepName, Class<? extends StepImplementation<M, C>> handlerClass, M metadata
    ) {
        return afterCommit(stepName, handlerClass, metadata, RetryPolicy.DEFAULT);
    }

    public <M> ProcessDefinitionBuilder<I, O, C> afterCommit(
        String stepName, Class<? extends StepImplementation<M, C>> handlerClass, M metadata, RetryPolicy retryPolicy
    ) {
        return add(new StepDefinition<>(stepName, handlerClass, metadata, StepPhase.AFTER_COMMIT, retryPolicy, null));
    }

    public <M> ProcessDefinitionBuilder<I, O, C> afterCommit(String stepName, StepSource<M, C> source) {
        StepSpec<M, C> spec = source.toStepSpec();
        return afterCommit(stepName, spec.handlerClass(), spec.metadata(), RetryPolicy.DEFAULT);
    }

    /** Appends the steps a shared block declares (several processes with a common part). */
    public ProcessDefinitionBuilder<I, O, C> steps(java.util.function.Consumer<ProcessDefinitionBuilder<I, O, C>> block) {
        block.accept(this);
        return this;
    }

    /** Appends an in-transaction computation declared in place; it needs no bean. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ProcessDefinitionBuilder<I, O, C> compute(String stepName, ComputeStep<NoMetadata, C> computation) {
        Objects.requireNonNull(computation, "computation must not be null");
        Class<? extends StepImplementation<NoMetadata, C>> type = (Class) ComputeStep.class;
        return add(new StepDefinition<>(stepName, type, NoMetadata.INSTANCE, StepPhase.IN_TX, RetryPolicy.NONE,
            computation));
    }

    private ProcessDefinitionBuilder<I, O, C> add(StepDefinition<?, C> step) {
        if (!stepNames.add(step.stepName())) {
            throw new IllegalArgumentException("Process " + name + ": duplicate step name '" + step.stepName() + "'");
        }
        steps.add(step);
        return this;
    }

    ProcessDefinition<I, O, C> build() {
        return new ProcessDefinition<>(
            name, version, description, inputType, outputType, contextType,
            contextFactory, outputMapper, steps, permissions, deprecated, access, actsOn, mfa, verifiedEmail,
            admission, alternativePermissions);
    }
}
