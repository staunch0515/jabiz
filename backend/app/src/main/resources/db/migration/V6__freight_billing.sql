-- Freight billing, the sample of business parameters and scenario replay (ROADMAP phase 8): the fuel surcharge of a
-- waybill uses the rate in effect when it was shipped; a month's charges are closed into one statement.
CREATE TABLE t_freight_charge (
    f_charge_id        varchar(36)   PRIMARY KEY,
    f_wb_sn            varchar(64)   NOT NULL REFERENCES t_legacy_waybill_2026 (f_wb_sn),
    f_charge_month     varchar(7)    NOT NULL,
    f_shipped_at       timestamptz   NOT NULL,
    f_base_amt         numeric(19,0) NOT NULL,
    f_surcharge_rate   numeric(5,4)  NOT NULL,
    f_surcharge_amt    numeric(19,0) NOT NULL,
    f_total_amt        numeric(19,0) NOT NULL,
    f_settled          boolean       NOT NULL DEFAULT false,
    f_sys_created_at   timestamptz   NOT NULL,
    f_version          bigint        NOT NULL DEFAULT 1
);
-- One charge per waybill (eb.unique, same name).
CREATE UNIQUE INDEX uk_freight_charge_waybill ON t_freight_charge (f_wb_sn);
CREATE INDEX t_freight_charge_month_idx ON t_freight_charge (f_charge_month, f_settled);

-- The running statement of a month: every charge updates it (optimistic lock), closing flips it to closed, so a
-- charge and a close of the same month can never both commit on stale reads.
CREATE TABLE t_freight_statement (
    f_statement_id     varchar(36)   PRIMARY KEY,
    f_statement_month  varchar(7)    NOT NULL,
    f_charge_count     integer       NOT NULL,
    f_total_amt        numeric(19,0) NOT NULL,
    f_closed           boolean       NOT NULL DEFAULT false,
    f_closed_at        timestamptz,
    f_sys_created_at   timestamptz   NOT NULL,
    f_version          bigint        NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX uk_freight_statement_month ON t_freight_statement (f_statement_month);
