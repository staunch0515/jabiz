-- Publications open to a list of e-mail addresses only (docs/quizbuks/plans/Q25-invitations.md). The audience is the
-- publication's setting, frozen to its card when it goes on; PUBLIC is every publication before Q25.
ALTER TABLE qb_publication_version
    ADD COLUMN audience varchar(10) NOT NULL DEFAULT 'PUBLIC' CHECK (audience IN ('PUBLIC', 'INVITED'));
ALTER TABLE qb_publication_version ALTER COLUMN audience DROP DEFAULT;
ALTER TABLE qb_publication_card
    ADD COLUMN audience varchar(10) NOT NULL DEFAULT 'PUBLIC' CHECK (audience IN ('PUBLIC', 'INVITED'));
ALTER TABLE qb_publication_card ALTER COLUMN audience DROP DEFAULT;

-- The invited addresses of a publication, one row each (the address in lower case), written by the invitation
-- processes only: added by the sponsor, queued for the mail job and sent (the mail carries a one-time sign-in link,
-- platform decision D55), claimed by the taker's account (user_id), started and answered with the attempt's score and
-- reward. An ordinary table: a row not yet started may be removed.
CREATE TABLE qb_invitation (
    invitation_id  varchar(36)   PRIMARY KEY,
    publication_id uuid          NOT NULL REFERENCES entity_registry (entity_id),
    sponsor_id     varchar(128)  NOT NULL,
    email          varchar(320)  NOT NULL,
    status         varchar(10)   NOT NULL CHECK (status IN ('ADDED', 'QUEUED', 'SENT', 'STARTED', 'ANSWERED')),
    added_at       timestamptz   NOT NULL,
    sent_count     numeric(5, 0) NOT NULL CHECK (sent_count >= 0),
    last_sent_at   timestamptz,
    user_id        varchar(128),
    attempt_id     varchar(36),
    started_at     timestamptz,
    answered_at    timestamptz,
    score          numeric(7, 0),
    reward         numeric(19, 0),
    version        bigint        NOT NULL,
    CONSTRAINT uk_qb_invitation UNIQUE (publication_id, email)
);
CREATE INDEX qb_invitation_sponsor_idx ON qb_invitation (sponsor_id, added_at);
CREATE INDEX qb_invitation_user_idx ON qb_invitation (publication_id, user_id);
CREATE INDEX qb_invitation_attempt_idx ON qb_invitation (attempt_id);
-- The mail job's queue: the rows to send.
CREATE INDEX qb_invitation_queued_idx ON qb_invitation (added_at) WHERE status = 'QUEUED';

-- How many invitations a sponsor queued on a day (the deployment's zone), for the daily limit.
CREATE TABLE qb_invitation_quota (
    quota_id   varchar(36)   PRIMARY KEY,
    sponsor_id varchar(128)  NOT NULL,
    day        varchar(10)   NOT NULL,
    sent       numeric(9, 0) NOT NULL CHECK (sent >= 0),
    version    bigint        NOT NULL,
    CONSTRAINT uk_qb_invitation_quota UNIQUE (sponsor_id, day)
);
