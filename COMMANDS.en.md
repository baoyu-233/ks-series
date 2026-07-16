# KS-Series Command Reference

> [中文](COMMANDS.md) | English

This is the administrator and player command index for the published modules. Availability still depends on installed
plugins, Extra modules, permissions, and configuration.

## ks-core - Core Gateway

| Command | Permission | Description |
|---|---|---|
| `/kscore` | none | Show gateway status. |
| `/kscore reload` | admin | Reload gateway configuration. |
| `/kscore status` | admin | Show routes, registered modules, and Web address. |

## Web Routes

| Route | Module | Description |
|---|---|---|
| `/IE` | KS-ItemEditor | Web item designer. |
| `/kSHWP` | ksHWP | World map. |
| `/ks-Eco` | ks-Eco | Economy management panel. |
| `/ks-Eco/bank` | ks-Eco-bank | Banking panel. |
| `/ks-Eco/enterprise` | ks-Eco-enterprise | Enterprise panel. |
| `/ks-Eco/tax` | ks-Eco-tax | Tax panel. |

## KS-ItemEditor

| Command | Permission | Description |
|---|---|---|
| `/itemedit` | `itemedit.admin` for admin mode | Open the GUI editor for the held item. |
| `/ie web` | `itemedit.admin` | Get the administrator Web panel link. |
| `/ie reload` | `itemedit.admin` | Reload configuration. |
| `/design` | player or admin | Open the Web designer. |
| `/design load <template>` | player | Load a player template. |
| `/refine` | player | Open the refinement UI. |

`/ie` is an alias for `/itemedit`. Administrator templates use `op-`; player templates use `pl-`.

## ksHWP

| Command | Permission | Description |
|---|---|---|
| `/map` | `kshwp.use` | Get the map link. |
| `/mapnote add <text>` | `kshwp.note` | Add a note at the current position. |
| `/mapnote list` | `kshwp.note` | List your notes. |
| `/mapnote delete <id>` | `kshwp.note` | Delete your note. |
| `/kshwp reload` | `kshwp.admin` | Reload map configuration. |
| `/kshwp status` | `kshwp.admin` | Show map status. |
| `/kshwp forcerender [world]` | `kshwp.admin` | Render loaded regions. |
| `/kshwp forcerender-area <world> <x1> <z1> <x2> <z2>` | `kshwp.admin` | Render a selected area. |
| `/kshwp prerender [world]` | `kshwp.admin` | Pre-render a world area. |
| `/kshwp cache` | `kshwp.admin` | Show memory and disk cache statistics. |
| `/kshwp clearcache [world]` | `kshwp.admin` | Clear disk cache. |

## ks-Eco

| Command | Permission | Description |
|---|---|---|
| `/kseco gui` | none | Open the economy GUI. |
| `/kseco web` | none | Get the Web panel link. |
| `/kseco prices` | none | View official prices and trends. |
| `/market` | `kseco.market` | Open the player market. |
| `/trade <player>` | `kseco.trade` | Start a player trade. |
| `/storage` | `kseco.storage` | Open storage. |
| `/kseco reload` | `kseco.admin` | Reload economy configuration. |
| `/kseco status` | `kseco.admin` | Inspect economy and Extra state. |
| `/kseco force-price <material> <price>` | `kseco.admin` | Apply a bounded price override. |
| `/kseco void-trade <material> <amount> <price> <BUY\|SELL>` | `kseco.admin` | Add a test/intervention trade. |
| `/blindboxadmin ...` | `kseco.admin` | Manage blind-box pools and rules. |
| `/limitedsaleadmin ...` | `kseco.admin` | Manage limited-sale campaigns. |
| `/compensationadmin ...` | `kseco.admin` | Manage compensation plans. |

Aliases include `/kse`, `/mkt`, `/ah`, `/deal`, `/stash`, `/chest`, `/lsale`, and `/timesale` where the corresponding
module declares them.

## Extra Modules

The bank, enterprise, and tax modules are managed from their Web routes and APIs. They do not add independent Bukkit
commands. Real-estate, dungeon, and politics entry points are exposed by the current `ks-Eco` Extra configuration.

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| `itemedit.admin` | OP | ItemEditor administration. |
| `kshwp.admin` | OP | Map administration. |
| `kseco.admin` | OP | Economy and Extra administration. |
| `kseco.market` | all players | Market usage. |
| `kseco.trade` | all players | Player trading. |
| `kseco.storage` | all players | Storage usage. |
| `ksinherit.use` | all players | Inheritance storage. |
| `ksinherit.admin` | OP | Inheritance capacity and review. |

For the design rationale and implementation details, see [the full report](docs/KS-SERIES-REPORT.en.md). For player-only
commands, see [the player guide](docs/KS-SERIES-PLAYER-README.en.md).
