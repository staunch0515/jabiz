package com.jabiz.runtime.param;

import java.util.Objects;

/**
 * What lets one write of a controlled parameter pass {@link ControlledParamGuard} (decision D40): attached by
 * {@link ParamControlTarget} to the single write the publication of an approved controlled change makes, for that
 * parameter and that operation only. Only this package can make one, so no other code, a process of any name
 * included, can write a controlled parameter.
 */
final class ParamWriteGrant {

    private final String key;
    private final long processSeqId;

    ParamWriteGrant(String key, long processSeqId) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.processSeqId = processSeqId;
    }

    boolean allows(Object writtenKey, long operationSeqId) {
        return key.equals(writtenKey) && processSeqId == operationSeqId;
    }

    @Override
    public String toString() {
        return "ParamWriteGrant[" + key + " in operation " + processSeqId + "]";
    }
}
