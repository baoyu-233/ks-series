# ks-Series Agent Guide

## Start Here

- Read `docs/CODEX_MEMORY.md` and `docs/CODEBASE_MAP.md` before changing ks-Series code.
- Inspect `git status --short` when valid Git metadata is available. The current workspace may have an empty `.git` directory, so continue with targeted file inspection if Git is unavailable. Preserve user changes and never revert unrelated work.
- Use the code map and targeted `rg` searches before opening large files or scanning modules.

## Scope And Context

- Keep changes inside the requested module and its direct contracts.
- Treat project memory as an index; verify behavior against current code before making decisions.
- Use at most one focused subagent unless the user explicitly requests broad parallel work.
- Update `CODEX_MEMORY.md` after material behavior, deployment, incident, or verification changes.
- Update `CODEBASE_MAP.md` when entry points, ownership, database, or thread boundaries change.
- Every project touched by a release must update its local `CHANGELOG.md`; repository-wide releases also update the
  root `CHANGELOG.md`. Write the changelog before publishing to GitHub and never include credentials or runtime data.

## Configurable Product Contract

- Put operator-tunable gameplay values, feature gates, messages and integration choices in validated YAML instead of
  hard-coding them in Java. Keep protocol constants, security boundaries and state-machine invariants in code.
- Expose reusable cross-module capabilities through a narrow, versioned service/API contract rather than copying
  another module's implementation or database internals.
- When safe, implement reload as parse + full validation + immutable atomic swap; an invalid candidate must leave the
  previous runtime active. Database pools, server identity, transport lifecycle and other restart-only settings must be
  marked clearly in the shipped YAML and documentation.

## Paper Thread Contract

- Keep Bukkit, Paper, Leaves live objects, inventories, item metadata, GUI operations, and Vault calls on the server thread.
- Snapshot live state into immutable data before worker execution.
- Run SQL and pure computation off the server thread without holding a transaction across a server-thread callback.
- Return immutable results to the server thread and make settlement retries idempotent.
- Do not deserialize or inspect `ItemStack` on workers, block the server thread on futures or I/O, or capture mutable Bukkit objects in async lambdas.

## Verification And Deployment

- Scale verification to risk and use the module's existing Maven and asset checks.
- Build before deployment. Use `scripts/deploy-plugin.ps1` for every ks-Series plugin deployment. It stores the
  replaced JAR only under `backup/<plugin-id>/`, assigns a unique backup ID, appends its record to that plugin's
  `index.jsonl`, and verifies the deployed SHA-256 matches the artifact. Never create new JAR backups beside the
  deployed plugin or outside the root `backup/` tree. Existing legacy backups remain untouched.
- Deploy ks-Eco to `test_1_21/plugins/ks-Eco-1.1.0.jar` unless the project memory records a newer target.
- Do not start or restart Paper unless the user explicitly asks; the user normally restarts it.
- Do not use an old `latest.log` to assess a new deployment without verifying timestamps and the server start boundary.
