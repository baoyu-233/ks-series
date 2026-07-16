# ItemEditor Player Guide

> [中文](GUIDE_PLAYER.md) | English

Use `/design` to get a clickable Web designer link. The editor supports material search, six special-symbol groups,
live name/lore preview, enchantment selection, conflict warnings, formatting buttons, and template management.

## Templates

Save a player template to receive a code such as `pl-a1b2c3d4`. Share or load it with `/design load <code>`. The
`op-` prefix identifies administrator templates and cannot be loaded by ordinary players.

## Refinement

1. Hold the weapon.
2. Run `/refine`.
3. Edit the name, lore, and supported enchantments.
4. Confirm to consume the configured voucher and save the result.

Closing the GUI or disconnecting returns the locked item. The configured player enchantment limit is normally 10;
FotiaEnchantment entries and administrator-only attributes are excluded.

Formatting uses `&` color and style codes such as `&6`, `&l`, `&o`, `&n`, `&m`, `&k`, and `&r`.
