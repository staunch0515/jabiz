-- A person's resolution of a task (docs/design/18-numbering-approvals-tasks.md section 5.3a, phase 16y): the note they
-- gave when they closed it with TASK_RESOLVE. Tasks closed by their processes have none. Sealed rows are not affected:
-- a seal digests only the columns it recorded (design 21 section 2).
ALTER TABLE sys_task_version ADD COLUMN close_note varchar(1000);
