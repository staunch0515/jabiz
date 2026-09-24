-- CustomsDeclaration becomes writable (optimistic-lock version) and gets a database-level
-- backstop for the waybill reference declared in the entity definition.
ALTER TABLE t_customs_declaration ADD COLUMN f_version bigint NOT NULL DEFAULT 1;
ALTER TABLE t_customs_declaration
    ADD CONSTRAINT fk_customs_waybill FOREIGN KEY (f_wb_ref_sn) REFERENCES t_legacy_waybill_2026 (f_wb_sn);
