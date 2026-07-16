# Platform Baseline

## Verified Stack

- MythicMobs Premium `5.12.0-c087ceb9`
- ModelEngine `R4.0.9`
- MythicCrucible `5.12.0`
- MythicLib `1.7.1-SNAPSHOT`
- MMOCore `1.13.1-SNAPSHOT`
- MMOItems `6.10.1-SNAPSHOT`
- ItemsAdder `4.0.16`

## Design Capabilities

MythicMobs supplies targeters, delayed skills, named auras, aura stacks, incoming-damage callbacks,
damage cancellation, particles, helper mobs, scoreboard tags and ModelEngine state mechanics.

MMOItems types are available for equipment-specific logic. Exact per-type incoming-damage scaling is
implemented in `ks-BossCombat` through a narrow runtime MMOItems API hook, rather than by guessing item NBT.

## Rules

- Give unavoidable control effects a visible telegraph and a cooperative release path.
- A failure state must cost health, time, positioning, or a damage window; it must not merely show an effect.
- A successful team mechanic should create a measurable counterplay window.
- Keep helper mobs bounded by delayed removal and target only the associated boss type.
