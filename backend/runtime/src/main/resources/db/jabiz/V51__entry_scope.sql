-- Entry scopes (decision D64; docs/design/10-security.md section 13.4): approval requests, their decisions and tasks
-- say which entry scope they belong to, so that a scoped session sees and decides its own only. Empty for rows of no
-- scope, which only sessions without a scope see. Columns only, as the append-only tables allow (decision D58).
ALTER TABLE sys_approval_request_version ADD COLUMN scope_name varchar(64), ADD COLUMN scope_value varchar(128);
ALTER TABLE sys_approval_decision ADD COLUMN scope_name varchar(64), ADD COLUMN scope_value varchar(128);
ALTER TABLE sys_task_version ADD COLUMN scope_name varchar(64), ADD COLUMN scope_value varchar(128);
CREATE INDEX sys_approval_request_version_scope_idx ON sys_approval_request_version (scope_name, scope_value)
    WHERE scope_name IS NOT NULL;
CREATE INDEX sys_task_version_scope_idx ON sys_task_version (scope_name, scope_value) WHERE scope_name IS NOT NULL;
ALTER TABLE sys_approval_evaluation ADD COLUMN scope_name varchar(64), ADD COLUMN scope_value varchar(128);
