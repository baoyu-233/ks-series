# Dungeon, Economy, and Combat Progression

> [中文](dungeon-economy-integration.zh-CN.md) | English

## Verified Existing Foundation

`ks-Eco-RealEstateDungeon` already provides instance templates, party size limits, a leader-paid entry
ticket, property-key requirements, escalating paid revives, MythicMobs boss tracking, timeout handling,
and completion rewards. Its reward bridge can grant money, MythicMobs items, MMOItems, and controlled
server commands per participant.

This makes the dungeon system the correct place to connect economy-funded preparation with
combat-earned progression. It must not become a mechanism that sells combat power directly.

## Three Reward Lanes

### Economy Lane: purchasable and tradeable

- Entry tickets, consumables, repairs, revival attempts, common crafting materials, and base equipment.
- Players may buy these from other players, the market, or NPC/server sinks.
- This lane improves readiness, convenience, and recovery. It cannot unlock a defining active skill,
  a final equipment breakthrough, or entry to the next combat tier by itself.

### Survival Lane: gathered and crafted

- Regional resources, farming inputs, rare exploration drops, recipes, and upgrade components.
- This keeps ordinary survival, gathering, building logistics, and player trade relevant to RPG players.
- Crafting should consume both survival materials and modest currency so progress returns value to the economy.

### Combat Lane: account-bound proof of mastery

- Boss seals, combat licenses, technique fragments, dungeon clear records, and breakthrough authority.
- These are granted only by successful combat objectives and are not marketable, blind-box rewards, or
  direct shop items.
- A player spends the proof together with economic and survival inputs to unlock a key active ability,
  a new ability slot, a specialization choice, or a higher equipment tier.

## Required Upgrade Formula

Every meaningful upgrade needs inputs from at least two lanes. Key upgrades require all three:

`survival materials + currency/crafting cost + combat proof = ability or breakthrough`

Examples:

- Upgrade a weapon from Tier 1 to Tier 2: crafted alloy + coins + field-elite mark.
- Unlock a shield-reflection active skill: consumable training manual + coins + Frostbound clear seal.
- Enter a higher dungeon: purchasable expedition ticket + crafted key + prior boss license.

The economy can accelerate preparation, but it cannot replace the combat proof.

## Dungeon Reward Policy

- Keep direct dungeon money low and predictable. Tickets and revives are money sinks, not a reason to
  repeatedly print currency.
- Give tradeable materials and cosmetics as normal drops; cap high-value supply by template, difficulty,
  and weekly/account limits when needed.
- Use completion commands only for bound combat proofs or audited progression grants. Do not use commands
  to award tradeable best-in-slot equipment.
- Boss-specific rewards should favor fragments or licenses. Finished gear remains a rare side reward and
  must have an economy sink such as repair, refinement, or inheritance cost.

## First Implementation Slice

1. Add a player progression ledger for combat proofs and unlocked abilities.
2. Add one field-elite mark, one dungeon-clear seal, and one boss-clear seal.
3. Add one base equipment upgrade purchasable/craftable with coins and survival materials.
4. Gate its breakthrough or active ability behind the relevant combat seal.
5. Deliver combat seals from dungeon `reward_config` through a dedicated progression API, replacing the
   initial command-only integration before it becomes a high-value system.
