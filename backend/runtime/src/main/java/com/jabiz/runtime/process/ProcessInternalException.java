package com.jabiz.runtime.process;

import com.jabiz.process.ProcessAccess;
import com.jabiz.runtime.PermissionDeniedException;

/**
 * A process was started in a way its declaration does not allow (decision D41): an {@code internal()} process by
 * anyone but the system, an {@code entryOnly()} process other than through its platform entry point, a
 * {@code subProcessOnly()} process on its own. Answered with 403 {@code PROCESS_INTERNAL}, whatever the caller's
 * permissions.
 */
public class ProcessInternalException extends PermissionDeniedException {

    private final String process;
    private final ProcessAccess access;

    public ProcessInternalException(String process, ProcessAccess access, String message) {
        super("-", message);
        this.process = process;
        this.access = access;
    }

    public String process() {
        return process;
    }

    public ProcessAccess access() {
        return access;
    }

    /**
     * How a process of this kind is run, for the caller: the process API answers every process outside the catalog
     * with the same 403 and code, and this says which way it runs instead.
     */
    public static String howItRuns(ProcessAccess access) {
        return switch (access) {
            case OPEN -> "it can be run through the process API";
            case INTERNAL -> "the platform runs it as the system, on events, schedules or callbacks";
            case ENTRY_ONLY -> "it runs through its own endpoint, as the caller";
            case SUB_PROCESS_ONLY -> "it runs only as part of another process";
            case APP_PROXY -> "it runs through its app proxy, for another platform's visitors";
        };
    }
}
