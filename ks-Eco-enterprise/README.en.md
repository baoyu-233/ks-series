# ks-Eco-enterprise v1.1.0

> [中文](README.md) | English

Enterprise registration and tendering: organization management, procurement, subcontracting, consortiums, and
qualification checks.

## Features

- Register private or state enterprises with an owner, legal representative, and paid-in capital.
- Dissolve an enterprise and return remaining assets proportionally.
- Publish projects with budget, advance ratio, penalty ratio, deadline, and location.
- Submit bids when registered capital is at least 75% of the project budget.
- Automatic lowest-price award with advance settlement.
- Web panel at `/ks-Eco/enterprise`.

Tables: `ks_ent_enterprises`, `ks_ent_members`, `ks_ent_projects`, and `ks_ent_bids`.

Build with `cd ks-Eco-enterprise && mvn clean package`, then place the JAR in `plugins/ks-Eco/extra/`.
