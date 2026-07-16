# Decision Log

## Accepted

- The server is survival-first and RPG-progressive, not a lobby MMO.
- Bosses are cooperative advantage content, not the sole route to basic progression.
- Boss-specific cross-plugin combat rules belong in `ks-BossCombat`, not `ks-Compat`.
- The Frostbound Conductor is a prototype for readable teamwork mechanics and must be tuned from
  live data before it becomes a permanent progression gate.
- Purchasable power is limited to preparation, base ranks, recovery, and convenience. Defining active
  abilities and equipment breakthroughs require non-tradeable combat proof earned from RPG combat.
- `ks-Skill` and `ks-BossCombat` are approved to converge into `ks-RPG`. The new foundation owns player
  progression, ability grants, skill triggers, combat proofs, and narrow cross-plugin combat rules;
  it does not own MythicMobs encounter definitions, MMOItems equipment data, or ks-Eco settlement.
- MMO integration is survival-first and single-identity: MMOCore provides restrained baseline attributes,
  resources, and region-scoped proficiency; MMOItems provides equipment and crafting; MMOInventory exposes
  only a small accessory surface. MMOProfiles stays uninstalled because profile-specific identity, inventory,
  location, and balance are incompatible with this server's persistent survival world and economy.
- Core active abilities and account-bound combat authorization belong to `ks-RPG`, never to a parallel
  MMOCore skill system. MMOCore's built-in party and guild features must be disabled in favor of the
  existing dungeon party flow and the server's established social/economy systems.
- The first release before a dungeon is a low-load relic prelude: one shared starter identity, a small fixed
  equipment catalog, regional elites/special resource nodes, and non-tradeable cooperation marks. Ordinary
  survival harvesting and automated machines are not general RPG XP sources.

## Open Questions

- What are the first three player-facing professions or identities, and which existing survival
  activity does each deepen?
- Is the first group dungeon instanced, a protected overworld structure, or a timed world event?
- Which rewards are tradeable, account-bound, cosmetic, or purely material inputs?
- What is the intended player-count band for the first boss and dungeon release?
- Which economy metrics will cap reward injection before Stage 2 launches?
