# KS-Series Economy System Assessment

> [中文](REPORT.md) | English

This report records the architectural and economic assessment behind the `ks-Eco` family. It is a design and
verification record, not a promise that every described feature is enabled on every server.

## Architecture Choice

KS-Series uses a modular, micro-kernel-like layout. `ks-core` supplies shared Web routing and SQLite access;
`ks-Eco` owns settlement; optional Extra modules own banking, enterprises, tax, real estate, dungeons, and politics.
This keeps deployment optional and gives each business domain a clear owner.

## Economic Model

The economy combines a player market with official buyback, a central-bank/commercial-bank model, enterprise tenders,
progressive taxes, and property sinks. Player listings discover prices, while official buyback provides bounded
liquidity. Dynamic prices use real official SELL volume, a rolling baseline, mean-reverting drift, and a configured
fluctuation ceiling.

## Reliability and Permissions

Settlement uses database transactions, item snapshots, storage fallbacks, and rollback paths. Bukkit live objects,
inventories, Vault calls, and GUI actions stay on the server thread; SQL and pure calculations run asynchronously.
Permissions are grouped by domain, with operator-only emergency and administrative operations.

## Dungeon and Property Extension

`ks-Eco-RealEstateDungeon` shares plot ownership with real estate while adding party instances, tickets, paid revives,
void-world grids, MythicMobs completion tracking, and JSON-configured rewards. The dungeon economy separates purchased
preparation, gathered inputs, and account-bound combat proof so progression cannot be bought wholesale.

## Current Verification

The repository contains automated market simulation records and module-specific test notes. GitHub Actions proves the
Maven build and uploads JAR artifacts; local Paper acceptance is still required for GUI, cross-plugin, third-party,
and load-sensitive behavior.

See the [full technical report](docs/KS-SERIES-REPORT.en.md) for the current module contract and the [Chinese
assessment](REPORT.md) for the detailed historical record.
