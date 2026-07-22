# Codex Project Memory

> [English](CODEX_MEMORY.en.md) | 中文

Last updated: 2026-07-22 Asia/Hong_Kong

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
- SHA-256: `5AE04AEFF9B356F01D56286EC55C22E6B5D83ECC3AB01873E3F0BC90D2681FA4`
- Root backup ID: `ks-Eco-20260721T060541396Z-911dfdcdcb11`
- Root backup: `backup/ks-Eco/ks-Eco-20260721T060541396Z-911dfdcdcb11.jar`
- Enterprise Extra: `test_1_21/plugins/ks-Eco/extra/ks-Eco-enterprise-1.1.0.jar`
- Enterprise SHA-256: `3949DA5BC1C90F752274B9802761A725F74E633147DB5207310702306E3CFD66`
- Enterprise root backup ID: `ks-Eco-enterprise-20260717T155652733Z-e14aca5dfab3`
- Enterprise root backup: `backup/ks-Eco-enterprise/ks-Eco-enterprise-20260717T155652733Z-e14aca5dfab3.jar`
- RealEstate Extra: `test_1_21/plugins/ks-Eco/extra/ks-Eco-RealEstate-1.1.0.jar`
- RealEstate SHA-256: `0EAC1DE4D186FB106026DE4F3C9D4D18869003FAF5E534F09114756203B4C6BE`
- RealEstate root backup ID: `ks-Eco-RealEstate-20260721T050948609Z-7c121e381779`
- RealEstate root backup: `backup/ks-Eco-RealEstate/ks-Eco-RealEstate-20260721T050948609Z-7c121e381779.jar`
- 当前 Eco/RealEstate 制品与部署目标哈希一致；Paper 已于 2026-07-21 重启并完成玩家/管理地产网页验收。
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
- `docs/survival-rpg-knowledge-base/future-development.md` is the durable content blueprint: survival identity
  stays primary, story state remains separate from combat proof, class naming waits until after the first
  cooperative dungeon, and every release is gated by performance, economy, readability, and cleanup evidence.
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

## GUI Interaction Fix (2026-07-18)

- Player-facing destructive or batch shortcuts that previously required `Shift+右键` now use middle-click instead.
  Covered surfaces: limited-sale batch buy x10, purchase-order cancel, enterprise member removal, limited-sale admin
  delete, compensation plan delete, and blind-box admin pool delete.
- Limited-sale list lore and player README now document middle-click for x10; preview page still accepts
  `Shift+左键`/middle-click for x10 and `Shift+左键` for whole-box purchase.
- Market and temporary storage menus now expose a first-page left-nav return to the economy main menu; other nested
  GUIs already returned via slot 49.
- Web desktop scripts: all 22 JavaScript modules still pass `node --check`. No web return-path defect was found in
  side-sheet close, shell hub activation, or the real-estate 3D-to-2D return control.
- `mvn clean package -DskipTests` compiled all 63 ks-Eco sources successfully after the rebind.
- Deployed to `test_1_21/plugins/ks-Eco-1.1.0.jar` with SHA-256 `BA04D9CBB35DA685D7D0388C350FCF79028BBFACF8E9430886EA3EB1C4BD6AC0` and root backup `ks-Eco-20260718T095704512Z-4a697ee04c50`. Paper was not started or restarted; live acceptance awaits a user restart.

## Multi-Database, Cross-Server And Currency Foundation (2026-07-18)

- `ks-core` now owns a configurable HikariCP-backed JDBC connection layer for SQLite, MySQL, MariaDB and
  PostgreSQL while preserving `KsDataStore.getConnection()`. Remote database failure is fail-fast unless
  `database.fallback-to-sqlite` is explicitly enabled; a fallback node is marked local-only and must not be
  treated as cross-server storage. The core schema is portable, but most existing ks-Eco business SQL still
  contains SQLite-specific DDL/upsert/query syntax and has not been certified against a live remote database.
- `ks-Eco/database/EcoDatabase` adds a stable server ID, per-start instance ID, database-authoritative heartbeat,
  payload-bound operation claims and retained fenced leases. Duplicate live server IDs fail closed. Release and
  shutdown retain coordination rows so fencing tokens stay monotonic across ownership changes.
- `ks-Eco/crossserver/` now contains immutable event, inbox/outbox, heartbeat, cache invalidation, database polling,
  SQL-dialect and distributed-lease contracts. This is coordination infrastructure, not a claim that every economy
  workflow is cross-server safe. `cross-server.enabled` defaults to false, and startup rejects an attempted enable
  while the business-mutation fencing constant remains incomplete. Ordinary market settlement now has its own durable
  journal, but balance, storage, limited-sale, blind-box, enterprise and price-engine mutation fencing/invalidation
  still must be wired before shared cross-server runtime may be enabled.
- `ks-Eco/currency/` adds exact minor-unit currency definitions, a portable JDBC balance/ledger with global
  idempotency, exclusive or alternative-currency payment rules and configured one-way exchange rules. `CASH`
  remains owned by the existing Vault/builtin economy path until a durable bridge is implemented; do not create a
  second live CASH balance in the new ledger. Listings and limited-sale rows now persist `currency_id`, default old
  data to `CASH`, and reject non-CASH settlement before Vault charging until the currency runtime is connected.
- No plugin JAR was deployed and Paper was not started. Local verification passed for ks-core, ks-Eco,
  ks-Eco-bank, ks-Eco-enterprise, ks-Eco-politic, ks-Eco-RealEstate, ks-Eco-RealEstateDungeon, ks-Eco-tax,
  ks-Cinematic, ks-RPG/GUI, ks-Skill, ks-Sentinel, ks-Inherit, KS-ItemEditor, KS-ItemSteal and ks-Title.

## Parallel Reliability Pass (2026-07-18)

- `ks-Eco-bank` now uses configurable loan bounds, fixed approval quotes, atomic approval/asset updates,
  overdue limits, main-thread Vault gateways and compensating rollback when guided-loan delivery fails.
- `ks-Eco-enterprise` routes Bukkit/Vault work to the server thread and journals dividend settlement as
  `PENDING/PAID/COMPENSATED/COMPENSATION_REQUIRED`, blocking further dividends after an uncertain result.
- `ks-Eco-tax` normalizes legacy percentage rates, separates industry-rate persistence, refreshes shared-database
  snapshots, validates finite tax bases and uses idempotent asynchronous audit plus refund on final write failure.
- `ks-Eco-RealEstate` moves player plot/trust GUI reads and writes to database DTO tasks, adds stale-callback and
  in-flight guards, and replaces per-protection-event player trust SQL with immutable cache snapshots.
- `ks-Eco-RealEstateDungeon` makes terminal lifecycle cleanup transactional and retryable, and releases pending and
  active instance-world handles during plugin disable. Revive validation/refund, ticket charge/admission/refund journal
  recovery and per-reward completion retry are implemented; live restart, offline proof and instance recovery still
  require acceptance testing.
- `ks-RPG` now fails closed on invalid/unknown proof-gate references and swaps material, progression and combat
  catalogs only after the complete reload validates. `ks-Cinematic` now accounts for and releases pending prepares.
- `ks-Skill` excludes Leaves ServerBots from automatic/interval skill execution. `ks-Sentinel` and `ks-Inherit`
  serialize database lifecycle/work; item tools and title handling gained permission, path, amount and GUI bounds.


## Bug Hunt Handoff (2026-07-18)

- Cross-session handoff for the current Bug Hunt stop point:
  docs/BUG-HUNT-HANDOFF-2026-07-18.md.
- Batches 1-4 are code-fixed and documented; no JAR deploy and no Paper start.
- Resume from the remaining open list in that handoff unless the user redirects.

## Bug Hunt Fixes (2026-07-18)

- `ks-Eco-RealEstateDungeon` revive now validates ACTIVE/WAITING instance membership and DEAD participant
  state before Vault withdraw; `recordRevive` requires a successful DEAD→ALIVE update and refunds on
  persistence failure.
