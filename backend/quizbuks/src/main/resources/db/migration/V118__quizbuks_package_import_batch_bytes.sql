-- F-F11a (docs/quizbuks/plans/impl/F-F11.md section 3.3, the controller's decision 9): the bytes one request of a
-- large bank's import carries, as the import started. The steps' keys name their batch by its number
-- (quiz:q1:v:v1:batch:2), so a resumed import must cut its bank as the first run did, by the question count
-- (batch_size) and by these bytes. Empty: an import that started before the bytes limit existed (it goes on by the
-- question count only). An ordinary table, not an append-only one: no sealed columns are touched.
ALTER TABLE qb_package_import ADD COLUMN batch_bytes numeric(7, 0);
