# ks-Series Codebase Map

Last verified: 2026-07-16

Current cross-session handoff: `docs/CODEX_MEMORY.md`

Economy design and validation knowledge: `docs/economy-knowledge-base/README.md`

Survival-RPG product and systems knowledge: `docs/survival-rpg-knowledge-base/README.md`

This file is the first-stop index for future maintenance. Update it when an entry point,
thread boundary, database ownership rule, or major workflow changes.

## ks-Eco Core

Main lifecycle and service wiring: `ks-Eco/src/main/java/org/kseco/KsEco.java`

Market listing write/read and atomic quantity claim: `ListingManager.java`

Player market workflows and Vault settlement: `MarketManager.java`

Official low-price acquisition: `OfficialMarketSweepManager.java`

Official and protected price calculation: `MarketValueService.java`

Dynamic official material prices and trade history: `PriceEngine.java`

Player storage and official warehouse: `StorageManager.java`, `OfficialWarehouseManager.java`

Limited-sale purchase preparation, stock transaction, and settlement callbacks: `LimitedSaleManager.java`.
Player single/ten/limited blind-box batch preparation and pity transaction: `BlindBoxManager.java`.

## ks-BotGuard

Leaves fake-player filtering and MythicLib/MMOCore listener wrapping:
`ks-BotGuard/src/main/java/org/kseries/botguard/KsBotGuard.java`

Do not assume an event with `getPlayer()` extends `PlayerEvent`. In particular,
`BlockPlaceEvent` and `BlockBreakEvent` must be handled explicitly when identifying `ServerBot` actors.
`PlayerToggleSneakEvent` and `PlayerExpChangeEvent` are protected because the deployed MythicLib and
MMOCore versions perform strict player-data lookups for them. MMO plugin disable events must discard
their wrappers; never restore an original listener owned by a disabled plugin.

## ks-Compat KSBot

Command routing, ownership and action safety:
`ks-Compat/src/main/java/org/kseries/compat/bot/BotManagerModule.java`

Leaves reflection bridge, action counting and duplicate-type detection:
`ks-Compat/src/main/java/org/kseries/compat/bot/LeavesBotBridge.java`

Command blocks are supported. Do not rely on sender type for resource safety because
`/execute as <player>` can present a player sender. Enforce cooldown, interval, repetition,
concurrency and duplicate-type limits at the bot action boundary.

## ks-BossCombat

MMOItems weapon-type damage adaptation for `Frostbound_Conductor`:
`ks-BossCombat/src/main/java/org/kseries/bosscombat/frostbound/FrostboundWeaponAdaptationListener.java`.
It is deliberately scoped to the Boss scoreboard tag `Frostbound_WeaponAdaptation`; team-mechanic
skills remove that tag temporarily to provide the counterplay window.

## ks-RPG

First-season material exchange foundation: `ks-RPG/src/main/java/org/kseries/rpg/KsRpg.java`.
`RpgCommand` exposes `/ksrpg catalog`, `/ksrpg exchange <id> [amount]`, and administrator reload;
`MaterialExchangeService` performs exact MMOItems/vanilla inventory validation, removal, and output on the
server thread. `MmoItemsBridge` uses the MMOItems runtime API by reflection, so an unavailable or incompatible
MMOItems installation disables exchange gracefully. The authoritative ratios live in `ks-RPG/config.yml` and
the player/economy contract lives in `docs/survival-rpg-knowledge-base/foundation-catalog.md`.

Progression integration surface: `api/RpgProgressionApi.java`, `ProgressionService.java`, and
`ProgressionCatalog.java`. `KsRpg` registers the API with Bukkit's `ServicesManager`; it owns configured combat
proof definitions, configured proof gates, and player PDC proof flags. All API and PDC access is server-thread-only.
`/ksrpg proof grant|revoke` provides a console/configuration bridge for MythicMobs and dungeon rewards; its target
must be online. Adding proof/gate definitions and running `/ksrpg reload` does not require a JAR replacement.

