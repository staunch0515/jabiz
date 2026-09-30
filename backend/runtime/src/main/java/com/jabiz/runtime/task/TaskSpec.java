package com.jabiz.runtime.task;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A task to create (docs/design/18-numbering-approvals-tasks.md section 5.1): for one user, or for every holder of a
 * permission. The title is a message key (in the platform's or the application's bundles) with parameters, so each
 * user reads it in their language.
 *
 * @param type        what kind of task ({@code approval}, an application's own); lower-case, dotted
 * @param titleKey    message key of the title
 * @param titleParams parameters of the title
 * @param userId      the assignee; or null with {@code permission}
 * @param permission  the permission whose holders see the task; or null with {@code userId}
 * @param subjectEntity the entity the task is about, if any
 * @param subjectId   its id
 * @param link        where in the admin the task is done (a path such as {@code /tasks}), if anywhere
 * @param dueTime     when it should be done, if known
 * @param sourceKey   what created it ({@code approval:<request>}): {@link CloseTasks} closes the open tasks of a key
 */
public record TaskSpec(String type, String titleKey, Map<String, String> titleParams, String userId,
    String permission, String subjectEntity, String subjectId, String link, Instant dueTime, String sourceKey) {

    /** Types of tasks. */
    public static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9._-]{0,99}");

    public TaskSpec {
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Task type '" + type + "' must match " + TYPE.pattern());
        }
        if (titleKey == null || titleKey.isBlank()) {
            throw new IllegalArgumentException("titleKey must not be blank");
        }
        if ((userId == null) == (permission == null)) {
            throw new IllegalArgumentException("A task is assigned to a user or to a permission, not both");
        }
        titleParams = titleParams == null ? Map.of() : Map.copyOf(titleParams);
    }

    /** A task for one user. */
    public static TaskSpec forUser(String type, String titleKey, Map<String, String> titleParams, String userId) {
        return new TaskSpec(type, titleKey, titleParams, Objects.requireNonNull(userId), null, null, null, null, null,
            null);
    }

    /** A task for every holder of {@code permission}; the first to do it does it for all. */
    public static TaskSpec forPermission(String type, String titleKey, Map<String, String> titleParams,
        String permission) {
        return new TaskSpec(type, titleKey, titleParams, null, Objects.requireNonNull(permission), null, null, null,
            null, null);
    }

    public TaskSpec about(String entity, Object id) {
        return new TaskSpec(type, titleKey, titleParams, userId, permission, entity, String.valueOf(id), link,
            dueTime, sourceKey);
    }

    public TaskSpec link(String path) {
        return new TaskSpec(type, titleKey, titleParams, userId, permission, subjectEntity, subjectId, path, dueTime,
            sourceKey);
    }

    public TaskSpec due(Instant time) {
        return new TaskSpec(type, titleKey, titleParams, userId, permission, subjectEntity, subjectId, link, time,
            sourceKey);
    }

    public TaskSpec source(String key) {
        return new TaskSpec(type, titleKey, titleParams, userId, permission, subjectEntity, subjectId, link, dueTime,
            key);
    }
}
