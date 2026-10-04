-- Copies what a unit of processes wrote, many times over, for the performance data (docs/finance/perf.md §8): the unit
-- is a few top-level processes the builder ran through the API (say, an invoice saved and posted and a receipt applied
-- to it), with their child processes. Every row they wrote in a table with a process_seq_id is written again COPIES
-- times, each copy with
--   * processes of its own (op_process, numbered from op_process_seq);
--   * identifiers of its own: every id the unit registered (entity_registry), and the unique ids of its other rows,
--     become new ids wherever they appear, in uuid columns and as text; new versions the unit wrote of entities it did
--     not register (a bank account's next check number) are not copied;
--   * numbers of its own: each number the unit drew (sys_number_assignment) becomes the next of its sequence and
--     scope, the counters moving on, so that the sequences stay without gaps;
--   * days of its own: date columns move (copy - 1) % DAYS days later (the builder's units start on a month's first
--     day, so with DAYS = 28 a copy stays in the unit's month and period); times stay as they were;
--   * the substitutions SUBST: a text value of the unit given there ({"C-TEMPLATE": ["C0001", …]}) becomes, in copy k,
--     the ((k - 1) mod n) + 1-th of its list — customers and vendors take turns.
-- Not copied: what only records the operation (op_process_item and _result, the audit records, outbox, notifications,
-- integrity rows). The amounts are the unit's. For a database of its own only (build.sh checks the name).
--   psql -v roots=101,107 -v copies=20000 -v days=28 -v salt=a1 -v subst='{"C-T":["C1","C2"]}' -f clone.sql
\set ON_ERROR_STOP on

CREATE OR REPLACE FUNCTION pg_temp.perf_clone(p_roots bigint[], p_copies int, p_days int, p_salt text, p_subst jsonb)
RETURNS void LANGUAGE plpgsql AS $fn$
DECLARE
  n int;
  base bigint;
  t record;
  col record;
  cols text;
  exprs text;
  own text;
  found boolean;
  mapped boolean;
  started timestamptz;
  phase timestamptz := clock_timestamp();
  skip text[] := ARRAY['op_process', 'op_process_item', 'op_process_result', 'op_process_after_commit',
    'sys_audit_record', 'sys_outbox_event', 'sys_event_consumption', 'sys_notification', 'sys_integrity_seal',
    'sys_integrity_check', 'sys_number_assignment', 'entity_registry'];
BEGIN
  -- The unit's processes, in order: copy k of the i-th is base + (k - 1) * n + i.
  CREATE TEMP TABLE perf_seq ON COMMIT DROP AS
  WITH RECURSIVE tree AS (
    SELECT process_seq_id FROM op_process WHERE process_seq_id = ANY (p_roots)
    UNION ALL SELECT o.process_seq_id FROM op_process o JOIN tree ON o.parent_seq_id = tree.process_seq_id)
  SELECT process_seq_id AS old, (row_number() OVER (ORDER BY process_seq_id) - 1)::int AS i FROM tree;
  SELECT count(*) INTO n FROM perf_seq;
  IF n = 0 THEN RAISE EXCEPTION 'no processes under %', p_roots; END IF;
  base := nextval('op_process_seq');
  PERFORM setval('op_process_seq', base + p_copies::bigint * n);
  CREATE TEMP TABLE perf_k ON COMMIT DROP AS SELECT k FROM generate_series(1, p_copies) AS k;

  -- The unit's identifiers: those it registered…
  CREATE TEMP TABLE perf_id (old uuid PRIMARY KEY) ON COMMIT DROP;
  INSERT INTO perf_id SELECT entity_id FROM entity_registry WHERE created_seq_id IN (SELECT old FROM perf_seq)
    ON CONFLICT DO NOTHING;
  -- …and those of rows unique by a uuid of their own (not versions of an entity: a version table's rows are copied
  -- only for the entities the unit registered; a new version of something it found, say the bank account's next
  -- check number, is not repeated).
  FOR t IN SELECT DISTINCT c.table_name FROM information_schema.columns c
           WHERE c.table_schema = current_schema() AND c.column_name = 'process_seq_id'
             AND c.table_name <> ALL (skip) LOOP
    FOR col IN SELECT a.attname
             FROM pg_index x JOIN pg_class r ON r.oid = x.indrelid
             JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum = x.indkey[0]
             WHERE r.relname = t.table_name AND r.relnamespace = to_regnamespace(current_schema())
               AND x.indisunique AND x.indnatts = 1
               AND a.atttypid IN ('uuid'::regtype, 'text'::regtype, 'varchar'::regtype) LOOP
      -- (A text key holding a uuid counts too, as an approval evaluation's.)
      EXECUTE format('INSERT INTO perf_id SELECT DISTINCT %I::uuid FROM %I WHERE process_seq_id IN'
        || ' (SELECT old FROM perf_seq) AND %I::text ~ %L ON CONFLICT DO NOTHING', col.attname, t.table_name,
        col.attname, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
    END LOOP;
  END LOOP;
  CREATE TEMP TABLE perf_id_text ON COMMIT DROP AS SELECT old::text AS old FROM perf_id;
  RAISE NOTICE 'ids: % in %', (SELECT count(*) FROM perf_id), clock_timestamp() - phase;

  -- The unit's numbers: the j-th of m in its sequence and scope becomes, in copy k, last + (k - 1) * m + j.
  CREATE TEMP TABLE perf_num ON COMMIT DROP AS
  SELECT a.number AS old, a.sequence_name, a.scope_key,
         row_number() OVER (PARTITION BY a.sequence_name, a.scope_key ORDER BY a.value_no)::bigint AS j,
         count(*) OVER (PARTITION BY a.sequence_name, a.scope_key)::bigint AS m,
         c.last_value AS last, substring(a.number FROM '\d+$') AS digits
  FROM sys_number_assignment a
  JOIN sys_number_counter c ON c.sequence_name = a.sequence_name AND c.scope_key = a.scope_key
  WHERE a.process_seq_id IN (SELECT old FROM perf_seq);
  CREATE TEMP TABLE perf_subst ON COMMIT DROP AS
  SELECT key AS old, ARRAY(SELECT jsonb_array_elements_text(value)) AS choices FROM jsonb_each(p_subst);

  -- The processes.
  INSERT INTO op_process (process_seq_id, parent_seq_id, process_name, process_version, actor_id, tenant_id, reason,
                          op_time)
  SELECT base + (k.k - 1) * n + s.i,
         CASE WHEN o.parent_seq_id IS NULL THEN NULL
              ELSE base + (k.k - 1) * n + (SELECT p.i FROM perf_seq p WHERE p.old = o.parent_seq_id) END,
         o.process_name, o.process_version, o.actor_id, o.tenant_id, 'performance data', o.op_time
  FROM op_process o JOIN perf_seq s ON s.old = o.process_seq_id CROSS JOIN perf_k k
  ORDER BY 1;

  -- The registered ids, then every other table.
  INSERT INTO entity_registry (entity_id, entity_type, created_seq_id)
  SELECT md5(e.entity_id::text || ':' || p_salt || ':' || k.k)::uuid, e.entity_type,
         base + (k.k - 1) * n + s.i
  FROM entity_registry e JOIN perf_seq s ON s.old = e.created_seq_id CROSS JOIN perf_k k;

  INSERT INTO sys_number_assignment (assignment_id, sequence_name, scope_key, value_no, number, process_seq_id,
                                     assigned_time, version)
  SELECT md5(a.assignment_id::text || ':' || p_salt || ':' || k.k)::uuid, a.sequence_name, a.scope_key,
         u.last + (k.k - 1) * u.m + u.j,
         regexp_replace(a.number, '\d+$', lpad((u.last + (k.k - 1) * u.m + u.j)::text, length(u.digits), '0')),
         base + (k.k - 1) * n + s.i, a.assigned_time, a.version
  FROM sys_number_assignment a JOIN perf_seq s ON s.old = a.process_seq_id
  JOIN perf_num u ON u.old = a.number AND u.sequence_name = a.sequence_name AND u.scope_key = a.scope_key
  CROSS JOIN perf_k k;

  FOR t IN SELECT DISTINCT c.table_name FROM information_schema.columns c
           WHERE c.table_schema = current_schema() AND c.column_name = 'process_seq_id'
             AND c.table_name <> ALL (skip) ORDER BY 1 LOOP
    -- Only the tables the unit wrote, and in them only the columns holding something to change: a statement with
    -- every column's lookups takes seconds to plan.
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE process_seq_id IN (SELECT old FROM perf_seq))', t.table_name)
      INTO found;
    CONTINUE WHEN NOT found;
    cols := ''; exprs := ''; own := NULL;
    -- A version table: copied for the unit's own entities only.
    SELECT a.attname INTO own
    FROM pg_index x JOIN pg_class r ON r.oid = x.indrelid
    JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum = x.indkey[0]
    WHERE r.relname = t.table_name AND r.relnamespace = to_regnamespace(current_schema())
      AND x.indisunique AND x.indnatts = 2 AND a.atttypid = 'uuid'::regtype
      AND EXISTS (SELECT 1 FROM pg_attribute v WHERE v.attrelid = r.oid AND v.attnum = x.indkey[1]
                  AND v.attname = 'version_no')
    LIMIT 1;
    FOR col IN SELECT column_name, data_type, is_identity, is_generated FROM information_schema.columns
             WHERE table_schema = current_schema() AND table_name = t.table_name ORDER BY ordinal_position LOOP
      CONTINUE WHEN col.is_identity = 'YES' OR col.is_generated = 'ALWAYS';
      cols := cols || format('%I, ', col.column_name);
      mapped := false;
      IF col.data_type = 'uuid' THEN
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE process_seq_id IN (SELECT old FROM perf_seq)'
          || ' AND %I IN (SELECT old FROM perf_id))', t.table_name, col.column_name) INTO mapped;
      ELSIF col.data_type IN ('text', 'character varying') THEN
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE process_seq_id IN (SELECT old FROM perf_seq)'
          || ' AND (%2$I IN (SELECT old FROM perf_id_text) OR %2$I IN (SELECT old FROM perf_num)'
          || ' OR %2$I IN (SELECT old FROM perf_subst)))', t.table_name, col.column_name) INTO mapped;
      END IF;
      exprs := exprs || CASE
        WHEN col.data_type IN ('uuid', 'text', 'character varying') AND NOT mapped
          THEN format('r.%I', col.column_name)
        WHEN col.column_name = 'process_seq_id' THEN format('%s + (k.k - 1) * %s + s.i', base, n)
        WHEN col.data_type = 'uuid' THEN format('CASE WHEN r.%1$I IN (SELECT old FROM perf_id) THEN md5(r.%1$I::text'
          || ' || %2$L || k.k)::uuid ELSE r.%1$I END', col.column_name, ':' || p_salt || ':')
        WHEN col.data_type IN ('text', 'character varying') THEN format('CASE'
          || ' WHEN r.%1$I IN (SELECT old FROM perf_id_text) THEN md5(r.%1$I || %2$L || k.k)::uuid::text'
          || ' WHEN r.%1$I IN (SELECT old FROM perf_num) THEN (SELECT regexp_replace(u.old, ''\d+$'','
          || ' lpad((u.last + (k.k - 1) * u.m + u.j)::text, length(u.digits), ''0'')) FROM perf_num u'
          || ' WHERE u.old = r.%1$I LIMIT 1)'
          || ' WHEN r.%1$I IN (SELECT old FROM perf_subst) THEN (SELECT x.choices[1 + (k.k - 1)'
          || ' %% cardinality(x.choices)] FROM perf_subst x WHERE x.old = r.%1$I)'
          || ' ELSE r.%1$I END', col.column_name, ':' || p_salt || ':')
        WHEN col.data_type = 'date' THEN format('r.%I + (k.k - 1) %% %s', col.column_name, p_days)
        ELSE format('r.%I', col.column_name) END || ', ';
    END LOOP;
    started := clock_timestamp();
    EXECUTE format('INSERT INTO %I (%s) SELECT %s FROM %I r JOIN perf_seq s ON s.old = r.process_seq_id'
      || ' CROSS JOIN perf_k k %s', t.table_name, rtrim(cols, ', '), rtrim(exprs, ', '), t.table_name,
      CASE WHEN own IS NULL THEN '' ELSE format('WHERE r.%I IN (SELECT old FROM perf_id)', own) END);
    IF clock_timestamp() - started > interval '1 second' THEN
      RAISE NOTICE '% took %', t.table_name, clock_timestamp() - started;
    END IF;
  END LOOP;

  -- The counters move past the numbers given.
  UPDATE sys_number_counter c SET last_value = c.last_value + p_copies * u.m
  FROM (SELECT DISTINCT sequence_name, scope_key, m FROM perf_num) u
  WHERE c.sequence_name = u.sequence_name AND c.scope_key = u.scope_key;
END
$fn$;

BEGIN;
SELECT pg_temp.perf_clone(string_to_array(:'roots', ',')::bigint[], :copies, :days, :'salt', :'subst'::jsonb);
COMMIT;
