package com.jabiz.runtime.task;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.steps.EventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates and closes tasks inside a running process (its change set and its transaction): the steps
 * {@link CreateTask} and {@link CloseTasks}, and the approval steps, which make and close the tasks of approval
 * requests. A created task publishes {@value #CREATED}, which the notifications consume.
 */
@Component
public class TaskWriter {

    /** Published for every created task; the payload names the task and its type. */
    public static final String CREATED = "jabiz.task.created";

    /** Payload of {@link #CREATED}. */
    public record Created(String taskId, String type) {}

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final ObjectProvider<EventPublisher> publisher;
    private final JsonMapper json;

    public TaskWriter(DatasetEntityManager entities, DatasetRegistry datasets, ObjectProvider<EventPublisher> publisher,
        JsonMapper json) {
        this.entities = entities;
        this.datasets = datasets;
        this.publisher = publisher;
        this.json = json;
    }

    /** Registers the task in the process's changes and publishes {@link #CREATED}. */
    public Mono<Void> create(ProcessContext ctx, TaskSpec spec) {
        return Mono.defer(() -> {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("taskType", spec.type());
            task.put("titleKey", spec.titleKey());
            task.put("titleParams", json.writeValueAsString(new java.util.TreeMap<>(spec.titleParams())));
            task.put("assigneeUserId", spec.userId());
            task.put("assigneePermission", spec.permission());
            task.put("subjectEntity", spec.subjectEntity());
            task.put("subjectId", spec.subjectId());
            task.put("link", spec.link());
            task.put("status", TaskEntities.OPEN);
            task.put("dueTime", spec.dueTime());
            task.put("sourceKey", spec.sourceKey());
            Object id = ctx.changes().insert(TaskEntities.TASK, task);
            EventPublisher target = publisher.getIfUnique();
            if (target == null) {
                return Mono.error(new IllegalStateException("No EventPublisher is configured"));
            }
            return target.publish(CREATED, new Created(String.valueOf(id), spec.type()), ctx.processSeqId());
        });
    }

    /** Moves the open tasks of {@code sourceKey} to {@code status} ({@code DONE} or {@code CANCELLED}). */
    public Mono<Void> close(ProcessContext ctx, String sourceKey, String status) {
        if (!TaskEntities.DONE.equals(status) && !TaskEntities.CANCELLED.equals(status)) {
            return Mono.error(new IllegalArgumentException("A task is closed as DONE or CANCELLED, not " + status));
        }
        DatasetDefinition dataset = datasets.findById(TaskEntities.TASK_DATASET).orElseThrow();
        EntityQuery query = EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("sourceKey", sourceKey),
            new QueryPredicate.Eq("status", TaskEntities.OPEN)))).limit(TaskEntities.MAX_ROWS).build();
        return entities.queryAll(dataset, TaskEntities.SYS_TASK, query)
            .doOnNext(task -> {
                Map<String, Object> closed = new LinkedHashMap<>();
                closed.put("status", status);
                closed.put("closedBy", ctx.request().actorId());
                ctx.changes().update(TaskEntities.TASK, task.id(), task.version(), closed);
            })
            .then();
    }

    boolean publishes() {
        return publisher.getIfUnique() != null;
    }
}
