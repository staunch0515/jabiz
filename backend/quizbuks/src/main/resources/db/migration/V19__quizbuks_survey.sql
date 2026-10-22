-- Surveys (docs/quizbuks/plans/Q12-survey.md, Q12a): a quiz is a scored quiz or a survey, chosen when it is made and
-- never changed (D-Q12-1); a survey's questions are single or multiple choice (D-Q12-2); the card freezes the kind
-- when the publication goes on, so the takers' pages and processes read it from there. Every existing row is a quiz.
-- Sealed rows are not affected: a seal digests only the columns it recorded (design 21 section 2).
ALTER TABLE qb_quiz_version ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'QUIZ' CHECK (kind IN ('QUIZ', 'SURVEY'));
ALTER TABLE qb_question_version ADD COLUMN selection varchar(10) CHECK (selection IN ('SINGLE', 'MULTIPLE'));
ALTER TABLE qb_publication_card ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'QUIZ' CHECK (kind IN ('QUIZ', 'SURVEY'));
