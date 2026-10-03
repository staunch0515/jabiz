# Design documents

Written in phase P0 (first deliverable), one file per topic. Each starts with the goal, then the rules, then the tests that enforce them.

| File | Topic |
|---|---|
| `01-pipeline.md` | Stages, state machine, `state.json`, step inputs and outputs, failure paths |
| `02-datasheet.md` | JSON Schema, validator, contract tests, registration |
| `03-llm.md` | `LlmProvider`, OpenAI-compatible endpoint requirements, structured output, record/replay, usage accounting, prompt pack |
| `04-agent.md` | The coding agent loop, its four tools, limits, repair rounds |
| `05-sandbox.md` | Sandbox image, mounts, limits, egress, static gate, compose project lifecycle, cleanup |
| `06-smoke.md` | Smoke harness, coverage rule, error summaries |
| `07-git.md` | Gitea deployment, repository per project, commits and tags |
| `08-packaging.md` | Zip layout, start scripts, README rendering, checksums |
| `09-web.md` | API, auth, SSE, wizard pages, admin chip registry |
| `10-security.md` | Threat model, secrets, quotas, checklist |
