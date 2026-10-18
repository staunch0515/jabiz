-- The sponsors' analytics data (docs/quizbuks/plans/Q9e-analytics.md, Q9e1): hourly buckets of the counters'
-- increments, a cursor per publication and the question statistics a sponsor is shown. All ordinary tables written only
-- by the job qb.analytics (QB_ANALYTICS_REFRESH / QB_ANALYTICS_CHUNK); answering writes none of them (D-Q9e-2).
-- Generated keys of ordinary entities are text (UUIDv7); keys that are a publication's refer to entity_registry.

-- A publication's increments within one UTC hour: first views, starts, completions, passes, winners and rewards spent.
-- An hour without any increment has no row (the templates fill in zeros). gap: the row also holds increments of a time
-- the job did not run (they are not spread over the hours), or the history before the first run.
CREATE TABLE qb_analytics_hour (
    hour_id        varchar(36)    PRIMARY KEY,
    publication_id uuid           NOT NULL REFERENCES entity_registry (entity_id),
    sponsor_id     varchar(128)   NOT NULL,
    hour_start     timestamptz    NOT NULL,
    views          numeric(9, 0)  NOT NULL CHECK (views >= 0),
    starts         numeric(9, 0)  NOT NULL CHECK (starts >= 0),
    completions    numeric(9, 0)  NOT NULL CHECK (completions >= 0),
    passes         numeric(9, 0)  NOT NULL CHECK (passes >= 0),
    winners        numeric(9, 0)  NOT NULL CHECK (winners >= 0),
    spent          numeric(19, 0) NOT NULL CHECK (spent >= 0),
    gap            boolean        NOT NULL,
    version        bigint         NOT NULL,
    -- Whole hours only (a bucket is an hour of UTC, and the days of the deployment's zone are whole hours of it).
    CHECK (extract(epoch FROM hour_start) = floor(extract(epoch FROM hour_start) / 3600) * 3600),
    CONSTRAINT qb_analytics_hour_once UNIQUE (publication_id, hour_start)
);
-- A sponsor's trend over all their publications; the daily purge of the hours older than 400 days (E-9).
CREATE INDEX qb_analytics_hour_sponsor_idx ON qb_analytics_hour (sponsor_id, hour_start);
CREATE INDEX qb_analytics_hour_start_idx ON qb_analytics_hour (hour_start);

-- Where the job stands with a publication: the counters' values (and the first views counted) at its last change, how
-- far the first views and the finished attempts were read, the question totals and timings so far, and the ones the
-- sponsor is shown (updated only after at least the minimum group of new completions, E-4).
CREATE TABLE qb_analytics_cursor (
    publication_id      uuid           PRIMARY KEY REFERENCES entity_registry (entity_id),
    sponsor_id          varchar(128)   NOT NULL,
    views               numeric(9, 0)  NOT NULL CHECK (views >= 0),
    starts              numeric(9, 0)  NOT NULL CHECK (starts >= 0),
    completions         numeric(9, 0)  NOT NULL CHECK (completions >= 0),
    passes              numeric(9, 0)  NOT NULL CHECK (passes >= 0),
    winners             numeric(9, 0)  NOT NULL CHECK (winners >= 0),
    spent               numeric(19, 0) NOT NULL CHECK (spent >= 0),
    views_until         timestamptz,
    watermark_at        timestamptz,
    watermark_id        varchar(36),
    analysed            numeric(9, 0)  NOT NULL CHECK (analysed >= 0),
    submitted           numeric(9, 0)  NOT NULL CHECK (submitted >= 0),
    expired             numeric(9, 0)  NOT NULL CHECK (expired >= 0),
    duration_sec        numeric(15, 3) NOT NULL CHECK (duration_sec >= 0),
    questions           varchar(4000)  NOT NULL,
    shown_analysed      numeric(9, 0)  NOT NULL CHECK (shown_analysed >= 0),
    shown_submitted     numeric(9, 0)  NOT NULL CHECK (shown_submitted >= 0),
    shown_expired       numeric(9, 0)  NOT NULL CHECK (shown_expired >= 0),
    shown_duration_sec  numeric(15, 3) NOT NULL CHECK (shown_duration_sec >= 0),
    shown_at            timestamptz,
    finalized_at        timestamptz,
    version             bigint         NOT NULL
);
-- The ended publications not yet finalized (the job's selection, besides the running ones).
CREATE INDEX qb_analytics_cursor_open_idx ON qb_analytics_cursor (publication_id) WHERE finalized_at IS NULL;

-- Each question's figures as the sponsor is shown them: completions and right answers at the last time they changed.
CREATE TABLE qb_question_stat (
    stat_id        varchar(36)   PRIMARY KEY,
    publication_id uuid          NOT NULL REFERENCES entity_registry (entity_id),
    sponsor_id     varchar(128)  NOT NULL,
    question_no    numeric(4, 0) NOT NULL CHECK (question_no >= 1),
    completions    numeric(9, 0) NOT NULL CHECK (completions >= 0),
    correct        numeric(9, 0) NOT NULL CHECK (correct >= 0 AND correct <= completions),
    shown_at       timestamptz   NOT NULL,
    version        bigint        NOT NULL,
    CONSTRAINT qb_question_stat_once UNIQUE (publication_id, question_no)
);

-- The zone the analytics job grouped days by when it last ran (jabiz.fiscal-year-zone, D-Q9e-3): the templates read
-- the deployment's days from it. Null for the other statistics job.
ALTER TABLE qb_stats_run ADD COLUMN day_zone varchar(64);

-- The views of a publication since the job's last read (D-Q9e-2). QB_VIEW takes no business lock: the index is the
-- only cost on the answering side, and no index is added to qb_attempt.
CREATE INDEX qb_publication_view_publication_idx ON qb_publication_view (publication_id, first_viewed_at);
