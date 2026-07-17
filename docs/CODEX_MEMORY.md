# Codex Project Memory

> [English](CODEX_MEMORY.en.md) | 中文

Last updated: 2026-07-17 Asia/Hong_Kong

Read this file and `docs/CODEBASE_MAP.md` before working on ks-Series.

## Working Preferences

- Communicate in Chinese.
- Treat this conversation as the dedicated ks-Eco-series maintenance thread. Keep work focused on ks-Eco,
  its Extra modules, shared services directly required by them, and their verification/deployment handoffs.
- Preserve all existing user and prior-agent changes.
- Default documentation scope is project memory, code map when ownership/contracts change, and the
  relevant specialized knowledge base. Do not edit `docs/KS-SERIES-REPORT.md` or
  `docs/KS-SERIES-PLAYER-README.md` unless the user explicitly asks for either document.
- Keep token use low: consult the maps first, use targeted `rg`, and avoid full-file reads.
- Use at most one focused sub-agent unless the user explicitly requests broad parallel work.
- Treat ks-Series Web as desktop-first. Skip mobile-specific layout work and mobile QA unless the
  user explicitly requests it or a surface has a concrete mobile requirement; current access is
  game-command based and is not intended as a public HTTPS mobile workflow.
- All future plugin JAR backups use the root `backup/` store only. Keep one stable plugin ID per directory,
  use a unique `<plugin-id>-<UTC timestamp>-<GUID fragment>` backup ID, and append each replacement to that
  plugin's `backup/<plugin-id>/index.jsonl`. Use `scripts/deploy-plugin.ps1`; never create new backups beside
  deployed JARs. Legacy adjacent backups remain historical data and must not be moved or deleted without approval.
- Build and deploy completed ks-Eco fixes to `test_1_21/plugins/ks-Eco-1.1.0.jar`.
- Do not start the Paper server unless the user explicitly asks; the user normally restarts it.

## Local Codex Tooling

- Root `AGENTS.md` contains the durable repository, thread, verification, and deployment rules.
- Personal plugin source: `~/plugins/ks-dev`; personal marketplace: `~/.agents/plugins/marketplace.json`.
- Installed `ks-dev` version: `0.1.0+codex.20260715134308`. Skills: `ks-context-router`, `ks-build-deploy`, `ks-paper-thread-audit`,
  `ks-crash-triage`, and `ks-gui-regression`.
- The workspace `scripts/deploy-plugin.ps1` makes root-indexed unique-ID backups, verifies SHA-256, and never starts Paper.
- Global skill `ks-series-plugin-backup` is installed under `~/.codex/skills` and enforces this backup contract
  for future ks-Series deployment or rollback requests in all Codex conversations.
- The GUI skill's asset checker passed all 22 current ks-Eco Web JavaScript files.
- The public GitHub repository is `https://github.com/baoyu-233/ks-series`. The prepared publishing
  checkout is `E:\ks_series`; it contains the 21 ks plugin modules, project docs, scripts and a local
 `.gitignore`, while test-server data, backups, runtime databases, tokens, build caches and `mcsm-panel`
 are excluded. The first published commit is `3531a7d` on `main`.
- The public repository uses the standard Mozilla Public License 2.0 (`MPL-2.0`).
- The Browser Codex plugin remains installed for local Web regression testing.
- `build-web-apps@openai-api-curated` is installed and enabled for ks-Eco Web, real-estate map,
  and local admin/player UI implementation and browser QA.
- `codex-security@openai-api-curated` is installed and enabled for scoped local security audits.
  Prefer module or diff-sized scans because repository-wide scans are intentionally token-intensive.

## Current Deployment

- Core JAR: `test_1_21/plugins/ks-Eco-1.1.0.jar`
- SHA-256: `8AD0F282BFC6D9724DBC149EB6BB069883B4F0C4351732C654481BB071FD5D1E`
- Root backup ID: `ks-Eco-20260717T155633323Z-05ade93988e5`
- Root backup: `backup/ks-Eco/ks-Eco-20260717T155633323Z-05ade93988e5.jar`
- Enterprise Extra: `test_1_21/plugins/ks-Eco/extra/ks-Eco-enterprise-1.1.0.jar`
- Enterprise SHA-256: `3949DA5BC1C90F752274B9802761A725F74E633147DB5207310702306E3CFD66`
- Enterprise root backup ID: `ks-Eco-enterprise-20260717T155652733Z-e14aca5dfab3`
- Enterprise root backup: `backup/ks-Eco-enterprise/ks-Eco-enterprise-20260717T155652733Z-e14aca5dfab3.jar`
- Both JARs were built from GitHub commit `cdd3d77` by successful Actions run `29593918604`; the downloaded artifact
  and deployed target hashes match. Paper was not started or restarted, so game acceptance is pending.
