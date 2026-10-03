-- FIN-NF-003 invariants on the database after a run (tools/finance/concurrency): every line printed is a problem.
-- :acked is the run's acknowledged postings (kind, document id, owner) and its customers (CUST, -, customer),
-- :prefix its journals' descriptions,
-- :receivable the receivables control account.
CREATE TEMP TABLE acked (kind text, id text, owner text);
\set copy '\\copy acked FROM ' :'acked' ' CSV'
:copy

-- Every acknowledged posting is in the books exactly once.
SELECT 'posted ' || count(DISTINCT p.posting_id) || ' times: ' || a.kind || ' ' || a.id
FROM acked a LEFT JOIN fi_posting_version p ON p.source_id = a.id
WHERE a.kind <> 'CUST'
GROUP BY a.kind, a.id HAVING count(DISTINCT p.posting_id) <> 1;

-- Nothing of the run's own beyond what was acknowledged: a retry never made a second document.
SELECT 'not acknowledged: journal ' || j.journal_id
FROM (SELECT DISTINCT journal_id FROM fi_journal_version WHERE description LIKE :'prefix' || '%') j
WHERE j.journal_id::text NOT IN (SELECT id FROM acked WHERE kind = 'JE');
SELECT 'not acknowledged: invoice ' || i.invoice_id
FROM (SELECT DISTINCT invoice_id FROM fi_invoice_version
      WHERE customer_code IN (SELECT owner FROM acked WHERE kind = 'CUST')) i
WHERE i.invoice_id::text NOT IN (SELECT id FROM acked WHERE kind = 'INV');
SELECT 'not acknowledged: receipt ' || r.receipt_id
FROM (SELECT DISTINCT receipt_id FROM fi_receipt_version
      WHERE customer_code IN (SELECT owner FROM acked WHERE kind = 'CUST')) r
WHERE r.receipt_id::text NOT IN (SELECT id FROM acked WHERE kind = 'RCPT');

-- No number sequence has a gap or a number twice (all of the books, not only the run's; a gap before the first
-- number of a scope is not seen: where a sequence starts is its own declaration).
SELECT 'gap or repeat in ' || sequence_name || ' ' || scope_key || ': ' || count(*) || ' numbers from '
  || min(value_no) || ' to ' || max(value_no)
FROM sys_number_assignment GROUP BY sequence_name, scope_key
HAVING count(*) <> max(value_no) - min(value_no) + 1 OR count(DISTINCT value_no) <> count(*);

-- Every ledger transaction balances.
SELECT 'unbalanced transaction ' || transaction_id FROM ledger_entry_version WHERE NOT is_deleted
GROUP BY transaction_id
HAVING sum(CASE direction WHEN 'DEBIT' THEN amount ELSE 0 END) <> sum(CASE direction WHEN 'CREDIT' THEN amount ELSE 0 END);

-- Every invoice of the run was paid in full by its receipt: together they leave nothing on the receivables control
-- account (the subledger, all paid, is nothing too).
SELECT 'the run leaves ' || sum(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE -e.amount END)
  || ' on the receivables control account'
FROM acked a
JOIN fi_posting_version p ON p.source_id = a.id
JOIN ledger_entry_version e ON e.transaction_id = p.transaction_id AND NOT e.is_deleted
JOIN (SELECT DISTINCT account_id FROM ledger_account_version WHERE account_code = :'receivable') r
  ON r.account_id = e.account_id
WHERE a.kind IN ('INV', 'RCPT')
HAVING sum(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE -e.amount END) <> 0;

