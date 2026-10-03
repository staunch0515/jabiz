# qecode (Quick & Easy Code) · First-Version Development Plan (v0.2)

Source: *UBOS Digital Business Chip Platform · First-Version Planning Document* (2026-09-30), plus the owner's
decisions of 2026-10-03 (English only; self-hosted LLM; self-hosted Git; no Claude Code and no GitHub calls from the platform).
This plan answers two questions: **build on jabiz or start a new repository**, and **the phased plan from zero**.
The planning document will keep growing; this plan is revised with it (section 10, revision log at the end).

---

## 1. Conclusion: a new, independent repository, not jabiz

### 1.1 Reasoning

jabiz is a **metadata-driven business application platform for enterprise back-office systems** (about 55k lines of Java,
Spring WebFlux + R2DBC, bi-temporal append-only storage, every write is a process inside one transaction, startup self-checks,
scenario replay, audit sealing). qecode's first version is a **pipeline website**: requirement → chip matching → AI generation →
smoke test → package → download. Its heavy work is calling an LLM, running a coding agent in a sandbox, building and starting
containers, running smoke tests and packaging files. The two products share no core.

| Dimension | What qecode v1 needs | What jabiz offers | Fit |
|---|---|---|---|
| Unit of work | Minute-scale asynchronous jobs: LLM calls, agent sessions, `docker compose` build and start, smoke tests, bounded retry loops, live progress to the user | A process is one short transaction (06 §4, D11): `ComputeStep` forbids I/O, `BlockingStep` may not touch the platform database, external side effects only in `AFTER_COMMIT` with in-process retries; scheduled jobs may only call processes | **No fit.** jabiz would first need a "long job + progress stream + sandbox" layer, which is qecode itself |
| User interface | A wizard for non-technical users: enter requirement, confirm features one by one, see matching result, watch generation progress and logs, download | Admin UI generated from metadata (lists, forms, history); workflows metadata cannot express are hand-written extension pages (12 §9, D22) | **Low.** Every wizard page would be an extension page; the generated pages are unused |
| Data model | A dozen simple tables: users, projects, requirement versions, feature items, chips and datasheets, match results, generation runs, artifacts | Bi-temporal append-only entities, undo, audit records, HMAC sealing, retention, approvals, ledger, documents, reports, imports | **Too heavy.** None of it is needed in v1, yet the platform rules impose it on every business object |
| Technology | Generated apps are React + Node.js + TypeScript; the project template, the smoke-test harness and the coding agent are TypeScript | JDK 21 + Spring WebFlux; business modules may not reference `reactor.*`; blocking calls forbidden on the request path | **No fit.** Two stacks for a v1 whose goal is to validate a flow with the least possible machinery |
| Discipline | Planning document: *"validate the flow under the simplest, most idealised conditions; anything complex is out"* | CLAUDE.md: design docs before code, binding decisions D1–D31, three UI languages, coverage gates, scenario snapshots, `platformCheck` | **Opposite direction.** Assets for an enterprise system, friction for a website that must validate a hypothesis fast |
| Branch model | One independent product | Application branches may not touch platform directories; every missing platform capability must first be built on the platform branch with its own tests and demo (17 §1) | **Expensive.** Everything qecode needs (async jobs, sandbox, progress stream) is missing from the platform, so each item would be done twice |
| Security model | Running AI-generated code and containers: sandbox isolation, resource and cost limits, egress restrictions | JWT, TOTP, OIDC, RBAC, field masking, data periods (all good, none needed in v1) | **Orthogonal.** jabiz has no sandbox; qecode v1 does not need jabiz's security depth |

Building on jabiz would mean carrying 55k lines of unrelated platform code and a full discipline to host a pipeline website of
roughly 10k lines, while the hard parts of qecode (sandbox, long jobs, LLM reliability) get nothing from jabiz.
**The two share an author, not a core.**

### 1.2 What to take from jabiz (ideas, not code)

- **Way of working**: `CLAUDE.md` + `docs/design/` + binding decision records + a phased roadmap where every phase starts with a plan. qecode keeps this, with the rules held to one page.
- **Fail at startup**: inconsistencies between datasheets, project template and prompt pack are reported in full at service start (or in CI), never at request time.
- **Fixed inputs + snapshot comparison**: LLM responses are recorded and replayed, so pipeline tests are deterministic, repeatable and free.
- **One command to start**: `docker compose up` is both qecode's own deployment and what customers receive.
- **Frontend toolchain**: pnpm, React 19, TypeScript, Vite, TanStack Query, React Router, Vitest, Playwright — identical to jabiz, no new tools for the team.

