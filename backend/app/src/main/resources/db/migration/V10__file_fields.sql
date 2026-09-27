-- Files of the commerce sample (docs/design/14-files.md): a product photo and a supplier contract. No foreign key to
-- sys_file: history may point at a deleted file; references are checked on write and before a file is deleted.
ALTER TABLE product_version ADD COLUMN image_file_id uuid;
ALTER TABLE supplier_version ADD COLUMN contract_file_id uuid;
-- Deleting a file and the sweep look up which rows refer to it.
CREATE INDEX product_version_image_idx ON product_version (image_file_id) WHERE image_file_id IS NOT NULL;
CREATE INDEX supplier_version_contract_idx ON supplier_version (contract_file_id) WHERE contract_file_id IS NOT NULL;
