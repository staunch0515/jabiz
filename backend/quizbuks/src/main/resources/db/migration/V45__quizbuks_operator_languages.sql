-- The language whose new sponsors an operator takes (docs/quizbuks/plans/Q59-languages.md, Q59c). A sponsor applying
-- for the first time with no operator is assigned to the active operator of the language they applied in; none:
-- headquarters'. One active operator per language, so that the assignment is never a choice; a suspended operator
-- keeps its language but takes no one. The sponsors assigned before stay as they are (the requester, 2026-10-30).
ALTER TABLE qb_operator ADD COLUMN language varchar(2) CHECK (language IN ('ja', 'zh', 'en'));
CREATE UNIQUE INDEX ux_qb_operator_active_language ON qb_operator (language)
    WHERE status = 'ACTIVE' AND language IS NOT NULL;
