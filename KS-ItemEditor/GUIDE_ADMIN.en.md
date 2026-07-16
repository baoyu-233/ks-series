# ItemEditor Administrator Guide

> [中文](GUIDE_ADMIN.md) | English

Administrators with `itemedit.admin` can edit held items through `/itemedit`, use `/ie web`, reload with
`/itemedit reload`, manage templates, apply ItemsAdder models, configure FotiaEnchantment conflict handling, and edit
27 vanilla attribute types.

## Template Boundary

- `op-xxxxxxxx` templates are administrator-only.
- `pl-xxxxxxxx` templates are player-safe and strip FotiaEnchantment, ItemsAdder, and attribute modifier data.
- Importing a template creates an editable copy; the source template is unchanged.

## Safety and Compatibility

The Web server should bind to `127.0.0.1` unless a firewall and an explicit public address are configured. Links expire
according to `token-timeout-seconds`. FotiaEnchantment and MythicMobs are called through reflection. FotiaEnchantment
levels remain bounded by its own `max-level`, even when vanilla administrator levels allow 32767.

Attribute modifiers support additive, base-multiplicative, and total-multiplicative operations and can target any,
mainhand, offhand, head, chest, legs, or feet slots.
