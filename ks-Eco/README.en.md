# ks-Eco v1.1.0

> [中文](README.md) | English

**KS-Series economy core**: player market, official buyback, player trades, NBT-aware storage, dynamic pricing, and
test-trade intervention.

## Features

- `/market`: 54-slot listing and purchase GUI.
- Official buyback for the configured production-item catalog.
- Weighted blind boxes with rarity and pity replacing the old direct-sale path for non-production rewards.
- `/trade`: two-party item and currency exchange.
- `/storage`: pending delivery and refund queue with recursive shulker-box handling.
- Dynamic pricing from real official SELL volume, supply pressure, and mean-reverting drift; default ceiling ±30%.
- `/ks-Eco` Web management routes for economy and optional Extra modules.
- Runtime loading of JARs from `plugins/ks-Eco/extra/`.

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/market` | `kseco.market` | Open the market. |
| `/trade <player>` | `kseco.trade` | Start a trade. |
| `/storage` | `kseco.storage` | Open storage. |
| `/kseco web` | `kseco.admin` | Get the admin Web link. |
| `/kseco status` | `kseco.admin` | Show economy state. |
| `/kseco reload` | `kseco.admin` | Reload configuration. |
| `/kseco force-price <item> <price>` | `kseco.admin` | Set a bounded price override. |
| `/kseco void-trade <item> <amount> <price> <BUY\|SELL>` | `kseco.admin` | Add a test/intervention trade. |

Aliases: `/kse`, `/mkt`, `/ah`, `/deal`, `/stash`, `/chest`.

## Pricing Model

```text
supplyPressure = clamp((recentSell - baseline) / baseline, -1, 1)
totalOffset = clamp(driftValue * maxFluctuation - supplyPressure * maxFluctuation,
                    -maxFluctuation, maxFluctuation)
officialBuy = round(basePrice * (1 + totalOffset), 2)
```

Test trades are marked `is_test` and do not contaminate official history. Build with `cd ks-Eco && mvn clean package`.
