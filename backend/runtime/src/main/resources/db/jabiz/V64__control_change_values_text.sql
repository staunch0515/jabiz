-- The values of a controlled change as text without a length (decision D72): a proposed mail text within the rules'
-- limits may be longer than 30000 characters once written as JSON (a control character takes six). Each target says
-- how long its values may be (ControlTarget.maxValuesLength). Text of another length reads the same to the integrity
-- seal (decision D58), and the change rewrites no row.
ALTER TABLE sys_control_change_version ALTER COLUMN change_values TYPE text;
