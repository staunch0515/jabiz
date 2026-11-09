/*---
id: qb.system.recon-fee-account
description: Whether the account that pays the platform's fees agrees with its lots and payments, by sponsor (F-M1) - the fee credit (2190) with its wallet and grants, the cash (2160) with its lots, and the fee payments posted with their parts; and by operator (F-M2) the promotion expenses of the grants (5140) with what was granted less what was voided (the daily reconciliation; differences only)
entities: [QbWallet, QbFeeCreditGrant, QbCashLot, QbFeePayment, LedgerEntry, LedgerAccount, LedgerTransaction]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbCashLot: urn:jabiz:dataset:default:QbCashLot
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  sponsorId: { kind: { type: text, maxLength: 128 } }
  expected:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  actual:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [checkCode, sponsorId]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode, sponsorId]
permissions: [qb.wallet.system]
---*/
-- One statement, one snapshot: every payment posts, writes its parts and changes the lots and wallets in one
-- transaction under the sponsor's wallet lock.
-- FEE_CREDIT: 2190 for a sponsor (credit less debit) against their FEE_CREDIT wallet and against what their grants have
--   left (the wallet when it differs, else the grants).
-- CASH_LOTS: 2160 for a sponsor against what their cash lots have left.
-- FEE_CREDIT_PROMOTION (F-M2): 5140 posted from the grants (debit less credit) by the operator each grant recorded when
--   it was made, against the grants' amounts less what was voided of them; sponsorId holds the operator ('-': none).
-- FEE_PAYMENTS: what the postings of fees and invoices took from 2160 and 2190 for a sponsor (debit less credit: a
--   voided invoice's reversal gives it back) against the parts written for those postings. A payment from before F-M1
--   has no parts: a posting no part names, of a payment made before the first part was written, is left out, and so is
--   its reversal (the plan's §4: the parts are checked from F-M1 on).
WITH credit_ledger AS (
    SELECT e.{{LedgerEntry.dimension1}} AS r_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2190'
    GROUP BY e.{{LedgerEntry.dimension1}}
),
credit_wallets AS (
    SELECT w.{{QbWallet.ownerId}} AS r_party, w.{{QbWallet.balance}} AS r_balance
    FROM {{QbWallet}} w
    WHERE w.{{QbWallet.kind}} = 'FEE_CREDIT'
),
grants AS (
    SELECT g.{{QbFeeCreditGrant.sponsorId}} AS r_party, sum(g.{{QbFeeCreditGrant.remaining}}) AS r_remaining
    FROM {{QbFeeCreditGrant}} g
    GROUP BY g.{{QbFeeCreditGrant.sponsorId}}
),
cash_ledger AS (
    SELECT e.{{LedgerEntry.dimension1}} AS r_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2160'
    GROUP BY e.{{LedgerEntry.dimension1}}
),
lots AS (
    SELECT l.{{QbCashLot.sponsorId}} AS r_party, sum(l.{{QbCashLot.remaining}}) AS r_remaining
    FROM {{QbCashLot}} l
    GROUP BY l.{{QbCashLot.sponsorId}}
),
parts AS (
    SELECT p.{{QbFeePayment.transactionId}} AS r_tx, sum(p.{{QbFeePayment.amount}}) AS r_amount
    FROM {{QbFeePayment}} p
    GROUP BY p.{{QbFeePayment.transactionId}}
),
promotion_ledger AS (
    SELECT coalesce(g.{{QbFeeCreditGrant.operatorId}}, '-') AS r_operator,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerTransaction}} t
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    LEFT JOIN {{QbFeeCreditGrant}} g
        ON CAST(g.{{QbFeeCreditGrant.grantId}} AS text) = t.{{LedgerTransaction.sourceId}}
    WHERE t.{{LedgerTransaction.sourceEntity}} = 'QbFeeCreditGrant'
      AND a.{{LedgerAccount.accountCode}} = '5140'
    GROUP BY coalesce(g.{{QbFeeCreditGrant.operatorId}}, '-')
),
promotion_grants AS (
    SELECT coalesce(g.{{QbFeeCreditGrant.operatorId}}, '-') AS r_operator,
           sum(g.{{QbFeeCreditGrant.amount}} - g.{{QbFeeCreditGrant.voidedAmount}}) AS r_net
    FROM {{QbFeeCreditGrant}} g
    GROUP BY coalesce(g.{{QbFeeCreditGrant.operatorId}}, '-')
),
first_part AS (
    SELECT min(p.{{QbFeePayment.paidAt}}) AS r_at FROM {{QbFeePayment}} p
),
fee_tx AS (
    SELECT CAST(t.{{LedgerTransaction.transactionId}} AS text) AS r_tx,
           coalesce(t.{{LedgerTransaction.reversesTransactionId}}, t.{{LedgerTransaction.transactionId}}) AS r_original,
           e.{{LedgerEntry.dimension1}} AS r_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_taken
    FROM {{LedgerTransaction}} t
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE t.{{LedgerTransaction.sourceEntity}} IN ('QbFeeCharge', 'QbFeeInvoice')
      AND a.{{LedgerAccount.accountCode}} IN ('2160', '2190')
    GROUP BY t.{{LedgerTransaction.transactionId}}, t.{{LedgerTransaction.reversesTransactionId}},
             e.{{LedgerEntry.dimension1}}
),
checked_tx AS (
    SELECT f.r_party, f.r_taken, coalesce(pt.r_amount, 0) AS r_parts
    FROM fee_tx f
    LEFT JOIN parts pt ON pt.r_tx = f.r_tx
    JOIN {{LedgerTransaction}} o ON o.{{LedgerTransaction.transactionId}} = f.r_original
    CROSS JOIN first_part fp
    WHERE pt.r_tx IS NOT NULL
       OR EXISTS (SELECT 1 FROM parts q WHERE q.r_tx = CAST(f.r_original AS text))
       OR NOT (fp.r_at IS NULL OR o.{{LedgerTransaction.createdTime}} < fp.r_at)
)
SELECT CAST('FEE_CREDIT' AS varchar(30)) AS checkCode,
       CAST(coalesce(l.r_party, w.r_party, g.r_party) AS varchar(128)) AS sponsorId,
       coalesce(l.r_balance, 0) AS expected,
       CASE WHEN coalesce(w.r_balance, 0) <> coalesce(l.r_balance, 0) THEN coalesce(w.r_balance, 0)
            ELSE coalesce(g.r_remaining, 0) END AS actual