- RealEstate Extra: `test_1_21/plugins/ks-Eco/extra/ks-Eco-RealEstate-1.1.0.jar`
- RealEstate SHA-256: `4F78438CF7AD490C3252A161E3191A2ECAA76E05AADC493F8D5AB31333D7D0B3`
- RealEstate backup: `ks-Eco-RealEstate-1.1.0.jar.bak.20260716030001`
- Paper remap cache was removed before handoff.
- BotGuard JAR: `test_1_21/plugins/ks-BotGuard-1.0.0.jar`
- BotGuard SHA-256: `BF8C46730C6BDAB1A647A91455613A13D7065FCBC98C46BF2F74F00D0E83AB03`
- BotGuard backup: `ks-BotGuard-1.0.0.jar.bak.20260715203056`
- ks-Compat JAR: `test_1_21/plugins/ks-Compat-1.0.0.jar`
- ks-Compat SHA-256: `AC5F85AC8FB4AF6D946A1BF39B62AA61D48FAA50FAD5DD9FD627D2F2492E3F5E`
- ks-Compat backup: `ks-Compat-1.0.0.jar.bak.20260715205904`
- ks-RPG foundation JAR: `test_1_21/plugins/ks-RPG-0.1.0.jar`
- ks-RPG SHA-256: `E18DA67910775D204B8810DF0D0B4F39E46230A89F0E906DA6D1E75311BB8039`
- ks-RPG root backup ID: `ks-RPG-20260716T110238499Z-24241084e029` at
  `backup/ks-RPG/ks-RPG-20260716T110238499Z-24241084e029.jar`. `mvn clean package` and YAML parsing
  passed. Paper was not started or restarted.
- ks-RPG-Gui JAR: `test_1_21/plugins/ks-RPG-Gui-0.1.0.jar`
- ks-RPG-Gui SHA-256: `2C6D6A8C7523A4ECC8FCD00C06E1C6DC99165C2E231287AED1A4FB2FD5DF9FB0`
- ks-RPG-Gui root backup ID: `ks-RPG-Gui-20260716T110254835Z-e340cc93e0c1`
- ks-RPG-Gui root backup: `backup/ks-RPG-Gui/ks-RPG-Gui-20260716T110254835Z-e340cc93e0c1.jar`
  with its append-only index at `backup/ks-RPG-Gui/index.jsonl`. `mvn clean package` and menu YAML parsing
  passed; live GUI verification awaits restart. `minecraft:*` IDs and component counts in earlier screenshots were
  client-side advanced tooltips, toggled by F3+H, not ks-RPG-Gui lore.

## KSBot Action Safety

- Command blocks remain supported for `/ksbot action`; an admin command sender can control a bot
  directly, and `/execute as <player>` retains the normal owner check.
- Each bot defaults to at most 4 concurrent actions, one action of each type, and one new action
  per 1000 ms. Limits are configurable under `modules.bot-manager`.
- General actions require at least 2 ticks between executions; item-use actions require 4 ticks.
- Positive repetition counts are capped at 100000. Use `-1` for an intentional infinite action;
  infinite actions remain subject to concurrency, duplicate-type, interval and cooldown limits.
- Failure to read the Leaves action list fails closed and rejects the new action.

## BotGuard Compatibility

- Leaves `ServerBot` placement and break actions emit `BlockPlaceEvent`/`BlockBreakEvent`, which
  carry a player but do not inherit from `PlayerEvent`.
- `KsBotGuard.isLeavesServerBotEvent` handles both block event classes explicitly before forwarding
  MythicLib/MMOCore listeners. This prevents MythicLib `Player data not loaded` errors for bots.
- Bot sneak and experience-change events are also protected. Both can call unguarded MythicLib or
  MMOCore player-data lookups when fired for a Leaves bot.
- Protected MMO plugin disable events discard their wrappers. BotGuard restores originals only for
  plugins that remain enabled, preventing stale listener resurrection after reload/shutdown.
- Bot identification checks the Leaves Bot interface as well as current implementation class names.

## MythicMobs Boss Design Baseline

- Test server MythicMobs is Premium `v5.12.0-c087ceb9`.
- Confirmed companion stack: ModelEngine `R4.0.9`, MythicCrucible `5.12.0`, MythicLib
  `1.7.1-SNAPSHOT`, MMOCore `1.13.1-SNAPSHOT`, MMOItems `6.10.1-SNAPSHOT`, ItemsAdder
  `4.0.16`, PlaceholderAPI `2.12.2`, and ProtocolLib `5.5.0-SNAPSHOT-b0b9b66`.
- Existing MythicMobs content already uses ModelEngine models, state-machine animations,
  part brightness, segments, and visual-effect mobs. Prefer these over generic entities for
  new boss telegraphs and interactable-looking mechanics.
- Native MythicMobs mechanisms confirmed in existing skills: `stun`, named `aura`,
  `onDamaged` with cancelled damage and a callback skill, aura stacks, aura removal, skill
  delays, player-radius targeters, particles, projectiles, and model states.