- Dungeon completion rewards use `reward_status` (`NONE/PENDING/GRANTED`). Terminal commit marks PENDING,
  retries keep the reward roster, and successful grant flips to GRANTED. Per-player reward failures no
  longer abort the rest of the party.
- `ks-Eco-enterprise` stamps dividend settlement ownership with EcoDatabase server/instance IDs. Startup
  recovery only rewrites this node's PENDING rows. `hasUnresolved` also blocks on PARTIAL/SETTLING/
  COMPENSATION_REQUIRED dividend headers so partial payout retries cannot re-pay successful recipients.
  Vault gateway futures are cancelled on timeout.
- `ks-Eco-bank` inserts loans as `PENDING_PAYOUT`, activates only after wallet credit, and rolls unpaid
  loans back on plugin start.
- `ks-Eco-tax` treats connection/runtime failures as audit failure and still schedules refunds.
- `ks-Inherit` claims `APPROVED → DELIVERING → DELIVERED` before inventory mutation to prevent duplicate
  delivery after a status-update failure.
- Verified: `ks-Eco-RealEstateDungeon` `mvn test` (3/3), package for enterprise/bank/tax/Inherit.
  No JAR deployed and Paper was not restarted.
- Bug Hunt batch 2 security/data-loss P1: HWP annotation search token + public/own filter; `/api/debug` and
  `/api/clear-cache` require admin; TileStore path normalize/startsWith; ItemEditor refine session overwrite
  blocked and template load preserves amount/durability/body; Title IA upload decode size bounds; RealEstate
  cache refresh keeps old cache and PlotProtectionListener fails closed when unhealthy.
- Bug Hunt batch 3 reliability P1: `ks-InstanceWorld` `finishRelease` frees a grid only when `occupied_by`
  still matches the releasing instance, so delayed A release cannot free B's reused grid; paste/cleanup uses
  the union of arena radius and schematic paste bounds. `LimitedSaleManager` commits stock with
  `sold+qty<=total_stock`, pins the charge/refund cash backend (Vault vs Builtin), and reports refund failure
  instead of claiming success after a backend switch. `ks-RPG` proof gates reject empty/non-list
  `required-proofs`, and `/ksrpg reload` validates a candidate `config.yml` before swapping live catalogs.
- Batch 3 verified: `ks-InstanceWorld` `mvn test`, `ks-RPG` `mvn test`, `ks-Eco` compile. No JAR deployed and
  Paper was not restarted.
- Bug Hunt batch 4 local reliability P1: politic `SENATE_VOTING` only auto-finalizes when remaining
  ballots cannot reverse an absolute majority; `ks-Sentinel` requeues failed audit flush batches;
  KSBot rejects Leaves infinite state actions (`sneak`/`swim`/`move`) when infinite actions are
  disabled. Verified: politic/Sentinel/Compat compile. No JAR deployed and Paper was not restarted.
## Full Build And Documentation Closeout (2026-07-18)

- Java 21 (`21.0.10`) and Maven 3.9.16 were used to run `mvn clean test` followed by package/install for all 23
  Maven modules in dependency order. Every module succeeded; no source fix was required.
- Surefire/JUnit executed 17 tests with zero failures/errors/skips: ks-core 4, ks-InstanceWorld 4, ks-RPG 3,
  ks-Eco-RealEstateDungeon 3 and ks-Skill 3. The other 18 modules reported zero framework tests.
- ks-Eco's five dependency-free `main` contract suites were executed separately and passed: currency compatibility,
  cross-server event/lease contracts, SQL self-test, SQL dialect contracts and database polling contracts.
- Static verification passed for all 22 ks-Eco Web JavaScript files, 241 non-runtime source/config YAML files,
  23 primary JARs with 80 source resources, 25 local Web references, 23 production Web assets and 46 required
  bilingual documentation pairs after indexing the 2026-07-18 Bug Hunt report.
- Residual warnings are non-blocking: ks-core/ks-InstanceWorld Shade overlap and module-descriptor warnings;
  ByteBuddy dynamic-agent warnings in ks-core tests; project-local `systemPath` warnings in ks-RPG-Gui and
  ks-Cinematic; and deprecated Paper/Bukkit APIs in ks-Cinematic, ks-Skill, ks-Title, ks-Inherit and ks-Sentinel.
- No JAR was deployed, no backup was created, and Paper was not started or restarted. No live MySQL, MariaDB or
  PostgreSQL integration suite was available, so remote-database business SQL, P0 cross-server runtime wiring,
  non-CASH settlement and external Vault/item-delivery crash journals remain unverified/open.
- Root and relevant module READMEs now describe the multi-database, cross-server, currency, bank, enterprise, tax,
  real-estate, dungeon, RPG and utility reliability foundations without claiming unfinished runtime integration.

## Concurrent Reliability And Gameplay Expansion (2026-07-18 to 2026-07-19)

### Currency And Test Integrity

- Currency identifiers remain canonical uppercase, while account IDs and idempotency operation IDs are deliberately
  case-sensitive. MySQL/MariaDB currency tables and migrations now use `utf8mb4_bin` for identifier columns so their
  database keys match the Java equality contract; SQLite and PostgreSQL retain their native case-sensitive semantics.
- One-way exchange fees are calculated in exact target-currency minor units and rounded upward. Splitting one exchange
  into smaller requests cannot reduce the total fee, and every accepted quote enforces `gross = fee + net` without
  floating-point settlement drift. Invalid, unavailable or non-spendable tender fails closed without falling back to
  `CASH`; changing the selected tender while replaying the same idempotency key produces a conflict.
- `ks-Eco` now uses JUnit Jupiter 5 and Surefire. The five former dependency-free `main` contract programs were
  converted into 24 counted JUnit tests, eliminating the previous Maven fake-green condition where `mvn test`
  succeeded with zero framework tests. They coexist with the new ledger, payment, demand, liquidation and major-order
  suites rather than replacing them.

### BotGuard And Banking

- `ks-BotGuard` now reconciles MythicLib/MMOCore listener wrappers every 20 ticks and reacts to plugin enable/disable.
  It removes stale wrappers, deduplicates reload registrations, restores an original listener only when its wrapper is
  still live and its owning plugin remains enabled, and cancels pending reconcile work during shutdown. An internal MMO
  plugin reload can still leave a protection gap of at most 20 ticks before the next reconciliation pass.
- Bank loan payout now follows `PENDING_PAYOUT -> PAYOUT_SETTLING -> ACTIVE` for direct, guidance and approved loans.
  A restart no longer assumes every unfinished payout was unpaid: uncertain external-wallet results become
  `RECONCILE_REQUIRED` instead of deleting the loan and restoring bank assets, closing the free-money recovery path.
- Loan gameplay now has a 300-850 credit score, A-E tiers, dynamic amount and term limits, risk and term spreads, and a
  fixed quote/confirmation contract. Requests persist the quote components, total repayment, score and tier; completed
  and overdue history is retained for later scoring.
- Deposit interest is now based on exact balance-time slices in `CASH` minor units. Deposits, withdrawals,
  failed-withdrawal restoration and periodic maintenance settle the old observed balance first; completed periods use
  deterministic keys and persisted postings, CAS-protected state, average-balance records and half-even rounding.
  Direct out-of-module account credits are treated as arriving when the bank next observes them, so they cannot earn
  unverifiable historical interest. Existing accounts initialize this state at first touch without historical backpay.
  Explicit rate-change entry points are not yet wired to settle every account at the exact change timestamp; the stored
  rate changes on the next account settlement, so a full rate-management wiring pass remains open.
- 企业还款已使用旧余额/旧状态 CAS，央行贷款手动还款与定时回收共用 `OPEN -> CLAIMED -> REPAID`
  原子认领；违约处理也会在没收产权或创建拍卖前认领 `DEFAULTING`。仍未关闭的银行风险是竞价资金 escrow、
  旧竞拍者退款 journal、拍卖成交 claim、`PROJECT_CONTRACT` 产权交割，以及信用模型的 Web/GUI 展示。
