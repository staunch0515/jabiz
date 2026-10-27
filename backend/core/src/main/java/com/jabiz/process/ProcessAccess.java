package com.jabiz.process;

/**
 * Who may start a process (decision D41). Every kind but {@link #OPEN} is left out of the process catalog and cannot
 * be offered as an action on rows; the platform checks the kind where the process starts, whatever the caller's
 * permissions, which are checked as well.
 */
public enum ProcessAccess {

    /** Run through the process API and as a sub-process by anyone holding its permissions. */
    OPEN,

    /**
     * A hand-off point of the platform or the application (event consumers, jobs, callbacks): runs as the system
     * only, as a top-level process or under a top-level process run by the system.
     */
    INTERNAL,

    /**
     * Run only by the platform's own entry point for it (signing in, the entity API, uploads, imports...), as the
     * real caller; the entry names the process in its execution options. Never through the process API or as a
     * sub-process.
     */
    ENTRY_ONLY,

    /** Run only as a sub-process, under any top-level process (such as {@code FILE_ARCHIVE}). */
    SUB_PROCESS_ONLY,

    /**
     * Run only through one app proxy, {@code /api/proxy/<proxy>/<process>} (decision D60): a request another platform
     * forwarded and signed for an anonymous visitor of one of its tenants, run as that proxy's caller
     * ({@link com.jabiz.security.AppProxyCaller}). Never through the process API or as a sub-process.
     */
    APP_PROXY;

    /** Whether the process is listed in the catalog and may be offered as an action. */
    public boolean listed() {
        return this == OPEN;
    }
}
