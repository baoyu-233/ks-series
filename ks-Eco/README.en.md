# ks-Eco v1.1.0

> [中文](README.md) | English

**KS-Series economy core**: player market, official buyback, player trades, NBT-aware storage, dynamic pricing, and
test-trade intervention.

## Features

- `/market`: 54-slot listing and purchase GUI.
- Official buyback for the configured production-item catalog.
- Weighted blind boxes with rarity and pity replacing the old direct-sale path for non-production rewards.
- `/trade`: two-party item and currency exchange.
- `/storage`: pending delivery and refund queue with recursive shulker-box handling.
- `/kseco gui`: economy menu with an administrator-only, paginated official warehouse claim view.
- Dynamic pricing from real official SELL volume, supply pressure, and mean-reverting drift; default ceiling ±30%.
- `/ks-Eco` Web management routes for economy and optional Extra modules. Personal wealth rankings exclude central-bank
  and system identities.
- Runtime loading of JARs from `plugins/ks-Eco/extra/`.
- JDBC outbox/inbox, database polling, heartbeats, versioned cache invalidation, idempotent operation claims, and fenced leases.
- Exact minor-unit currency ledger, idempotent entries, one-way exchange rules, and backward-compatible market/limited-sale `currency_id` fields.

## Thread And Settlement Boundary

- SQL and pure data work use bounded worker lanes; Bukkit, GUI, permissions, items, and Vault stay on the server thread.
- Purchase-order create, fulfill, and cancel paths use asynchronous transactions and a private pending-settlement table
  so buyers cannot claim delivery before seller settlement.
- Blind-box administration loads raw rows on the database lane and decodes `ItemStack` values only on the server thread.
- A durable journal is still planned for the process-crash window between SQLite commits and external Vault settlement.

## Current Wiring Limits

- SQLite remains the local default. Most existing business SQL still contains SQLite-specific syntax and has not been
  integration-tested against live MySQL, MariaDB, or PostgreSQL instances.
- The outbox/inbox/poller/cache/lease layer is tested infrastructure, but it is not fully wired into P0 balance,
  market, storage, blind-box, enterprise, or pricing settlement paths.
- `CASH` remains owned by Vault or the built-in economy. Legacy rows default to `CASH`; non-`CASH` market and
  limited-sale settlement fails before charging until the runtime bridge is connected.

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/market` | `kseco.market` | Open the market. |
| `/trade <player>` | `kseco.trade` | Start a trade. |
| `/storage` | `kseco.storage` | Open storage. |
| `/kseco gui` | `kseco.use` / `kseco.admin` | Open the economy menu and administrator warehouse. |
| `/kseco web` | `kseco.admin` | Get the admin Web link. |
| `/kseco status` | `kseco.admin` | Show economy state. |
| `/kseco reload` | `kseco.admin` | Reload configuration. |
| `/kseco force-price <item> <price>` | `kseco.admin` | Set a bounded price override. |
| `/kseco void-trade <item> <amount> <price> <BUY\|SELL>` | `kseco.admin` | Add a test/intervention trade. |

Aliases: `/kse`, `/mkt`, `/ah`, `/deal`, `/stash`, `/chest`.

## Pricing Model

```text
supplyPressure = clamp((recentSell - baseline) / baseline, -1, 1)
totalOffset = clamp(driftValue * maxFluctuation - supplyPressure * maxFluctuation,
                    -maxFluctuation, maxFluctuation)
officialBuy = round(basePrice * (1 + totalOffset), 2)
```

Test trades are marked `is_test` and do not contaminate official history. On 2026-07-18, Maven test and package/install
passed; Surefire found no framework tests, while five standalone currency/cross-server/SQL/polling contract suites passed.
All 22 Web JavaScript files, source YAML, and packaged/local Web resource checks passed. No JAR was deployed and Paper
was not started.
