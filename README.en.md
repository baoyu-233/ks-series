# KS Series - Minecraft Survival Server Plugin Suite

> English | [中文](README.md)

KS Series is a collection of Minecraft server plugins maintained for LeavesMC 1.21.11. It organizes economy,
market, land, enterprises, world maps, bots, bosses, and gradual RPG systems into modules that can be built and
deployed independently.

The project keeps clear ownership boundaries: the economy settles currencies and items, RPG owns progression and
combat proofs, MythicMobs owns encounter presentation, and optional modules connect through explicit interfaces.
The project is still evolving, so verify commands, configuration, dependencies, and API behavior on your own test
server before production use.

![KS-Series overview](docs/assets/ks-series-overview.png)

## Project Structure

```text
ks-core
  |- ks-Eco
  |   `- extra/
  |       |- ks-Eco-bank
  |       |- ks-Eco-enterprise
  |       |- ks-Eco-tax
  |       |- ks-Eco-RealEstate
  |       |- ks-Eco-RealEstateDungeon
  |       `- ks-Eco-politic
  |- ks-Compat / ks-BotGuard
  |- ks-RPG / ks-RPG-Gui
  `- other independent plugins
```

`ks-core` provides the shared gateway and data services. `ks-Eco` is the economy core and hosts optional Extra
modules. Extra JARs belong in `plugins/ks-Eco/extra/`; independent plugins are installed only when the server
gameplay needs them.

## Module Map

| Module | Responsibility |
|---|---|
| `ks-core` | SQLite data services, Web gateway, routing, and authentication. |
| `ks-Compat` | Compatibility bridge between Leaves, Vault, third-party plugins, and KS modules; also exposes KSBot capabilities. |
| `ks-BotGuard` | Protects third-party player-data listeners from Leaves Bot events. |
| `ks-Maintenance` | Maintenance mode and operational helpers. |
| `ks-Eco` | Market, official buyback, dynamic pricing, blind boxes, limited sales, compensation, trades, and storage. |
| `ks-Eco-bank` | Central and commercial banking, loans, rates, liquidity, and money supply. |
| `ks-Eco-enterprise` | Enterprises, public accounts, tenders, procurement, dividends, and member roles. |
| `ks-Eco-tax` | Transaction and industry taxes, progressive rates, and tax records. |
| `ks-Eco-RealEstate` | Regions, plots, property registration, homes, and trusted players. |
| `ks-Eco-RealEstateDungeon` | Dungeon instances, parties, tickets, revives, property, and completion rewards. |
| `ks-Eco-politic` | Senate, offices, proposals, voting, and legislation gates. |
| `ksHWP` | Web world maps, tiles, player positions, and map notes. |
| `ks-RPG` | RPG catalog, material exchanges, combat proofs, content configuration, skills, and drops. |
| `ks-RPG-Gui` | RPG progress, proof status, equipment entry points, and admin catalog UI. |
| `ks-BossCombat` | Encounter-specific weapon and combat rules. |
| `ks-Skill` | Legacy passive-skill module being migrated into `ks-RPG`. |
| `KS-ItemEditor` | In-game item editing, Web design, and weapon refinement. |
| `KS-ItemSteal` | Controlled item disarm and return flows for server-administered encounters. |
| `ks-Inherit` | Item preservation, inheritance, review, and delivery. |
| `ks-Title` | Titles and presentation features. |
| `ks-Sentinel` | Admin action and high-risk command auditing. |

## Player Entry Points

These are common player commands. Availability depends on the installed modules and server configuration.

| Entry point | Purpose |
|---|---|
| `/kseco gui` | Open the economy hub for market, blind boxes, limited sales, compensation, and storage. |
| `/market` | Open the player market. |
| `/trade <player>` | Start a player-to-player trade. |
| `/storage` | Open the item storage queue. |
| `/map` | Get the Web world map link. |
| `/land` | Open land and property management. |
| `/dungeon` | Open the dungeon lobby and party entry. |
| `/ksrpg catalog` | Browse the configured RPG catalog. |
| `/ksrpg exchange <id> [amount]` | Exchange configured materials for RPG content. |
| `/rpggear` | Open the RPG loadout entry point. |

See the [player guide](docs/KS-SERIES-PLAYER-README.en.md) for the complete player workflow. The [full technical
report](docs/KS-SERIES-REPORT.en.md) contains administrator commands, permissions, APIs, and deployment boundaries.

## Requirements and Build

- LeavesMC 1.21.11 or a compatible Paper 1.21 server.
- Java 21.
- Load `ks-core` before modules that depend on it.
- Put `ks-Eco` Extra JARs under `plugins/ks-Eco/extra/`.
- Install optional dependencies such as Vault, LuckPerms, ItemsAdder, MythicMobs, ModelEngine, FAWE, and ProtocolLib only when the selected features need them.

The repository contains independent Maven modules and has no root `pom.xml`. Build the core API first, then the
economy core and required extensions:

```powershell
Set-Location .\ks-core
mvn clean install

Set-Location ..\ks-Eco
mvn clean install

Set-Location ..\ks-Eco-bank
mvn clean package
```

Use `scripts/deploy-plugin.ps1` for deployment and backups. Do not commit runtime databases, backups, test tokens,
server logs, or local credentials.

## Documentation

| Document | Audience | English |
|---|---|---|
| Full technical report | Server owners and developers | [Open](docs/KS-SERIES-REPORT.en.md) |
| Player guide | Players | [Open](docs/KS-SERIES-PLAYER-README.en.md) |
| Real-estate and dungeon guide | Players | [Open](docs/房地产与副本插件玩家教程.en.md) |
| Codebase map | Developers | [Open](docs/CODEBASE_MAP.md) |
| Economy knowledge base | Maintainers | [Open](docs/economy-knowledge-base/README.en.md) |
| Survival RPG knowledge base | Maintainers | [Open](docs/survival-rpg-knowledge-base/README.md) |

## License

KS Series is distributed under the [Mozilla Public License 2.0](LICENSE). Covered files may be copied, modified,
built, used commercially, and redistributed. Modified covered files must continue to provide their source and retain
the license and copyright notices. Independent files combined with the project may use their own license. Third-party
dependencies and external assets remain subject to their own terms.

## Maintenance

Documentation is maintained against the current source, configuration, and verified behavior. The repository runs a
scheduled documentation-pair check every week and on every change to Markdown files. A passing check confirms that
each published document has both language entries; it does not replace testing or review of behavioral accuracy.
