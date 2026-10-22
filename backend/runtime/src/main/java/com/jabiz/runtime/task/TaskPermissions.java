package com.jabiz.runtime.task;

/** Permission codes of tasks and notifications (docs/design/18-numbering-approvals-tasks.md section 5). */
public final class TaskPermissions {

    /** Read every task and notification through their datasets (one's own tasks need no permission). */
    public static final String READ = "task.read";
    /** The declared write permission of the task datasets, which only processes write. */
    public static final String WRITE = "task.write";
    /**
     * Run {@code TASK_RESOLVE}: close, with a note, an open task one can see (assigned to one, or to a permission one
     * holds), except approvals' ({@link TaskResolution}).
     */
    public static final String RESOLVE = "task.resolve";
    /** Run {@code TASK_NOTIFY}; the event consumer runs it as the system. */
    public static final String NOTIFY = "task.notify";

    private TaskPermissions() {}
}
