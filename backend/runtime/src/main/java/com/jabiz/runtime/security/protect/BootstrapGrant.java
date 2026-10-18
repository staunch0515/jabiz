package com.jabiz.runtime.security.protect;

/**
 * What lets {@code SEC_BOOTSTRAP_ADMIN} give the first administrator {@code *} with
 * {@code jabiz.security.protected-grants.always=true} (decision D48, review M1): {@link ProtectedGrantWriteCheck}
 * accepts it only in that process's own operation, run by the system, on a database without users from any other
 * operation - which is the first administrator's creation itself, nothing more.
 */
public final class BootstrapGrant {

    private final long processSeqId;

    private BootstrapGrant(long processSeqId) {
        this.processSeqId = processSeqId;
    }

    /** For the writes of {@code SEC_BOOTSTRAP_ADMIN} in operation {@code processSeqId}. */
    public static BootstrapGrant of(long processSeqId) {
        return new BootstrapGrant(processSeqId);
    }

    boolean allows(long operationSeqId) {
        return processSeqId == operationSeqId;
    }

    @Override
    public String toString() {
        return "BootstrapGrant[operation " + processSeqId + "]";
    }
}
