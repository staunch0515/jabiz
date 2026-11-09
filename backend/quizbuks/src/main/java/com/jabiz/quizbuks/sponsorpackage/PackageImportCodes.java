package com.jabiz.quizbuks.sponsorpackage;

import java.util.List;

/** The error codes of the sponsor package's imports (F-C4); each has its text in three languages. */
public final class PackageImportCodes {

    /** The account submitted a publication: only the profile part can be imported. */
    public static final String SUBMITTED = "QB_PACKAGE_SUBMITTED";
    /** Another package's import is not finished: go on with it or give it up first. */
    public static final String OPEN = "QB_PACKAGE_IMPORT_OPEN";
    /** The import is finished or given up. */
    public static final String CLOSED = "QB_PACKAGE_IMPORT_CLOSED";
    /** A step names no action, or more than one, or one the import's way does not take. */
    public static final String STEP_INVALID = "QB_PACKAGE_STEP_INVALID";

    public static final List<String> ALL = List.of(SUBMITTED, OPEN, CLOSED, STEP_INVALID);

    private PackageImportCodes() {}
}
