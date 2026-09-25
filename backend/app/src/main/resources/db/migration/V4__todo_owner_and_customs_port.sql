-- Todo entries belong to an actor (member dataset); customs declarations name their port, a database dictionary.
ALTER TABLE todo ADD COLUMN owner_id varchar(128);
ALTER TABLE t_customs_declaration ADD COLUMN f_port_code varchar(16);

INSERT INTO sys_dict_item (dict_urn, item_code, labels, sort_order) VALUES
    ('urn:jabiz:dict:customs_port', 'JPTYO', '{"zh": "东京港", "ja": "東京港", "en": "Port of Tokyo"}', 1),
    ('urn:jabiz:dict:customs_port', 'JPYOK', '{"zh": "横滨港", "ja": "横浜港", "en": "Port of Yokohama"}', 2),
    ('urn:jabiz:dict:customs_port', 'JPOSA', '{"zh": "大阪港", "ja": "大阪港", "en": "Port of Osaka"}', 3);
