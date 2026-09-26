package com.jabiz.process;

import com.jabiz.context.RequestContext;

import java.time.Instant;
import java.util.Objects;

/**
 * What the platform knows when an execution starts, handed to {@link ProcessDefinition.ContextFactory}.
 *
 * @param processSeqId identifier of the execution ({@code op_process.process_seq_id})
 * @param opTime       the one time of the operation; sub-processes share their parent's
 * @param request      who is acting, for which tenant, in which language
 * @param ids          generates primary keys for {@link ChangeSet#insert}
 */
public record ProcessStart(long processSeqId, Instant opTime, RequestContext request, IdAssigner ids) {

    public ProcessStart {
        Objects.requireNonNull(opTime, "opTime must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(ids, "ids must not be null");
    }
}
