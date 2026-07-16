# Content Architecture

## Foundation Layer

Vanilla survival, land/real-estate, player trading, the official market, storage, enterprises, and
community projects form the persistent world. These systems provide ownership, materials, currency,
and social identity.

## Progression Layer

MMOCore and MMOItems add optional professions, combat identities, crafted gear, and resource sinks.
Progression should first modify how players approach familiar activities, such as gathering rare
materials in dangerous biomes or using specialized tools, before it introduces fully separate content.

## World Challenge Layer

MythicMobs supplies field elites, events, dungeon encounters, and bosses. ModelEngine and ItemsAdder
provide readable models and telegraphs. ks-BossCombat owns only cross-plugin combat rules that cannot
be expressed safely in MythicMobs configuration.

## Reward Loop

Exploration produces materials and maps. Crafting and professions convert them into equipment or
economy value. Bosses and coordinated content award scarce upgrade inputs, cosmetics, or access keys,
not unrestricted finished best-in-slot gear. The market and repair/upgrade systems remove value again.

Combat proofs are account-bound. Currency, materials, tickets, repairs, and base equipment remain part
of the economy; key active abilities and tier breakthroughs require combat proof in addition to those inputs.
See `dungeon-economy-integration.md` for the required three-lane progression rule.

## Ownership Boundaries

- `ks-Eco`: currency, market, storage, enterprises, real estate, and economy-facing rewards.
- `ks-Skill`: server-specific player skills and progression hooks.
- MythicMobs packs: encounter definitions, mob behavior, drops, and visual telegraphs.
- `ks-BossCombat`: narrow runtime combat rules requiring MMOItems or Bukkit event integration.
- Knowledge bases: product decisions and verified behavior; they do not replace current code/config inspection.