- Teamwork boss direction: build around a frost-control boss. The primary mechanic is
  `冰棺急救`: one player is immobilized, teammates strike the prison/marked player with
  incoming damage cancelled, and a small hit-count breaks the prison early. A shorter
  pre-cast interrupt on the boss is a complementary, simpler variant.
- Design rule: give every coordinated response a clearly visible ModelEngine/ItemsAdder
  telegraph, a 1.5-2.5 second reaction window, and a successful-response payoff such as a
  short boss vulnerability window. Do not introduce untelegraphed hard crowd control.
- Initial implementation: `Frostbound_Conductor` is isolated in
  `test_1_21/plugins/MythicMobs/Mobs/frostbound_conductor.yml` and
  `test_1_21/plugins/MythicMobs/Skills/frostbound_conductor_skills.yml`. The distributable
  test package is `deploy_package/frostbound_conductor-20260715.zip`; both YAML files parse
  with SnakeYAML 2.2. The Ice Core Relay specifically needs a live test of its `@trigger`
  transfer behavior before balancing or production use.
- Boss knowledge base: `docs/boss-knowledge-base/` contains the platform contract, encounter spec, and
  playtest log. The Frostbound Conductor model was confirmed working in formal-server feedback; the
  original lantern-based Cold Night was replaced by Whiteout. Its Hammer/Greathammer/Spear/Lance 50%
  damage adaptation is implemented by the scoped ks-BossCombat `FrostboundWeaponAdaptationListener` and is
  removed for five seconds after a successful team mechanic.
- `ks-BossCombat` is an independent Paper plugin for Boss-specific cross-plugin combat rules; it has no
  ks-core or ks-Compat dependency. `ks-Compat` no longer contains Frostbound combat code. The current
  formal-server test artifact is `deploy_package/frostbound_conductor-v3-20260715.zip`.

## Survival RPG Direction

- ks-Series is evolving as a survival-first server with gradual, optional RPG layers. The product
  knowledge base is `docs/survival-rpg-knowledge-base/`; Boss-specific records remain in
  `docs/boss-knowledge-base/` as its specialized sub-library.
- Add RPG systems in stages: stabilize survival/economy first, then light specialization and world
  discovery, then cooperative challenge, regional identity, and finally long-term seasonal content.
- Do not make classes, daily chores, boss clears, or gear-score gates mandatory for basic survival.
  Group play should accelerate or diversify progression, while economy sinks constrain reward inflation.
- `ks-Eco-RealEstateDungeon` is the verified economy/RPG integration point: it supports party instances,
  leader-paid tickets, paid revives, MythicMobs boss tracking, and completion reward JSON. Apply the
  three-lane progression rule in `docs/survival-rpg-knowledge-base/dungeon-economy-integration.md`:
  purchasable preparation, gathered/crafted inputs, and account-bound combat proof. Key abilities and
  equipment breakthroughs must require combat proof and cannot be bought, traded, or blind-boxed.
- Accepted architecture direction: replace the separate `ks-Skill` and `ks-BossCombat` plugins with a
  future `ks-RPG` foundation plugin. `ks-RPG` owns progression, combat proofs, ability unlocks, skill
  triggers/cooldowns, and narrow MMOItems/Boss combat rules; MythicMobs keeps encounters/effects,
  MMOItems keeps equipment, and ks-Eco keeps currency/material settlement. Migrate existing
  `ks-Skill` grants before retiring it, then move BossCombat rules, add a stable API for dungeon rewards,
  and only afterwards stop deploying the two legacy plugins to avoid duplicate listeners.
- MMO integration is approved as a single-server, survival-first layer: MMOCore supplies constrained
  baseline combat attributes/resources and optional region-scoped proficiency; MMOItems owns equipment,
  recipes, and upgrade presentation; MMOInventory supplies only a small accessory surface (two rings and
  one amulet). MMOProfiles remains uninstalled because profile-specific inventory, location, health, and
  balance conflict with one persistent survival identity, land, machines, and the economy.
- The first-dungeon prelude equipment is specified, not implemented, in
  `docs/survival-rpg-knowledge-base/first-wave-loadouts.md`: four primary weapon directions, six rings,
  and four talismans form a modular `one weapon + two different rings + one talisman` loadout. Future
  dungeon entry locks only that RPG loadout for the instance, while normal survival armor and supplies
  stay unrestricted. The accepted design target is now a full-enchanted-netherite baseline or higher,
  with ks-RPG-owned weapon and talisman skills; the first implementation is deployed and awaits live
  combat-stat validation.
- The current draft for that validation pass is
  `docs/survival-rpg-knowledge-base/field-elite-and-stat-proposal.md`. It selects the already verified
  `pet_ice_dragon` model for a low-frequency Frostwaste Whelp field elite, recommends mixed material/
  starter-weapon-cache drops, and records concrete full-enchanted-netherite-or-better stat and skill
  targets. `ks-RPG` now owns event-driven weapon/talisman skills, accessory passives, cache opening, and
  configured scoreboard-tagged mob drops; MMOItems and MythicMobs receive the corresponding first-wave content.
  MMOInventory reflection compatibility, random-spawn behavior, and balance still require an in-game test after restart.
