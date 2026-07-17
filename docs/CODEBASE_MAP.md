# ks-Series Codebase Map

> [中文](CODEBASE_MAP.zh-CN.md) | English

Last verified: 2026-07-17

Current cross-session handoff: `docs/CODEX_MEMORY.md`

Economy design and validation knowledge: `docs/economy-knowledge-base/README.md`

Survival-RPG product and systems knowledge: `docs/survival-rpg-knowledge-base/README.md`

This file is the first-stop index for future maintenance. Update it when an entry point,
thread boundary, database ownership rule, or major workflow changes.

## ks-Eco Core

Main lifecycle and service wiring: `ks-Eco/src/main/java/org/kseco/KsEco.java`

Market listing write/read and atomic quantity claim: `ListingManager.java`

Player market workflows and Vault settlement: `MarketManager.java`

Bounded async execution: `AsyncWorkPool.java`. `execute` submits pure computation to a bounded multi-worker lane;
`executeDatabase` submits SQL/audit work to a bounded single-worker lane. Both expose queue metrics and pressure/rejection
logging. Do not submit Bukkit, ItemStack, GUI, or Vault work to either lane.

Official low-price acquisition: `OfficialMarketSweepManager.java`

Official and protected price calculation: `MarketValueService.java`

Dynamic official material prices and trade history: `PriceEngine.java`

Player storage: `StorageManager.java` and `gui/StorageMenu.java`. Official acquisition storage:
`OfficialWarehouseManager.java` and administrator-only `gui/OfficialWarehouseGui.java`. The warehouse GUI loads raw
immutable pages on the database lane, decodes ItemStacks on the server thread, atomically claims rows and restores a
claim if delivery cannot complete. Its entry is `/kseco gui` slot 30.

Limited-sale purchase preparation, stock transaction, and settlement callbacks: `LimitedSaleManager.java`.
Player single/ten/limited blind-box batch preparation and pity transaction: `BlindBoxManager.java`. Its
`loadPoolsAsync` and `loadLootViewsAsync` admin/Web read paths query on the serial database lane, return raw item bytes,
then decode ItemStack/lore and complete callbacks on the server thread. `BlindBoxAdminGui` renders loading/error states
and consumes one predecoded preview per loot row instead of issuing per-slot SQL queries.

Purchase orders: `PurchaseOrderManager.loadActiveOrderSnapshots` reads raw immutable rows on the database lane and
`materializeOrders` decodes `ItemStack` on the server thread. `createAsync`, `fulfillAsync`, and `cancelAsync` snapshot
items and Vault state on the server thread, execute order/storage reservation transactions on the serial database lane,
then return to the server thread for inventory and Vault settlement. Finite fulfillment reserves quantity and writes
invisible `ks_eco_purchase_order_pending_items` rows atomically; successful settlement promotes them into buyer storage,
while failure compensation deletes them and restores quantity or refunds a concurrently cancelled slice.
`PurchaseOrderMenu` owns per-operation in-flight guards and overload feedback.

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

## ks-InstanceWorld

Standalone plugin entry: `ks-InstanceWorld/src/main/java/org/kseries/instanceworld/KsInstanceWorld.java`.

Stable service surface: `api/InstanceWorldApi.java`, `InstancePreparation.java`, `InstancePrepareRequest.java`,
`PreparedInstance.java`, `InstanceSnapshot.java`, and `InstanceLifecycleEvent.java`. Prepare/release mutations start
on the server thread; their futures complete on the server thread. Cached snapshot/grid queries are immutable and
safe for Web readers.

Lifecycle orchestration: `InstanceWorldService.java`. Worker-only persistence and legacy read-only import:
`internal/InstanceStore.java`. Worker-only schematic parsing and namespace-root containment:
`internal/SchematicRepository.java`. Server-thread WorldEdit/FAWE canvas operations and tick-bounded Bukkit marker
scans: `internal/CanvasService.java` and `internal/MarkerScanner.java`.

Persistence belongs to `plugins/ks-InstanceWorld/instance-world.db` (`iw_pools`, `iw_grids`, `iw_instances`,
`iw_meta`). The optional legacy import reads but never modifies `plugins/ks-core/data.db:ks_dungeon_grids`.