- 央行注资 API、旧兼容重载和 Web 表单均统一为 `LOAN`；后端禁止的 `GRANT` 不再作为默认管理员操作展示。

### Economy, Property And Dungeon Loops

- Real-estate pricing remains backward compatible with legacy `FLAT` zones and adds optional `PER_BLOCK` pricing,
  minimum prices, per-plot area caps, player/enterprise soft and hard owned-area limits, and linear soft-limit
  surcharges. Only permanent plots count toward holdings; dungeon plots are excluded. Coordinate, area, aggregate and
  price overflow fails closed. Player purchases quote before charging and re-read policy, holdings and price inside the
  write transaction; enterprise charging and the final eligibility check remain in one transaction. No automatic land
  seizure was added, and the Web UI does not yet expose the new policy controls.
- 企业房产不再允许普通成员经个人市场出售并把房款打入个人钱包；在企业公户结算模型完成前，该路径失败关闭。
  限时销售在扣款后的数据库队列拒绝时固定使用原支付后端退款并清理进行中状态。仍开放的边界包括内置经济
  退款结果不可确认、主线程回交失败，以及已提交库存/个人额度缺少持久化补偿 journal。
- Dungeon tickets now persist charge, entry and refund stages and recover them after restart. Bukkit/Vault operations
  stay on the server thread, SQL stays on the database worker, unknown external outcomes become `REVIEW_REQUIRED`, and
  safely charged but never-entered tickets can be refunded idempotently. Party membership and active-instance mappings
  are frozen and checked before charging.
- Dungeon completion rewards now freeze a per-player, per-reward-key plan. Money, commands, MythicItems and MMOItems
  receive stable content-hash keys; probability is deterministic by instance/player/key; `GRANTED` entries never replay;
  explicit failures become retryable and unknown external outcomes remain pending for review. Startup resumes completed
  instances with pending rewards, with bounded automatic retries and fail-closed behavior if the configured reward plan
  changed. 多 Boss 现在必须全部死亡才完成，`LEFT` 玩家不进入奖励 roster；离线 proof、概率跳过或 proof
  未实际写入时不会标记 `GRANTED`，而是保留复核/重试状态。离线 proof 仍需直接、持久化的
  `RpgProgressionApi`/outbox 交付，当前不能宣称自动补发完成。
- Official warehouse liquidation now exposes each eligible warehouse row as one finite `CASH` lot with original item
  snapshot, quantity and acquisition cost. Lot and purchase versions prevent overselling; reserve, pay, deliver, release
  and refund transitions are idempotent, and unfinished `RESERVED`/`PAID` purchases are recoverable. Official sweep
  acquisitions must first move from `PENDING_SETTLEMENT` to `AVAILABLE` after payout confirmation, and liquidation-locked
  rows cannot be removed through the legacy admin claim/delete path.
- The demand-campaign store provides finite campaign targets/budgets, per-player quotas, idempotent operation payloads
  and CAS-protected concurrent progress. It is `CASH`-only and `demand.enabled` remains false by default; no scheduled
  tasks, UI, Vault settlement or player-facing campaign has been wired.
- `MajorOrderManager` recognizes the default-disabled `RPG_PROJECT` read-model metric. It reads only finite,
  non-negative absolute progress from an externally installed source keyed by order/project ID. Submitted and legacy
  `manual_value` data is ignored and persisted as zero for this metric; unavailable sources cannot auto-complete an
  order. The manager does not grant rewards or money.

### RPG Content And Seasons

- Configured world drops now rotate fairly among combat participants instead of always rewarding the final killer.
  Eligible recipients must be online, non-spectators, in the same world and within 64 blocks. Combat content loading
  rejects unknown or category-mismatched mechanics, discovers all five bundled `content/**/*.yml` categories in the
  JAR, and extracts missing defaults without overwriting server edits.
- The second-wave staging package adds low-frequency `KS_Cinderback_Whelp` and
  `KS_Stormwatch_Thunderbird` elites, four telegraphed skills, RandomSpawns rates of `0.00035`/`0.00025`, ten-minute
  hard lifetimes and no helper mobs. The third-wave design package adds 14 MMOItems, four ks-RPG content files, four
  proofs, seven gates, three dungeon templates and encounter/unlock contracts. Third-wave entry gates and breakthrough
  exchanges remain metadata/design contracts; the dungeon runtime and an atomic material/CASH/proof transaction do not
  enforce them yet.
- The season foundation is a pure Java/SQLite domain with seasons, regional reputation, weekly caps and catch-up credit,
  monotonic absolute event snapshots, idempotent project source keys, explicit reward-claim states and non-destructive
  archives. `season.enabled` is false by default. When explicitly enabled, only the dedicated `ks-rpg-season-db` worker
  initializes `plugins/ks-RPG/season.db`; it does not create a season, schedule events, grant rewards or mutate players.
  `RpgSeasonStatusApi`, cached status access and `/ksrpg season status` expose runtime state without I/O on callers.
- SeasonService 现按服务端时间校验 `startsAt <= now < endsAt`，调用方周索引必须匹配服务端按七天计算的结果；
  InMemory/JDBC 进度写入和归档会在同一季节锁/事务中复核状态，阻止归档后写入。运行时仍默认关闭，且没有
  自动赛季编排、世界事件、奖励发放或 Paper 实机验证。

### Staging Boss Packages

- `ashen_foundry_overseer-20260718` is a three-phase foundry encounter with a two-valve pressure split, five-hit teammate
  rescue and eight-hit core interrupt. All major attacks have at least a 1.5-second orange telegraph; successful team
  responses open stun/weakness counterattack windows. Its proof hook is `ashen_foundry_clear`; the sample `CASH 300`
  and RPG material reward are staging placeholders, not MythicMobs-issued production settlement.
- `aurora_packwarden-20260718` is a four-player frost encounter built around four simultaneous wind runes, a fixed
  `2+2` warm-furnace split and three ice-fang intercepts by different players. It provides the
  `aurora_packwarden_clear` proof, low-value `CASH 250`, existing field materials and a separate breakthrough proposal;
  the proof/gate and dungeon reward snippets still require manual integration.
- `stormforge_overseer-20260718` is a three-phase storm encounter with line/spread telegraphs, three grounding coils,
  paired pressure valves, alternating safe zones and a nine-minute soft enrage. The package exposes
  `stormforge_overseer_clear` plus a low `CASH 50`/existing-material reward hook and deliberately performs no direct
  MythicMobs money, item or proof delivery.
- All three packages are isolated staging content with parsed YAML/JSON, closed static Mob/skill references and explicit
  proof/reward integration snippets. None has been copied into `test_1_21`, reloaded with MythicMobs or play-tested.
  Instance ownership for helper mobs, phase-lock/AI cleanup, projectile/target semantics, particle budgets, ModelEngine
  presentation, party-size sampling, wipe cleanup, offline proof durability and numerical balance remain live-test work.

### Verification And Runtime Limits

- 最终 Java 21 依赖顺序矩阵覆盖全部 23 个 Maven 模块，`clean test package` 全部成功。11 个有测试模块共
  187 项测试且 0 failure/error/skip：`ks-core` 4、`ks-Eco` 71、`ks-InstanceWorld` 5、`ks-RPG` 45、
  `ks-BotGuard` 2、`ks-Skill` 3、`ks-Eco-bank` 22、`ks-Eco-enterprise` 1、`ks-Eco-RealEstate` 9、
  `ks-Eco-politic` 8、`ks-Eco-RealEstateDungeon` 17；其余 12 个模块当前没有 Surefire 测试。
- Web 外部 JavaScript 28/28 与 HTML 内联脚本 6/6 通过语法检查，严格 YAML 311/311 通过；五个新增内容包
  的 47 个 YAML、6 个 JSON 值、UTF-8、重复 ID 和引用闭合检查全部通过。旧的未引用 `test_token.json`
  格式损坏，是唯一静态 JSON 警告。静态通过不代表 MythicMobs、ModelEngine、MMOItems、Vault 或 Paper 实机通过。
