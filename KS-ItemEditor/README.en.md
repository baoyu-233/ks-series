# KS-ItemEditor v1.4.0

> [中文](README.md) | English

ItemEditor provides in-game and Web item editing, player templates, administrator templates, preview rendering,
attribute modifiers, ItemsAdder model support, FotiaEnchantment/MythicMobs reflection compatibility, and voucher-based
weapon refinement.

## Entry Points

| Command | Purpose |
|---|---|
| `/itemedit` or `/ie` | Open the GUI editor. |
| `/design` | Open the player Web designer. |
| `/design load <template>` | Load a `pl-` player template. |
| `/refine` | Open player weapon refinement. |
| `/itemedit web` | Get the admin Web editor link. |
| `/itemedit reload` | Reload configuration. |

Administrator templates use `op-` and cannot be loaded by players. Player templates strip administrator-only
FotiaEnchantment, ItemsAdder, and attribute data. The Web server is configured with `bind-address`,
`public-address`, and a token timeout. ItemsAdder, FotiaEnchantment, and MythicMobs are soft dependencies accessed by
reflection, so updating them does not require compiling ItemEditor again.

Build with JDK 21 and Maven. The player and administrator guides contain the complete workflows:
[player guide](GUIDE_PLAYER.en.md) and [administrator guide](GUIDE_ADMIN.en.md).