### 1.3 When jabiz might come back

Billing, chip licensing and customer/order management (all "out of scope" in the planning document) are typical jabiz business
(money, approvals, ledger, documents). If that day comes, qecode calls a separately deployed jabiz application over an API.
qecode is never moved into jabiz.

---

## 2. Requirements as understood

### 2.1 What v1 does

One website that runs the pipeline below, with two gates:

```
Requirement input (natural language) → Requirement structuring (LLM → feature list) → [Gate 1] user confirms each feature
→ Chip matching (each feature against the datasheets) → [Gate 2] every feature mapped? no → project terminated
→ Generation (coding agent in a sandbox writes the frontend and glue code) → Smoke test (start everything, call every feature)
→ failure: error summary fed back to the agent, bounded number of retries → Package and download (source + chip images + docker-compose)
```

Fixed conditions for v1:

| Dimension | v1 choice |
|---|---|
| Chip invocation | Sidecar only: a chip is a separate process exposing HTTP |
| Chip data | Each chip embeds its own SQLite database |
| Generated stack | One: React + Node.js, all TypeScript |
| Deployment | `docker compose`, one command (customer installs Docker Desktop) |
| Business scenario | One: order management for a small web shop |
| Chip choice | Fixed scenario, customers do not choose chip types |
| First chips | Product, Inventory, Order (Order calls the other two) |
| Deliverable | A zip: `docker-compose.yml`, `start.sh` / `start.bat`, `README.md`, `app/`, `chips/*.tar` |

Validation order from the planning document: **chips first → manual validation of AI generation (no website) → website**.
If step 2 fails, step 3 is not started. The phases below follow this order strictly.

### 2.2 Owner decisions of 2026-10-03

| Decision | Consequence for this plan |
|---|---|
| English only (UI, documents, code, messages) | No i18n work in v1; message resources exist so a language can be added later without code changes |
| The LLM is self-hosted; the platform does not call Claude Code | qecode talks to an OpenAI-compatible chat-completions endpoint (vLLM, llama.cpp server, Ollama or similar) that the owner runs; qecode ships its own minimal coding agent (section 4.7) instead of Claude Code |
| The Git server is self-hosted; the platform does not call GitHub | Each project's generated source lives in a repository on a self-hosted Git server (Gitea) that qecode deploys alongside itself (section 4.6); the zip remains the customer deliverable |
| Repository name | `qecode` (Quick & Easy Code); CLI `qecode`, npm scope `@qecode/*`, images `qecode/*` |

### 2.3 Scope boundary

- **In scope**: the website, the pipeline, the datasheet format and validator, the project template, the prompt pack, the coding agent, the sandbox, the smoke-test harness, packaging, customer-side start scripts, stub chips (2.4), the self-hosted Git integration.
- **Out of scope**: the three chips themselves (Rust); everything in the planning document's "out of scope" table (FFI/WASM, multiple stacks, external databases, licensing, upgrades and data migration, cross-chip transactions, SBOM, billing); hosting and tuning the LLM (the owner provides an endpoint; the plan states what it must support).
- **Interface with the chip team** (defined by this project, followed by the chip team): datasheet format, image delivery, health check, configuration conventions. See 4.3.

### 2.4 Not blocked by chip delivery: stub chips

Chip development is outside this project and its schedule is not ours. This project builds three **stub chips** (small Node.js
services implementing exactly the OpenAPI of the datasheets, data in memory or SQLite) to develop and test the pipeline. When the
real chips arrive, the images are swapped and the same contract tests run against both. Stubs are **not** part of the product
and are never shipped to customers.

### 2.5 Open questions and the assumptions used until answered

