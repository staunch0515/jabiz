-- F-C10c: the months of the help gaps report are the deployment's zone's (jabiz.fiscal-year-zone, the controller's
-- decision of 2026-11-09), so the process that rates an answer or hands a conversation over records the month it fell
-- in, as qb_help_usage does. Rows from before are filled in UTC (the help had not been released).
ALTER TABLE qb_help_message ADD COLUMN rated_month varchar(7);
ALTER TABLE qb_help_thread ADD COLUMN escalated_month varchar(7);
UPDATE qb_help_message SET rated_month = to_char(rated_at AT TIME ZONE 'UTC', 'YYYY-MM') WHERE rated_at IS NOT NULL;
UPDATE qb_help_thread SET escalated_month = to_char(escalated_at AT TIME ZONE 'UTC', 'YYYY-MM')
    WHERE escalated_at IS NOT NULL;
DROP INDEX qb_help_message_rating_idx;
CREATE INDEX qb_help_message_rating_idx ON qb_help_message (rated_month) WHERE rating = 'UNSOLVED';
