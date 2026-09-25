-- One number per process execution (process_seq_id), shared by all application instances.
-- op_process itself arrives in ROADMAP phase 4 (docs/design/04-temporal-append-only.md section 2.2).
CREATE SEQUENCE op_process_seq AS bigint;
