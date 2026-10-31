-- Q65b: the sponsor profile and the shop page are one page of the console (docs/quizbuks/plans/Q65-start-and-profile.md
-- section 1). The published page shows the onboarding's avatar as its logo and the onboarding's introduction as its
-- "About us", so the introduction may be as long as the page's own story was: 5,000 characters (was 2,000).
--
-- qb_sponsor_version is append-only and sealed: a text column may only get longer (platform decision D58: its values
-- digest the same in to_jsonb; SealedColumnsMigrationIT). Widening a varchar rewrites no row.
ALTER TABLE qb_sponsor_version ALTER COLUMN intro TYPE varchar(5000);

-- qb_sponsor_page is an ordinary table; its own logo and story are no longer fields of QbSponsorPage and are neither
-- read nor written. The columns stay, unused, rather than being dropped (keep the migration minimal).
COMMENT ON COLUMN qb_sponsor_page.logo IS 'Unused since Q65b: the page shows the onboarding avatar (qb_sponsor_version.avatar)';
COMMENT ON COLUMN qb_sponsor_page.story IS 'Unused since Q65b: the page shows the onboarding introduction (qb_sponsor_version.intro)';
