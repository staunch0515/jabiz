package com.jabiz.runtime.firstlaunch.accounts;

/**
 * What lets the first launch's own processes give, enable or change the waiting role ({@link WaitingRoleWriteCheck}):
 * accepted only in the operation it was made for, run by the system, in one of those processes.
 */
public final class FirstLaunchGrant {

    private final long processSeqId;

    private FirstLaunchGrant(long processSeqId) {
        this.processSeqId = processSeqId;
    }

    /** For the writes of a first launch process in operation {@code processSeqId}. */
    public static FirstLaunchGrant of(long processSeqId) {
        return new FirstLaunchGrant(processSeqId);
    }

    boolean allows(long operationSeqId) {
        return processSeqId == operationSeqId;
    }

    @Override
    public String toString() {
        return "FirstLaunchGrant[operation " + processSeqId + "]";
    }
}
