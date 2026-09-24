-- Todo: adapt the existing table to the Todo entity definition.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'todo'
          AND column_name = 'id' AND is_identity = 'YES'
    ) THEN
        ALTER TABLE todo ALTER COLUMN id DROP IDENTITY;
    END IF;
END $$;

ALTER TABLE todo ALTER COLUMN id DROP DEFAULT;
ALTER TABLE todo ALTER COLUMN id TYPE varchar(36) USING id::text;
ALTER TABLE todo ADD COLUMN version bigint NOT NULL DEFAULT 1;
ALTER TABLE todo ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();

-- WaybillTracking
CREATE TABLE t_legacy_waybill_2026 (
    f_wb_sn             varchar(64)   PRIMARY KEY,
    f_charge_amt        numeric(19,0),
    f_gross_wt          numeric(12,3),
    f_shipped_timestamp timestamptz,
    f_sys_created_at    timestamptz   NOT NULL DEFAULT now(),
    f_version           bigint        NOT NULL DEFAULT 1,
    f_current_h3_cell   bigint,
    f_status_code       varchar(32)
);

-- CustomsDeclaration
CREATE TABLE t_customs_declaration (
    f_decl_no     varchar(64)   PRIMARY KEY,
    f_wb_ref_sn   varchar(64),
    f_duty_amt    numeric(19,0)
);