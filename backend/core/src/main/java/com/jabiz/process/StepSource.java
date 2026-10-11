package com.jabiz.process;

/**
 * What a process builder takes as a step: a {@link StepSpec}, or a step factory's fluent builder that makes one (for
 * example {@code StartExternalJob.of(…).reference(…).outputKey(…)}).
 */
public interface StepSource<M, C extends ProcessContext> {

    StepSpec<M, C> toStepSpec();
}
