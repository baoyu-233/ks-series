# ks-Eco-tax v1.1.0

> [中文](README.md) | English

Tax and macro-control module with progressive rates, live rate changes, penalties, and evasion detection.

| Tax type | Default | Meaning |
|---|---:|---|
| `MARKET_TRADE` | 2% | Market transaction tax |
| `OFFICIAL_TRADE` | 0% | Official trade exemption |
| `ENTERPRISE_SMALL` | 5% | Enterprise below 100,000 capital |
| `ENTERPRISE_MEDIUM` | 8% | Enterprise from 100,000 to 500,000 |
| `ENTERPRISE_LARGE` | 12% | Enterprise above 500,000 |
| `BANK_INTEREST` | 10% | Bank interest tax |
| `PENALTY_TAX` | 20% | Penalty tax |

The module provides progressive enterprise rates, Web adjustments, a minimum tax of 1.0, configurable penalties,
and records in `ks_tax_records`, `ks_tax_penalties`, and `ks_tax_rates`. Its Web panel is `/ks-Eco/tax`.

Build with `cd ks-Eco-tax && mvn clean package`, then place the JAR in `plugins/ks-Eco/extra/`.