FROM credit_ledger l
FULL OUTER JOIN credit_wallets w ON w.r_party = l.r_party
FULL OUTER JOIN grants g ON g.r_party = coalesce(l.r_party, w.r_party)
WHERE coalesce(w.r_balance, 0) <> coalesce(l.r_balance, 0) OR coalesce(g.r_remaining, 0) <> coalesce(l.r_balance, 0)
UNION ALL
SELECT CAST('CASH_LOTS' AS varchar(30)), CAST(coalesce(c.r_party, x.r_party) AS varchar(128)),
       coalesce(c.r_balance, 0), coalesce(x.r_remaining, 0)
FROM cash_ledger c
FULL OUTER JOIN lots x ON x.r_party = c.r_party
WHERE coalesce(c.r_balance, 0) <> coalesce(x.r_remaining, 0)
UNION ALL
SELECT CAST('FEE_CREDIT_PROMOTION' AS varchar(30)), CAST(coalesce(pg.r_operator, pl.r_operator) AS varchar(128)),
       coalesce(pg.r_net, 0), coalesce(pl.r_balance, 0)
FROM promotion_grants pg
FULL OUTER JOIN promotion_ledger pl ON pl.r_operator = pg.r_operator
WHERE coalesce(pg.r_net, 0) <> coalesce(pl.r_balance, 0)
UNION ALL
SELECT CAST('FEE_PAYMENTS' AS varchar(30)), CAST(k.r_party AS varchar(128)), sum(k.r_taken), sum(k.r_parts)
FROM checked_tx k
GROUP BY k.r_party
HAVING sum(k.r_taken) <> sum(k.r_parts)
