package com.jabiz.runtime.task;

import com.jabiz.context.RequestContext;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.MessageTemplate;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The caller's open tasks (docs/design/18-numbering-approvals-tasks.md section 5.3): assigned to them, or to a
 * permission they hold ({@code *} holds every permission). Only authentication is needed: a user sees their own work.
 * Titles are in the language of the request.
 */
@RestController
@RequestMapping("/api/tasks")
class TaskController {

    /** Most tasks one answer lists; {@code total} counts them all. */
    static final int MAX_TASKS = 200;

    record Task(String taskId, String type, String title, String titleKey, Map<String, String> titleParams,
        String subjectEntity, String subjectId, String link, Instant dueTime, Instant createdTime) {}

    record MyTasks(long total, List<Task> tasks) {}

    // The open latest version of each task; the unique (task_id, version_no) index finds later versions.
    private static final String OPEN = " FROM sys_task_version t WHERE t.status = 'OPEN' AND NOT t.is_deleted"
        + " AND NOT EXISTS (SELECT 1 FROM sys_task_version n WHERE n.task_id = t.task_id"
        + " AND n.version_no > t.version_no)"
        + " AND (t.assignee_user_id = :actor OR t.assignee_permission = ANY(:permissions)"
        + " OR (:all AND t.assignee_permission IS NOT NULL))";

    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

    private final StorageAdapterRegistry storages;
    private final MessageCatalog messages;
    private final JsonMapper json;
    private final String poolRef;

    TaskController(StorageAdapterRegistry storages, MessageCatalog messages, JsonMapper json,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.messages = messages;
        this.json = json;
        this.poolRef = poolRef;
    }

    @GetMapping("/mine")
    Mono<MyTasks> mine(@RequestParam(defaultValue = "100") int limit) {
        return RequestContexts.current().flatMap(request -> {
            Map<String, BoundValue> params = Map.of(
                "actor", BoundValue.of(request.actorId()),
                "permissions", BoundValue.of(request.permissions().toArray(String[]::new)),
                "all", BoundValue.of(request.hasPermission(RequestContext.ALL_PERMISSIONS)));
            Map<String, BoundValue> page = new java.util.HashMap<>(params);
            page.put("limit", BoundValue.of(Math.clamp(limit, 1, MAX_TASKS)));
            var engine = storages.getEngine(poolRef);
            Mono<Long> total = engine.select("SELECT count(*) AS n" + OPEN, params).next()
                .map(row -> ((Number) row.get("n")).longValue());
            Mono<List<Task>> tasks = engine.select("SELECT t.task_id, t.task_type, t.title_key, t.title_params,"
                    + " t.subject_entity, t.subject_id, t.link, t.due_time, t.created_time" + OPEN
                    + " ORDER BY t.due_time NULLS LAST, t.created_time, t.task_id LIMIT :limit", page)
                .map(row -> task(row, request))
                .collectList();
            return total.zipWith(tasks, MyTasks::new);
        });
    }

    private Task task(Map<String, Object> row, RequestContext request) {
        String key = (String) row.get("title_key");
        Map<String, String> params = json.readValue((String) row.get("title_params"), PARAMS);
        String title = messages.find(key, request.locale()).map(text -> MessageTemplate.format(text, params))
            .orElse(key);
        return new Task(String.valueOf(row.get("task_id")), (String) row.get("task_type"), title, key, params,
            (String) row.get("subject_entity"), (String) row.get("subject_id"), (String) row.get("link"),
            instant(row.get("due_time")), instant(row.get("created_time")));
    }

    private static Instant instant(Object value) {
        return value instanceof OffsetDateTime time ? time.toInstant() : (Instant) value;
    }
}
