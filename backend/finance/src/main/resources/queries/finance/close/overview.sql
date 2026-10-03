/*---
id: finance.close.overview
description: >-
  Where a period's close stands (FIN-PC-009, FIN-UI-006), one row per item in the order a controller reads them:
  PROGRESS, how many of the checklist's tasks are done (manual tasks done, automatic checks passed) of all of them;
  TASK, each checklist task, open and failed ones first, with its owner (the permission the task is assigned to), due
  date and who completed it when; SUBLEDGER, the state of the general ledger and each subledger; RECONCILIATION, each
  active bank account with its latest reconciliation of a statement closing in the period (MISSING when there is none).
  A period whose close has not started has no task rows and a progress of 0 of 0; an unknown period has no rows.
entities: [FinPeriod, FinCloseTask, FinBankAccount, FinBankReconciliation]
params:
  periodKey: { like: FinPeriod.periodKey, required: true }
results:
  rank:     { kind: { type: numeric, precision: 6, scale: 0 } }
  taskId:   { kind: { type: text, maxLength: 40 } }
  section:  { kind: { type: text, maxLength: 20 } }
  code:     { kind: { type: text, maxLength: 30 } }
  name:     { kind: { type: text, maxLength: 200 } }
  kind:     { kind: { type: text, maxLength: 10 } }
  status:   { kind: { type: text, maxLength: 20 } }
  required: { kind: { type: bool } }
  owner:    { kind: { type: text, maxLength: 100 } }
  dueDate:  { from: FinCloseTask.dueDate }
  doneBy:   { kind: { type: text, maxLength: 100 } }
  doneAt:   { from: FinCloseTask.completedAt }
  done:     { kind: { type: numeric, precision: 4, scale: 0 } }
  total:    { kind: { type: numeric, precision: 4, scale: 0 } }
  detail:   { kind: { type: text, maxLength: 2000 } }
list:
  filters: [section, status]
  sorts:   [rank]
  defaultSort: { field: rank, asc: true }
  key: [section, code]
permissions: [fin.period.read]
---*/
WITH p AS (
    SELECT fp.{{FinPeriod.periodKey}} AS pkey, fp.{{FinPeriod.startDate}} AS start_day,
           fp.{{FinPeriod.endDate}} AS end_day, COALESCE(fp.{{FinPeriod.adjustment}}, false) AS adj_period,
           fp.{{FinPeriod.status}} AS gl_state, fp.{{FinPeriod.arStatus}} AS ar_state,
           fp.{{FinPeriod.apStatus}} AS ap_state, fp.{{FinPeriod.bankStatus}} AS bank_state,
           fp.{{FinPeriod.faStatus}} AS fa_state
    FROM {{FinPeriod}} fp
    WHERE fp.{{FinPeriod.periodKey}} = :periodKey
),
tasks AS (
    SELECT CAST(t.{{FinCloseTask.taskId}} AS text) AS tsk, t.{{FinCloseTask.taskCode}} AS code,
           t.{{FinCloseTask.name}} AS task_name, t.{{FinCloseTask.kind}} AS kind,
           t.{{FinCloseTask.status}} AS status, t.{{FinCloseTask.required}} AS req,
           t.{{FinCloseTask.ownerPermission}} AS owner, t.{{FinCloseTask.dueDate}} AS due,
           t.{{FinCloseTask.completedBy}} AS done_by,
           COALESCE(t.{{FinCloseTask.completedAt}}, t.{{FinCloseTask.checkedAt}}) AS done_at,
           COALESCE(t.{{FinCloseTask.result}}, t.{{FinCloseTask.note}}) AS detail,
           t.{{FinCloseTask.sortOrder}} AS sort_no,
           t.{{FinCloseTask.status}} IN ('DONE', 'PASSED') AS finished
    FROM {{FinCloseTask}} t JOIN p ON t.{{FinCloseTask.periodKey}} = p.pkey
)
SELECT 0 AS rank, CAST(NULL AS text) AS taskId, 'PROGRESS' AS section, 'PROGRESS' AS code,
       CAST(COUNT(*) FILTER (WHERE finished) AS text) || ' of ' || CAST(COUNT(*) AS text) || ' done' AS name,
       CAST(NULL AS text) AS kind,
       CASE WHEN COUNT(*) = 0 THEN 'NOT_STARTED' WHEN COUNT(*) FILTER (WHERE NOT finished) = 0 THEN 'COMPLETE'
            ELSE 'IN_PROGRESS' END AS status,
       CAST(NULL AS boolean) AS required, CAST(NULL AS text) AS owner, CAST(NULL AS date) AS dueDate,
       CAST(NULL AS text) AS doneBy, CAST(NULL AS timestamptz) AS doneAt,
       COUNT(*) FILTER (WHERE finished) AS done, COUNT(*) AS total, CAST(NULL AS text) AS detail