- The pre-dungeon rollout is a low-load "relic prelude": a shared starter identity, a small fixed MMOItems
  catalog, regional elites/special resource nodes, and account-bound cooperation marks. Do not make normal
  mining/farming or automated machines a universal RPG XP source. Disable MMOCore's duplicate party/guild
  systems, avoid parallel MMOCore and ks-RPG active-skill bindings, start with one four-player instance,
  and measure MSPT, heap, and GC under live-like load before increasing concurrency.
- First-season RPG foundation is implemented and deployed to the test server. `ks-RPG` 0.1.0 owns four
  one-way material exchanges through `/ksrpg exchange`: Field Scrap 8:1 Refined Alloy; 4 Refined Alloy plus
  16 Redstone to one Conductive Coil; 4 Coil plus 8 Amethyst Shard to one Stabilized Core; 2 Core plus one
  Echo Shard to one Relic Fragment. It prevalidates the initiating player's storage inventory and capacity
  before deducting materials, and degrades safely when MMOItems is unavailable.
- MMOItems now has isolated `KS_STANDARD`, `KS_REFINED`, `KS_RARE`, and `KS_RELIC` tiers plus the five
  foundation materials and three first-season accessories. MMOInventory uses a new `ks_rpg_accessories`
  layout with two RING slots and one TALISMAN slot (`/rpggear`); the old default inventory is disabled but
  retained. Player-facing ks-RPG feedback, exchange displays, custom quality labels, foundation materials,
  accessories, and accessory inventory are Chinese-localized; `目录`/`兑换`/`重载` are Chinese aliases for the
  original ks-RPG subcommands. MMOCore now uses no built-in party/guild system and has its default action bar disabled.

## ks-RPG Extension Surface

- Combat content is now fully file-backed under `plugins/ks-RPG/content/`, split into `weapons/`,
  `talismans/`, `rings/`, `caches/`, and `world-drops/` like MythicMobs content categories. First startup
  extracts the shipped `first-wave.yml` files without overwriting local edits. `/ksrpg reload` strictly
  parses every YAML file and atomically swaps the immutable catalog only when all files are valid; on error,
  the previous combat catalog remains active. Java contains no concrete MMOItems IDs, boss tags, or mob IDs.
- The runtime owns generic mechanics only: active abilities, passive modifiers, weighted caches, and
  scoreboard-tagged drops. Adding an item means adding a category YAML definition and then hot-reloading;
  a new generic mechanic still requires code support before it can be referenced by content.
- `RpgContentApi` is a second server-thread-only service owned by ks-RPG. It lists, previews, and delivers one
  copy of every unique currently configured RPG MMOItem. It is rebuilt from the active content catalog plus
  configured exchange MMOItems on every call, so `/ksrpg reload` is immediately reflected without an item list
  being copied into a UI plugin.

- ks-RPG now registers `RpgProgressionApi` through Bukkit's `ServicesManager`. It owns declared combat proofs
  and proof-gates, both hot-reloadable from `ks-RPG/config.yml` through `/ksrpg reload`.
- Proofs are account-bound player PDC flags, not MMOItems or economy assets. The first declared proof is
  `frostbound_conductor_clear`; `frostbound_relic_breakthrough` is a dormant proof-gate with no transaction wired yet.
- Admin/console integration points are `/ksrpg proof grant <player> <proofId>` and `/ksrpg proof revoke <player> <proofId>`;
  players can list their own proof state and check a declared gate. The target player must be online, and all API/PDC calls
  are server-thread-only. Future configured encounters can use these commands without new ks-RPG Java changes.
- `ks-RPG-Gui` is a separate hard-dependent UI plugin that presents proofs, proof-gates, material catalog access,
  the MMOInventory accessory entry point, and an administrator-only RPG item library. It has no economy, storage,
  item-creation, or persistence ownership: the library delegates current-list preview and delivery to `RpgContentApi`.
  Its independent `plugins/ks-RPG-Gui/menu.yml` is loaded asynchronously and atomically applied on the server thread
  by `/rpgmenu reload` or the administrator reload button; changing that layout needs no Paper restart. The menu and
  command feedback are Chinese-localized; item names/lore remain owned by their MMOItems definitions.

## Cinematic Runtime

- `ks-Cinematic` is a new standalone, not-yet-deployed Paper plugin that hard-depends on `ks-InstanceWorld`.
  Each private story package has its own hot-reloadable `plugins/ks-Cinematic/stories/<id>/story.yml` and schematic.
  The engine owns configured MMOItems/vanilla/plugin-PDC triggers, spectator camera, titles, sounds, particles,
  safe block replacement, trusted private commands, PDC completion flags, and cleanup/recovery. `/cinematic give`
  generates only configured plugin-PDC trigger items; it never creates an external-plugin item or RPG progression.
