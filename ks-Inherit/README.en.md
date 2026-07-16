# ks-Inherit v1.0.0 - Cross-Version Item Inheritance

> [中文](README.md) | English

Preserve player items during a migration from 1.20.6 to 1.21.11 through a GUI deposit, complete NBT serialization,
SQLite persistence, Web review, and OpenInv delivery.

## Flow

```text
1.20.6: /inherit open -> deposit items -> close and save
1.21.11: Web review -> approve or reject -> deliver to the player
```

Shulker boxes are blocked by default to prevent nested storage abuse. Ordinary players use `/inherit open` with
`ksinherit.use`; administrators use `/inherit slots <player> <amount>` with `ksinherit.admin`.

Configuration includes `default-slots`, `gui-rows`, and `allow-shulker-boxes`. Build with JDK 21 and Maven, then deploy
the JAR to `plugins/` and restart the server. The Web panel is exposed through `ks-core` at `/ks-Inherit/` when the
gateway is available.
