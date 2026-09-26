package com.jabiz.process;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State of one process execution, shared by its steps (docs/design/06-process.md section 3): who is acting and
 * when, the changes registered for the platform to commit, the business rule violations found so far, and values
 * steps hand to each other by key. Concrete processes extend it with typed accessors. It is thread-safe because
 * steps may complete on different threads.
 */
public class ProcessContext {

    private final long processSeqId;
    private final Instant opTime;
    private final RequestContext request;
    private final ChangeSet changes;
    private final List<Violation> violations = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public ProcessContext(ProcessStart start) {
        Objects.requireNonNull(start, "start must not be null");
        this.processSeqId = start.processSeqId();
        this.opTime = start.opTime();
        this.request = start.request();
        this.changes = new ChangeSet(start.ids());
    }

    /** Identifier of this execution, used to trace every change made by the process. */
    public long processSeqId() {
        return processSeqId;
    }

    /** The one time of this operation; every version it writes is recorded at this time. */
    public Instant opTime() {
        return opTime;
    }

    /** Who is acting, for which tenant, in which language. */
    public RequestContext request() {
        return request;
    }

    /** Changes to commit when the in-transaction steps have run. */
    public ChangeSet changes() {
        return changes;
    }

    /**
     * Business rule violations found so far; steps add to it. When any are present after the last in-transaction
     * step, the process fails as a whole (422, all violations) and nothing is committed.
     */
    public List<Violation> violations() {
        return violations;
    }

    /** Adds a violation; shorthand for {@code violations().add(violation)}. */
    public void reject(Violation violation) {
        violations.add(Objects.requireNonNull(violation, "violation must not be null"));
    }

    public boolean hasViolations() {
        return !violations.isEmpty();
    }

    /** Stores a value; a null value removes the key. */
    public void put(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    public Object get(String key) {
        return attributes.get(key);
    }

    public <T> T get(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Context key '" + key + "' holds " + value.getClass().getName()
                + " but " + type.getName() + " was expected");
        }
        return type.cast(value);
    }

    public boolean contains(String key) {
        return attributes.containsKey(key);
    }
}