- The first private runtime package is `test_1_21/plugins/ks-Cinematic/stories/gaze/` (`凝视`). It has a
  plugin-owned observation-station pass, independent timeline and a private `RPG_Catalyst` Mythic actor definition.
  Its `gaze.schem`, model assets, Mythic skills, resource-pack data and actual server configuration remain private;
  do not copy them to the source tree or public publishing checkout.

## Completed Architecture Work

- `AsyncWorkPool` now has two real bounded lanes instead of an unbounded fixed-pool queue: a multi-worker
  compute lane and a single-worker serialized database lane. Existing `AsyncWorkPool` call sites that perform
  SQL/audit work use `executeDatabase`; both lanes expose active/queued/submitted/completed/rejected metrics, warn at 75% queue
  occupancy, and fail visibly on rejection instead of growing memory without limit.
- Purchase-order menu loading now reads immutable `OrderSnapshot` rows and raw item bytes on the database lane,
  then validates material IDs and deserializes `ItemStack` only after returning to the server thread. Purchase-order
  create, fulfill, and cancel settlement remain synchronous and are still backlog work.
- All 13 `AsyncChatEvent`/`AsyncPlayerChatEvent` GUI handlers currently present in ks-Eco and ks-Eco-RealEstate
  now limit the async side to UUID/text snapshots, event cancellation, and concurrent pending-state lookup. Player,
  permission, message, GUI, inventory, ItemStack, Bukkit lookup, and manager calls resume through `runTask`;
  `PlotTrustMenu` pending state is now a `ConcurrentHashMap`.
- Official dynamic pricing uses two separate components: `supplyPressure` in `[-1,1]` from real official SELL volume versus a rolling historical baseline, and mean-reverting random-walk `driftValue` with optional per-material `trendBias`. The final offset is clamped by `max-fluctuation`; low recent supply can raise the official buy price, while oversupply lowers it.
- `official-buy.default-items` is the only direct official-buy list. `market-protection.internal-reference-prices` is only a fallback floor for player listings and official sweeps; it must not be treated as an official-buy catalog. Vanilla/Fotia premiums and shulker contents belong to floor valuation rules, not normal official-buy payout.
- Price refresh minutes and global volatility/test mode persist in `ks_eco_price_settings`; Web changes restart the refresh task without a server restart. Admin `void-trade`/`simulate-trade` rows marked `is_test` are excluded from real supply and market-average queries, so test previews do not change live pricing.
- Official market protection now buys listings below the protected price instead of rejecting them.
- New SELL listings trigger an immediate official acquisition check; periodic sweeps are fallback only.
- Bukkit recipes and item state are captured on the server thread as immutable snapshots.
- Protected-price calculation runs on workers using a cached immutable recipe graph.
- Recipe cycles and excessive depth terminate with direct configured/internal material values.
- Official listing claim plus warehouse insertion is one worker-side SQL transaction.
- Player listing stock claim plus storage delivery is one worker-side SQL transaction.
- Vault and Bukkit operations remain on the server thread.
- Trade history supports batched writes and atomic per-material supply updates.

## Web UI State

- Admin cancel/destroy listing buttons use delegated `data-listing-action` events and are verified.
- API errors preserve HTTP status and readable server errors.
- `admin.html` and `player.html` contain structure only.
- CSS and JavaScript modules are under `ks-Eco/src/main/resources/web/assets/`.
- `EcoWebHandler.serveWebAsset` serves only allowlisted admin/player asset names.
- Browser preview verified admin cancel, destroy, special-character seller names, and player-page load.
- The real-estate player map now opens a selected 2D district as a full-page Three.js digital twin.
  Districts stream in `32 x 32` region-voxel tiles from the center outward and merge into one scene.
  Browser QA loaded all 15 preview tiles / 10,287 blocks, returned to 2D, and reported no console errors.

## Compensation System

- `/kseco gui` slot 29 opens server compensation claims; admins get a management entry in the same GUI.
- Admins create a plan from the held NBT/PDC item, then configure name, quantity, expiry days, enabled state,
  and replacement item. Plans are open to all players and uniquely claimable once per player.
- Claim settlement inserts the unique claim and one or more legal item stacks into `ks_eco_storage` in one
  worker-side SQLite transaction. Item serialization/deserialization and GUI work stay on the server thread.
- In-game GUI paths were statically audited and compiled but were not live-tested because Paper was not started.

## Shared Instance World Runtime

- `ks-InstanceWorld` 0.1.0 is the standalone owner of instance-grid allocation, void-world loading,
  schematic parsing/paste, bounded marker scans, arena cleanup and idempotent release. It has no ks-Eco,
  Vault, MythicMobs, MMOItems, reward or private-content dependency.
- `InstanceWorldApi` is registered through Bukkit's `ServicesManager`. A prepare call immediately returns an
  `InstancePreparation` containing a cancellable instance ID and a server-thread-completed future. The prepared
  result exposes the world, grid center, schematic bounds, paste center, spawn and immutable generic marker data.
