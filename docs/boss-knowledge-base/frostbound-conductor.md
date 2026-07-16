# Frostbound Conductor

## Identity

A four-player frost raid boss that punishes static damage rotations. Its default pressure is Frostbite,
close-range Rime Bite, and a 50% damage reduction against MMOItems Hammer/Greathammer/Spear/Lance types.

## Cooperation Reward

Every successful mechanic activates `Frostbound_WeaponWindow` for five seconds. The Boss loses its
`Frostbound_WeaponAdaptation` scoreboard tag, so the ks-BossCombat listener stops reducing those weapon types.
This makes coordinated responses directly valuable to the weapons the boss normally counters.

## Core Mechanics

- Ice Prison: five cancelled teammate hits free the trapped player.
- Mirror Guard: two nearby shields must be raised simultaneously.
- Frozen Frontline: destroy both walls before they close.
- Whiteout: split across two Storm Eyes while a raid-wide Frostbite pulse is active.
- Beacon Trial: identify and destroy the cyan true beacon.
- Snowblind Judgment: destroy the boss's exposed rear ice heart.
- Ice Core Relay: hit the current carrier to transfer the core three times.

## Tuning Direction

The encounter should feel relentless between mechanics, but each major failure should remain recoverable
with healing and disciplined positioning. Do not reintroduce passive random safe spots for Whiteout.
