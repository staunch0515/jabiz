-- F7b (FIN-FX-003, 004): payables in a foreign currency. Bills keep their rate and US dollars, applications what
-- they cleared and what the source gave in US dollars with the difference (the realized gain or loss), payment runs
-- and payments their currency and rate. Existing rows are in US dollars and are not backfilled (append-only): null
-- reads as a rate of 1 and dollars equal to the amount.

ALTER TABLE fi_bill_version
    ADD COLUMN exchange_rate   numeric(19,10),
    ADD COLUMN total_usd       numeric(15,2),
    ADD COLUMN open_amount_usd numeric(15,2);

ALTER TABLE fi_ap_application_version
    ADD COLUMN amount_usd        numeric(15,2),
    ADD COLUMN source_amount_usd numeric(15,2),
    ADD COLUMN fx_gain_loss      numeric(15,2);

ALTER TABLE fi_payment_run_version
    ADD COLUMN currency      varchar(3),
    ADD COLUMN exchange_rate numeric(19,10);

ALTER TABLE fi_payment_version
    ADD COLUMN currency      varchar(3),
    ADD COLUMN exchange_rate numeric(19,10),
    ADD COLUMN amount_usd    numeric(15,2);