- Lifecycle state is persisted in `plugins/ks-InstanceWorld/instance-world.db`. SQL and schematic file parsing use
  a bounded worker pool; Bukkit world/block/entity/event work and WorldEdit/FAWE calls stay on the server thread.
  Marker scans are split across ticks. Preparation failure, timeout, external cancellation, duplicate release and
  plugin disable all converge on idempotent grid release.
- The optional one-time compatibility import reads `plugins/ks-core/data.db:ks_dungeon_grids` without modifying or
  deleting the source table or old dungeon-instance rows. Imported coordinates live in the new module's own tables;
  the migration marker is also stored only in the new database.
- `ks-Eco-RealEstateDungeon` now consumes the API and no longer contains `DungeonGridAllocator`, `SchematicService`,
  world creation, canvas clearing, schematic paste or block marker scanning. Its `instance_world_id` column is added
  non-destructively; legacy `grid_id` and `ks_dungeon_grids` data remain untouched for compatibility.
- The economy dungeon still owns tickets/refunds, property keys, parties, deaths/revives, Boss tracking, rewards,
  MythicMobs spawning and `/dungeon`/Web entry points. It interprets legacy `[mm]` markers and generic
  `[marker]/mm/...` data returned by the shared module; the shared module has no Boss or Mythic semantics.
- Verification on 2026-07-16: `mvn clean install` passed for ks-InstanceWorld with 2 SQLite tests covering grid
  reuse/idempotent release and non-destructive legacy import; the final shaded JAR opened an in-memory SQLite
  connection successfully. `mvn clean package -DskipTests` passed for ks-Eco-RealEstateDungeon and ks-Eco, and
  `node --check` passed for the touched admin Web script. Paper was not started and no JAR was deployed.

## Limited Sale And Player Blind Box Threading

- Limited-sale direct, boxed, and limited-blind-box purchases now use asynchronous preparation and a worker-side
  stock/player/log transaction. Vault, item decoding, shulker creation, inventory delivery, sounds, and GUI refresh
  remain on the server thread.
- The limited-sale GUI and manager both reject a second in-flight purchase for the same operation. Stale price,
  stock, limit, pool state, database failure, and fulfillment failure return through the server-thread callback;
  charged failures refund on the server thread and roll counters back on a worker.
- Player blind-box single, ten-pull, limited-sale batch, and their player Web endpoints share one async batch path.
  Workers carry immutable metadata and raw item bytes only; the server thread decodes ItemStacks and delivers them.
  Pull logs and all rarity pity counters are persisted in one worker-side transaction, and same-player/same-pool
  overlapping pulls are rejected. Quantities larger than a legal stack are split before inventory/storage delivery.
- Blind-box admin pool/loot reads now use the serial database lane and show a loading state. Raw loot bytes return
  to the server thread before ItemStack/lore decoding, one decoded preview travels with each GUI DTO, and the old
  per-slot `getLootItemStack` SQL path is removed. The Web loot-list endpoint reuses this boundary and returns explicit
  queue-busy, timeout, and interruption errors. Enterprise draw/GUI and limited-sale detail reads remain backlog work.

## Enterprise Levels And Retired Tickets

- Durable economy knowledge now lives in `docs/economy-knowledge-base/`. It combines current code/data facts,
  accepted product and thread decisions, verification history, and useful items recovered from
  `.claude/ks-server-ops-memory.md`. Claude deployment/build status is historical only and cannot override this file.
- The retired enterprise blind-box ticket was a shared enterprise counter, not a distributable employee credential:
  `ks_bb_tickets` has no assignee, claim, transfer, or role-allocation field. Buying it prepays the same pool price
  without a discount and optionally adds expiry; direct corporate-funded drawing already provides the same reward/pity path.
- The 2026-07-16 test SQLite snapshot contained 1 PUBLIC pool, 23 total pulls (1 enterprise), and zero tickets,
  ticket-use logs, or ticket audit rows. This is test-server evidence only, not production usage data.
- Purchased enterprise blind-box tickets are `ACCEPTED / RETIRED`: manager methods, Web routes, game/Web UI,
  table creation and reset handling were removed. Existing ticket tables remain untouched as dormant legacy data.
- `EnterpriseLevelManager` owns persisted levels, configured bounds, cached reads and land-perk multipliers.
  Administrators set levels explicitly; no speculative XP economy was introduced.
- Blind-box pools persist `min_enterprise_level` (default 1) and reject under-level enterprise draws before charging.
- Enterprise-owned agricultural/industrial percentage perks scale by level; player-owned plots remain unchanged.

## Enterprise Governance And Dividend Fixes (2026-07-17)

- Joining an enterprise now creates a durable `ks_ent_join_requests` PENDING row. Membership is inserted only after a
  manager with `MANAGE_MEMBERS` approves it in the in-game enterprise GUI; rejection, voluntary leave and manager
  removal close the request so the player may apply again later.
