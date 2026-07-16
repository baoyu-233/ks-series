# KS-Series Full Plugin Report

> [中文](KS-SERIES-REPORT.md) | English
>
> Scope date: 2026-07-15. Baseline: LeavesMC 1.21.11 (Paper 1.21 fork).

This report documents the published KS-Series modules, their commands and permissions, deployment boundaries,
integration contracts, and the design and behavior of `ks-Eco`. Runtime facts are checked against source,
`plugin.yml`, module configuration, the code map, and available verification records. A feature marked as planned or
awaiting live validation must not be treated as production-ready.

## 1. Scope and Module Model

The repository contains independent Bukkit/Paper plugins and six `ks-Eco` Extra modules. Independent plugins are
installed as JARs in `plugins/`. Extra modules do not have their own `plugin.yml`; they are loaded by
`ks-Eco` from `plugins/ks-Eco/extra/` and must not be placed at the server plugin root.

```text
LeavesMC 1.21.11
|- ks-core                         Web gateway, tokens, SQLite, routing
|- ks-Eco                          Economy core, GUI, Web, Extra host
|  `- plugins/ks-Eco/extra/
|     |- ks-Eco-bank               Banking and money supply
|     |- ks-Eco-enterprise         Enterprises and tenders
|     |- ks-Eco-tax                Taxes and penalties
|     |- ks-Eco-RealEstate         Plots and property
|     |- ks-Eco-RealEstateDungeon  Dungeons, parties, revives
|     `- ks-Eco-politic            Senate and legislation
|- ksHWP                           Web world map
|- KS-ItemEditor / KS-ItemSteal    Item tools
|- ks-Inherit / ks-Title            Item migration and titles
|- ks-Compat / ks-BotGuard          Compatibility and bot safety
|- ks-RPG / ks-RPG-Gui              RPG foundation and UI
|- ks-BossCombat / ks-Skill          Combat rules and legacy skills
`- ks-Maintenance / ks-Sentinel     Operations and audit
```

`ks-core` owns the shared Web gateway and SQLite service. `ks-Eco` owns economy settlement and optional economy
extensions. RPG progression and combat proofs belong to `ks-RPG`; MythicMobs owns encounter content and effects;
MMOItems owns equipment presentation. This separation keeps an absent optional module from disabling unrelated
economy functions.

## 2. Plugin Reference

| Module | Main responsibility | Deployment |
|---|---|---|
| `ks-core` | Web gateway, routing, token authentication, shared SQLite | `plugins/` |
| `ks-Eco` | Market, official buyback, prices, GUI, blind boxes, limited sales, compensation, storage | `plugins/` |
| `ksHWP` | World-map tiles, player positions, notes, Web map | `plugins/` |
| `KS-ItemEditor` | GUI/Web item editing, templates, refinement | `plugins/` |
| `KS-ItemSteal` | Safe disarm and return of configured weapon types | `plugins/` |
| `ks-Inherit` | Cross-version item storage, review, and delivery | `plugins/` |
| `ks-Skill` | Legacy passive-skill trigger engine | `plugins/` |
| `ks-Title` | Titles, attributes, animations, and unlock conditions | `plugins/` |
| `ks-Compat` | Leaves/Vault/third-party compatibility and KSBot integration | `plugins/` |
| `ks-BotGuard` | Leaves ServerBot event protection | `plugins/` |
| `ks-BossCombat` | Frostbound and other encounter-specific combat rules | `plugins/` |
| `ks-Maintenance` | Maintenance mode and operator tools | `plugins/` |
| `ks-Sentinel` | High-risk operator command audit | `plugins/` |
| `ks-Eco-*` Extra | Banking, enterprises, tax, real estate, dungeons, politics | `plugins/ks-Eco/extra/` |

## 3. Commands and Permission Groups

The following are the main command surfaces. Module `plugin.yml` files and the Chinese report remain authoritative
for version-specific aliases and newly introduced nodes.

### Player entry points

| Command | Permission | Purpose |
|---|---|---|
| `/kseco gui` | none | Open the economy hub. |
| `/kseco web` | none | Get the player Web panel link. |
| `/kseco prices` | none | View official prices, trend, and tax information. |
| `/market` | `kseco.market` | Browse, buy, list, and sell through the player market. |
| `/trade <player>` | `kseco.trade` | Start a player trade. |
| `/storage` | `kseco.storage` | Retrieve pending items and refunds. |
| `/exchange` | `kseco.exchange` | Use configured material exchanges. |
| `/limitedsale` | `kseco.limitedsale` | View limited-time direct-sale stock. |
| `/balance` | `kseco.balance` | View the current balance. |
| `/ksrpg catalog` | player default | Browse RPG content. |
| `/ksrpg exchange <id> [amount]` | player default | Exchange configured materials. |
| `/rpggear` | player default | Open the RPG equipment entry point. |
| `/map` | `kshwp.use` | Get the world-map link. |
| `/inherit open` | `ksinherit.use` | Open the cross-version item storage GUI. |

### Administrator surfaces

| Command | Permission | Purpose |
|---|---|---|
| `/kseco reload` | `kseco.admin` | Reload economy configuration and catalogs. |
| `/kseco status` | `kseco.admin` | Inspect economy, storage, Vault, and Extra state. |
| `/kseco force-price <item> <price>` | `kseco.admin` | Set a bounded official price override. |
| `/kseco void-trade <item> <amount> <price> <BUY\|SELL>` | `kseco.admin` | Add a test/intervention trade to the pricing stream. |
| `/blindboxadmin ...` | `kseco.admin` | Create and tune blind-box pools, weights, pity, and stock. |
| `/limitedsaleadmin ...` | `kseco.admin` | Create, open, close, restock, and refund limited sales. |
| `/compensationadmin ...` | `kseco.admin` | Create, enable, expire, and inspect compensation plans. |
| `/kshwp reload` / `status` | `kshwp.admin` | Reload and inspect the map service. |
| `/itemedit reload` / `/itemedit web` | `itemedit.admin` | Reload ItemEditor and open its admin Web surface. |
| `/inherit slots <player> <amount>` | `ksinherit.admin` | Set a player's inheritance capacity. |
| `/itemsteal return <thiefUuid>` | operator/admin | Force a stored item return. |
| `/ksrpg proof grant|revoke <player> <proofId>` | admin | Manage account-bound combat proofs. |
| `/rpgmenu reload` | admin | Reload the RPG GUI menu definition. |

The player guide intentionally excludes these administrator surfaces. The public repository must not publish
runtime tokens, private server assets, databases, or production configuration together with command examples.

Suggested groups are `player` for ordinary gameplay nodes, `builder` or `content-editor` for item and map authoring,
`economy-admin` for economy and Extra administration, `rpg-admin` for progression/content management, and `operator`
for maintenance, audit, and emergency recovery. Apply the smallest group required by the server role.

## 4. `ks-Eco` Design

### Core services

- **Market**: stores player listings, validates prices and item snapshots, settles currency and item delivery, and
  sends overflow items to `/storage`.
- **Official buyback**: uses only `official-buy.default-items` as the direct buyback catalog. Internal reference prices
  remain a protection floor and must not silently become an official catalog.
- **Dynamic price engine**: combines real official SELL volume against a rolling baseline with a mean-reverting
  random-walk drift and optional material trend bias. The final offset is clamped by `max-fluctuation`.
- **Player trade**: uses a two-party confirmation flow and transaction rollback for invalidated inventory or balance
  state.
- **Blind boxes**: use named pools, weighted entries, rarity, pity counters, multi-open operations, enterprise funds,
  ticket materials, NBT-preserving item snapshots, and a temporary storage fallback.
- **Limited sales**: bind a sale to a blind-box or item pool, enforce start/end time, global stock, per-player limits,
  batch or box purchases, and refund/rollback when delivery cannot complete.
- **Compensation**: administrators create a plan with replacement items, quantity, expiry, and enabled state. A player
  can claim each plan once; full inventory is handled through `/storage`.

### Extra modules

`ks-Eco-bank`, `ks-Eco-enterprise`, `ks-Eco-tax`, `ks-Eco-RealEstate`, `ks-Eco-RealEstateDungeon`, and
`ks-Eco-politic` are optional extensions. The host discovers JARs in `extra/`, validates their metadata, registers
their services and Web routes, and removes them from the active set when the module is absent or disabled.

Their ownership is deliberately narrow: the bank module settles accounts and loans, enterprise owns organizations and
tenders, tax owns rates and records, real estate owns plots and property, the dungeon module owns instance and reward
settlement, and politics owns proposals and votes. Shared currency, item delivery, and database access still cross
the `ks-Eco` contract rather than duplicating storage.

### Data and thread model

SQLite reads/writes and pure pricing calculations run asynchronously. Bukkit/Paper live objects, inventories, item
metadata, Vault calls, and GUI operations remain on the server thread. A worker receives immutable snapshots and
returns an immutable result; settlement is then applied on the server thread with an idempotent transaction key.
`ItemStack` must not be deserialized or inspected on a worker.

## 5. Simulation Examples

### Market price

```text
supplyPressure = clamp((recentOfficialSell - historicalBaseline) / historicalBaseline, -1, 1)
totalOffset = clamp(driftValue * maxFluctuation - supplyPressure * maxFluctuation,
                    -maxFluctuation, maxFluctuation)
