package com.jabiz.runtime.temporal;

/**
 * Permission codes of the temporal model, checked against {@link com.jabiz.context.RequestContext#permissions()}
 * (default deny).
 */
public final class TemporalPermissions {

    /** Writing versions that take effect before the time of the operation (docs/design/04 section 3.1). */
    public static final String BACKDATE = "temporal.backdate";
    /** Reverting and redoing operations (decision D2). */
    public static final String REVERT = "temporal.revert";
    /** Reading operation details. */
    public static final String OPERATION_READ = "operation.read";

    private TemporalPermissions() {}
}