- Ordinary members can leave with a second-click confirmation. Owners cannot silently leave; a single-owner enterprise
  can be dissolved only after confirmation and only when no active/overdue enterprise loan or pending loan request exists.
  Dissolution atomically marks the enterprise, clears its corporate account/current assets and cancels pending joins
  before the remaining balance is returned. Multi-owner enterprises require ownership adjustment rather than unilateral
  dissolution.
- Dividend tax accepts both fractional (`0.10`) and legacy percentage (`10`) storage, normalizes to `0..1`, and calculates
  `tax = gross * rate`, `net = gross - tax`. Both enterprise-manager and administrator-Web distributions write the
  dividend header, per-recipient gross/tax/net payout rows and a `DIVIDEND_TAX` tax record. The in-game history shows
  tax rate, tax paid, net amount and payout count.
- Administrator enterprise editing now covers name, description, type, region, industry, owners, registered capital,
  corporate balance, dividend percentage, status and level. Ownership rows, corporate-bank mirrored assets, member
  count, dividend shares and the level cache are synchronized by the edit workflow.

## Official Warehouse And Rankings (2026-07-17)

- `/kseco gui` slot 30 now exposes an administrator-only official warehouse GUI. It asynchronously pages immutable raw
  rows, decodes ItemStacks on the server thread, checks inventory capacity, atomically claims a row and restores it if
  delivery cannot complete. Player temporary storage remains a separate slot and metric.
- Admin Web market statistics now distinguish `storedItems` from `officialWarehouseItems`; the official warehouse card
  no longer labels player temporary storage as official inventory.
- Personal Top50 filters system, console and central-bank identities. Rank sub-tabs explicitly load their own data and
  set `个人财富 / 企业财富 / 银行资产 Top50` titles, preventing a stale central-bank title on the personal ranking.

## Spark Performance Incident (2026-07-17)

- Two unchanged-configuration Spark samples confirm sustained main-thread overload, not a GC pause or a
  one-off spike. `uVylazNECl.sparkprofile` ended at 16.84 TPS / 59.34 ms mean MSPT with 3,901 entities;
  `rcWHSIi3AJ.sparkprofile` ended at 14.41 TPS / 69.37 ms despite only 3,041 entities.
- The highest actionable regression is hopper activity plus CoreProtect logging: native hopper push work rose
  from about 152k to 182k sampled ms, CoreProtect aggregate time from about 59k to 116k, and its inventory-move
  listener from about 20k to 36k. Disable `hopper-transactions` in
  `test_1_21/plugins/CoreProtect/config.yml` before considering a logger replacement, then collect another
  comparable 10-minute Spark profile.
- FotiaEnchantment normalization remains a secondary main-thread cost but fell between samples; ItemsAdder,
  Multiverse and ksHWP stayed broadly level. Heap increased from 2.66 GiB to 5.09 GiB with no old-generation
  collection, so heap/GC tuning is not the first intervention. Loaded chunks rose to about 6.3k and tile entities
  remained about 8.6k; bot/loaded-chunk and entity caps are follow-up work after hopper logging is tested.

## Local Web Test Bench

- `web/test.html` embeds the exact production admin/player pages with localhost-only fixtures.
- It supports normal, empty, API-error and slow scenarios plus refresh and separate-window controls.
- Start it with `scripts/start-web-test.ps1`; the current server is `http://127.0.0.1:8788/test.html`.

## Extra Module Safety

- `ExtraModuleLoader` uses concurrent module state.
- Modules become Web-visible only after successful `onEnable`.
- Modules are hidden from Web before `onDisable` during reload.
- Failed modules are removed and their classloaders are closed.
- Player Web bootstrap intersects feature gates with actual module availability.
- Admin pages retain management views and render missing modules as offline/empty states.

## Purchase Order Async Settlement (2026-07-17)

- `PurchaseOrderManager.createAsync`, `fulfillAsync`, and `cancelAsync` replace the former synchronous GUI paths.
  Bukkit item snapshots, inventory mutation, messages, sounds and all Vault calls remain on the server thread.
- The serial database lane inserts orders and atomically reserves finite-order quantity together with rows in the
  private `ks_eco_purchase_order_pending_items` table. After main-thread inventory/Vault settlement, a worker atomically
  promotes those rows into buyer storage, preventing the buyer from claiming delivery before the seller is settled.
  Worker tasks carry only primitive, byte-array and immutable collection data; ItemStack decoding stays on the server thread.
- Fulfillment rechecks the seller's current main-hand item before delivery. Offline sellers, changed items, failed
  unlimited-order charges and failed seller deposits trigger idempotent storage/quantity compensation. A concurrent
  cancellation that already refunded the visible remainder causes the reserved slice to be refunded separately.
- Purchase-order GUI operations have per-operation in-flight guards and explicit bounded-queue rejection feedback.
  A failed cancellation refund attempts to reactivate the order instead of silently leaving it cancelled.
