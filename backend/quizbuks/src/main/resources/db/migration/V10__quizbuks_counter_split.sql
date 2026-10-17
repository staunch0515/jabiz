-- The lock split (docs/quizbuks/plans/Q10-lock-split.md, Q10a4): a start takes the publication's start lock
-- (qb.pub-start:<publication>) and a completion the publication's lock (qb.pub:<publication>), so their counters are
-- two rows with one writer each. qb_publication_start_counter is the starts' side: attempts started and the highest
-- rewards they reserved, ever. qb_publication_counter keeps the completions' side and gains what the completions
-- released, ever; its starts and reserved move to the new table. Attempts in progress = starts - completions and
-- reserved = reserved_total - released, read from both tables in one statement.
--
-- One step (LS-6): QuizBuks runs one instance and is not live; the old jar cannot run on the new tables, nor the new
-- jar on the old. The old rows are checked first: a row without a card, more completions than starts, or a reserve
-- other than (starts - completions) x the card's highest reward fails the migration, which rolls back as a whole
-- (the database stays at V9). Such a row is a defect of the counters, to be found rather than carried over.

DO $$
DECLARE
    bad record;
BEGIN
    FOR bad IN
        SELECT n.publication_id, n.starts, n.completions, n.reserved, k.max_reward
        FROM qb_publication_counter n
        LEFT JOIN qb_publication_card k ON k.publication_id = n.publication_id
        WHERE k.publication_id IS NULL
           OR n.starts < n.completions
           OR n.reserved <> (n.starts - n.completions) * k.max_reward
        LIMIT 1
    LOOP
        RAISE EXCEPTION 'qb_publication_counter of publication % does not hold its invariants (starts %, completions '
            '%, reserved %, the card''s max_reward %): find the cause before the split', bad.publication_id,
            bad.starts, bad.completions, bad.reserved, bad.max_reward;
    END LOOP;
END $$;

CREATE TABLE qb_publication_start_counter (
    publication_id uuid           PRIMARY KEY REFERENCES entity_registry (entity_id),
    starts         numeric(9, 0)  NOT NULL CHECK (starts >= 0),
    reserved_total numeric(19, 0) NOT NULL CHECK (reserved_total >= 0),
    version        bigint         NOT NULL
);

ALTER TABLE qb_publication_counter
    ADD COLUMN released numeric(19, 0) NOT NULL DEFAULT 0 CHECK (released >= 0);

-- Every start reserved the card's max_reward and every completion released it: reserved_total - released is the old
-- reserved exactly.
INSERT INTO qb_publication_start_counter (publication_id, starts, reserved_total, version)
SELECT n.publication_id, n.starts, n.starts * k.max_reward, 1
FROM qb_publication_counter n
JOIN qb_publication_card k ON k.publication_id = n.publication_id;

UPDATE qb_publication_counter n
SET released = n.completions * k.max_reward
FROM qb_publication_card k
WHERE k.publication_id = n.publication_id;

ALTER TABLE qb_publication_counter
    DROP COLUMN starts,
    DROP COLUMN reserved,
    ALTER COLUMN released DROP DEFAULT;
