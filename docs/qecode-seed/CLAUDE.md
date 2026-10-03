# CLAUDE.md — working rules for qecode

qecode (Quick & Easy Code) is a website that turns a natural-language requirement into a runnable application built on
pre-made **digital business chips** (closed-source Rust services with an HTTP interface and a datasheet), and lets the user
download it. Read `docs/plan/development-plan.md`, `docs/decisions.md` and `docs/design/` before any task.
Decisions in `docs/decisions.md` are binding: to change one, add a new decision that supersedes it and get it confirmed first.

## What we are building (v1)

Requirement → LLM structures it into a feature list → user confirms → each feature matched against chip datasheets →
all mapped? → coding agent generates frontend + glue code in a sandbox → smoke test calls every feature → zip download.
Everything else (billing, licensing, other stacks, other chip forms) is out of scope; see `docs/requirements/`.

## Stack (decided, do not change)

TypeScript everywhere, Node.js 22, pnpm workspace. Fastify + Zod (API), PostgreSQL 16 + Drizzle (data and `pg-boss` queue),
React 19 + Vite + Ant Design 5 (web), SSE for progress, Vitest + Playwright. LLM: self-hosted model behind an
OpenAI-compatible endpoint, reached only through `packages/llm` (`LlmProvider`). Git: self-hosted Gitea, written only by the
worker through `packages/git`. No Redis, no WebSocket, no vendor LLM SDK, no Claude Code, no GitHub calls from the platform.

## Rules

1. **The agent only writes files.** Build, container start, smoke tests and Git commits run in the worker, outside the sandbox.
   The sandbox has no Docker socket, no credentials and an egress allow-list (LLM endpoint, npm registry).
2. **Secrets only from environment variables**; never in code, generated output, logs or the zip. The static gate checks this.
3. **Every LLM call goes through `LlmProvider` and is recordable.** CI runs replay only; live calls run in the nightly golden job.
   Prompts live in `packages/llm/prompts/` and change only through pull requests with replay-snapshot diffs.
4. **Machine-checkable gates.** Match results reference existing `chip.capability` ids or `GLUE_UI` / `UNMAPPED`; one `UNMAPPED`
   terminates the project. Every confirmed feature needs `smoke/F-nn.ts`; missing coverage fails the run.
5. **Hard limits on every run**: tokens, wall-clock time, retries, CPU, memory, disk. Exceeding one fails the run and cleans up.
6. **Datasheets are validated against the JSON Schema in `packages/datasheet`** on registration and at service start.
   Inconsistencies between datasheets, template and prompt pack are all reported at start, never at request time.
7. **Time comes from an injected clock**; ids are UUIDv7; money does not exist in v1.
8. **Errors are RFC 9457 problem details** with stable `code` values; text is in message resources (English only in v1).
9. **Pipeline steps are pure functions** with explicit inputs and outputs; state is `state.json` in the work directory.
10. **No test, not done.** A bug fix starts with a failing test. Replay, contract, integration and end-to-end layers
    are described in the plan, section 7.

## Commands (repository root)

- `pnpm install`, `pnpm lint`, `pnpm typecheck`, `pnpm test` (replay mode, no LLM), `pnpm build`
- `pnpm qecode run <requirement.txt>` — full pipeline from the command line; `--replay` uses recorded responses
- `pnpm datasheet validate <file>` — validate a datasheet
- `docker compose up -d` (in `deploy/`) — db, gitea, api, worker
- Live golden run (costs GPU time): `pnpm test:live`

## Delivery per phase

1. Read the phase in `docs/plan/development-plan.md`. 2. Write a detailed phase plan and get it confirmed. 3. Implement on a
branch `phase-<N>-<name>`. 4. Run all checks. 5. Open a pull request whose description walks through the acceptance criteria.
6. Update the plan's status and the design documents if conventions changed.
