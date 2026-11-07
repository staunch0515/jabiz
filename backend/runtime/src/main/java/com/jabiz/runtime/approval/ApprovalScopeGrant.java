package com.jabiz.runtime.approval;

import java.util.Objects;

/**
 * What lets one write of an approval request or a task change its entry scope's value (decision D76): attached by
 * {@code APPROVAL_SCOPE_MOVE} only, for that instance, scope name, new value and operation. Only this package can make
 * one, so neither the dataset API nor the generic entity processes nor any other process moves a request or task to
 * another scope ({@link ApprovalScopeGuard}).
 *
 * @param entity the entity written ({@code SysApprovalRequest} or {@code SysTask})
 * @param id     the instance's id
 */
record ApprovalScopeGrant(String entity, String id, String scopeName, String toValue, long processSeqId) {

    ApprovalScopeGrant {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(scopeName, "scopeName must not be null");
        Objects.requireNonNull(toValue, "toValue must not be null");
    }

    /** Whether the grant covers writing {@code toValue} under the unchanged {@code scopeName} of this instance. */
    boolean allows(String writtenEntity, Object writtenId, Object writtenName, Object writtenValue,
        long operationSeqId) {
        return entity.equals(writtenEntity) && id.equals(String.valueOf(writtenId))
            && scopeName.equals(writtenName) && toValue.equals(writtenValue) && processSeqId == operationSeqId;
    }
}
