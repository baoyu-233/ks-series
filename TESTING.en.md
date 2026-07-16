# KS-Series Plugin Testing Guide

> [中文](TESTING.md) | English

This guide describes the repeatable build, deployment, Web, economy, map, and market-simulation checks for KS-Series.
It is written for maintainers and test-server operators.

## Environment

- LeavesMC 1.21.11 / Paper-compatible test server.
- Java 21 and Maven.
- `ks-core` before dependent modules.
- Optional Vault, LuckPerms, ItemsAdder, MythicMobs, ModelEngine, FAWE, ProtocolLib, and related integrations only
  when the test case needs them.

## Build and Deploy Loop

1. Change one focused module.
2. Push the change to GitHub.
3. Let the `Build KS-Series` Action compile modules in dependency order and upload `ks-series-jars`.
4. Download the selected successful artifact.
5. Back up the replaced JAR under `backup/<plugin-id>/` and verify SHA-256.
6. Deploy to the local test server without starting or restarting Paper automatically.
7. Restart the server manually when the operator is ready, then test commands, GUI, Web routes, logs, and rollback.

The Action is a build/artifact pipeline only. It does not publish releases or deploy to GitHub. A green build is not a
substitute for an in-game acceptance pass.

## Core Test Areas

### ks-core

Verify startup, route registration, token creation/expiry, CORS behavior, SQLite initialization, and graceful
shutdown. Check that disabled child routes do not remain reachable.

### ks-Eco

Verify player listings, official buyback, price trend calculations, taxes, player trades, storage overflow, blind-box
weights and pity, limited-sale stock and limits, compensation one-time claims, and transaction rollback. Test both an
empty inventory and a full inventory.

### Extra modules

Verify that bank, enterprise, tax, real-estate, dungeon, and politics modules load only when present, register their
routes, use the shared database contract, and fail without corrupting unrelated economy features.

### ksHWP

Verify map links, world switching, tile rendering and disk cache, player position privacy, note create/list/delete,
area refresh, and cache clearing permissions.

### Item and RPG modules

Verify item NBT preservation, template boundaries, refinement rollback, RPG catalog reload, proof grants, accessory
entry points, boss combat hooks, and server-thread boundaries around live item objects.

## Automated Market Simulation

The market simulation checks price clamping, supply-pressure direction, drift reversion, tax settlement, test-trade
isolation, and rollback scenarios. Run the current script from the test-results tooling and attach the result to the
verification log. Treat generated charts as evidence for the model, not as a replacement for live server testing.

## Failure Handling

Capture the server version, module versions, Action run, artifact SHA-256, exact command sequence, timestamps, full
stack trace, and whether the server had been restarted after deployment. Never attach runtime databases, tokens,
private server assets, or unredacted logs.