First-wave combat runtime: `CombatCatalog.java`, `CombatSkillListener.java`, `MmoInventoryBridge.java`, and
`ConfiguredMobDropListener.java`. `CombatCatalog` strictly scans `plugins/ks-RPG/content/` by category:
`weapons/`, `talismans/`, `rings/`, `caches/`, and `world-drops/`. It is immutable and atomically replaced
only after every file validates during `/ksrpg reload`; the previous catalog remains live on failure. No Java
source owns a concrete MMOItems ID, Mythic mob identity, or Boss tag. Weapon skills trigger from sneaking
right-click with registered MMOItems; equipped talismans trigger from sneaking hand-swap and are read from
MMOInventory custom slots through reflection. All handlers run on the server thread. Configured scoreboard-tag
mob drops are delivered to the killer's inventory, with overflow dropped at the player.

## ks-RPG-Gui

Independent RPG inventory interface: `ks-RPG-Gui/src/main/java/org/kseries/rpggui/KsRpgGui.java`.
It hard-depends on `ks-RPG` and consumes `RpgProgressionApi` plus the server-thread-only `RpgContentApi` through
Bukkit's `ServicesManager`; it owns no economy, item creation, inventory delivery, or player-progress persistence.
`RpgMenu.java` creates the main/proof/gate views and the administrator-only paged item library. The library previews
the live item created by ks-RPG and delegates one-item delivery back to `RpgContentApi`; its list refreshes from the
current ks-RPG catalog after `/ksrpg reload`. `RpgMenuListener.java` cancels every click and drag inside its holder.
Layout and Chinese copy live in the independent `plugins/ks-RPG-Gui/menu.yml`; `/rpgmenu reload` parses it
asynchronously, then atomically swaps the immutable layout on the server thread. Build ks-RPG before ks-RPG-Gui because
its compile-only API dependency resolves from the current ks-RPG target JAR.

## ks-Eco Web UI

Page structure only:

- `ks-Eco/src/main/resources/web/admin.html`
- `ks-Eco/src/main/resources/web/player.html`

Styles and behavior live under `web/assets/`. Start with `admin-core.js` or `player-core.js`,
then read only the named feature module (`smart-inputs`, `consoles`, `drill`, `shell`,
`side-sheet`, `entity-drawers`, `dungeon-drawers`, or `cards`).

Static assets are served by `EcoWebHandler.serveWebAsset`. Admin listing actions use delegated
`data-listing-action` buttons; do not put IDs or player names inside generated `onclick` strings.

Player bootstrap intersects feature gates with the enabled Extra module set. `ExtraModuleLoader`
publishes a module only after `onEnable` succeeds and removes it from Web visibility before
`onDisable`, so pages must treat missing module APIs as an offline/empty state.

Local full-page Web regression entry: `web/test.html`, `web/assets/test.js`, and `web/assets/test.css`.
It embeds the production admin/player pages and selects localhost-only normal, empty, API-error, or slow scenarios.

Real-estate district map entry and progressive 3D scene:
`player.html`, `web/assets/player-core.js` (`openBrowseDistrictEntity`, `openZoneVoxelViewer`), and
`web/assets/player.css` (`#houseVoxelModal.zone-mode`). A district is split into `32 x 32` requests to
`/api/realestate/region/voxels`; each response is merged into one Three.js scene with world-coordinate offsets.

## Compensation Flow

Persistence and atomic once-per-player claim: `CompensationManager.java`.

Player claim and same-surface admin configuration: `gui/CompensationGui.java`. Main-menu entry:
`gui/EcoGuiMainMenu.java` slot 29, gated by feature key `compensation`.

Runtime contract:

1. The server thread snapshots the held `ItemStack` into immutable bytes and primitive metadata.
2. Plan reads/writes and claim settlement run on `AsyncWorkPool` without Bukkit objects.
3. A claim transaction inserts both the unique `(plan_id, player_uuid)` row and legal-size stacks in
   `ks_eco_storage`; transaction rollback prevents duplicate or partial delivery.
4. Worker results return to the server thread before messages, sounds, item decoding, or GUI refresh.

## Official Market Flow

1. The server thread validates inventory and creates immutable item/listing snapshots.
2. A worker inserts the listing row.
3. A committed normal SELL listing immediately calls `evaluateNewListing`.
4. A worker reloads the latest ACTIVE row.
5. The server thread decodes the item and creates `PreparedItem` plus a price session.
6. A worker calculates the protected price from immutable data only.
7. A worker atomically claims the listing and writes the official warehouse row.
8. The server thread performs only Vault settlement and player notification.
9. A worker records the trade or atomically rolls back an unpaid acquisition.
10. The periodic sweep remains a randomized fallback and shares per-ID deduplication.