- No JAR or content package from this concurrent pass was deployed, no deployment backup was created, and Paper was not
  started or restarted. No MySQL, MariaDB or PostgreSQL server was used. Remote-database migrations, locking semantics,
  cross-server settlement, non-`CASH` live payment, external Vault/item crash recovery, GUI flows, boss encounters,
  dungeon offline rewards and season runtime behavior therefore remain unverified in a real server.

## Next Priorities

1. Deploy and live-test ks-InstanceWorld plus a normal combat dungeon: prepare, marker spawn, entry, Boss clear,
   timeout, last-player leave, repeated force release and plugin-disable recovery.
2. After the user restarts Paper, live-test enterprise join approval/rejection, voluntary leave, manager removal,
   single-owner dissolution blocks, 10% dividend tax and payout/tax logs, full administrator editing, enterprise levels,
   blind-box level rejection, land-perk multipliers, the official warehouse GUI, and personal Top50 filtering.
3. Finish the live remote-database migration: replace remaining SQLite-only ks-Eco SQL by dialect-aware statements,
   then run disposable MySQL/MariaDB/PostgreSQL integration suites before enabling shared production storage.
4. Wire the cross-server JDBC repository/poller, cache invalidation and fenced settlement ownership into the P0
   balance, market, storage, limited-sale, blind-box, enterprise and price-engine call sites.
5. Connect the currency runtime to commands/GUI and limited-sale/market settlement. Keep CASH on Vault, add a durable
   journaled bridge for configured special-currency-to-CASH conversion, and expose explicit exclusive-currency prices.
6. Remove synchronous SQL and legacy draw settlement from enterprise blind-box GUI paths, then move remaining
   limited-sale and enterprise/tax GUI detail reads off the server thread.
7. Add durable settlement journals for the SQLite/shared-database-to-external-Vault and item-delivery crash windows.
8. Add operation-specific overload responses for bounded executor rejection so interactive requests always clear
   in-flight state and return a player/Web error under extreme queue pressure.

## Verification Baseline

- `mvn clean package -DskipTests` passes in `ks-Eco`.
- All split Web JavaScript modules pass `node --check`.
- The Web asset checker passes all 22 JavaScript files and local references.
- `mvn clean package -DskipTests` passes with the compensation manager and GUI.
- `ks-Eco` now has 64 counted JUnit tests and `ks-Eco-RealEstate` has 9. Older entries below that report no test
  sources are retained as dated historical verification records, not the current baseline.
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

## 2026-07-19 基础设施、P0/P1 与经济收口

- `JdbcDatabaseTransportStore` 使用数据库分配的单调发布序号，不再用业务时间复合 cursor；`DatabasePollingTransport` 以 `serverId/consumerId` 隔离 cursor，CAS 冲突重载持久位置，迟到事件、广播竞争和未来事件缺口已有 SQLite 合同测试。
- `TransportEvent` 按 payload 字节内容实现 equals/hashCode。缓存失效不再以跨节点 HLC 大小丢弃唯一事件，监听失败会释放去重标记允许同 event ID 重试；消息物理创建时间与 HLC 版本时间分离。
- cross-server lease 和 `EcoDatabase` lease 在 release/shutdown 时提升 fencing token，释放前启动的旧事务不能续租或执行 fenced 回调。
- 此阶段的 `cross-server.enabled` 默认关闭，完整接线常量仍为 false，管理员误开会让 ks-Eco 启动失败；这是运行时接线前的历史快照，已由下方“跨服运行时接线收口”状态取代。
- 普通玩家市场使用 `MarketPurchaseSettlementStore` 持久化买家扣款、库存/隐藏暂存、卖家入账、最终交付和退款；`MARKET_PENDING` 不会出现在暂存列表，也不能领取、删除或过期清理。启动恢复续跑确定状态，外部钱包中断进入带 `review_stage` 的 `REVIEW_REQUIRED`。
- 个人工程保证金与预付款由 `ProjectWalletSettlementStore`/`ProjectWalletSettlementService` 记录扣款、托管、发放、最终提交和补偿。确定状态会在启动时续跑，扣款或发放结果未知时进入人工复核；企业 Web 发布、公户 escrow、企业保证金、项目/投标状态和企业预付款仍在同一数据库事务提交。
- 房产市场由 `PropertyMarketSettlementStore` 持久化买家扣款、条件产权转移、卖家付款和退款；`active_house_id` 唯一槽与挂牌原子 claim 阻止同一房屋并发挂牌/成交。个人卖款继续走 Vault journal；企业房产要求 `MANAGE_PROPERTY`，挂牌/成交前复核权限，并通过 `EnterpriseFundSettlementProvider` 将企业公户、开户行镜像和 journal `FINALIZED` 在同一 SQL 事务提交，失败整笔回滚，企业房款不会进入成员个人钱包。
- 管理端提供 `GET /api/admin/settlements/review` 与 `POST /api/admin/settlements/resolve`，统一列出并按 `review_stage` CAS 处理工程、普通市场、个人房产和银行个人贷款还款的不确定外部结果；银行通过 `BankAccessProvider` 桥接 `LoanRepaymentSettlementStore`。Web 管理页只展示当前阶段允许的确认动作。人工操作必须先核对外部钱包/产权事实，不能把 `REVIEW_REQUIRED` 当作自动重试队列。
- `BusinessSchemaDialect`/`PortableSqlMutation` 已覆盖普通市场、限时销售、个人工程、房产、采购单、官方清算、内置经济、Web、价格、兑换、重大订单、补偿、银行、企业、政治、地产和副本的目标 schema/upsert。`BuiltinEconomy` 余额增减采用原子更新，创建账户不再清零已有余额；事务内用 savepoint 处理唯一键竞争且不吞非约束错误。`KsEco` 核心迁移先于管理器、定时任务和 Web 恢复执行，失败会停用插件；央行单例槽避免并发创建多个中央银行，默认配置不再覆盖管理员值。除 SQLite/H2 方言合同外，测试会实际启动本机原生 PostgreSQL 与 MariaDB，重复初始化核心业务、结算、跨服协调、多货币账本和需求活动表并完成余额读写；真实 MySQL、外部存量远程库迁移和生产锁语义仍未验证。
- 银行抵押拍卖已有 escrow、退款 journal、`SETTLING` 成交 claim 和 `PROJECT_CONTRACT` 唯一企业交割；个人贷款还款新增扣款、入账、退款、恢复和人工复核 journal，企业还款与违约处置使用 CAS/`DEFAULTING` claim，央行零应还历史贷款可以结清。限时销售的内置经济结果、主线程回交和库存/个人额度补偿已改为可确认或持久恢复；其他旧外部结算边界仍以 Bug Hunt/Handoff 的最新开放项为准。
- 副本付费复活由 `DungeonDeathHandler`/`DungeonReviveStore` 的 `CHARGE_READY/CLAIMED -> PAID_PENDING -> RETURNED` 与退款状态机闭环；SQL 全部异步，Vault、GameMode、血量和安全检查点传送只在服务器线程。重复请求由 in-flight 和数据库 CAS 双重幂等，未知扣款/退款保持 `REVIVE_PENDING/REVIEW_REQUIRED`，不会再次扣款。
- 2026-07-20 最终依赖顺序复跑：23/23 个 Maven 模块 `clean install`（包含 `test/package`）成功，331 项测试，0 failure/error/skipped；外部 Web JS 22/22、HTML 内联脚本 6/6、Java 运行时生成脚本 12/12、严格源 YAML 310/310、插件入口 17/17、85 个源资源和 25 个本地引用通过。最终 Java 生成脚本审计修复了 `ks-Inherit` 两个文本块单引号被 Java 消耗、导致详情点击脚本运行时语法错误的问题。非失败日志仅包括 JDK native-access、Mockito/Byte Buddy 动态代理、CDS class-sharing、SLF4J NOP 和故障注入用警告。未部署任何 JAR，未启动或重启 Paper；本机原生 PostgreSQL/MariaDB 已通过，真实 MySQL、外部远程存量迁移和游戏内验收未执行。

