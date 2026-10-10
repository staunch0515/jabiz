-- Blocked customers of the orders sample (docs/design/10-security.md section 15, ROADMAP phase 16b-2): administrators
-- block a customer through its dataset; the portal's sign-in guard then refuses its account at the next sign-in or
-- refresh, and PORTAL_ORDER_PLACE refuses its orders. Optional: empty (as for every customer so far) is not blocked.
-- Adding a column runs no UPDATE, so the append-only guard of the table is not involved.
ALTER TABLE customer_version ADD COLUMN blocked boolean;
-- The guard reads a user's customers at every sign-in to the portal.
CREATE INDEX customer_version_user_idx ON customer_version (user_id) WHERE user_id IS NOT NULL;
