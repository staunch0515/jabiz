-- Database dictionaries become the temporal entity SysDictItem (docs/design/02-metamodel.md section 5,
-- ROADMAP phase 4 item 11). Versions live in sys_dict_item_version; sys_dict_item becomes a view of the current
-- items that still accepts INSERT, so that migrations can seed dictionaries as before.

CREATE TABLE sys_dict_item_version (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dict_item_id      uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    dict_urn          varchar(200) NOT NULL,
    item_code         varchar(100) NOT NULL,
    labels            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    sort_order        integer      NOT NULL DEFAULT 0,
    enabled           boolean      NOT NULL DEFAULT true,
    CONSTRAINT sys_dict_item_version_uk UNIQUE (dict_item_id, version_no)
);
CREATE INDEX sys_dict_item_version_current_idx
    ON sys_dict_item_version (dict_item_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sys_dict_item_version_process_idx ON sys_dict_item_version (process_seq_id);
CREATE INDEX sys_dict_item_version_urn_idx ON sys_dict_item_version (dict_urn, item_code);
SELECT jabiz_protect_append_only('sys_dict_item_version');

-- Versions are only ever inserted, so only inserts are announced (DictionaryChangeListener).
DROP TRIGGER sys_dict_item_changed ON sys_dict_item;
CREATE TRIGGER sys_dict_item_version_changed
    AFTER INSERT ON sys_dict_item_version
    FOR EACH ROW EXECUTE FUNCTION jabiz_notify_dict_changed();

-- Records base data of a dictionary from SQL (migrations, seeding). The version is effective from the epoch, so
-- base data applies at every business time; putting an existing item corrects its base version. Items that
-- already have versions effective later (written through the dictionary's dataset) are refused: such changes need
-- the rebase of the application (decision D1). All puts of one transaction form one operation 'jabiz.sql'.
CREATE FUNCTION jabiz_dict_put(p_urn varchar, p_code varchar, p_labels jsonb, p_sort_order integer,
                               p_enabled boolean) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    base_time constant timestamptz := '1970-01-01T00:00:00Z';
    seq       bigint;
    item_id   uuid;
    current   sys_dict_item_version%ROWTYPE;
    next_no   integer;
    changed   text[];
BEGIN
    seq := nullif(current_setting('jabiz.sql_operation', true), '')::bigint;
    IF seq IS NULL OR NOT EXISTS (SELECT 1 FROM op_process WHERE process_seq_id = seq) THEN
        seq := nextval('op_process_seq');
        INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id, op_time)
        VALUES (seq, 'jabiz.sql', 1, current_user, date_trunc('microseconds', clock_timestamp()));
        PERFORM set_config('jabiz.sql_operation', seq::text, true);
    END IF;

    SELECT dict_item_id INTO item_id
    FROM sys_dict_item_version WHERE dict_urn = p_urn AND item_code = p_code
    ORDER BY version_no DESC LIMIT 1;

    IF item_id IS NULL THEN
        item_id := jabiz_uuid_v7();
        INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) VALUES (item_id, 'SysDictItem', seq);
        INSERT INTO sys_dict_item_version (dict_item_id, version_no, effect_start_time, created_time, process_seq_id,
                                           dict_urn, item_code, labels, sort_order, enabled)
        VALUES (item_id, 1, base_time, (SELECT op_time FROM op_process WHERE process_seq_id = seq), seq,
                p_urn, p_code, p_labels, p_sort_order, p_enabled);
        INSERT INTO op_process_item (process_seq_id, entity_type, entity_id, version_no, base_version_no, action,
                                     effect_start_time, changed_fields)
        VALUES (seq, 'SysDictItem', item_id, 1, NULL, 'INSERT', base_time,
                ARRAY['dictUrn', 'itemCode', 'labels', 'sortOrder', 'enabled']);
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM sys_dict_item_version WHERE dict_item_id = item_id AND effect_start_time > base_time) THEN
        RAISE EXCEPTION 'jabiz: dictionary item %/% has versions effective after its base data; change it through '
                        'its dataset', p_urn, p_code;
    END IF;
    SELECT * INTO current FROM sys_dict_item_version WHERE dict_item_id = item_id
    ORDER BY version_no DESC LIMIT 1;
    changed := array_remove(ARRAY[
        CASE WHEN current.labels IS DISTINCT FROM p_labels THEN 'labels' END,
        CASE WHEN current.sort_order IS DISTINCT FROM p_sort_order THEN 'sortOrder' END,
        CASE WHEN current.enabled IS DISTINCT FROM p_enabled THEN 'enabled' END], NULL);
    IF cardinality(changed) = 0 AND NOT current.is_deleted THEN
        RETURN;
    END IF;
    IF current.is_deleted THEN
        -- Bringing a deleted item back changes all of it, like an insert.
        changed := ARRAY['dictUrn', 'itemCode', 'labels', 'sortOrder', 'enabled'];
    END IF;
    next_no := current.version_no + 1;
    INSERT INTO sys_dict_item_version (dict_item_id, version_no, effect_start_time, created_time, process_seq_id,
                                       dict_urn, item_code, labels, sort_order, enabled)
    VALUES (item_id, next_no, base_time, (SELECT op_time FROM op_process WHERE process_seq_id = seq), seq,
            p_urn, p_code, p_labels, p_sort_order, p_enabled);
    INSERT INTO op_process_item (process_seq_id, entity_type, entity_id, version_no, base_version_no, action,
                                 effect_start_time, changed_fields)
    VALUES (seq, 'SysDictItem', item_id, next_no, current.version_no,
            'UPDATE', base_time, changed);
END $$;

-- Existing items become base data.
SELECT jabiz_dict_put(dict_urn, item_code, labels, sort_order, enabled)
FROM sys_dict_item ORDER BY dict_urn, sort_order, item_code;
DROP TABLE sys_dict_item;

-- Current items for people and SQL tools. The application reads the versions at the time of its own clock
-- instead; the database clock is not business time.
CREATE VIEW sys_dict_item AS
SELECT dict_urn, item_code, labels, sort_order, enabled
FROM (SELECT DISTINCT ON (dict_item_id) *
      FROM sys_dict_item_version
      WHERE effect_start_time <= now()
      ORDER BY dict_item_id, effect_start_time DESC, version_no DESC) v
WHERE NOT is_deleted;

CREATE FUNCTION jabiz_dict_item_view_insert() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    PERFORM jabiz_dict_put(NEW.dict_urn, NEW.item_code, coalesce(NEW.labels, '{}'::jsonb),
                           coalesce(NEW.sort_order, 0), coalesce(NEW.enabled, true));
    RETURN NEW;
END $$;

CREATE TRIGGER sys_dict_item_insert
    INSTEAD OF INSERT ON sys_dict_item
    FOR EACH ROW EXECUTE FUNCTION jabiz_dict_item_view_insert();