officialBuy = round(basePrice * (1 + totalOffset), 2)
```

Selling more than the baseline pushes the buyback price down; lower recent supply pushes it up. A test or void trade
is marked separately and must not pollute the official history.

### Blind box

An administrator defines a pool, entries, weights, rarity, pity threshold, and optional ten-pull rules. A player pays
with the configured currency or ticket, the server records the draw transaction, rolls the pool, and places an item
in the inventory or storage. Pity is advanced and reset only by the documented result; a failed delivery cannot charge
the player without a compensating storage entry.

### Limited sale

The sale remains invisible or unavailable before its opening time, consumes stock atomically while open, and rejects
orders after the end time or the per-player cap. A box purchase is a single business operation: stock, payment, draw,
and delivery are either all recorded or rolled back to a recoverable state.

### Compensation

An operator publishes a plan, enables it, and optionally sets an expiry. Each player sees the plan through the economy
GUI, claims it once, and receives the configured items. If the inventory is full, the claim succeeds into `/storage`;
the claim record prevents duplicate delivery.

## 6. Web and Deployment

`ks-core` provides the shared Web gateway, normally on port 8123. Modules register their own routes; the owning module
still validates permissions, tokens, database operations, and item/economy settlement.

Build independent modules with Maven. Build through GitHub Actions for the published repository; the Action uploads
JAR artifacts and does not create a release or deploy to GitHub. The local deployment helper downloads the selected
successful artifact, backs up the replaced JAR under the workspace `backup/<plugin-id>/` directory, verifies SHA-256,
and copies it to the local test server. Paper is not started or restarted automatically. Acceptance happens on the
test server before README or report updates are considered complete.

## 7. Current Limitations

- Some multi-table market, limited-sale, and batch blind-box paths still need further asynchronous SQL refinement.
- Worker-side item deserialization must remain isolated from Bukkit live objects.
- Full Paper acceptance is required after changes to cross-plugin integrations, GUI flows, or third-party versions.
- The Action build proves compilation and artifact packaging; it does not prove live gameplay balance or server load.

For the Chinese detailed record, use [KS-SERIES-REPORT.md](KS-SERIES-REPORT.md).