| # | Question | Assumption in this plan |
|---|---|---|
| Q1 | Where does the website run? The sandbox needs a host that can run Docker | One owned Linux server (or cloud VM); website, worker, Git server and database on the same host via `docker compose` |
| Q2 | Which LLM endpoint and model? | An OpenAI-compatible endpoint with tool calling and JSON-schema constrained output (vLLM is the reference); the model is chosen in P1 by measurement (section 5, P1) |
| Q3 | Who delivers chip images and how? | The chip team delivers a `docker save` tar plus `datasheet.yaml`; an admin registers them in the website |
| Q4 | Cost and time limits per generation? | Per-run limits on LLM tokens, wall-clock time and retries (3), all configurable |
| Q5 | Acceptable download size? Three chip images may be hundreds of MB | v1 puts them in the zip; no incremental download |
| Q6 | Customers on Windows or macOS/Linux? | Both start scripts (`start.bat` / `start.sh`); Windows assumes Docker Desktop with WSL 2 |
| Q7 | User accounts in v1? | Email + password, **invitation-code sign-up** (no open registration); a user owns many projects |
| Q8 | Requirement input: text only? File upload? | Multi-line text only |
| Q9 | How much can the user edit the feature list? | Edit each item's text, delete, add an item, ask for re-structuring; locked after confirmation |
| Q10 | What does "call every feature" mean in the smoke test? | Section 4.5: one check script per confirmed feature, prescribed by the template; all must pass |
| Q11 | Must the user see the Git repository? | v1: the repository URL is shown on the project page; the Git server is internal, read-only for users |

---

## 3. Technology choices (new repository)

Principles: **one language, the fewest components, the same stack as the generated output, no external SaaS on the request path.**

| Layer | Choice | Reason |
|---|---|---|
| Language | TypeScript everywhere | Generated apps, template, smoke harness and coding agent are TypeScript; one person can read the whole chain |
| Runtime | Node.js 22 LTS, pnpm workspace (monorepo) | Same toolchain as the jabiz frontend |
| Backend | Fastify + Zod (request validation, OpenAPI generation) | Light, type-safe; no framework layer such as NestJS |
| Database | PostgreSQL 16 + Drizzle ORM with its migrations | Familiar; the job queue lives in Postgres too (`pg-boss`), **no Redis** |
| Worker | `apps/worker` in the same repository, consuming the `pg-boss` queue | Long jobs separated from the web process; restart and scale independently |
| LLM access | `packages/llm`: one `LlmProvider` interface with a single implementation, **OpenAI-compatible chat completions** (`/v1/chat/completions` with `tools` and `response_format: json_schema`); endpoint, model name and API key from environment variables | Owner-hosted model; the provider interface keeps the rest of the code model-agnostic and lets tests replay recorded responses |
| Coding agent | `packages/agent`: a minimal in-house tool-calling loop (section 4.7) running inside the sandbox | Claude Code is excluded by decision; a small loop with four tools is enough for v1 and fully under our control |
| Sandbox | One disposable container per generation attempt (Node + pnpm + the agent), work directory mounted; **no Docker socket**; egress only to the LLM endpoint and the npm registry (or a local npm mirror) | The agent only writes files; build, start and smoke test run outside the sandbox (4.4) |
| Git server | Gitea in the deployment compose; one repository per project, written only by the worker through the Gitea API and `git` | Owner-hosted, no GitHub; gives traceability and a base for later "iterate on an existing project" |
| Frontend | React 19 + Vite + TypeScript + TanStack Query + React Router + Ant Design 5 | Steps / Form / List cover the wizard; same toolchain as jabiz |
| Progress | Server-sent events | One-way, simple, sufficient; no WebSocket |
| File storage | Local directory (artifact zips, chip tars, run logs); path and quota in configuration | v1 is single-host |
| Tests | Vitest (unit, pipeline replay), Playwright (end to end), contract tests (datasheet ↔ stub chips ↔ real chips) | — |
| Observability | pino structured logs + one complete log file per run; no metrics in v1 | Enough to debug |
| Deployment | `docker compose up -d` at the repository root (db, gitea, api, worker; the web build is served by api) | Same mechanism as the deliverable |

Alternative on record: Java / Spring Boot MVC for the website is feasible. It is not chosen because template, smoke harness and
agent are TypeScript, and one language is cheaper than two. **The decisive point is "not jabiz", not the language.**

---

## 4. Design

### 4.1 Repository layout (monorepo)

