-- How people know the document of an approval request (a journal entry's number), shown in the approvers' tasks
-- (docs/design/18-numbering-approvals-tasks.md section 5.2). Requests made before have none and show the id.
ALTER TABLE sys_approval_request_version ADD COLUMN reference varchar(100);
