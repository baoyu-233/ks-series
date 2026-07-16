# First-Season RPG Foundation Catalog

This catalog is the authoritative initial material and equipment economy. It is deliberately small:
the goal is to connect survival production to future RPG combat without introducing random-stat or
high-frequency progression systems.

## Quality Bands

| MMOItems tier | Meaning | First-season use |
|---|---|---|
| `KS_STANDARD` | Basic field material and starter equipment | Tradeable, no combat gate |
| `KS_REFINED` | Machine-assisted material and first accessory | Tradeable, survival/economy inputs |
| `KS_RARE` | Elite and advanced-upgrade input | Tradeable, scarce through content caps |
| `KS_RELIC` | Boss-era material | Tradeable material only; breakthroughs still need a bound proof |

Combat proofs are not item tiers and must never be represented by an MMOItems stack. Future `ks-RPG`
progression records own them as account-bound data.

## Pre-Wired Progression API

`ks-RPG` publishes `RpgProgressionApi` through Bukkit's `ServicesManager`. It is a server-thread-only integration
surface for configured proof definitions, account-bound proof mutation, and proof-gate checks. Proofs are stored in
the online player's persistent data container, never as MMOItems, currency, or tradeable inventory state.

Proofs and gates live in `ks-RPG/config.yml`. New declarations become available through `/ksrpg reload`; no ks-RPG
JAR update is required. The initial dormant wiring declares `frostbound_conductor_clear` and the
`frostbound_relic_breakthrough` gate. This creates no reward or equipment transaction on its own.

For declarative integrations, a console-capable source can invoke `/ksrpg proof grant <player> <proofId>` or
`/ksrpg proof revoke <player> <proofId>`. The receiving player must be online. Java integrations should retrieve
`RpgProgressionApi` from Bukkit's ServicesManager and must call it on the server thread.

## Progression Menu

`ks-RPG-Gui` is an independently deployable inventory-menu plugin, intentionally outside ks-RPG's progression and
material ownership. It consumes the public API to display the player's proof state and gate state, and provides
navigation to the material catalog and accessory inventory. It must never award currency, items, proofs, or stats.

Its layout, copy, slot positions, and icon materials live in `plugins/ks-RPG-Gui/menu.yml`. `/rpgmenu reload` reads
that file asynchronously and swaps the immutable layout on the server thread; visual changes therefore need no Paper
restart. A new GUI JAR still needs the normal plugin/server restart to load for the first time.

The first in-game opening confirmed the main menu, proof entry, and gate entry render. It also exposed a corrected
rendering rule: every dynamic lore fragment and every value substituted into a menu template must pass through the
same legacy color conversion as `menu.yml`; otherwise `&7`/`&f` tokens render literally. Minecraft identifiers such
as `minecraft:end_portal_frame` and component counts are client-side advanced tooltips (F3+H), not plugin output.

## Materials And Ratios

`ks-RPG` enforces these one-way exchanges through `/ksrpg exchange <id> [amount]`.
The command rejects partial input, missing MMOItems, and insufficient inventory capacity before it removes
anything. No reverse conversion exists in the first season.

| Exchange ID | Inputs | Output |
|---|---|---|
| `refined_alloy` | 8 `MATERIAL:KS_FIELD_SCRAP` | 1 `MATERIAL:KS_REFINED_ALLOY` |
| `conductive_coil` | 4 Refined Alloy + 16 Redstone | 1 `MATERIAL:KS_CONDUCTIVE_COIL` |
| `stabilized_core` | 4 Conductive Coil + 8 Amethyst Shard | 1 `MATERIAL:KS_STABILIZED_CORE` |
| `relic_fragment` | 2 Stabilized Core + 1 Echo Shard | 1 `MATERIAL:KS_RELIC_FRAGMENT` |

The material source is intentionally separated from exchange: machines, gathering nodes, field elites, and
dungeon reward tables may supply the appropriate tradeable input, but ordinary automation must not award
general RPG XP or combat proofs.

## Accessory Surface

The active MMOInventory surface is `ks_rpg_accessories`, opened with `/rpggear` or `/gear` and gated by
`ksrpg.use`. It exposes exactly two `RING` slots and one `TALISMAN` slot. The previous broad default inventory
is disabled, not deleted, to preserve old test data.

Initial items are `RING:KS_RING_OF_BASTION`, `RING:KS_RING_OF_RESOLVE`, and
`TALISMAN:KS_PIONEER_TALISMAN`. They are first-season stat surfaces, not proof substitutes.

## Ownership And Performance

- `ks-RPG`: exchange validation, material catalog, later combat proofs and active abilities.
- MMOItems: material and equipment data, quality rendering, item attributes.
- MMOInventory: the three accessory slots only.
- MMOCore: baseline attributes/resources; its duplicate party/guild systems and default action bar are disabled.

Exchange operations read and update only the initiating player's storage inventory on the server thread. There
is no repeating player scan, random-stat generation, database operation, or background task in this slice.