```
qecode/
├── CLAUDE.md                     working rules (one page)
├── README.md
├── docs/
│   ├── plan/                     this plan and its revisions, phase reports
│   ├── design/                   pipeline, datasheet, agent, sandbox, packaging, git, security
│   ├── decisions.md              binding decisions (D1 …)
│   └── requirements/             requirement entries R-001 … (every addition to the planning document lands here, section 10)
├── apps/
│   ├── api/                      Fastify: auth, projects, pipeline endpoints, SSE, download, chip registry
│   ├── worker/                   queue consumer: structuring, matching, generation, smoke, packaging, git
│   └── web/                      React wizard
├── packages/
│   ├── datasheet/                datasheet JSON Schema, parser, validator, types
│   ├── pipeline/                 pure-TS pipeline core, runnable from the CLI without the website
│   ├── llm/                      LlmProvider (OpenAI-compatible), prompt pack, structured output, record/replay, usage accounting
│   ├── agent/                    the coding agent loop and its tools (runs inside the sandbox)
│   ├── sandbox/                  sandbox container lifecycle, limits, compose project start/stop, static gate
│   ├── git/                      Gitea client: create repository, commit, tag, read
│   ├── template-shop/            project template for generated apps (React + Node BFF + smoke harness + compose fragment)
│   └── stub-chips/               three stub chips (product / inventory / order) and their datasheets
├── deploy/docker-compose.yml     qecode's own deployment
└── tools/                        scripts: record LLM responses, validate datasheets, run the pipeline locally
```

### 4.2 Data model (Postgres, plain tables, soft delete where needed)

| Table | Content |
|---|---|
| `user` | email, password hash (bcrypt), role (user / admin), invitation code used |
| `project` | owner, name, slug, stage (`DRAFT` `STRUCTURING` `CONFIRMING` `MATCHING` `TERMINATED` `GENERATING` `TESTING` `PACKAGED` `FAILED`), termination reason, git repository URL |
| `requirement_version` | project, sequence, raw requirement text (every edit is a new version) |
| `feature_item` | requirement version, sequence, description, status (`PROPOSED` `CONFIRMED` `REMOVED`), user-edited text |
| `chip` / `chip_version` | chip id, version, datasheet (raw + parsed JSON), image tar path, image name and tag, registered by, time |
| `match_result` | project, feature item → `chip.capability` / `GLUE_UI` / `UNMAPPED`, model's reasoning; project-level verdict (all mapped / terminated) |
| `generation_run` | project, attempt number, status, start/end, token usage, sandbox container id, failure reason, log file path, git commit |
| `smoke_result` | run, feature item, pass/fail, error summary |
| `artifact` | project, zip path, size, sha256, git tag, created at |
| `download_log` | artifact, user, time |
| `llm_call` | run/project, purpose, model, input/output tokens, duration, recording path (replay and audit) |

### 4.3 Chip contract (owned by this project, followed by the chip team)

The planning document calls the datasheet "the platform's core asset", so **the datasheet format is owned by this project and
fixed by a JSON Schema**; `packages/datasheet` provides the validator and the chip team runs it before delivery.
`datasheet.yaml` in v1:

```yaml
id: order                       # chip id: lowercase letters and hyphens
name: Order chip
version: 1.0.0
image: { name: qecode/chip-order, tag: 1.0.0, tar: order.tar }
runtime:
  port: 8080                    # port inside the container
  health: GET /health           # 200 means ready
  env:
    DATA_PATH: /data/order.db   # SQLite file (mounted volume)
    PRODUCT_BASE_URL:           # addresses of dependency chips, injected by compose
    INVENTORY_BASE_URL:
depends_on: [product, inventory]
openapi: openapi.yaml           # requests, responses, error codes
capabilities:                   # matching maps a feature item to exactly one of these
  - id: order.place
    summary: Place an order (asks the product chip for prices, reserves stock in the inventory chip)
    operations: [placeOrder]    # operationIds in the OpenAPI document
  - id: order.confirm-payment
    summary: Confirm payment
    operations: [confirmPayment]
  - id: order.ship   …
  - id: order.cancel …          # releases reserved stock
examples:                       # complete call sequences for typical scenarios, for the agent to follow
  - scenario: From order to shipment
    steps: [ { call: placeOrder, body: {...} }, { call: confirmPayment, ... }, { call: ship, ... } ]
limitations:                    # explicitly unsupported cases
  - No partial shipment
  - Single currency only
```

Contract tests (shared by `packages/stub-chips` and the real chips): the image starts under compose; `/health` returns 200;
every `operationId` exists in the OpenAPI document; every `examples` sequence executes successfully in order; `depends_on`
and `env` agree.

