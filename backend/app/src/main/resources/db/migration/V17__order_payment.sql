-- Payments of orders, as a payment provider reports them by inbound webhook (ROADMAP phase 16d, decision D37): the
-- process ORDER_RECORD_PAYMENT, run from the outbox for each payment_intent.succeeded of the provider demo-pay, records
-- when the order was paid and the provider's reference of the payment. Both optional (unpaid orders, and every order so
-- far). Adding columns runs no UPDATE, so the append-only guard of the table is not involved.
ALTER TABLE sales_order_version ADD COLUMN paid_time timestamptz;
ALTER TABLE sales_order_version ADD COLUMN payment_ref varchar(255);
