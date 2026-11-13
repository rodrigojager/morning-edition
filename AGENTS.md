# morning-edition

## Purpose
This is the product repository of `morning-edition`: only what the project needs to build, run, test and ship.

## Architecture
<one sentence describing the architecture>

## Conventions
- <code conventions: language, formatting, naming, tests>
- Configuration comes from environment variables; `.env.example` lists the names, real values live outside this repository.

## Forbidden
- Creating new top-level folders without being asked.
- Creating `.md` files other than `README.md`, `AGENTS.md` (root and folders with their own rules) and `WORKSPACE.md` (root only).
- Saving AI working artifacts here (plans, ADR drafts, mocks, logs, handoffs, helper scripts, `.scratch/`). They live in the container's work folder.
- Versioned copies of files (`_v2`, `_final`, `_old`, `_copy`, `.bak`).

See `WORKSPACE.md` for where everything lives.
