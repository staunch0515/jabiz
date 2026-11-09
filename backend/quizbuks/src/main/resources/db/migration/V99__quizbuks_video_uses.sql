-- F-S14 (docs/quizbuks/plans/impl/F-S14.md): the ledger of shared videos' uses and both sides' figures. The number is
-- temporary (the base's highest is V96, V97 is S2's, V98 F-S13's): renumbered to the highest on 1.3/quizbuks + 1
-- before merging.

CREATE FUNCTION quizbuks_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE TABLE %I (
        row_id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        %I                uuid        NOT NULL REFERENCES entity_registry (entity_id),
        version_no        integer     NOT NULL,
        effect_start_time timestamptz NOT NULL,
        created_time      timestamptz NOT NULL,
        process_seq_id    bigint      NOT NULL REFERENCES op_process (process_seq_id),
        is_deleted        boolean     NOT NULL DEFAULT false,
        %s,
        CONSTRAINT %I UNIQUE (%I, version_no))',
        p_table, p_key, p_columns, p_table || '_uk', p_key);
    EXECUTE format('CREATE INDEX %I ON %I (%I, effect_start_time DESC, version_no DESC)',
        p_table || '_current_idx', p_table, p_key);
    EXECUTE format('CREATE INDEX %I ON %I (process_seq_id)', p_table || '_process_idx', p_table);
    PERFORM jabiz_protect_append_only(p_table::regclass);
END $$;

-- One use of another sponsor's shared video by a publication, as the user (the publication's sponsor) writes it and the
-- provider answers it: the period, the pricing and its price (no money moves on the platform for it, and it is not in
-- the platform's books). Append-only: every save, submission and answer is a new version (audited by the platform).
SELECT quizbuks_create_temporal_table('qb_video_use_version', 'video_use_id', '
    publication_id      varchar(36)    NOT NULL,
    shared_video_id     varchar(36)    NOT NULL,
    sponsor_id          varchar(128)   NOT NULL,
    provider_id         varchar(128)   NOT NULL,
    period_from         date           NOT NULL,
    period_to           date           NOT NULL,
    pricing             varchar(10)    NOT NULL CHECK (pricing IN (''FIXED'', ''PER_MILLE'', ''PER_TAKER'')),
    price               numeric(19, 0) NOT NULL CHECK (price >= 0),
    note                varchar(1000),
    status              varchar(10)    NOT NULL CHECK (status IN (''DRAFT'', ''SUBMITTED'', ''CONFIRMED'',
                                                                  ''REJECTED'')),
    submitted_at        timestamptz,
    confirmed_at        timestamptz,
    confirmed_by        varchar(128),
    confirmed_revision  numeric(9, 0),
    rejected_at         timestamptz,
    reject_reason       varchar(500),
    edited_at           timestamptz    NOT NULL,
    revision            numeric(9, 0)  NOT NULL,
    CHECK (period_from <= period_to)');

-- By the immutable fields only (a mutable one's condition reads every version): a publication's use (one, checked
-- under its lock), each side's.
CREATE INDEX qb_video_use_version_publication_idx ON qb_video_use_version (publication_id);
CREATE INDEX qb_video_use_version_sponsor_idx ON qb_video_use_version (sponsor_id);
CREATE INDEX qb_video_use_version_provider_idx ON qb_video_use_version (provider_id);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- The figures of a publication using another's shared video, from the counting tables (qb_exposure, qb_attempt) up to a
-- cut-off: refreshed every day by the job qb.video-use-figures, finalised at the first run after the publication ended
-- (the cut-off is then its end) and never written again. An ordinary table written by that job's process only.
CREATE TABLE qb_video_use_figures (
    figures_id      varchar(36)    PRIMARY KEY,
    publication_id  varchar(36)    NOT NULL,
    shared_video_id varchar(36)    NOT NULL,
    sponsor_id      varchar(128)   NOT NULL,
    provider_id     varchar(128)   NOT NULL,
    opens           numeric(12, 0) NOT NULL,
    successes       numeric(12, 0) NOT NULL,
    goes            numeric(12, 0) NOT NULL,
    qualified       numeric(12, 0) NOT NULL,
    cutoff_at       timestamptz    NOT NULL,
    refreshed_at    timestamptz    NOT NULL,
    finalised_at    timestamptz,
    version         bigint         NOT NULL,
    CONSTRAINT qb_video_use_figures_one UNIQUE (publication_id)
);
-- The provider's side, and the operators' scope by the user (the publication's sponsor).
CREATE INDEX qb_video_use_figures_provider_idx ON qb_video_use_figures (provider_id);
CREATE INDEX qb_video_use_figures_sponsor_idx ON qb_video_use_figures (sponsor_id);