- The broader durable settlement journal remains required for a process crash between a committed SQLite reservation
  and the following main-thread Vault/inventory settlement. No live acceptance test has been run yet.

## Next Priorities

1. Deploy and live-test ks-InstanceWorld plus a normal combat dungeon: prepare, marker spawn, entry, Boss clear,
   timeout, last-player leave, repeated force release and plugin-disable recovery.
2. After the user restarts Paper, live-test enterprise join approval/rejection, voluntary leave, manager removal,
   single-owner dissolution blocks, 10% dividend tax and payout/tax logs, full administrator editing, enterprise levels,
   blind-box level rejection, land-perk multipliers, the official warehouse GUI, and personal Top50 filtering.
3. Remove synchronous SQL and legacy draw settlement from enterprise blind-box GUI paths, then move limited-sale
   detail metadata reads off the server thread. Admin pool/loot reads and Web loot lore decoding are separated.
4. Convert bank, enterprise, bidding, invites, real-estate, tax and enterprise blind-box GUIs/Web actions to async DTO loading.
5. Add a durable settlement journal for the SQLite-to-external-Vault crash window.
6. Add operation-specific overload responses for bounded executor rejection so interactive requests always clear
   in-flight state and return a player/Web error under extreme queue pressure.

## Verification Baseline

- `mvn clean package -DskipTests` passes in `ks-Eco`.
- All split Web JavaScript modules pass `node --check`.
- The Web asset checker passes all 22 JavaScript files and local references.
- `mvn clean package -DskipTests` passes with the compensation manager and GUI.
- `mvn test` passes but reports no test sources. `mvn clean package -DskipTests` and the 21-script Web asset
  checker pass after the limited-sale/player-blind-box async refactor. The affected in-game paths were statically
  audited but not live-tested because Paper was not started.
- The project currently has no automated Java test sources for ks-Eco or ks-Eco-RealEstate.
- On 2026-07-17, local `mvn test` compiled all 62 ks-Eco sources successfully and reported no tests after the
  final 13-handler async-chat sweep and the purchase-order create/fulfill/cancel async transaction refactor.
  Targeted `rg` review found no remaining synchronous purchase-order GUI mutation entry. It was not packaged,
  deployed or live-tested; no JAR was replaced and Paper was not started or restarted.
- On 2026-07-17, local `mvn test` compiled all 63 ks-Eco sources successfully after the blind-box admin/Web DTO
  loading refactor and again reported no test sources. No package, deployment, JAR replacement, or Paper start occurred.
- On 2026-07-17, the final enterprise/governance, official-warehouse GUI and ranking integration pass compiled all
  63 ks-Eco sources plus 5 ks-Eco-enterprise sources with `mvn test`; both modules still have no test sources. Four
  touched admin scripts passed `node --check`. Browser desktop QA verified personal/enterprise rank switching, correct
  Top50 titles/data, official-warehouse statistics and the expanded enterprise edit panel. The configured
  `scripts/check-web-assets.ps1` was unavailable in this workspace, so that aggregate check could not be rerun.
  No package/deployment/JAR replacement occurred, and Paper was not started or restarted.
- On 2026-07-17, the source and paired module READMEs were committed as `cdd3d77`. GitHub Actions run `29593918604`
  built all configured Maven modules successfully and uploaded `ks-series-jars`; documentation-language run
  `29593917909` also passed. The downloaded Actions artifacts were deployed through `scripts/deploy-plugin.ps1`:
  ks-Eco hash `8AD0F282BFC6D9724DBC149EB6BB069883B4F0C4351732C654481BB071FD5D1E`, backup
  `ks-Eco-20260717T155633323Z-05ade93988e5`; enterprise hash
  `3949DA5BC1C90F752274B9802761A725F74E633147DB5207310702306E3CFD66`, backup
  `ks-Eco-enterprise-20260717T155652733Z-e14aca5dfab3`. Source/deployed hashes and both append-only index records
  were verified. Paper was not started or restarted; all affected game paths remain pending live acceptance.
- `mvn clean install` passes for ks-Eco and `mvn clean package` passes for ks-Eco-RealEstate. Browser desktop QA
  verified enterprise level editing, pool requirements, player lock states and all four Web test scenarios.
- `CODEBASE_MAP.md`, the economy knowledge base, `KS-SERIES-REPORT.md`, and the player README are synchronized
  with the retired-ticket and enterprise-level behavior; no documentation follow-up remains for this change.

## Documentation Language Maintenance

- Published Markdown now has a Chinese and English companion. Default Chinese documents use `.en.md`; documents whose
  primary content is English use `.zh-CN.md`.
- Each published pair should expose a top-of-page GitHub Markdown link to the other language. The language index is
  `docs/DOCUMENTATION.md` / `docs/DOCUMENTATION.en.md`.
- `.github/workflows/docs-language-check.yml` checks the required pair inventory on Markdown changes, pull requests,
  manual runs, and a weekly schedule. Codex should also recheck the inventory when syncing source and publishing
  checkouts or when a new document is added.