### 4.4 Sandbox and division of labour (the core of the security design)

```
Worker (trusted, outside)                              Sandbox container (untrusted, disposable)
──────────────────────────────                         ─────────────────────────────────────────
1 Prepare work dir: template + datasheets + features →
2 Start sandbox with the work dir mounted           →  the coding agent reads template, datasheets, feature list
                                                       and does one thing: write files (frontend, BFF, smoke checks);
                                                       it may run the template's typecheck/lint/unit tests inside
3 Wait for exit (kill on timeout)                   ←  exit
4 Static gate: directory allow-list, no secrets, no
  network addresses outside the template, one smoke
  check per confirmed feature
5 Commit the work dir to the project's Git repo
6 Build the app image; start app + three chips under
  a private compose project (random ports, own network)
7 Run smoke: /health + every feature's check script
8 Failure → write the error summary into the work dir,
  back to 2 (≤ N attempts)
9 Success → tag the commit, build the zip; always:
  compose down, remove containers and temp dirs,
  record usage
```

- **The agent only writes files, never runs containers**: no Docker, no host credentials inside the sandbox; egress only to the LLM endpoint and the npm registry.
- LLM and Git credentials exist only in the worker's and the sandbox's environment and **never enter generated code or the zip** (static gate, step 4).
- Every run has CPU, memory, disk, time and token limits; exceeding one fails the run and triggers cleanup.
- Concurrency: v1 runs generation jobs one at a time; the number is a configuration value.

### 4.5 Smoke-test verdict

The template prescribes a `smoke/` directory: every confirmed feature `F-nn` must have `smoke/F-nn.ts` written against the
template's small harness (HTTP client + assertions) that calls the running app and asserts on real responses. The worker first
checks coverage (a missing script fails the run before anything starts), then runs the scripts in order; any failure sends an
error summary (script, assertion, response) to the next repair attempt. "Call every feature" is therefore machine-checked, not
self-reported by the agent.

### 4.6 Git integration (self-hosted Gitea)

- Gitea runs in qecode's deployment compose; the worker owns one service account token (environment variable only).
- When a project enters generation, the worker creates `projects/<project-slug>` on Gitea (private), pushes the template as the first commit, then one commit per generation attempt (`attempt N: generated` / `attempt N: repaired`), and tags the attempt that passed smoke (`v1`, `v2` …). The zip is built from the tagged commit, so the download and the repository always agree.
- Users see the repository URL on the project page (read-only in v1). The sandbox never touches Git; only the worker commits.
- qecode's own source code lives wherever the owner keeps it (GitHub today); the platform never calls GitHub.

### 4.7 LLM usage and the coding agent

