-- A date field for the sample of docs/design/02-metamodel.md section 1 (the date kind): when a closed month's freight
-- is due, a calendar day rather than a moment.
ALTER TABLE t_freight_statement ADD COLUMN f_due_date date;
