-- Supplier certifications: the sample of content authoring (docs/design/16-content-authoring.md section 9). A temporal
-- table, only ever inserted into; the title and body are texts by language, stored as jsonb.
CREATE TABLE supplier_certification_version (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    certification_id  uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    supplier_id       uuid         NOT NULL,
    title             jsonb        NOT NULL,
    body              jsonb,
    status            varchar(20)  NOT NULL,
    review_comment    varchar(500),
    CONSTRAINT supplier_certification_version_uk UNIQUE (certification_id, version_no)
);
CREATE INDEX supplier_certification_version_current_idx
    ON supplier_certification_version (certification_id, effect_start_time DESC, version_no DESC);
CREATE INDEX supplier_certification_version_process_idx ON supplier_certification_version (process_seq_id);
CREATE INDEX supplier_certification_version_supplier_idx ON supplier_certification_version (supplier_id);
SELECT jabiz_protect_append_only('supplier_certification_version');
