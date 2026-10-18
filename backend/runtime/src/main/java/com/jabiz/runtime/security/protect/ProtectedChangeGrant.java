package com.jabiz.runtime.security.protect;

import java.util.Objects;

/**
 * What lets the one write of an approved protected change pass {@link ProtectedGrantWriteCheck} (decision D48, as
 * {@code ParamWriteGrant} for D40): attached by the process applying the change to that write only, for that entity
 * and operation. Only this package can make one, so no other code, a process of any name included, can make a
 * protected grant without the approval.
 */
final class ProtectedChangeGrant {

    private final String entity;
    private final String changeId;
    private final long processSeqId;

    ProtectedChangeGrant(String entity, String changeId, long processSeqId) {
        this.entity = Objects.requireNonNull(entity, "entity must not be null");
        this.changeId = Objects.requireNonNull(changeId, "changeId must not be null");
        this.processSeqId = processSeqId;
    }

    boolean allows(String writtenEntity, long operationSeqId) {
        return entity.equals(writtenEntity) && processSeqId == operationSeqId;
    }

    @Override
    public String toString() {
        return "ProtectedChangeGrant[" + entity + " for change " + changeId + " in operation " + processSeqId + "]";
    }
}
