-- Marketing copy of a product, written by the demo AI service as an asynchronous external job (ROADMAP phase 16e-2,
-- decision D38): PRODUCT_COPY_APPLY sets it once the job has its text; nothing else writes it (processOnly). Optional
-- (products without copy, and every product so far). Adding a column runs no UPDATE, so the append-only guard of the
-- table is not involved.
ALTER TABLE product_version ADD COLUMN marketing_copy varchar(2000);
