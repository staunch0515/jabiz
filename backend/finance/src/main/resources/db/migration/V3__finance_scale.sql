-- A posting has one version (written once, platform decision D29): the database keeps it so, and reads of postings
-- skip picking the latest version (FIN-NF-002, docs/finance/perf.md).
CREATE UNIQUE INDEX fi_posting_version_once_uk ON fi_posting_version (posting_id);

-- Uniqueness checks find their candidates through an index on the constraint's fields (platform decision D29); the
-- composite indexes replace the single-column ones they start with.
DROP INDEX fi_exchange_rate_version_pair_idx;
CREATE INDEX fi_exchange_rate_version_pair_idx ON fi_exchange_rate_version (from_currency, to_currency, rate_date, rate_type);
CREATE INDEX fi_period_version_start_idx ON fi_period_version (start_date, adjustment);
CREATE INDEX fi_journal_version_year_no_idx ON fi_journal_version (fiscal_year, journal_no);
DROP INDEX fi_journal_line_version_journal_idx;
CREATE INDEX fi_journal_line_version_journal_idx ON fi_journal_line_version (journal_id, line_no);
DROP INDEX fi_recurring_line_version_template_idx;
CREATE INDEX fi_recurring_line_version_template_idx ON fi_recurring_line_version (template_id, line_no);