| Step | Mechanism | Output |
|---|---|---|
| Requirement structuring | One chat completion with `response_format: json_schema` (array of feature items: id, description, inputs/outputs) | Feature list |
| Chip matching | One chat completion with `response_format: json_schema`; input = confirmed list + every datasheet's `capabilities` and `limitations`; output per feature → `chip.capability` or `GLUE_UI` or `UNMAPPED` + reason; **the program then verifies** that every referenced capability exists | Match result |
| Generation and repair | `packages/agent` inside the sandbox: a tool-calling loop over the same endpoint with four tools — `list_files`, `read_file`, `write_file`, `run_check` (the template's typecheck / lint / unit tests only); system prompt = template conventions + datasheets + feature list + (repair rounds) error summary; bounded turns and tokens | Files |

- `packages/llm` supports **record and replay**: tests use recorded responses, deterministic and free; live calls run only in the nightly golden-set job.
- Every call's usage goes to `llm_call`; the project page shows cumulative usage.
- The prompt pack is versioned under `packages/llm/prompts/`; changes go through pull requests with replay-snapshot diffs.
- Endpoint requirements (to be verified in P1 against the owner's deployment): tool calling, JSON-schema constrained output, context of at least 32k tokens, streaming. If the chosen model cannot do constrained output, the fallback is "JSON in text + validate + one retry", implemented once in `packages/llm`.
- Alternatives on record for the agent, not chosen for v1: Aider or OpenHands driven against the same endpoint. Reconsidered only if P1 shows the in-house loop cannot reach the bar.

### 4.8 Packaging

`<project-slug>.zip` as in the planning document; `docker-compose.yml` comes from the template (the app image is built on the
customer's machine, chip images are `docker load`ed by `start.sh` / `start.bat` before start); `README.md` is rendered from the
template plus the feature list. The zip's sha256 is stored and verified on download.

---

## 5. Phases

Effort unit: person-days, one full-time developer plus an AI coding assistant. Every phase starts with a detailed plan
(packages touched, tables added, test list, risks) that is confirmed before code is written.

| Phase | Name | Estimate | Planning document |
|---|---|---|---|
| P0 | Project setup, chip contract, stub chips, LLM endpoint check | 4–5 | validation step 1 (platform side) |
| P1 | Manual validation of AI generation (no website) | 6–9 | validation step 2 |
| **Gate** | P2 starts only if step 2 holds | — | "if step 2 fails, step 3 is not started" |
| P2 | Pipeline core (full flow from the command line) | 7–9 | half of step 3 |
| P3 | Website | 8–10 | step 3 |
| P4 | Acceptance, hardening, release | 3–5 | step 3 completion criterion |
| Total | | **28–38** | plus a 20 % buffer |

### P0 Project setup, chip contract, stub chips, LLM endpoint check (4–5 days)

Goal: the new repository builds and tests; the datasheet format is final; three stub chips start under compose and call each
other; the owner's LLM endpoint is reachable from code and supports what the plan needs.

Deliverables:
1. Repository skeleton (4.1), `CLAUDE.md`, first drafts in `docs/design/` (pipeline, datasheet, agent, sandbox, git, packaging), `docs/decisions.md` (section 6), CI (lint, typecheck, test).
2. `packages/datasheet`: JSON Schema v1 + validator + three example datasheets (product, inventory, order).
3. `packages/stub-chips`: three stubs + `openapi.yaml` + Dockerfile + compose; the order stub calls the product and inventory stubs.
4. Contract tests (4.3).
5. `packages/llm`: `LlmProvider` with the OpenAI-compatible implementation and record/replay; a probe script that checks tool calling, JSON-schema output and context size against the owner's endpoint and prints a report.
6. One contract review with the chip team (recorded as an R entry).

Acceptance: `pnpm test` green; `docker compose up` starts the three stubs and the `examples` sequences succeed; the validator rejects malformed datasheets; the endpoint probe passes.

### P1 Manual validation of AI generation (6–9 days)

Goal: without a website, prove that given the requirement, the datasheets and the project template, the self-hosted model can
produce an app that starts with one command with every feature working, and that this takes less code and fewer fixes than
generating from scratch.

Deliverables:
1. `packages/template-shop`: React + Node BFF + TypeScript template, `smoke/` harness, compose fragment, README template.
2. Prompt pack v1 (system prompt, how datasheets are presented, feature-list format, repair-round format).
3. `packages/agent` v1 (the four-tool loop), runnable by hand against a work directory.
4. A fixed "small web shop order management" requirement and a hand-written feature list (golden case G-01).
5. At least 3 runs per candidate model (the owner names the candidates, up to 3), recording generation time, lines of code, repair rounds, smoke result, tokens.
6. Control group: the same requirement without chips and without the template, generated from scratch, same metrics.
7. Report `docs/plan/p1-report.md`: data, problems, template and prompt improvements, model recommendation, go/no-go for P2.

Acceptance (planning document step 2): the generated app starts with one command and every feature works; less code and fewer
fixes than from scratch. **If not met, improve template, prompts and model choice and retry; if still not met, stop and report.**

### P2 Pipeline core (7–9 days)

Goal: `qecode run requirement.txt` completes structuring → matching → generation → smoke → package on a developer machine, without a website.

Deliverables:
1. `packages/llm` completed: structured output, usage accounting, prompt pack loading.
2. `packages/pipeline`: structuring, matching (with program-side verification and the "all mapped" verdict), generation driver, smoke runner, packaging; every step a pure function with explicit inputs and outputs, state in `state.json` in the work directory.
3. `packages/sandbox`: sandbox image, container start/stop and limits, compose project start/stop and cleanup, static gate (4.4 step 4).
4. `packages/git`: Gitea client; repository per project, commit per attempt, tag on success.
5. Golden set G-01 … G-05: three fully mappable, two with unmappable features (must terminate), with recorded LLM responses and snapshots.
6. CLI `tools/qecode-run`: full flow, single step, replay mode.

Acceptance: replay mode gives the expected result for all five golden cases; live mode produces a startable zip for G-01; the
sandbox cannot reach Docker or secrets (asserted by tests); failure paths (timeout, retries exhausted, unmappable feature) clean up and leave logs; the Git repository matches the zip.

### P3 Website (8–10 days)

Goal: P2's pipeline as a website; one person goes from requirement to zip without a developer.

Deliverables:
1. `apps/api`: Drizzle tables and migrations (4.2), invitation-code sign-up and login (session cookie), project CRUD, stage endpoints, SSE progress, download (auth + checksum), admin chip registry (upload `datasheet.yaml` + tar, validate, store).
2. `apps/worker`: `pg-boss` consumer calling `packages/pipeline`; idempotent jobs (one run per project stage); runs left unfinished by a restart are marked failed and cleaned up.
3. `apps/web`: wizard (requirement → structuring → confirm/edit features → match result incl. termination page → generation progress and log → download), project list with usage and repository URL, admin chip page.
4. OpenAPI generated by Fastify + Zod; frontend types generated from it.
5. Tests: API integration (real Postgres), component tests, Playwright end to end in replay mode.

Acceptance: with stub chips and replay mode, end-to-end tests cover "successful download" and "project terminated"; one manual live run succeeds.

### P4 Acceptance, hardening, release (3–5 days)

Deliverables:
1. `deploy/docker-compose.yml` and deployment guide; secrets only from environment variables; quotas (per-user concurrency, daily tokens) and cleanup jobs (expired artifacts, leftover containers).
2. Sandbox security checklist executed (egress, no socket, resource limits, no secrets on disk).
3. Real chips replace the stubs; contract tests and a live G-01 run pass.
4. Customer-side check: a clean Windows machine and a clean macOS machine start the downloaded zip via `start.bat` / `start.sh`.
5. **External acceptance**: a person who did not take part completes requirement → local run alone (planning document step 3), blockers recorded.
6. Documents: user guide, admin guide, operations guide; this plan revised to v1.0.

---

## 6. Binding decisions (accepted by the owner on 2026-10-03; kept in `docs/decisions.md`)

| # | Decision | Reason |
|---|---|---|
| D1 | qecode is an independent repository; it is not built on jabiz | Section 1 |
| D2 | TypeScript end to end, pnpm monorepo | Same stack as the generated output, template and agent |
| D3 | The datasheet format is owned by qecode and fixed by a JSON Schema; chips must pass the validator before delivery | It is the common input of matching, assessment and generation |
| D4 | The agent only writes files; build, start and tests run in the worker outside the sandbox; the sandbox has no Docker, no secrets and an egress allow-list | Smallest attack surface for running untrusted code |
| D5 | Match results are machine-verifiable: every feature → an existing `chip.capability`, or `GLUE_UI`, or `UNMAPPED`; one `UNMAPPED` terminates the project | Gate 2 of the planning document |
| D6 | Smoke tests are per feature, their location is prescribed by the template, and incomplete coverage fails the run | "Call every feature" must be machine-checked |
| D7 | Every LLM call can be recorded and replayed; CI runs replay only; live calls run in the nightly golden job | Deterministic, free, repeatable tests |
| D8 | Queue in Postgres (`pg-boss`), no Redis; SSE, no WebSocket; local file storage | Single host, fewest components |
| D9 | Every run has hard limits on tokens, time and retries; exceeding one fails and cleans up | Cost and resources stay bounded |
| D10 | Additions to the planning document are first recorded as `docs/requirements/R-nnn`, triaged, then planned; never coded directly | Section 10 |
| D11 | The LLM is self-hosted behind an OpenAI-compatible endpoint; qecode contains no vendor SDK and never calls Claude Code; all model access goes through `LlmProvider` | Owner decision; keeps the model swappable |
| D12 | Generated source is stored in a self-hosted Gitea deployed with qecode, one repository per project, written only by the worker; the platform never calls GitHub | Owner decision; traceability without external services |
| D13 | English only in v1 (UI, documents, code, messages); text lives in message resources so a language can be added later | Owner decision |
| D14 | The coding agent is an in-house bounded tool-calling loop with four tools (`list_files`, `read_file`, `write_file`, `run_check`) inside the sandbox; Aider / OpenHands are recorded alternatives | Full control, smallest surface; reconsidered only if P1 fails |

---

## 7. Test strategy

| Layer | Content | When |
|---|---|---|
| Unit | Datasheet validation, match verification, packaging, project stage machine, prompt rendering, agent tool handling | Every PR |
| Replay | Golden cases G-01 … G-05: structuring and matching snapshots from recorded responses | Every PR |
| Contract | Stub chips and real chips against the datasheets | Every PR (stubs); on chip delivery (real) |
| Integration | API + real Postgres; worker + real Docker (stub chips) + Gitea | Every PR |
| End to end | Playwright through both wizard paths (replay mode) | Every PR |
| Live generation | G-01 with the live model, live sandbox, live smoke; metrics and usage recorded | Nightly, and on prompt / template / model changes |
| Security | Sandbox self-check: socket, secrets, egress, limits | End of P2, P4, every sandbox change |
| Customer side | Clean Windows / macOS start the zip per README | P4, every template change |

Rules: a feature without tests is not done; a bug fix starts with a failing test.

---

## 8. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| The self-hosted model is not strong enough for generation, or lacks tool calling / constrained output | The core hypothesis fails | P0 endpoint probe; P1 measures up to 3 candidate models before any website work; the agent loop keeps tasks small (one file at a time, template does the scaffolding); fallback JSON parsing in `packages/llm` |
| Generation is unstable: the same requirement sometimes passes, sometimes not | Users lose trust | P1 quantifies over ≥ 3 runs; the template narrows freedom (fixed layout, fixed BFF skeleton, fixed smoke harness); repair rounds receive only error summaries |
| Chips arrive late | Pipeline cannot be integrated | Stub chips (2.4); contract defined by this project first |
| Running generated code is a security risk | Host compromise, credential leak | D4; static gate; resource limits; security checklist in acceptance |
| Per-run cost and time run away | GPU time, user experience | D9; usage shown per project; serial queue |
| Zip too large (chip images) | Slow download, disk | Accepted in v1; recorded as an R entry for a registry-pull option later |
| Customer Windows environments (Docker Desktop, WSL 2, port clashes) | "One command" fails | `start.bat` performs pre-checks with clear messages; P4 real-machine test |
| Continuous requirement additions derail the plan | Scope creep | Section 10; scope frozen at the start of each phase |
| Smoke scripts written by the agent are too lenient | False passes | The harness only offers substantive assertions; manual spot checks; platform-side generic checks later |

---

## 9. Milestones and reporting

| Milestone | Content |
|---|---|
| M0 | P0 done: contract final, stubs start, endpoint probe passes |
| M1 | P1 report: go/no-go for the website (owner decides) |
| M2 | P2 done: full flow from the command line |
| M3 | P3 done: website usable internally |
| M4 | P4 done: external acceptance passed, v1 released |

Every phase ends with: a pull request whose description walks through the acceptance criteria one by one, the status column of
this plan updated, open items and known issues listed.

---

## 10. Handling continuous requirement additions

1. Each new requirement becomes `docs/requirements/R-nnn.md`: source, original text, date, affected phases/packages, class (v1 must / v1 optional / out of scope).
2. "v1 must" items enter the current or next phase and this plan is revised (+0.1, revision log); "out of scope" items are recorded, not scheduled.
3. Anything that conflicts with the planning document is first settled in the planning document or as a decision, then coded.
4. The planning document's "out of scope" table is pre-recorded as R-101 … R-108 (FFI/WASM, multiple stacks, external databases, licensing, upgrades and migration, cross-chip transactions, SBOM, billing), class "out of scope".

---

## 11. Next steps

1. Create the `qecode` repository and move this seed into it (see `HANDOFF.md` in the seed).
2. Owner answers Q1–Q11 where the assumptions are wrong (section 2.5), names the candidate models for P1, and schedules the contract review with the chip team.
3. Start P0 with a detailed phase plan.

---

## Revision log

| Version | Date | Content |
|---|---|---|
| v0.1 | 2026-10-03 | First version (Chinese): "new repository" conclusion and the P0–P4 plan |
| v0.2 | 2026-10-03 | English only; self-hosted LLM behind an OpenAI-compatible endpoint with an in-house coding agent instead of Claude Code; self-hosted Gitea instead of GitHub; repository named `qecode`; decisions D11–D13; P0 gains the endpoint probe, P1 gains model comparison |
