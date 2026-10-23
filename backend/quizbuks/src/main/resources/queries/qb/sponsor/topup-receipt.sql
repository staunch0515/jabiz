/*---
id: qb.sponsor.topup-receipt
description: One paid top-up of the sponsor's own, as its receipt shows it (Q9d, F-09)
entities: [QbTopUp, QbSponsor]
datasets:
  QbTopUp: urn:jabiz:dataset:sponsor:QbTopUp
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
params:
  topUpId: { like: QbTopUp.topUpId, required: true, description: The top-up }
results:
  receiptNo:        { from: QbTopUp.topUpId }
  company:          { from: QbSponsor.company }
  contactName:      { from: QbSponsor.contactName }
  paidAt:           { from: QbTopUp.paidAt }
  paymentReference: { kind: { type: text, maxLength: 20 } }
  paidIn:           { kind: { type: text, maxLength: 40 } }
  kudos:            { kind: { type: numeric, precision: 19, scale: 0 } }
  amount:           { from: QbTopUp.amount }
permissions: [qb.topup]
---*/
-- Paid top-ups only: a receipt is for money received. The payment's reference is the end of Stripe's payment id, enough
-- for the sponsor to match their card statement's entry without the whole id; a bank transfer's is its code (Q13a).
-- Refunds do not change the receipt.
-- paidIn is what the card was charged: the currency Stripe converted to (its amount in the currency's minor unit, as
-- Stripe gives it: none for its zero-decimal currencies, three for the three-decimal ones, else two), else the yen.
SELECT
    t.{{QbTopUp.topUpId}} AS receiptNo,
    s.{{QbSponsor.company}} AS company,
    s.{{QbSponsor.contactName}} AS contactName,
    t.{{QbTopUp.paidAt}} AS paidAt,
    CAST(CASE WHEN t.{{QbTopUp.method}} = 'BANK' THEN t.{{QbTopUp.transferCode}}
        ELSE '...' || right(t.{{QbTopUp.paymentIntentId}}, 8) END AS varchar(20)) AS paymentReference,
    CAST(CASE
        WHEN t.{{QbTopUp.presentmentCurrency}} IS NULL OR t.{{QbTopUp.presentmentAmount}} IS NULL
            OR lower(t.{{QbTopUp.presentmentCurrency}}) = 'jpy'
            THEN 'JPY ' || to_char(t.{{QbTopUp.amount}}, 'FM999,999,999,999,990')
        WHEN lower(t.{{QbTopUp.presentmentCurrency}}) IN ('bif', 'clp', 'djf', 'gnf', 'kmf', 'krw', 'mga', 'pyg',
            'rwf', 'ugx', 'vnd', 'vuv', 'xaf', 'xof', 'xpf')
            THEN upper(t.{{QbTopUp.presentmentCurrency}}) || ' '
                || to_char(t.{{QbTopUp.presentmentAmount}}, 'FM999,999,999,999,990')
        WHEN lower(t.{{QbTopUp.presentmentCurrency}}) IN ('bhd', 'jod', 'kwd', 'omr', 'tnd')
            THEN upper(t.{{QbTopUp.presentmentCurrency}}) || ' '
                || to_char(t.{{QbTopUp.presentmentAmount}} / 1000.0, 'FM999,999,999,990.000')
        ELSE upper(t.{{QbTopUp.presentmentCurrency}}) || ' '
            || to_char(t.{{QbTopUp.presentmentAmount}} / 100.0, 'FM999,999,999,990.00')
    END AS varchar(40)) AS paidIn,
    t.{{QbTopUp.amount}} AS kudos,
    t.{{QbTopUp.amount}} AS amount
FROM {{QbTopUp}} t
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = t.{{QbTopUp.sponsorId}}
WHERE t.{{QbTopUp.topUpId}} = :topUpId
  AND t.{{QbTopUp.status}} = 'PAID'