FROM tasks
HAVING EXISTS (SELECT 1 FROM p)
UNION ALL
-- Open and failed tasks first (PC-009 acceptance 1), each group in the checklist's order, then by code.
SELECT CASE WHEN finished THEN 20000 ELSE 10000 END + ROW_NUMBER() OVER (ORDER BY finished, sort_no, code), tsk,
       'TASK', code, task_name, kind, status, req, owner, due,
       done_by, done_at, CAST(NULL AS numeric), CAST(NULL AS numeric), detail
FROM tasks
UNION ALL
SELECT 30000 + s.ord, CAST(NULL AS text), 'SUBLEDGER', s.code, s.label, CAST(NULL AS text), s.state,
       CAST(NULL AS boolean),
       CAST(NULL AS text), CAST(NULL AS date), CAST(NULL AS text), CAST(NULL AS timestamptz),
       CAST(NULL AS numeric), CAST(NULL AS numeric), CAST(NULL AS text)
FROM p CROSS JOIN LATERAL (VALUES (1, 'GL', 'General ledger', p.gl_state), (2, 'AR', 'Receivables', p.ar_state),
                                  (3, 'AP', 'Payables', p.ap_state), (4, 'BANK', 'Bank', p.bank_state),
                                  (5, 'FA', 'Fixed assets', p.fa_state)) AS s(ord, code, label, state)
UNION ALL
SELECT 40000 + ROW_NUMBER() OVER (ORDER BY ba.{{FinBankAccount.bankCode}}), CAST(NULL AS text),
       'RECONCILIATION',
       ba.{{FinBankAccount.bankCode}}, ba.{{FinBankAccount.glAccount}}, CAST(NULL AS text),
       COALESCE(r.status, 'MISSING'), CAST(NULL AS boolean), r.preparer, CAST(NULL AS date), r.reviewer,
       r.signed_off, CAST(NULL AS numeric), CAST(NULL AS numeric),
       CASE WHEN r.status IS NULL THEN NULL
            ELSE 'Statement ' || CAST(r.statement_day AS text) || ', difference ' || CAST(r.diff AS text) END
FROM {{FinBankAccount}} ba
CROSS JOIN p
LEFT JOIN LATERAL (
    -- A signed-off reconciliation counts before any other; among equals the latest statement.
    SELECT rec.{{FinBankReconciliation.status}} AS status, rec.{{FinBankReconciliation.preparedBy}} AS preparer,
           rec.{{FinBankReconciliation.reviewedBy}} AS reviewer,
           rec.{{FinBankReconciliation.signedOffTime}} AS signed_off,
           rec.{{FinBankReconciliation.statementDate}} AS statement_day,
           rec.{{FinBankReconciliation.difference}} AS diff
    FROM {{FinBankReconciliation}} rec
    WHERE rec.{{FinBankReconciliation.bankCode}} = ba.{{FinBankAccount.bankCode}}
      AND rec.{{FinBankReconciliation.statementDate}} BETWEEN p.start_day AND p.end_day
    ORDER BY CASE WHEN rec.{{FinBankReconciliation.status}} = 'SIGNED_OFF' THEN 0 ELSE 1 END,
             rec.{{FinBankReconciliation.statementDate}} DESC
    LIMIT 1
) r ON true
WHERE ba.{{FinBankAccount.active}}
  AND NOT p.adj_period
