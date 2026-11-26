-- Q113b-1: the home's post shows the beginning of the quiz's introduction under its name (docs/quizbuks/plans/impl/
-- Q113.md "Q113b-1 的决定"). The card freezes it as plain text when it goes live and when its version switches
-- (publishing.IntroLeads): in its own column for the version's base language, under "introLead" in its texts for each
-- other language offered, as it does the title. The feed reads it there, never the version's whole content.
--
-- The cards made before this are filled here with the same rules (IntroLeads: change both together): Markdown's marks
-- dropped (an image entirely, a link down to its text, list marks at the start of a line), the lines run together, a
-- longer text cut to 160 characters ending in an ellipsis; nothing left is none. The card is an ordinary table.

ALTER TABLE qb_publication_card ADD COLUMN intro_lead varchar(160);

CREATE FUNCTION pg_temp.qb_intro_lead(introduction text) RETURNS text
    LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    lead text;
BEGIN
    IF introduction IS NULL THEN
        RETURN NULL;
    END IF;
    lead := regexp_replace(introduction, '!\[[^]]*\]\([^)]*\)', '', 'g');
    lead := regexp_replace(lead, '\[([^]]*)\]\([^)]*\)', '\1', 'g');
    lead := regexp_replace(lead, E'^[ \t\n\r\f]*([-+]|[0-9]+\\.)[ \t\n\r\f]+', '', 'gn');
    lead := regexp_replace(lead, '[#*_>`~|]', '', 'g');
    lead := regexp_replace(lead, E'[ \t\n\r\f]+', ' ', 'g');
    lead := btrim(lead, ' ');
    IF lead = '' THEN
        RETURN NULL;
    END IF;
    IF char_length(lead) <= 160 THEN
        RETURN lead;
    END IF;
    RETURN left(lead, 159) || '…';
END
$$;

UPDATE qb_publication_card c
SET intro_lead = pg_temp.qb_intro_lead(CAST(v.content AS jsonb) ->> 'intro'),
    translations = CASE WHEN c.translations IS NULL THEN NULL ELSE (
        SELECT CAST(jsonb_object_agg(t.key, CASE
            WHEN pg_temp.qb_intro_lead(CAST(v.content AS jsonb) -> 'translations' -> t.key -> 'texts' ->> 'intro') IS NULL
                THEN t.value
            ELSE t.value || jsonb_build_object('introLead', pg_temp.qb_intro_lead(
                CAST(v.content AS jsonb) -> 'translations' -> t.key -> 'texts' ->> 'intro')) END) AS text)
        FROM jsonb_each(CAST(c.translations AS jsonb)) t) END
FROM qb_quiz_version_version v
WHERE v.version_id = c.version_id;
