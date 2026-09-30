-- Ledger enhancements, part 2 (docs/design/11-ledger-events-jobs.md section 1.8; decision D24 item 5): entries in
-- foreign currencies. amount stays the ledger-currency amount; a foreign entry also keeps its currency, its amount
-- in that currency and the rate. Existing rows keep NULL: entries in the ledger currency (append-only, decision D5).

ALTER TABLE ledger_entry_version
    ADD COLUMN currency           varchar(3),
    ADD COLUMN transaction_amount numeric(19,4),
    ADD COLUMN exchange_rate      numeric(20,10),
    ADD CONSTRAINT ledger_entry_version_foreign_ck CHECK (
        (currency IS NULL AND transaction_amount IS NULL AND exchange_rate IS NULL)
        OR (currency IS NOT NULL AND transaction_amount > 0 AND exchange_rate > 0));
CREATE INDEX ledger_entry_version_currency_idx ON ledger_entry_version (account_id, currency) WHERE currency IS NOT NULL;

-- The second line of defence now also checks each foreign currency: when the database transaction commits, the
-- current entries of every ledger transaction it wrote must balance in the ledger currency and in every transaction
-- currency. Violations raise SQLSTATE JZ002.
CREATE OR REPLACE FUNCTION jabiz_ledger_check_balance() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    difference numeric;
    unbalanced text;
BEGIN
    SELECT coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END), 0)
      INTO difference
      FROM (SELECT DISTINCT ON (entry_id) *
              FROM ledger_entry_version
             WHERE transaction_id = NEW.transaction_id
             ORDER BY entry_id, effect_start_time DESC, version_no DESC) e
     WHERE NOT e.is_deleted;
    IF difference <> 0 THEN
        RAISE EXCEPTION 'Ledger transaction % does not balance (debit - credit = %)', NEW.transaction_id, difference
            USING ERRCODE = 'JZ002';
    END IF;
    SELECT string_agg(currency || ' ' || total, ', ')
      INTO unbalanced
      FROM (SELECT e.currency,
                   sum(CASE WHEN e.direction = 'DEBIT' THEN e.transaction_amount ELSE -e.transaction_amount END) AS total
              FROM (SELECT DISTINCT ON (entry_id) *
                      FROM ledger_entry_version
                     WHERE transaction_id = NEW.transaction_id
                     ORDER BY entry_id, effect_start_time DESC, version_no DESC) e
             WHERE NOT e.is_deleted AND e.currency IS NOT NULL
             GROUP BY e.currency) c
     WHERE c.total <> 0;
    IF unbalanced IS NOT NULL THEN
        RAISE EXCEPTION 'Ledger transaction % does not balance in %', NEW.transaction_id, unbalanced
            USING ERRCODE = 'JZ002';
    END IF;
    RETURN NULL;
END $$;
