# qecode — Quick & Easy Code

A website that turns a natural-language requirement into a runnable application assembled from pre-made
**digital business chips** (closed-source services with an HTTP interface and a datasheet), tests it, and lets the user
download it as a zip that starts with one `docker compose` command.

Pipeline: requirement → structured feature list → user confirmation → chip matching → generation in a sandbox →
smoke test → package → download.

- Plan: `docs/plan/development-plan.md`
- Binding decisions: `docs/decisions.md`
- Requirement log: `docs/requirements/`
- Working rules: `CLAUDE.md`

Status: seed (plan, rules, decisions). Phase P0 starts next.
