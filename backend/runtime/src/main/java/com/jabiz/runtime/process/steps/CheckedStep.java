package com.jabiz.runtime.process.steps;

import java.util.List;

/**
 * A platform step whose metadata refers to other declarations (datasets, templates, processes, beans); the startup
 * check of processes asks it for the problems of each use, so that a misspelled reference fails startup rather than
 * the first execution.
 */
public interface CheckedStep<M> {

    /** Problems of one use of the step; empty when its references resolve. */
    List<String> problems(M metadata);
}
