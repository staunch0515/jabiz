/*---
id: finance.bank.stale_checks
description: >-
  Checks outstanding longer than the stale-check age on a day (FIN-BK-009), for follow-up and escheatment review:
  the checks paid from a bank account and not on its statement by then, and the outstanding payments brought over at
  the cutover, that are at least the given number of days old (the bank settings' stale-check age, 90 if not set). A
  check counts as on the statement only through a match whose lines and items all lie on or before the day, as in
  the reconciliation; a check voided by then is not outstanding.
entities: [FinBankAccount, FinBankSettings, FinBankOpening, FinBankOpeningItem, LedgerAccount, LedgerEntry, FinPosting,
  FinPayment, FinBankMatch, FinBankMatchItem]
params:
  asOf:     { like: FinPosting.postingDate, required: true, description: the day the checks are outstanding on }
  bankCode: { like: FinBankAccount.bankCode, description: "one bank account only; all when not given" }
  days:     { kind: { type: numeric, precision: 4, scale: 0 }, description: "the age of a stale check; the bank settings' when not given" }
results:
  bankCode:    { from: FinBankAccount.bankCode }
  refKind:     { from: FinBankMatchItem.refKind }
  refId:       { from: FinBankMatchItem.refId }
  checkNo:     { from: FinPayment.checkNo }
  issueDate:   { from: FinPosting.postingDate }
  daysOutstanding: { kind: { type: numeric, precision: 6, scale: 0 } }
  payee:       { from: FinPayment.payee }
  documentNo:  { from: FinPosting.documentNo }
  amount:      { from: FinPayment.amount }
list:
  filters: [bankCode, checkNo, payee]
  sorts:   [bankCode, issueDate, checkNo, amount, daysOutstanding]
  defaultSort: { field: issueDate, asc: true }
  key: [refKind, refId]
permissions: [fin.bank.activity.read]
report:
  period: { to: asOf }
---*/
WITH age AS (
    SELECT CAST(COALESCE(:days, (SELECT s.{{FinBankSettings.staleCheckDays}} FROM {{FinBankSettings}} s
                                 WHERE s.{{FinBankSettings.settingsKey}} = 'BANK'), 90) AS integer) AS stale_days
),
banks AS (
    SELECT b.{{FinBankAccount.bankCode}} AS bank_key, b.{{FinBankAccount.glAccount}} AS gl,
           o.{{FinBankOpening.cutoverDate}} AS cutover_day
    FROM {{FinBankAccount}} b
    JOIN {{FinBankOpening}} o ON o.{{FinBankOpening.bankCode}} = b.{{FinBankAccount.bankCode}}
    WHERE CAST(:bankCode AS varchar) IS NULL OR b.{{FinBankAccount.bankCode}} = :bankCode
),
settled_refs AS (
    -- On the statement by the day: in a match not undone whose every line and item lies on or before it.
    SELECT i.{{FinBankMatchItem.bankCode}} AS bank_key, i.{{FinBankMatchItem.refKind}} AS kind_code,
           i.{{FinBankMatchItem.refId}} AS ref_key
    FROM {{FinBankMatchItem}} i
    WHERE i.{{FinBankMatchItem.matchId}} IN (
        SELECT m.{{FinBankMatch.matchId}}
        FROM {{FinBankMatch}} m
        JOIN {{FinBankMatchItem}} mi ON mi.{{FinBankMatchItem.matchId}} = m.{{FinBankMatch.matchId}}
        WHERE m.{{FinBankMatch.action}} = 'MATCH'
          AND NOT EXISTS (SELECT 1 FROM {{FinBankMatch}} u
                          WHERE u.{{FinBankMatch.reversesMatchId}} = m.{{FinBankMatch.matchId}})
        GROUP BY m.{{FinBankMatch.matchId}}
        HAVING MAX(mi.{{FinBankMatchItem.itemDate}}) <= :asOf)
),
checks AS (
    SELECT bk.bank_key, CAST(fp.{{FinPosting.transactionId}} AS varchar) AS ref_key,
           p.{{FinPayment.checkNo}} AS check_ref, fp.{{FinPosting.postingDate}} AS issued_on,
           p.{{FinPayment.payee}} AS paid_to, fp.{{FinPosting.documentNo}} AS doc_no,
           SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                    ELSE -e.{{LedgerEntry.amount}} END) AS amt
    FROM banks bk
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountCode}} = bk.gl
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.accountId}} = a.{{LedgerAccount.accountId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPayment}} p ON fp.{{FinPosting.sourceEntity}} = 'FinPayment'
                          AND CAST(p.{{FinPayment.paymentId}} AS varchar) = fp.{{FinPosting.sourceId}}
    WHERE p.{{FinPayment.checkNo}} IS NOT NULL
      AND fp.{{FinPosting.postingDate}} > bk.cutover_day AND fp.{{FinPosting.postingDate}} <= :asOf
      AND (p.{{FinPayment.voidDate}} IS NULL OR p.{{FinPayment.voidDate}} > :asOf)
    GROUP BY bk.bank_key, fp.{{FinPosting.transactionId}}, p.{{FinPayment.checkNo}}, fp.{{FinPosting.postingDate}},
             p.{{FinPayment.payee}}, fp.{{FinPosting.documentNo}}
    -- The check itself, not its void's reversal.
    HAVING SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                    ELSE -e.{{LedgerEntry.amount}} END) < 0
),
outstanding AS (
    SELECT c.bank_key, 'LEDGER' AS kind_code, c.ref_key, c.check_ref, c.issued_on, c.paid_to, c.doc_no, -c.amt AS amt
    FROM checks c
    WHERE NOT EXISTS (SELECT 1 FROM settled_refs r
                      WHERE r.bank_key = c.bank_key AND r.kind_code = 'LEDGER' AND r.ref_key = c.ref_key)
    UNION ALL
    SELECT bk.bank_key, 'OPENING', CAST(o.{{FinBankOpeningItem.itemId}} AS varchar), o.{{FinBankOpeningItem.reference}},
           o.{{FinBankOpeningItem.itemDate}}, CAST(NULL AS varchar), o.{{FinBankOpeningItem.reference}},
           -o.{{FinBankOpeningItem.amount}}
    FROM banks bk
    JOIN {{FinBankOpeningItem}} o ON o.{{FinBankOpeningItem.bankCode}} = bk.bank_key
    WHERE o.{{FinBankOpeningItem.amount}} < 0
      AND NOT EXISTS (SELECT 1 FROM settled_refs r
                      WHERE r.bank_key = bk.bank_key AND r.kind_code = 'OPENING'
                        AND r.ref_key = CAST(o.{{FinBankOpeningItem.itemId}} AS varchar))
)
SELECT o.bank_key AS bankCode, o.kind_code AS refKind, o.ref_key AS refId, o.check_ref AS checkNo,
       o.issued_on AS issueDate, CAST(:asOf AS date) - o.issued_on AS daysOutstanding, o.paid_to AS payee,
       o.doc_no AS documentNo, o.amt AS amount
FROM outstanding o
CROSS JOIN age
WHERE CAST(:asOf AS date) - o.issued_on >= age.stale_days