## 2026-07-20 跨服运行时接线收口

- `KsEco.CROSS_SERVER_MUTATION_WIRING_COMPLETE` 已切换为 true：`cross-server.enabled=true` 现在会创建并启动 `DatabasePollingTransport`、缓存协调器、独立 daemon poll scheduler、共享 lease repository 和过期事件清理任务；停服先停止新价格刷新并等待在途价格事务，再停止 transport，最后关闭 Extra、工作池和数据库。
- 运行时只接受共享 MySQL/MariaDB/PostgreSQL。每个节点必须有唯一稳定的 `database.server-id`；外部 Vault 经济必须明确设置 `cross-server.external-economy-shared=true`，否则启动 fail closed。运行时/publish 或数据库节点身份不健康时，心跳看门狗会停用 ks-Eco，避免节点继续使用分裂状态。跨服配置均为 restart-only。
- `CrossServerRuntime` 将本地失效先应用，再以同一 event ID 最多重试 5 次发布；轮询在数据库工作 lane，事件回调在 Paper 主线程，只允许监听器投递异步 DB 刷新。余额财富榜、价格、企业等级、房地产保护/福利和政治职务/配置均已接入 namespace 失效；市场、暂存、限售和盲盒没有共享业务缓存，继续由 DB journal、唯一 claim 和条件更新保证权威状态。
- 价格刷新改为集群唯一 `ks-eco:price-refresh` lease 加事务内 `executeFenced`；随机漂移只由一个节点提交，`current_buy_price`/`market_average` 持久化后各节点原子替换缓存。真实交易不再只修改本节点瞬时价格，统一由下一次 fenced 刷新读取共享流水。银行利息、逾期、央行回收和违约维护使用 `ks-eco:bank-maintenance` 集群独占 lease。
- 房地产 Extra 在地块、房屋、信任和福利缓存更新后广播 `real-estate/all`；远端刷新在数据库 lane，失败会 fail closed 并有限重试。政治配置读取的错误列名已修复，配置/职务变更广播后原子替换远端快照。企业和地产的 `sqlite_master` 表存在判断已改为 JDBC metadata，缺少银行抵押表在所有方言下都安全解释为“无抵押”。
- 新增共享 SQLite 双节点运行时测试和 JDBC lease 接管测试；原生 PostgreSQL/MariaDB 测试现在实际执行 transport 初始化/发布/轮询及 fenced lease。最终矩阵为 23/23 模块、333 项测试、0 failure/error/skipped；静态检查为外部 JS 22/22、HTML 内联 6/6、Java 生成脚本 12/12、严格 YAML 319/319、插件入口 17/17、85 个源资源、25/25 本地引用。
- 仍未执行真实 MySQL、外部远程存量迁移、两台真实 Paper、外部 Vault 多节点、生产网络故障/延迟/压力或游戏内 GUI/物品/产权验收。开关默认 false；本轮没有部署 JAR，也没有启动或重启 Paper。

## Documentation Language Maintenance

- Published Markdown now has a Chinese and English companion. Default Chinese documents use `.en.md`; documents whose
  primary content is English use `.zh-CN.md`.
- Each published pair should expose a top-of-page GitHub Markdown link to the other language. The language index is
  `docs/DOCUMENTATION.md` / `docs/DOCUMENTATION.en.md`.
- `.github/workflows/docs-language-check.yml` checks the required pair inventory on Markdown changes, pull requests,
  manual runs, and a weekly schedule. Codex should also recheck the inventory when syncing source and publishing
  checkouts or when a new document is added.

## 2026-07-21 测试服经济栈部署

- 已通过 `scripts/deploy-plugin.ps1` 将本轮经济与跨服修复部署到测试服现有位置：`ks-core`、`ks-Eco`、`ks-Eco-bank`、`ks-Eco-enterprise`、`ks-Eco-politic`、`ks-Eco-RealEstate`、`ks-Eco-RealEstateDungeon`、`ks-Eco-tax`。每个模块均执行干净打包；此前依赖顺序全量验证的 23/23 模块和 333 项测试已经通过，因此部署打包使用 `-SkipTests`，没有重复运行测试。
- 八个构建制品与部署目标的 SHA-256 全部一致，八条根备份索引末行、备份文件路径和备份文件哈希全部复核通过。部署哈希依次为：`ks-core` `388AFF1CD05534FABEBAE21B9582E6122D9C14A6C30DDCF6144EE142BB0EBE83`；`ks-Eco` `38824B501B20DB91922B65AC3E35D1535A05405ED97130C5B50F7ADD48AD047B`；bank `2C833966DE32C4A3F1103905CAC75D2A9CA7F251FEEDC14EB97BA6AC22D99E61`；enterprise `CBF999928F3833D2AB168894DC4A8E6F3D64E9F1EB3A57FBC557B41A8998E103`；politic `79F5252AE36ACA92AF0CC0E6D2B6C9FFFDA6174599F96F315DFDCDAD7D5B6274`；RealEstate `5E4B6FB8D4323CF40A282D01B1E961C5E8B7DC851F8142A3167A76A89639E0D0`；RealEstateDungeon `20E01ACA4218D671C419D00F9DC77CA6C1229F48F699F311C07B4A26E99E5032`；tax `8F71316C3D134C70C45C3BADC73506FE439C93613BEE119D157415993899D580`。
- 没有启动或重启 Paper，部署时也未检测到测试服 Paper Java 进程；新 JAR 需由用户后续正常启动服务器后才会加载。跨服开关仍默认关闭，启用前仍须满足共享远程数据库、唯一稳定 `database.server-id` 和外部 Vault 共享声明等前置条件。

## 2026-07-21 Extra 缺失依赖启动事故修复

- 首次启动验收发现 `ks-Eco-RealEstateDungeon` 直接链接 `InstanceWorldApi`，而测试服当时没有部署 `ks-InstanceWorld`；`NoClassDefFoundError` 又未被只捕获 `Exception` 的 Extra 加载器隔离，导致整个 ks-Eco 在启用阶段被禁用，随后 `/eco gui` 仅报告插件已禁用。
- `ExtraModuleLoader` 现在会在加载、启用和停用边界隔离 `LinkageError`，清理该 Extra 的监听器和类加载器，并让 ks-Eco 主插件继续启动。修复通过 ks-Eco `mvn -q test`；随后新增部署 `test_1_21/plugins/ks-InstanceWorld-0.1.0.jar`，SHA-256 为 `BBB82669AB09037482FA20700AD1A89113273C40BA9346AD97A72A18B6DD11AF`。这是新目标文件，因此没有被替换的旧 JAR，也没有备份记录。
- 修复后的 `ks-Eco-1.1.0.jar` 已再次部署，SHA-256 为 `C6B27790584AE70EF543E97184B6651460DFD7AF6105300F4E60D7DF4E651C89`，备份 ID 为 `ks-Eco-20260720T164254794Z-c7ad181c7e11`；制品、目标和备份索引哈希均复核通过。没有启动或重启 Paper。

## 2026-07-21 地产后台地块查询定位修复

- 地产管理地图中的地块详情“查看区域地块”原先只关闭详情、写入区域过滤条件并刷新地块表；结果表位于同页下方，页面没有滚动或反馈，因此操作视觉上像无响应。现在由 `viewAdminZonePlots` 等待查询完成后滚动到 `ks-card-replots`，并显示成功提示。
- `admin-core.js` 已通过 `node --check`，ks-Eco 干净打包成功并重新部署；部署 SHA-256 为 `BF5E98290ED38A1D7BE10B34CCEA876A21A68E484C26EAC879688C60FFB66A34`，备份 ID 为 `ks-Eco-20260720T170422633Z-9bffb0c859aa`。制品、目标和备份索引哈希均复核通过，没有启动或重启 Paper。

## 2026-07-21 地产网页世界切换与游戏内示范

