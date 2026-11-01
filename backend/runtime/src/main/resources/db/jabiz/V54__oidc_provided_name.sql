-- Sign in with Apple (decision D67 item 3): Apple tells the person's name once, in the form it posts with its first
-- answer, never in the ID token. The pending sign-in keeps it until its callback, at most ten minutes (the rows are
-- removed when they expire).
ALTER TABLE sec_oidc_state ADD COLUMN provided_name varchar(200);
