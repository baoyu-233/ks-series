# ks-Eco-enterprise v1.1.0

> [中文](README.md) | English

Enterprise registration and tendering: organization management, procurement, subcontracting, consortiums, and
qualification checks.

## Features

- Register private or state enterprises with an owner, legal representative, and paid-in capital.
- Submit durable join requests; membership is created only after approval by a manager with member-management access.
- Ordinary members may leave with confirmation, managers may remove members, and owners must resolve ownership first.
- A confirmed single-owner dissolution is blocked by active/overdue loans or pending loan requests.
- Distribute dividends with normalized fractional or legacy percentage tax rates and write batch, recipient, and tax logs.
- Apply administrator-managed enterprise levels to blind-box eligibility and enterprise land bonuses.
- Publish projects with budget, advance ratio, penalty ratio, deadline, and location.
- Submit bids when registered capital is at least 75% of the project budget.
- Automatic lowest-price award with advance settlement.
- Web panel at `/ks-Eco/enterprise`, including full administrator editing for identity, owners, capital, balance,
  dividend rate, status, and level.

Tables include `ks_ent_enterprises`, `ks_ent_members`, `ks_ent_join_requests`, `ks_ent_projects`, `ks_ent_bids`,
`ks_ent_dividends`, and `ks_ent_dividend_payouts`. Join, leave, removal, and dissolution paths maintain request and
member-count state; dividend tax also writes the core tax ledger.

The current version has passed compilation and desktop Web regression checks. Join approval, dividend settlement,
leave, and dissolution still require in-game acceptance testing after deployment.

Build with `cd ks-Eco-enterprise && mvn clean package`, then place the JAR in `plugins/ks-Eco/extra/`.