- 地产玩家页原有世界下拉框只从房地产区域/地块记录推导选项，导致没有登记地产数据的已加载世界（例如 `test_world`）不可选择。现在会合并 ksHWP `/api/worlds` 返回的全部 Bukkit 世界，并显示维度标签；切到无登记区域的世界后仍可浏览地形、框选不超过 128×128 的范围并打开 3D 体素预览。
- 修复已通过 `node --check` 和 ks-Eco 干净打包并部署，SHA-256 为 `D02A66A9B932712F344437B920B887FAC7D64C72F431AE23F6BB6B766C98CCC4`，备份 ID 为 `ks-Eco-20260720T173856672Z-593dc32c44d1`；制品、部署目标与备份索引哈希一致。用户授权后已通过 MCSManager API 正常重启 Paper；`01:42:44` 完成启动，ks-Eco v1.2.0 与 6 个 Extra 均启用。在线 JS 已命中新世界枚举逻辑，ksHWP 返回 `world/world_nether/world_the_end/test_world`。
- `test_1_21/world/datapacks/ks-estate-demo` 已通过 MCSManager API 热加载，并在 `minecraft:test_world` 的 `(0,96,0)` 建成两层住宅、庭院、道路和观景台示范；基座方块经命令回执验证，临时强加载区块已解除。玩家 `baoyu_233` 已传送至 `(0.5,98,-17.5)`。
- 地产区域体素 API 已对 `test_world [-13,-11]–[13,11]` 实测成功，返回 22,537 个方块、Y=32–124、`truncated=false`，网页 3D 数据链路可用。

## 2026-07-21 地产 3D 页面 HTML/JSON 故障与 ksHWP 渲染修复

- 地产 3D 弹窗显示 `Unexpected token '<', "<!DOCTYPE "... is not valid JSON` 不是体素 JSON 本身损坏。对应启动周期的 Watchdog 线程栈显示 ksHWP 在 Paper 主线程执行高层图块的递归合成、`ImageIO.write` 和磁盘缓存；页面同时加载地图瓦片时服务端失去响应并被 Watchdog 终止，浏览器收到重启期间的 HTML，随后把它误当 JSON 解析。
- `MapRenderer` 现在只在 Paper 主线程分批抓取 `ChunkSnapshot`，每 tick 最多 8 个基础区块；磁盘读取、方块着色、图像合成、PNG 编码和保存全部在 `ksHWP-Renderer`。zoom=2/4/8 直接从 zoom=1 磁盘图块或快照合成最终图，不再为一张 zoom=8 图块递归生成、编码和解码 85 张中间 PNG；同图块并发请求仍共享 `renderInFlight`。
- 地产地图前端把 ksHWP 瓦片限制为最多 2 个并发，优先加载视口中心，移除已经离开视口的排队项，切换世界用 epoch 丢弃旧响应。单张瓦片失败只保留网格并继续加载其他区域，不再关闭整层地形；非 JSON 响应会显示安全错误，不再把 HTML/DOCTYPE 原文抛进 3D 弹窗。
- 验证通过：ksHWP 干净打包；ks-Eco `node --check` 与完整 Maven 打包/测试成功。重启后的未缓存 `test_world` zoom=8 请求为 153 ms，前一版同架构的两张并发请求为 95/105 ms；地产体素接口再次返回 HTTP 200、22,537 个方块、`truncated=false`。验收后 Paper 25565 与 Web 8123 均监听，当前启动边界内 Watchdog、ksHWP 和 ks-Eco 错误计数均为 0。测试时没有在线玩家，因此这些地图请求验证的是未探索图块快速返回和请求调度，已加载真实地形仍需玩家在线浏览时继续观察。
- 已通过 `scripts/deploy-plugin.ps1` 部署并核对三方哈希：ksHWP SHA-256 `459D9875222429F86A3BD9D309AF47E50DED040089990BA38355EE45905B9F22`，备份 ID `ksHWP-20260720T182323397Z-dcf634f98795`；ks-Eco SHA-256 `95B6AEF81979725F37377A0CAD4A0058F279ED21DF6137347FC8C718E371D0B8`，备份 ID `ks-Eco-20260720T181618189Z-c1ca78168f60`。用户已授权通过 MCSManager 重启，Paper 于 `02:24:57` 完成启动。

## 2026-07-21 空世界地产框选比例修复

- 没有地产区域记录的世界原先按 1 像素/方块居中，玩家在网页拖过 128 像素就会实际框出超过 128×128 方块；`test_world` 的 27×23 示范建筑因此很难精确选中。空世界默认比例已改为 4 像素/方块，原点仍居中，示范建筑无需登记地产区域即可清楚框选。
- 超限提示现在显示实际框选的宽×深，3D 按钮标题也显示当前尺寸与 128×128 上限，不再只给笼统“区域过大”。`player-core.js` 通过 `node --check`，在线资源确认已命中新逻辑。
- ks-Eco 已通过根部署脚本替换，SHA-256 `74D94240EF2B706ED15FE6CABA0AEE2AF395BE05444933B3922DA90911C2C7F3`，备份 ID `ks-Eco-20260720T183137024Z-d324fa8fcb9f`；制品、目标、备份索引哈希一致。用户此前已授权必要重启，Paper 于 `02:33:03` 完成启动，当前启动边界内 Watchdog 与 ks-Eco 错误均为 0。

## 2026-07-21 地产玩家/管理网页实操收口

- 内置浏览器对真实地产数据完成两端实操。管理员端从地图点中 `P58d49c39`，点击“查看区域地块”后会关闭详情、把区域过滤器设为 `Z459f0700`、滚动到查询结果并显示成功提示；目标地块仍在结果中。
- 玩家地图原先优先命中区域，覆盖同坐标的登记地块；现在改为地块优先。大地块 3D 的旧 `128×128` 单边限制改为 `384×384` 安全上限，城区与地块统一使用 `24×24` 渐进分片；玩家页面给核心脚本增加版本参数，避免重启部署后浏览器继续使用旧缓存。
- 最终验收从玩家“浏览可购”地图点中 `P58d49c39`，详情正确显示为“登记地块”而非区域；`145×77` 范围点击“进入地块 3D”后成功加载 `28/28` 分片、渲染 `118,085` 个表层方块并创建 WebGL 画布。对应 28 个真实体素 API 请求全部 HTTP 200、无截断、无错误。
- `player-core.js` 已通过 `node --check`，本轮改动后的 ks-Eco 全量测试为 `173/173` 通过；后续纯网页增量只重复干净打包。最终 JAR 已由 `scripts/deploy-plugin.ps1` 部署，SHA-256 `8E1F5A2664E41CCB170B5FA95E20B1298AFDF8D11C63CB6607FB8B4D81FF7875`，备份 ID `ks-Eco-20260720T194215065Z-7b25c637dbb7`，制品、部署目标与备份索引哈希一致。Paper 已经用户授权重启，并于 `03:43:48` 完成启动；ks-Eco 与房地产 Extra 正常启用。

## 2026-07-21 地产售楼处城区沙盘

- 区域 3D 不再把整片世界按地形分片堆进一个体素场景。`/api/realestate/city/manifest` 返回区域、地块与已登记楼栋清单，玩家端先画沙盘底座、环路、地块边界和楼栋占位，再以最多 3 栋并发逐栋装配；区域详情固定提供“进入城区售楼沙盘”，不会再被地块优先命中规则遮住入口。
- `RealEstateManager` 为每栋房屋维护 5 分钟预渲染缓存。首次请求返回 `PREPARING`，Bukkit 方块读取按每 tick 8192 个预算切片，完成后在异步线程裁掉完全封闭的内部方块并返回 `READY`；并发请求复用同一 future，模块停用会取消快照任务。玻璃与玻璃板使用透明/薄几何，城区楼栋目录和 WebGL 点选都可打开房源卡。
- 测试服 `test_world` 新建真实演示数据：区域 `Z3ca352cc`（海湾壹号示范城区）、地块 `P9259f818`、楼栋 `H776c36cb`（海湾壹号 · 现代样板宅），楼栋边界 `[-13,95,-11]–[13,108,11]` 对应此前游戏内样板宅。实际 API 验收观察到 `PREPARING → READY`，3286 个源方块裁为 3028 个外观方块，`truncated=false`；玩家内置浏览器成功显示 33×35 城区、1 地块、1 楼栋及道路骨架。
- `ks-Eco` 与 `ks-Eco-RealEstate` 的完整测试分别通过 173/173、9/9，`player-core.js` 通过 `node --check`。最终房地产 Extra SHA-256 为 `3868364C989B6A64525E0EA623912FC588587D8F7907F070B1FDD6693EA55165`，备份 ID `ks-Eco-RealEstate-20260721T045751179Z-0b6df9ce4c84`；最终 ks-Eco SHA-256 为 `8FAC0F66199FC03095827189CA12B81AE42E5022172F60AC0720F98342BD2D5D`，备份 ID `ks-Eco-20260721T050234422Z-a594aecead8d`。两者均由根部署脚本替换并核对哈希。

