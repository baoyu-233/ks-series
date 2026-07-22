# KS-Series Documentation Index

> [English](DOCUMENTATION.en.md) | 中文

公开文档统一采用以下语言文件约定：默认 `.md` 文件保留中文或现有主语言，英文伴随文件使用 `.en.md`；原本
以英文为主的知识库文件，中文伴随文件使用 `.zh-CN.md`。每份文档顶部都应有中英文互链。

## 入口文档

| 中文 | English |
|---|---|
| [项目首页](../README.md) | [Project README](../README.en.md) |
| [完整插件报告](KS-SERIES-REPORT.md) | [Full plugin report](KS-SERIES-REPORT.en.md) |
| [玩家指南](KS-SERIES-PLAYER-README.md) | [Player guide](KS-SERIES-PLAYER-README.en.md) |
| [指令参考](../COMMANDS.md) | [Command reference](../COMMANDS.en.md) |
| [测试指南](../TESTING.md) | [Testing guide](../TESTING.en.md) |
| [性能报告](../PERF_REPORT.md) | [Performance report](../PERF_REPORT.en.md) |
| [ks-Eco 系列玩法白皮书](KS-ECO-GAMEPLAY-WHITEPAPER.md) | 本轮仅维护中文版 |
| [跨服测试网络与 MCSM 运行边界](../network_1_21/README.md) | 本轮仅维护中文版 |
| [2026-07-23 ks-Eco 全功能与三端兼容验收](KS-ECO-FULL-FUNCTION-TEST-2026-07-23.md) | 本轮仅维护中文版 |
| [仓库修改日志](../CHANGELOG.md) | 本轮仅维护中文版；各模块目录另有本地日志 |
| [2026-07-18 Bug Hunt 报告](BUG-HUNT-2026-07-18.md) | [2026-07-18 Bug Hunt report](BUG-HUNT-2026-07-18.en.md) |
| [2026-07-18 Bug Hunt 当前收口入口（中文交接）](BUG-HUNT-HANDOFF-2026-07-18.md) | 暂无英文交接文件 |

## 知识库

- `economy-knowledge-base/`：经济平台、领域、决策、盲盒票券和验证记录。
- `survival-rpg-knowledge-base/`：生存 RPG 愿景、内容架构、配装、路线图和决策。
- `boss-knowledge-base/`：Boss 平台基线、遭遇规格和实测日志。

每个目录都同时维护中文与英文文件。GitHub Action 会在每周定时任务以及 Markdown 变更时检查配对是否完整。

## 维护边界

语言版本必须与当前源码、配置和已验证行为同步。提案、静态审计和历史记忆要明确标注状态；运行时数据库、
凭据、服务端日志、资源包、模型和测试服私有配置不属于公开文档。
