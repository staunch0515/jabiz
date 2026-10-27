package com.jabiz.quizbuks.audience;

import java.util.List;

/** The matching rules' violation codes (Q39); their messages are in the three languages. */
public final class AudienceCodes {

    /** No registered criterion has this name. */
    public static final String UNKNOWN = "QB_AUDIENCE_UNKNOWN";
    /** The same criterion twice in one publication or one profile. */
    public static final String REPEATED = "QB_AUDIENCE_REPEATED";
    /** An option criterion without an option chosen. */
    public static final String OPTIONS_REQUIRED = "QB_AUDIENCE_OPTIONS_REQUIRED";
    /** A value that is not one of the criterion's options. */
    public static final String OPTION = "QB_AUDIENCE_OPTION";
    /** A range criterion without both bounds. */
    public static final String RANGE_REQUIRED = "QB_AUDIENCE_RANGE_REQUIRED";
    /** Bounds reversed or outside the criterion's. */
    public static final String RANGE = "QB_AUDIENCE_RANGE";
    /** More conditions than a publication may set. */
    public static final String TOO_MANY = "QB_AUDIENCE_TOO_MANY";
    /** Several options told for a criterion that takes one. */
    public static final String ONE_OPTION = "QB_AUDIENCE_ONE_OPTION";
    /** A birth year outside the accepted years. */
    public static final String BIRTH_YEAR = "QB_AUDIENCE_BIRTH_YEAR";
    /** An invited publication (Q25) takes its list, not conditions. */
    public static final String INVITED = "QB_AUDIENCE_INVITED";
    /** The taker's profile does not match the publication's conditions (the start's check). */
    public static final String NOT_MATCHED = "QB_AUDIENCE_NOT_MATCHED";

    public static final List<String> ALL = List.of(UNKNOWN, REPEATED, OPTIONS_REQUIRED, OPTION, RANGE_REQUIRED, RANGE,
        TOO_MANY, ONE_OPTION, BIRTH_YEAR, INVITED, NOT_MATCHED);

    private AudienceCodes() {}
}
