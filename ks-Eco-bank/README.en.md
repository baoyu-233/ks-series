# ks-Eco-bank v1.1.0

> [中文](README.md) | English

Central and commercial banking for the KS-Series economy: deposits, loans, macro rates, and M0/M1/M2 money-supply
tracking.

## Dependencies

- `ks-core` for the Web gateway and shared storage.
- `ks-Eco` v1.1.0+ for economy settlement.

## Features

- One automatically created central bank with a 3.5% base rate and 10% reserve ratio.
- Player-created commercial banks with owner/capital requirements.
- Demand and term deposits, 8% loan rate, repayment, and liquidity tracking.
- Web panel at `/ks-Eco/bank`.

Tables: `ks_bank_banks`, `ks_bank_accounts`, `ks_bank_loans`, `ks_bank_cb_rates`, and `ks_bank_money_supply`.

Build with `cd ks-Eco-bank && mvn clean package`, then place the JAR in `plugins/ks-Eco/extra/`.
