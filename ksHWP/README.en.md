# ksHWP v1.2.0 - Persistent Web World Map

> [中文](README.md) | English

ksHWP renders Minecraft worlds as a public community Web map with multiple dimensions, notes, region management,
player positions, disk caching, unexplored markers, and zoom-level composition.

## Player Use

Run `/map` in game to receive an authenticated link. See the [player guide](GUIDE.en.md).

## Admin Commands

```text
/kshwp status
/kshwp reload
/kshwp forcerender [world]
/kshwp forcerender-area <world> <x1> <z1> <x2> <z2>
/kshwp prerender [world]
/kshwp cache
/kshwp clearcache [world]
```

Permissions: `kshwp.admin` for administration, `kshwp.use` for the map, `kshwp.note` for notes, and `kshwp.hidden`
for hiding a player position. The map uses `ks-core` for gateway, authentication, and storage. Build with
`cd ksHWP && mvn clean package`.
