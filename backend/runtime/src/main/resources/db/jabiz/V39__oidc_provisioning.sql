-- OIDC auto-provisioning and self-linking of external identities (docs/design/10-security.md section 12; decision D39;
-- ROADMAP phase 16f-1). Columns only: the state tables are not append-only (expired requests are deleted), and adding a
-- column to the temporal identity table changes no row (only UPDATE / DELETE are refused).

-- What an authorization request is for: signing in (SIGN_IN, every request before this phase) or linking a provider
-- account to the signed-in user who started it (LINK, link_user_id). A state of one purpose is refused by the other's
-- callback.
ALTER TABLE sec_oidc_state ADD COLUMN purpose varchar(8) NOT NULL DEFAULT 'SIGN_IN';
ALTER TABLE sec_oidc_state ADD CONSTRAINT sec_oidc_state_purpose_ck CHECK (purpose IN ('SIGN_IN', 'LINK'));
ALTER TABLE sec_oidc_state ADD COLUMN link_user_id uuid;
ALTER TABLE sec_oidc_state ADD CONSTRAINT sec_oidc_state_link_ck
    CHECK ((purpose = 'LINK') = (link_user_id IS NOT NULL));

-- The address the provider gave for the linked account, when the provisioning or the user linked it: shown to the user
-- beside the provider; never used to sign in.
ALTER TABLE sec_user_identity_version ADD COLUMN email varchar(320);

-- A user's links (the account settings list them; a takeover removes them all): by the immutable user id.
CREATE INDEX sec_user_identity_version_user_idx ON sec_user_identity_version (user_id);
