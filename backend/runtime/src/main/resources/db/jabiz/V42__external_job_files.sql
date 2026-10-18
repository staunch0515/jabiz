-- Files of asynchronous external jobs (docs/design/11-ledger-events-jobs.md section 4a; decision D50 items 2-3).
-- Append-only like the other job tables.
--
-- INPUT: a file the starting process gave the job, inserted with the job in that process's transaction once the
-- requester was found allowed to read it. The executor copies these files, and no others, for each attempt.
-- OUTPUT: a file an attempt produced, stored and registered (FILE_REGISTER, as the requester's upload) before the
-- attempt was recorded, and inserted with the attempt: the completion process gets the files of the attempt that
-- succeeded. Those of attempts that did not (failed after storing, cancelled, dropped after their lease) are referred
-- to by nothing and swept as orphans (FILE_SWEEP); this table keeps which job produced them.
-- file_id (the text of sys_file.file_id) has no foreign key: files are deleted (sweep, FILE_DELETE), the record of
-- what a job read or wrote stays.
CREATE TABLE sys_external_job_file (
    job_file_id     varchar(36)   PRIMARY KEY,
    job_id          varchar(36)   NOT NULL REFERENCES sys_external_job (job_id),
    direction       varchar(10)   NOT NULL CHECK (direction IN ('INPUT', 'OUTPUT')),
    position        integer       NOT NULL CHECK (position >= 0),
    attempt_no      integer,
    output_key      varchar(64),
    file_id         varchar(36)   NOT NULL,
    policy          varchar(100)  NOT NULL,
    size_bytes      bigint        NOT NULL CHECK (size_bytes >= 0),
    sha256          varchar(64)   NOT NULL,
    recorded_time   timestamptz   NOT NULL,
    CHECK ((direction = 'INPUT' AND attempt_no IS NULL AND output_key IS NULL)
        OR (direction = 'OUTPUT' AND attempt_no IS NOT NULL AND output_key IS NOT NULL))
);
CREATE UNIQUE INDEX sys_external_job_file_input_idx ON sys_external_job_file (job_id, position)
    WHERE direction = 'INPUT';
CREATE UNIQUE INDEX sys_external_job_file_output_idx ON sys_external_job_file (job_id, attempt_no, output_key)
    WHERE direction = 'OUTPUT';
-- Which jobs read or produced a file.
CREATE INDEX sys_external_job_file_file_idx ON sys_external_job_file (file_id);
SELECT jabiz_protect_append_only('sys_external_job_file');