## 2026-07-21 四栋售楼沙盘试验城区

- `test_1_21/world/datapacks/ks-estate-demo` 新增可重复执行的 `ks:estate_city_build`，在 `test_world` 以 `(128,96,32)` 为中心建成 69×69 小型城区：十字道路、中心广场和四栋不同材料/体量的住宅。清空命令已拆成四段，单条 `fill` 不超过原版 32768 方块上限；目标 36 个区块已强加载，避免未加载区块使函数空跑。
- 新区域为 `Z229ffdd2`（海湾新城四季里），地块 `P908e3d3f`。四栋楼为 `Hba223779` A01 樱庭独栋（洋红，880,000）、`Hcf1a58cb` B02 青湾玻璃宅（青色，1,120,000）、`Hae46604d` C03 曜石联排（金色，760,000）、`Hc24ca341` D04 绿谷复式（绿色，990,000）。`showcase_price/showcase_marker` 只用于沙盘展示，正式房产市场挂单仍是成交权威来源。
- 城区页面会在三栋以上时绘制内部十字道路，每栋在 3D 场景和左侧目录都有同色特殊标识；点击目录会显示状态、地块、长宽高、占地、包围体体积和展示/正式售价。四栋真实世界快照均完成 `PREPARING -> READY`，外观方块分别为 1991、2659、2281、2375，总计 9306，全部 `truncated=false`。
- 内置浏览器玩家端实操通过：`test_world -> 海湾新城四季里 -> 进入城区售楼沙盘` 显示 `4/4`，四个侧栏按钮逐栋打开正确房源卡；管理端“领地地块”能看到新区域和地块。两端浏览器控制台错误均为 0。`player-core.js` 通过 `node --check`，ks-Eco 173 项与 RealEstate 9 项测试全部通过。
- 当前部署哈希：ks-Eco `5AE04AEFF9B356F01D56286EC55C22E6B5D83ECC3AB01873E3F0BC90D2681FA4`，备份 ID `ks-Eco-20260721T060541396Z-911dfdcdcb11`；RealEstate `0EAC1DE4D186FB106026DE4F3C9D4D18869003FAF5E534F09114756203B4C6BE`，备份 ID `ks-Eco-RealEstate-20260721T050948609Z-7c121e381779`。Paper 于 `14:06:54` 完成最终启动，ks-Eco 与房地产 Extra 正常加载。

## 2026-07-21 售楼沙盘相机与方块渲染修复

- 城区售楼沙盘改用正交等距相机，消除近大远小造成的建筑透视畸变；默认视角重新居中并覆盖整座城区。玩家可左键拖动旋转、右键或 `Shift+左键` 拖动平移、滚轮缩放、`WASD`/方向键移动，双击或点击“视角复位”恢复默认视角；点击楼栋目录会先聚焦对应建筑，再打开房源卡。
- 修复方块材质的根因：普通单材质回退曾错误返回长度为 1 的材质数组，而 `BoxGeometry` 有 6 个材质组，最终只渲染第一个面，玻璃等方块因此呈现斜三角和竖条。现在单材质路径返回单个 `Material`，只有确有顶/侧/底纹理差异的方块才返回完整六面材质数组。
- 完整玻璃方块使用低粗糙度、不透明的展示材质，玻璃板继续使用薄几何，避免 WebGL 透明实例内部面排序造成的闪烁和穿插；实机验收中四栋建筑窗面均恢复为规整矩形。
- 玩家内置浏览器完成 `4/4` 楼栋加载、旋转/平移/缩放/复位、目录聚焦和房源卡实操，控制台错误为 0。`player-core.js` 通过 `node --check`，ks-Eco 全量测试 `173/173` 通过。最终 JAR 与部署目标 SHA-256 均为 `5AE04AEFF9B356F01D56286EC55C22E6B5D83ECC3AB01873E3F0BC90D2681FA4`，根备份 ID 为 `ks-Eco-20260721T060541396Z-911dfdcdcb11`；Paper 于 `14:06:54` 完成启动。

## 2026-07-21 银行经营玩法扩展

- `BankGameplayManager` 接通玩家金融总览、按银行存款保险覆盖展示、7/30/90 日定期、180 日大额存单、提前支取与自动续存。提前支取罚金只作用于已产生利息，不扣本金；有效定期存款计入 M2。
- 贷款目录新增消费、住房、经营和项目四类产品，并支持到期一次还本付息、等额本息、等额本金三种方式。正式报价持久化产品、还款方式、用途、有效期和政策修正；借款人可申请 7/14/30 日展期，银行审批后原子更新到期日、剩余应还、展期次数并重建计划。
- 商业银行经营页汇总流动性、存款负债、贷款资产、权益、资本/流动性比率、坏账、利息收入、存款利息成本、损失准备、历史分红和留存收益，生成 A-E 评级及 `NORMAL/WATCH/RESTRICTED/RESOLUTION` 状态。正常经营且分红后仍满足准备金约束时，分红批次和股东活期入账在同一事务完成。
- 管理端可以创建利率、流动性、房地产、违约潮和存款竞争事件；事件按时间窗进入 `SCHEDULED/ACTIVE/ENDED`，修正后续贷款报价与风险评估，不直接改玩家余额。央行可以把银行置于观察、限制新增业务或处置状态。
- 新表为 `ks_bank_deposit_products`、`ks_bank_term_deposits`、`ks_bank_loan_schedules`、`ks_bank_risk_state`、`ks_bank_policy_events`、`ks_bank_dividend_batches`、`ks_bank_dividend_payouts`、`ks_bank_restructure_requests`。核心 Web 新增个人总览、产品、报价、存单、经营、分红、展期和政策/处置 API；玩家页和管理页均提供相应入口并移除新增流程中的手填银行 ID。
- 验证：ks-Eco `173/173`、ks-Eco-bank `42/42` 测试通过，两个模块均完成 `clean test package`；玩家/管理核心 JavaScript 通过 `node --check`。测试服只读实测返回 5 家银行、4 类贷款产品、4 类存款产品，个人总览、经营指标、分红、展期和政策接口均 HTTP 200，消费贷等额本息报价成功。自动化测试 token 接口曾返回非法 JSON，已改为 Gson 序列化并在部署后复测可用。
- 最后窄审计修复 `BankManager.getConfigDouble` 查询 `config_value` 却读取旧列名 `value` 的问题；此前异常被吞后会静默使用默认银行创建资本门槛，使管理员配置看似保存但不生效。修复后银行 42 项测试再次通过并单独重部署。
- 部署：银行 Extra SHA-256 `F803CD50FECE81BA0AA13C6D1DFD00662D818F63161C5F052B7BCA8E784B1D1F`，备份 ID `ks-Eco-bank-20260721T094221360Z-8a2bb0c0b47a`；最终 ks-Eco SHA-256 `3031513AEC364670D634510982F009C88E370757ED14C781A607E75663506765`，备份 ID `ks-Eco-20260721T093338386Z-3fef0fa46a80`。Paper 于 `17:43:29` 完成启动，ks-Eco v1.2.0、银行 Extra 和其余五个 Extra 正常启用，业务表于 `17:34:48` 就绪。
- 当日版本限制（已由 2026-07-22 收口部分解除）：当时玩家产品尚未绑定可执行抵押、股份尚不可交易、保险只展示覆盖额；当前状态必须以下一节为准。真实写资金的开存单、贷款申请、分红和展期审批没有在该次存量数据上强行执行；真实 MySQL、外部远程存量库、外部 Vault 双节点和生产并发仍未验收。

