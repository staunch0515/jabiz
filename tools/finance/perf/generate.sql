-- Generates one year of general-ledger postings for the performance probe (docs/finance/perf.md): balanced
-- transactions of two or three lines on the books' postable accounts, spread over the year's posting dates, each with
-- its FinPosting. Written straight into the tables (one op_process row per run), bypassing LEDGER_POST, the audit
-- records and the numbering, to build millions of lines in minutes: for a database of its own only (probe.sh checks
-- the name). The processes are measured separately. Variables: year, lines (ledger lines wanted), seed (0 … 1).
--   psql -v year=2026 -v lines=2500000 -v seed=0.42 -f generate.sql
\set ON_ERROR_STOP on
BEGIN;
SELECT setseed(:seed);

-- Time-ordered identifiers, as the application's UUIDv7 keys are.
CREATE FUNCTION pg_temp.uuid7(at timestamptz) RETURNS uuid LANGUAGE sql VOLATILE AS $$
  SELECT (lpad(to_hex((extract(epoch FROM at) * 1000)::bigint), 12, '0') || '7'
    || substr(md5(random()::text), 1, 3) || to_hex(8 + floor(random() * 4)::int)
    || substr(md5(random()::text), 1, 15))::uuid
$$;

CREATE TEMP TABLE perf_run ON COMMIT DROP AS
SELECT nextval('op_process_seq') AS seq_id, clock_timestamp() AS made;
INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id, reason, op_time)
SELECT seq_id, 'PERF_GENERATE', 1, 'perf', 'performance probe, year ' || :year, made FROM perf_run;

-- The accounts that take postings, as one array: a line picks its account by index.
CREATE TEMP TABLE perf_account ON COMMIT DROP AS
SELECT array_agg(a.account_id ORDER BY a.account_code) AS ids
FROM (SELECT DISTINCT ON (account_id) * FROM ledger_account_version
      ORDER BY account_id, effect_start_time DESC, version_no DESC) a
WHERE NOT a.is_deleted AND a.enabled AND NOT a.summary;

-- Transactions: on average 2.5 lines each (two or three), in the order of their posting dates. Each is recorded (in
-- effect, and known) at the start of its posting day in Chicago, as a posting made that day would be, and never after
-- the generation itself.
CREATE TEMP TABLE perf_day ON COMMIT DROP AS
SELECT i, make_date(:year, 1, 1) + (random() * (make_date(:year, 12, 31) - make_date(:year, 1, 1)))::int AS posting_date
FROM generate_series(1, (:lines / 2.5)::int) AS i;

CREATE TEMP TABLE perf_tx ON COMMIT DROP AS
SELECT pg_temp.uuid7(recorded) AS tx_id, pg_temp.uuid7(recorded) AS posting_id, i, posting_date, recorded,
       2 + (i % 2) AS line_count,
       round((10 + random() * 9990)::numeric, 2) AS a1,
       round((10 + random() * 9990)::numeric, 2) AS a2
FROM (SELECT row_number() OVER (ORDER BY posting_date, i) AS i, posting_date,
             least(posting_date::timestamp AT TIME ZONE 'America/Chicago', (SELECT made FROM perf_run)) AS recorded
      FROM perf_day) d;

CREATE TEMP TABLE perf_line ON COMMIT DROP AS
SELECT pg_temp.uuid7(t.recorded) AS entry_id, t.tx_id, l.line_no,
       CASE WHEN l.line_no < t.line_count THEN 'DEBIT' ELSE 'CREDIT' END AS direction,
       CASE WHEN l.line_no = 1 THEN t.a1
            WHEN l.line_no < t.line_count THEN t.a2
            WHEN t.line_count = 2 THEN t.a1
            ELSE t.a1 + t.a2 END AS amount,
       a.ids[1 + abs(hashtext(t.tx_id::text || l.line_no)) % cardinality(a.ids)] AS account_id
FROM perf_tx t
CROSS JOIN perf_account a
JOIN LATERAL generate_series(1, t.line_count) AS l(line_no) ON true;

INSERT INTO entity_registry (entity_id, entity_type, created_seq_id)
SELECT tx_id, 'LedgerTransaction', (SELECT seq_id FROM perf_run) FROM perf_tx
UNION ALL SELECT posting_id, 'FinPosting', (SELECT seq_id FROM perf_run) FROM perf_tx
UNION ALL SELECT entry_id, 'LedgerEntry', (SELECT seq_id FROM perf_run) FROM perf_line;

-- Booked at the start of the posting day in Chicago, as the processes book them (BookingTime).
INSERT INTO ledger_transaction_version (transaction_id, version_no, effect_start_time, created_time, process_seq_id,
    booking_time, description, reference, source_entity, source_id)
SELECT tx_id, 1, recorded, recorded,
       (SELECT seq_id FROM perf_run), (posting_date::timestamp AT TIME ZONE 'America/Chicago'),
       'Generated entry ' || i, 'PERF-' || :year || '-' || i, 'FinJournal', tx_id::text
FROM perf_tx;

INSERT INTO ledger_entry_version (entry_id, version_no, effect_start_time, created_time, process_seq_id,
    transaction_id, account_id, line_no, direction, amount, memo)
SELECT l.entry_id, 1, t.recorded, t.recorded,
       (SELECT seq_id FROM perf_run), l.tx_id, l.account_id, l.line_no, l.direction, l.amount, NULL
FROM perf_line l JOIN perf_tx t ON t.tx_id = l.tx_id
ORDER BY t.i, l.line_no;

INSERT INTO fi_posting_version (posting_id, version_no, effect_start_time, created_time, process_seq_id,
    transaction_id, posting_date, fiscal_year, period_no, period_key, source, gl_no, document_no, source_entity,
    source_id)
SELECT posting_id, 1, recorded, recorded,
       (SELECT seq_id FROM perf_run), tx_id, posting_date, :year, extract(month FROM posting_date),
       to_char(posting_date, 'YYYY-MM'), 'MAN', 'GJ-PERF-' || :year || '-' || lpad(i::text, 7, '0'),
       'PERF-' || :year || '-' || i, 'FinJournal', tx_id::text
FROM perf_tx;

SELECT :year AS year, (SELECT count(*) FROM perf_tx) AS transactions, (SELECT count(*) FROM perf_line) AS lines;
COMMIT;
ANALYZE ledger_transaction_version;
ANALYZE ledger_entry_version;
ANALYZE fi_posting_version;
