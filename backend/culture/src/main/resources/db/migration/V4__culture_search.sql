-- Search of the public site (docs/culture/00-design.md section 7.2): public templates match a visitor's words with
-- ILIKE against the text of every language of the searched fields. Trigram indexes serve English and Chinese or
-- Japanese alike; at the archive's scale (hundreds to thousands of rows) this is enough, and full-text search with
-- stemming and ranking can come later.

-- In public (not the application's schema): an extension exists once per database, and tests give every test class
-- a schema of its own. pg_trgm is a trusted extension, so the database owner may create it.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- The texts of all languages of a multilingual value ({language: text}), in a stable order; '' for none, so that
-- concatenations of several fields are never null.
CREATE FUNCTION cu_i18n_text(value jsonb) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN coalesce((SELECT string_agg(t.value, ' ' ORDER BY t.key) FROM jsonb_each_text(value) t), '');

-- A LIKE pattern that finds the visitor's words anywhere, with the wildcards % and _ (and the escape character)
-- taken literally. Used with ESCAPE '\'.
CREATE FUNCTION cu_like_pattern(words text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN '%' || replace(replace(replace(words, '\', '\\'), '%', '\%'), '_', '\_') || '%';

-- One index per entity on the searched text, written exactly as the templates write it, so the planner uses it.
CREATE INDEX cu_story_search_idx ON cu_story USING gin ((
    cu_i18n_text(title) || ' ' || cu_i18n_text(summary) || ' ' || cu_i18n_text(body) || ' ' || cu_i18n_text(transcript)
) public.gin_trgm_ops);

CREATE INDEX cu_participant_search_idx ON cu_participant USING gin ((
    display_name || ' ' || cu_i18n_text(short_bio) || ' ' || cu_i18n_text(bio)
) public.gin_trgm_ops);

CREATE INDEX cu_theme_search_idx ON cu_theme USING gin ((
    cu_i18n_text(title) || ' ' || cu_i18n_text(question)
) public.gin_trgm_ops);

CREATE INDEX cu_resource_search_idx ON cu_resource USING gin ((
    cu_i18n_text(title) || ' ' || cu_i18n_text(description)
) public.gin_trgm_ops);