`ks-Eco-RealEstateDungeon/DungeonInstanceManager.java` is the first consumer. It stores the external handle in
`ks_dungeon_instances.instance_world_id`, retains all economy/party/revive/reward/Boss behavior, and interprets
generic marker data through `MythicSpawner`. `DungeonCommand.java` and `DungeonWebHandler.java` read grid state from
`InstanceWorldApi`; Web actions that touch Bukkit or Vault are marshalled to the server thread.

## ks-Cinematic

Standalone observer-cinematic plugin: `ks-Cinematic/src/main/java/org/kseries/cinematic/KsCinematic.java`.
`CinematicService.java` owns private per-story package loading, configured item triggers, spectator sessions,
timeline actions, PDC-backed recovery, and `ks-InstanceWorld` release. `CinematicCommand.java` manages list,
reload, admin preview and editor selection. Private content is rooted at
`plugins/ks-Cinematic/stories/<id>/story.yml`; `KsCinematic` registers that root as the `ks-cinematic`
schematic namespace. PDC trigger items are generated only through `/cinematic give <story> [player]`; external
MMOItems triggers remain configuration-only. `BLOCK` timeline actions and private commands are applied only to
the prepared instance. No schematic, MythicMobs content, model, resource-pack asset, economy rule, or combat
progression belongs to the public module.

`InstancePrepareRequest` accepts a contained relative schematic path such as `gaze/gaze.schem`, rejects absolute
and traversal paths, and `SchematicRepository` enforces the registered-root boundary again before file access.

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

Use `AsyncWorkPool.executeDatabase` for SQL/audit tasks submitted through this pool and `execute` only for pure computation. Both
queues are bounded; rejection is logged and surfaced to the submitter. Interactive callers still need explicit
operation-specific rejection cleanup before queue saturation can be treated as a fully handled user-facing state.

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

## Enterprise Governance And Dividends

`ks-Eco-enterprise/EnterpriseManager.java` owns join requests, approval/rejection, voluntary leave, dissolution and both
configured/custom dividend settlement. `ks_ent_join_requests` is the approval source of truth; only an approved request
creates `ks_ent_members`. Leave/removal cancels its approved row so reapplication remains possible. `EnterpriseGui.java`
owns the in-game approval list and second-click leave/dissolve confirmation.

Dividend headers live in `ks_ent_dividends`; recipient gross/tax/net details live in `ks_ent_dividend_payouts`; tax audit
lives in `ks_tax_records` under `DIVIDEND_TAX`. Rates may be stored as `0.10` or legacy `10` and are normalized before
settlement. `EcoWebHandler.handleEnterpriseAdminEdit` owns the full administrator edit transaction and mirrors corporate
balance changes into the selected corporate bank; `EnterpriseLevelManager` applies the post-transaction level/cache update.

## Threading Audit Backlog

Highest priority:

- Add a durable settlement journal for the remaining crash window between SQLite commit and
  external Vault settlement. Normal failures already use atomic asynchronous compensation.
- Remove synchronous SQL and legacy settlement from enterprise blind-box GUI paths and limited-sale detail reads;
  player batches plus admin/Web pool and loot-list reads are already separated.
- Convert bank, enterprise, bidding, invites, real-estate, tax and enterprise blind-box GUIs to
  loading views backed by async DTO queries.

Infrastructure:

- Add operation-specific rejection cleanup and user/Web errors for bounded executor saturation.
- Make EconomyResetManager run as an explicit maintenance operation outside the server thread.

All 13 current ks-Eco/ks-Eco-RealEstate async chat handlers snapshot only UUID/text/cancellation state before
returning Player, permission, message, GUI, inventory, ItemStack, Bukkit lookup, and manager work to the server thread.

## Maintenance Rule

Before changing ks-Eco, read this file, search only the named entry points, and expand scope only
when a discovered caller requires it. Use one focused audit agent at most unless broad parallel
work is explicitly requested.

## Plugin Backup Contract

All future ks-Series plugin deployments use `scripts/deploy-plugin.ps1`. It builds the module, copies the replaced
JAR to `backup/<plugin-id>/<unique-backup-id>.jar`, appends a JSON record to that plugin's `index.jsonl`, and checks
the source/deployed SHA-256 match. Stable plugin IDs and the index format are defined by `backup/README.md`.
Do not create future adjacent `*.jar.bak.*` files or move/delete historical adjacent backups without user approval.
