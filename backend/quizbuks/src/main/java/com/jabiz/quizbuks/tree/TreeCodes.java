package com.jabiz.quizbuks.tree;

import java.util.List;

/** The error codes of the knowledge graphs' processes (F-S2); the rules' own are in {@link TreeRules}. */
public final class TreeCodes {

    /** The graph changed since the caller read it (another save, a decision). */
    public static final String CHANGED = "QB_TREE_CHANGED";
    /** The graph or its version is not in a state that allows the act. */
    public static final String STATE = "QB_TREE_STATE";
    /** The Sponsor has as many graphs of their own as {@code qb.tree.sponsor.max-trees} allows. */
    public static final String LIMIT = "QB_TREE_LIMIT";
    /** Only a template of the platform whose nodes Sponsors light is copied by operators. */
    public static final String NOT_A_TEMPLATE = "QB_TREE_NOT_A_TEMPLATE";
    /** The operator has a copy of that template already. */
    public static final String COPY_EXISTS = "QB_TREE_COPY_EXISTS";
    /** A draft with problems is not submitted; the problems are the rules' codes. */
    public static final String HAS_PROBLEMS = "QB_TREE_HAS_PROBLEMS";
    /** A condition that is no condition (its shape), or a node named twice in a save. */
    public static final String CONDITION_INVALID = "QB_TREE_CONDITION_INVALID";
    /** The graph's name is required in at least one language. */
    public static final String NAME_REQUIRED = "QB_TREE_NAME_REQUIRED";

    public static final List<String> ALL = List.of(CHANGED, STATE, LIMIT, NOT_A_TEMPLATE, COPY_EXISTS, HAS_PROBLEMS,
        CONDITION_INVALID, NAME_REQUIRED);

    private TreeCodes() {}
}
