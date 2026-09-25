-- Database dictionaries (docs/design/02-metamodel.md section 5). An ordinary table for now; phase 4 turns it
-- into a temporal entity. labels holds one label per language: {"zh": "...", "ja": "...", "en": "..."}.
CREATE TABLE sys_dict_item (
    dict_urn   varchar(200) NOT NULL,
    item_code  varchar(100) NOT NULL,
    labels     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    sort_order integer      NOT NULL DEFAULT 0,
    enabled    boolean      NOT NULL DEFAULT true,
    PRIMARY KEY (dict_urn, item_code)
);

-- Every change is announced so that all instances evict their cached copy (DictionaryChangeListener).
CREATE FUNCTION jabiz_notify_dict_changed() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM pg_notify('jabiz_dict_changed', OLD.dict_urn);
    ELSE
        PERFORM pg_notify('jabiz_dict_changed', NEW.dict_urn);
        IF TG_OP = 'UPDATE' AND OLD.dict_urn <> NEW.dict_urn THEN
            PERFORM pg_notify('jabiz_dict_changed', OLD.dict_urn);
        END IF;
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER sys_dict_item_changed
    AFTER INSERT OR UPDATE OR DELETE ON sys_dict_item
    FOR EACH ROW EXECUTE FUNCTION jabiz_notify_dict_changed();
