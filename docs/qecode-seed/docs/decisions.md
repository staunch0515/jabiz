# Decisions

Binding. Each entry records what was decided and why. To change one, add a new entry that names the one it supersedes
and get it confirmed before changing code. Accepted by the owner on 2026-10-03 unless stated otherwise.

## D1 Independent repository, not jabiz
qecode is its own repository. jabiz (metadata-driven enterprise platform, Java/WebFlux, bi-temporal storage, transactional
processes) shares no core with a pipeline website whose heavy work is LLM calls, sandboxed agents, containers and packaging.
Analysis: plan section 1.

## D2 TypeScript end to end, pnpm monorepo
The generated applications, the project template, the smoke harness and the coding agent are TypeScript; one language for the whole chain.

## D3 The datasheet format belongs to qecode
Fixed by a JSON Schema in `packages/datasheet`. Chips must pass the validator before delivery. The datasheet is the common
input of matching, feasibility assessment and generation.

## D4 The agent only writes files
Build, container start, smoke tests and Git commits run in the worker outside the sandbox. The sandbox has no Docker socket,
no credentials and an egress allow-list (LLM endpoint, npm registry). Smallest attack surface for running untrusted code.

## D5 Match results are machine-verifiable
Every feature maps to an existing `chip.capability` id, or `GLUE_UI`, or `UNMAPPED`; the program verifies the ids;
one `UNMAPPED` terminates the project ("AI development effort too large").

## D6 Smoke tests are per feature and prescribed by the template
`smoke/F-nn.ts` for every confirmed feature, written against the template's harness; missing coverage fails the run before
anything starts; any failing script feeds its error summary to the next repair attempt.

## D7 Every LLM call is recordable and replayable
CI runs replay only. Live calls run in the nightly golden job and on prompt, template or model changes.

## D8 Fewest components
Job queue in PostgreSQL (`pg-boss`), no Redis. Progress over server-sent events, no WebSocket. Files on local disk. Single host in v1.

## D9 Hard limits on every run
Tokens, wall-clock time, retries, CPU, memory, disk. Exceeding one fails the run and triggers cleanup.

## D10 Requirement additions are logged before they are planned
`docs/requirements/R-nnn.md` first (source, text, class), then the plan is revised, then code. Never coded directly.

## D11 Self-hosted LLM behind an OpenAI-compatible endpoint
The owner runs the model (vLLM is the reference server). qecode contains no vendor SDK, never calls Claude Code, and reaches
the model only through `LlmProvider` in `packages/llm`. Required endpoint features: chat completions, tool calling,
JSON-schema constrained output, streaming, ≥ 32k context. Verified by the P0 endpoint probe.

## D12 Self-hosted Git (Gitea), written only by the worker
Gitea is deployed with qecode. One private repository per project; the template is the first commit, one commit per generation
attempt, a tag on the attempt that passed smoke; the zip is built from the tagged commit. The sandbox never touches Git.
The platform never calls GitHub.

## D13 English only in v1
UI, documents, code and messages are English. Text lives in message resources so a language can be added later without code changes.

## D14 In-house coding agent with four tools
`packages/agent` is a bounded tool-calling loop (`list_files`, `read_file`, `write_file`, `run_check`) over `LlmProvider`,
running inside the sandbox. `run_check` runs only the template's typecheck, lint and unit tests. Aider / OpenHands are
recorded alternatives, reconsidered only if P1 shows the loop cannot reach the bar.
