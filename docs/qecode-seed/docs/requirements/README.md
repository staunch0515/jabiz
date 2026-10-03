# Requirement log

Every requirement, including each addition to the planning document, is one file `R-nnn.md` with: source, date, original
text (verbatim), affected phases/packages, class (**v1 must** / **v1 optional** / **out of scope**), status.
"v1 must" entries enter the plan (`docs/plan/development-plan.md`, revision log); "out of scope" entries are recorded only.

| Id | Title | Class | Status |
|---|---|---|---|
| R-001 | First-version planning document (2026-09-30) | v1 must | planned (P0–P4) |
| R-002 | English only | v1 must | decided (D13) |
| R-003 | Self-hosted LLM, no Claude Code | v1 must | decided (D11, D14) |
| R-004 | Self-hosted Git, no GitHub calls from the platform | v1 must | decided (D12) |
| R-101 | FFI and WASM chip forms | out of scope | recorded |
| R-102 | Multiple generated technology stacks | out of scope | recorded |
| R-103 | External databases for chips (e.g. PostgreSQL) | out of scope | recorded |
| R-104 | Chip licensing for local deployments | out of scope | recorded |
| R-105 | Chip upgrades with data migration | out of scope | recorded |
| R-106 | Cross-chip transactions | out of scope | recorded |
| R-107 | Security audit material (binary signing, SBOM) | out of scope | recorded |
| R-108 | Billing (website fees, chip licence fees) | out of scope | recorded |
