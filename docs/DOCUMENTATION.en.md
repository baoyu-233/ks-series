# KS-Series Documentation Index

> English | [中文](DOCUMENTATION.md)

Published documents use a predictable pair convention. A default `.md` file keeps the Chinese or established primary
language; English companions use `.en.md`. Files whose primary content is English use `.zh-CN.md` for the Chinese
companion. Every document should link to both versions at the top.

## Entry Documents

| Chinese | English |
|---|---|
| [项目首页](../README.md) | [Project README](../README.en.md) |
| [完整插件报告](KS-SERIES-REPORT.md) | [Full plugin report](KS-SERIES-REPORT.en.md) |
| [玩家指南](KS-SERIES-PLAYER-README.md) | [Player guide](KS-SERIES-PLAYER-README.en.md) |
| [指令参考](../COMMANDS.md) | [Command reference](../COMMANDS.en.md) |
| [测试指南](../TESTING.md) | [Testing guide](../TESTING.en.md) |
| [性能报告](../PERF_REPORT.md) | [Performance report](../PERF_REPORT.en.md) |
| [2026-07-18 Bug Hunt 报告](BUG-HUNT-2026-07-18.md) | [2026-07-18 Bug Hunt report](BUG-HUNT-2026-07-18.en.md) |

## Knowledge Bases

- `economy-knowledge-base/`: economy platform, domains, decisions, blind-box tickets, and verification records.
- `survival-rpg-knowledge-base/`: survival RPG vision, architecture, loadouts, roadmap, and decisions.
- `boss-knowledge-base/`: Boss platform baseline, encounter specifications, and playtest logs.

Every directory maintains Chinese and English entries. GitHub Actions checks pair completeness weekly and whenever
Markdown files change.

## Maintenance Boundary

Language versions must track current source, configuration, and verified behavior. Proposals, static audits, and
historical memory must state their status. Runtime databases, credentials, server logs, resource packs, models, and
private test-server configuration do not belong in public documentation.
