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
}
