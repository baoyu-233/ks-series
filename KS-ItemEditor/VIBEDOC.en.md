# FotiaEnchantment Item-Authoring Notes

> [中文](VIBEDOC.md) | English

This document explains the conventions used when authoring FotiaEnchantment-compatible items through KS-ItemEditor.
Keep custom enchantment identifiers and maximum levels in FotiaEnchantment's own configuration. ItemEditor writes
compatible item data through reflection and does not bake generated lore into the item.

Use the preview panel to check name, lore, colors, Unicode symbols, and formatting before saving. Treat player
templates as untrusted input: only administrator templates may contain custom-enchantment, model, or attribute data.
After changing third-party enchantments, verify conflict warnings, natural level limits, and the final in-game lore on
the target server.
