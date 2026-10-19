package com.jabiz.runtime.external;

/**
 * A simulated result was asked for a job of a type that its business parameter holds now (decision D52 item 4): the
 * executor would not claim the job either. Scenario replay reports it with {@link #reason()}.
 */
public class ExternalJobTypeHeldException extends IllegalStateException {

    private final String jobType;
    private final String reason;

    public ExternalJobTypeHeldException(String jobType, String reason) {
        super("External job type " + jobType + " is paused: " + reason);
        this.jobType = jobType;
        this.reason = reason;
    }

    public String jobType() {
        return jobType;
    }

    /** Why the type is held, as {@code parameter <key> is false}, {@code … has no value in effect} and the like. */
    public String reason() {
        return reason;
    }
}
