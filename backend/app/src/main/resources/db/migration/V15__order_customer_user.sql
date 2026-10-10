-- The customer of an order as a user of the platform, who hears of its shipment by mail
-- (docs/design/18-numbering-approvals-tasks.md section 5.6, ROADMAP phase 16a). Optional: orders of customers without
-- an account have none.
ALTER TABLE sales_order_version ADD COLUMN customer_user_id uuid REFERENCES entity_registry (entity_id);
