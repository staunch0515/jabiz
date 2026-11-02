-- Sign in with Apple (decision D67 item 4): Apple tells the person's name once, in the form it posts with its first
-- answer, never in the ID token. The pending sign-in keeps it here until its callback uses the request, which removes
-- the row; an unused one goes with its expired request (each start and each posted answer removes those). A table of
-- its own, so that the requests themselves stay insert-only.
CREATE TABLE sec_oidc_state_name (
    state_hash    char(64)     PRIMARY KEY REFERENCES sec_oidc_state ON DELETE CASCADE,
    provided_name varchar(200) NOT NULL
);
