package com.jabiz.runtime.approval;

import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a controlled change can change (docs/design/18-numbering-approvals-tasks.md section 3.5): one bean per kind
 * of control. {@link ControlChanges} keeps the proposal, the four eyes and the publication; a target loads the state
 * a change is based on, checks a proposal and writes a published change, checking it again. Platform code: the
 * approval rules, limits and SoD rules ({@link ApprovalControlTarget}) and the controlled business parameters
 * (decision D40) are the targets.
 */
public interface ControlTarget {

    /**
     * A change as proposed or as recorded.
     *
     * @param targetId      the instance the change names; null when it creates one (as recorded at the proposal)
     * @param values        the fields to set (as given, or as recorded); empty for none
     * @param effectiveTime when the change takes effect; null when it is published
     */
    record Request(String entity, String targetId, boolean delete, Map<String, Object> values,
        Instant effectiveTime) {

        public Request {
            values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /**
     * What a proposal records.
     *
     * @param targetId the instance the change is for; null when it creates one
     * @param values   the values to store with the change; null to store none
     */
    record Proposal(String targetId, Map<String, Object> values) {}

    /** The entities this target changes. */
    Set<String> entities();

    /** The dataset changes of {@code entity} are written through. */
    String dataset(String entity);

    /** The state the change is based on; empty when there is none (an instance to create). */
    Mono<Object> load(Request request, ProcessContext ctx);

    /**
     * Checks a proposal against {@code loaded} (null: nothing loaded), rejecting it in {@code ctx} when it cannot be
     * made.
     *
     * @return what to record; null after rejecting it
     */
    Proposal propose(Request request, Object loaded, ProcessContext ctx);

    /**
     * Checks a recorded change again against the state it is based on now and registers its writes in
     * {@code writes} (the target's dataset, at the change's effective time).
     *
     * @return the id of the changed instance; null after rejecting the change in {@code ctx}
     */
    Object publish(Request request, Object loaded, ProcessContext ctx, ChangeSet.Target writes);
}
