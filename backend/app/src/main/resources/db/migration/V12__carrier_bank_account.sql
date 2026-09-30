-- A masked field for the sample of docs/design/10-security.md section 13.1: carriers' bank accounts.
ALTER TABLE carrier_version ADD COLUMN bank_account varchar(34);
