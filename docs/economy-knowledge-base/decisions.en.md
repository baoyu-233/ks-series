# Economy Decisions

> [中文](decisions.md) | English

Accepted direction: official dynamic pricing combines supply pressure from real official SELL volume with a
mean-reverting random-walk drift. `official-buy.default-items` is the only direct buyback catalog; internal reference
prices remain a protection floor.

Accepted safety direction: preserve NBT, use `/storage` for overflow, make settlement idempotent, keep live Bukkit
objects on the server thread, and isolate SQL from callbacks. Enterprise blind-box tickets must remain traceable to
the enterprise public account and ticket configuration.

Open decisions and historical alternatives remain in [decisions.md](decisions.md).