## 2026-07-22 银行抵押、股权、保险与最终部署

- 个人住房、经营和项目贷款分别使用本人地块/房屋或中标项目合同，LTV 为 75%/60%/70%。抵押物按 `RESERVED -> LOCKED -> RELEASED/SEIZED/SOLD` 维护；结清释放，超过宽限期的违约进入已有 escrow 拍卖。房屋及其父地块的抵押/挂牌冲突会同时复核，不能以另一种资产类型绕过锁定。
- 商业银行拥有授权/已发行股本、逐股东持仓、预留股份、一级增资和二级挂牌成交；分红按实际持股精确分配，重要持股人和控制股东会同步到所有者。受限、处置中或已清算银行不能继续交易股权。
- 存款保险按每名存款人合并活期和有效定期、最高 100,000，商业银行按月缴费。处置预览计算资产回收、保险补助和未保险折损；执行时由正常桥接银行在同一数据库事务承接账户、贷款、抵押物和拍卖，并留下处置案和逐存款人记录。存在外部钱包未知结算时禁止清算。
- 放款外部钱包结果未知会进入 `RECONCILE_REQUIRED`。管理端“放款复核”要求先核对 Vault 流水：确认到账激活贷款，确认未到账则恢复银行流动性、申请和抵押物。央行页面新增“风险与保险”和“放款复核”实际入口，修复了功能已存在但被暂存区隐藏的问题。
- 最终验证：23/23 Maven 模块依赖顺序成功，347 项测试 0 failure/error/skipped；外部 JavaScript 22/22、HTML 内联脚本 6/6、Java 运行时脚本 12/12、严格 YAML 319/319、插件入口 17/17、85 个源资源和 25 个 HTML 本地引用通过。内置浏览器实际操作玩家抵押贷款/股权区和管理员保险/处置/复核页面，控制台均为 0 error。
- 部署：银行 Extra SHA-256 `5025BF69C9B25CE8EE7CCD6F838CC9CADECFEABAACAA6AEC6FF38B1B5C3EAF9F`，备份 ID `ks-Eco-bank-20260722T085105042Z-223603d2022a`；最终 ks-Eco SHA-256 `4973DCCF548F7E9F5A4CD9CFC75D8D5B8BDA9D5D4C55988D32A7E4A0651DD195`，备份 ID `ks-Eco-20260722T090153739Z-57e58c5ab8b3`。Paper 于 `17:08:53` 完成启动，六个 Extra 正常启用。首次重启曾因 PlaceholderAPI 远程恶意扩展清单 TLS 握手无限等待而卡住，确认线程栈后终止该挂起进程并重新启动成功；这不是 ks-Eco 启用错误。
- 当前限制：没有在真实业务存量上执行不可逆桥接清算，也没有注入真实 Vault 崩溃窗口；真实 MySQL、外部远程存量迁移、Paper/Vault 双节点、生产延迟/断网/压力仍未验收。资产回收折价和保险基金是游戏玩法模型，不是现实金融监管模型。

## 2026-07-22 MariaDB 跨服网络与 Folia 收口

- MCSManager 现托管五个实例：MariaDB `127.0.0.1:3307`、BungeeCord `25577`、Leaves 主端 `25565`、Paper RPG 端 `25571`、Folia 实验端 `25573`。Leaves 直接复用 `test_1_21` 的世界、玩家和完整插件；Paper 只保留 RPG/ks 相关栈；Folia 仅安装 `ks-core` 和 `ks-Eco` 的独立 Folia 制品。
- Leaves/Paper 原 SQLite 的 139 张源表已事务迁移到 `ks_network`，逐表行数无差异；余额、企业公户、银行账户、挂牌、交易、审计、副本网格与房屋的定向汇总也一致。原 SQLite 和迁移前备份保留。真实 MySQL 和外部远程存量库仍未迁移或验收。
- `KsConfig` 新增 `database.password-env` 与 `database.password-file`，优先级为显式 password、环境变量、密码文件、旧 mysql.password。配置了但不可读取的外部来源会失败关闭；相对文件以插件数据目录为根。三端运行时 YAML 已移除明文密码，改为工作区外且仅当前用户可读写的密码文件。连接池、凭据来源、节点 ID 和跨服 transport 都是重启项。
- `ks-Eco` 新增 `-Pfolia` 独立制品。`EcoScheduler` 统一 global/async/entity 调度与超时取消，旧 BukkitScheduler/BukkitRunnable/`isPrimaryThread()` 调用清零；玩家 GUI、消息、Inventory 与在线钱包操作回交实体调度器。无 Vault 的 Folia 直接启用 JDBC `BuiltinEconomy`，普通市场扣款、卖家入账和退款走数据库 lane。未适配 Extra 与全服财富榜在 Folia 失败关闭。
- 最终单次依赖矩阵：23/23 Maven 模块 `clean install` 成功，11 个有测试模块共 358 项测试，0 failure/error/skipped；ks-core 9、ks-InstanceWorld 7、ks-RPG 45、ks-Eco 180、银行 51、企业 3、政治 15、税务 6、地产 9、副本 28、BotGuard 2、Skill 3。默认与 Folia 两套 ks-core/ks-Eco 构件成功；静态结果继续为外部 JS 22/22、HTML 内联 6/6、严格 YAML 319/319、入口 17/17、源资源 85、本地引用 25/25。
- 三端实机日志都显示 MariaDB 数据源、唯一节点 ID `survival/rpg/folia`、ks-Eco 启用、跨服运行时启动和 `Done`。保持 `DIAMOND=100.0` 的烟测事件令 transport 发布序号从 2 变为 3，三个 consumer cursor 全部推进到 3；六个关键端口（DB、代理、三后端、Leaves Web）均监听。
- 最终部署：Leaves ks-core `8E6E9D2520B18A000CDAD3E90C2AE2AC1783E1FDE407B083009EAFDC758783AA`，备份 `ks-core-20260722T152119832Z-111595c1fe21`；Paper ks-core `51E4B9B693F4DA949EA6CDB839E118BDB39A37C402EE54248B6DC9DC6C09321A`，备份 `ks-core-20260722T152136341Z-5cdf30933164`；Folia ks-core `107ACEE242E7603352CFA73BF898909E8A660402625008AC73DB92F1D9FD35E4`，备份 `ks-core-folia-20260722T152157459Z-5d45d5a0854b`。Leaves ks-Eco `CEF6F25CA36EA13E6AA6EC7E89BB92CD4FB24D9EE2E426E316829614B9B5DF82`，备份 `ks-Eco-20260722T152215308Z-dda7e24c7fff`；Paper ks-Eco `B77C96EC9B93CE2FFC0713130B65CA893A8D87E60DB3FD359EB9E9B1DDB67000`，备份 `ks-Eco-20260722T152231572Z-63987c4f83f1`；Folia ks-Eco 首次部署 `2283AFFE81480D117CC6CCCB8169A9DEFD1061247713E419F7037C197C18144B`，无被替换旧 JAR。所有制品/目标/备份索引哈希已复核。
- 非阻断残余：MariaDB 驱动会为部分幂等补列打印 duplicate-column warning；Leaves/Paper 还保留 PlaceholderAPI 网络、MMOItems/MMOCore 示例内容和 MCPets 外部库警告。Folia 启动边界没有 ks-Series ERROR 或区域线程异常。当前离线 Bungee 配置不是公网认证方案，未执行多机断网、延迟、压力、外部 Vault 崩溃或不可逆银行桥接清算。
