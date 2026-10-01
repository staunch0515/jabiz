-- Documents sent by e-mail (docs/design/22-documents.md section 5, decision D30): the addresses a document goes to by
-- default, kept with it when it is issued (read from the layout's recipients column), and one delivery per address
-- and send - the message as written, who asked for it and when - with every attempt to send it after the commit.
-- The attachment is the document's kept PDF, never laid out again. Append-only like the archive they belong to.
ALTER TABLE sys_document_run ADD COLUMN recipients text;

CREATE TABLE sys_document_delivery (
    delivery_id    uuid          PRIMARY KEY,
    run_id         uuid          NOT NULL REFERENCES sys_document_run (run_id),
    address        varchar(320)  NOT NULL,
    subject        varchar(300)  NOT NULL,
    body           varchar(4000) NOT NULL,
    requested_by   varchar(100)  NOT NULL,
    created_time   timestamptz   NOT NULL,
    process_seq_id bigint        NOT NULL REFERENCES op_process (process_seq_id),
    version        bigint        NOT NULL
);
CREATE INDEX sys_document_delivery_run_idx ON sys_document_delivery (run_id, created_time);
CREATE INDEX sys_document_delivery_process_idx ON sys_document_delivery (process_seq_id);
SELECT jabiz_protect_append_only('sys_document_delivery');

-- Every attempt to send a delivery; a delivery is sent when it has a SENT attempt.
CREATE TABLE sys_document_delivery_attempt (
    delivery_id    uuid          NOT NULL REFERENCES sys_document_delivery (delivery_id),
    attempt_no     integer       NOT NULL,
    outcome        varchar(10)   NOT NULL CHECK (outcome IN ('SENT', 'FAILED')),
    error          varchar(2000),
    attempted_time timestamptz   NOT NULL,
    PRIMARY KEY (delivery_id, attempt_no)
);
SELECT jabiz_protect_append_only('sys_document_delivery_attempt');