`MarketValueService` snapshots Bukkit recipes into an immutable recipe graph at startup and
runtime reload. Recipe valuation has cycle detection, a depth limit, and session caching.

## Thread Contract

Server thread only:

- Bukkit recipe registry, Player, Inventory, ItemStack metadata/PDC and GUI operations
- Vault balance checks, withdrawals and deposits
- Creating `MarketValueService.PreparedItem`
- Refreshing the recipe graph

Worker threads:

- SQL reads/writes that do not hold a transaction across a server-thread callback
- Pure valuation through `MarketFloorSession` and `PreparedItem`
- Pure sorting, aggregation and report construction

Never deserialize or inspect Bukkit ItemStack state on a worker. Snapshot it on the server
thread first. Never hold a database transaction while waiting for the server thread.

## Limited Sale And Player Blind Box Flow

1. A server-thread GUI or a Web request starts an operation using UUID, player name, sale/pool ID, and quantity.
2. A worker loads sale/pool rows, raw item bytes, limits, stock, and pity state and performs pure RNG/validation.
3. The server thread decodes item bytes, verifies the online player, and performs Vault withdrawal.
4. Limited sales recheck price/stock/limit and atomically update stock, player count, and sale log on a worker.
5. The server thread creates shulkers or legal item stacks and applies inventory delivery; overflow is snapshotted
   into the asynchronous storage queue.
6. Blind-box pull logs and final per-rarity pity counters are written together in one worker transaction before
   the server-thread callback refreshes the GUI or completes the Web future.

Entry points: `gui/LimitedSaleGui.java` (`purchaseAsync`, `purchaseBoxAsync`), `gui/BlindBoxGui.java`
(`pullAsync`, `pullTenAsync`), and `EcoWebHandler.java` (`handleBbPull`, `handleBbPullTen`).

## Enterprise Levels And Blind Boxes

`EnterpriseLevelManager` is the sole owner of persisted enterprise levels, cached reads, configured bounds and land-perk
multipliers. `BlindBoxManager` owns `ks_bb_pools.min_enterprise_level` and validates the enterprise level before charging.
`LandPerkManager.getBlockPerkValue` applies the cached multiplier only to enterprise-owned percentage perks.

Purchased enterprise tickets are retired. Old ticket tables may still exist in deployed databases but have no active manager,
route, GUI, table-creation or reset entry. Do not reintroduce the old shared-counter model.

## Threading Audit Backlog

Highest priority:

- Add a durable settlement journal for the remaining crash window between SQLite commit and
  external Vault settlement. Normal failures already use atomic asynchronous compensation.
- Move PurchaseOrderManager creation, fulfillment and cancellation DB work off the server thread.
- Remove worker-side ItemStack deserialization from PurchaseOrderManager and the remaining legacy blind-box
  list/admin/enterprise surfaces; player single/ten/limited batches are already separated.
- Convert bank, enterprise, bidding, invites, real-estate, tax and enterprise blind-box GUIs to
  loading views backed by async DTO queries.

Infrastructure:

- Replace the shared unbounded fixed thread pool with a bounded compute pool and a serialized
  database writer with queue metrics.
- Make EconomyResetManager run as an explicit maintenance operation outside the server thread.
- Route every AsyncChatEvent GUI/message operation back to the server thread.

## Maintenance Rule

Before changing ks-Eco, read this file, search only the named entry points, and expand scope only
when a discovered caller requires it. Use one focused audit agent at most unless broad parallel
work is explicitly requested.

## Plugin Backup Contract

All future ks-Series plugin deployments use `scripts/deploy-plugin.ps1`. It builds the module, copies the replaced
JAR to `backup/<plugin-id>/<unique-backup-id>.jar`, appends a JSON record to that plugin's `index.jsonl`, and checks
the source/deployed SHA-256 match. Stable plugin IDs and the index format are defined by `backup/README.md`.
Do not create future adjacent `*.jar.bak.*` files or move/delete historical adjacent backups without user approval.
