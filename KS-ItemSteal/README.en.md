# KS-ItemSteal v1.0.2 - Safe Disarm and Return Helper

> [中文](README.md) | English

KS-ItemSteal records which thief took which configured weapon from which owner and returns all stored items when the
thief dies. It preserves enchantments, names, and NBT, persists records across restarts, and delivers to an offline
owner when they next join.

## Configuration

```yaml
suffixes:
  - _SWORD
  - _AXE
  - SHIELD
  - TRIDENT
```

Reload with `/itemsteal reload`. MythicMobs can call `itemsteal` commands from a skill; a warning skill can give
players a reaction window before a boss disarms them. Players may use `/itemsteal givebow <player>` when the server
enables the one-shot soul bow flow.

Emergency/admin commands are `/itemsteal return <thiefUuid>`, `/itemsteal reload`, and
`/itemsteal steal <thiefUuid> <victimUuid>`. Build with JDK 21 and Maven, then deploy `target/KS-ItemSteal.jar` to
`plugins/`.
