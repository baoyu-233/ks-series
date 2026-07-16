# Economy Knowledge Base

这是 ks-Series 经济系统的长期设计、实现边界和验证记录。它用于回答三个问题：

1. 一个功能为什么存在，它解决哪条资源或货币流动问题。
2. 当前实现由哪个模块、数据表和入口负责，哪些线程可以接触哪些对象。
3. 一项旧设计是继续保留、重做还是退役，依据是什么。

## 文件索引

- [platform.md](platform.md)：平台分层、数据契约、线程契约、权限和入口原则。
- [domains.md](domains.md)：市场、银行、企业、税收、地产、盲盒等领域的职责与经济闭环。
- [decisions.md](decisions.md)：已接受和待确认的长期产品/技术决策。
- [enterprise-blindbox-tickets.md](enterprise-blindbox-tickets.md)：企业盲盒票券的现状审计、真实数据和去留建议。
- [claude-import.md](claude-import.md)：从 `.claude` 历史记忆中提取并重新核实的有效内容。
- [verification-log.md](verification-log.md)：按日期记录构建、部署、数据快照和未验证项。

## 事实优先级

发生冲突时按以下顺序判断：

1. 当前源代码、数据库 schema 和只读运行数据。
2. `docs/CODEX_MEMORY.md` 与 `docs/CODEBASE_MAP.md` 的当前交接记录。
3. 本知识库中状态为“已接受”的决策。
4. `KS-SERIES-REPORT.md`、玩家教程和其他阶段性报告。
5. `.claude` 历史记忆。它只作为定位线索，必须重新对照当前代码。

## 更新规则

- 业务职责、结算边界或权限模型改变时，更新 `platform.md`、`domains.md` 或 `decisions.md`。
- 一个功能被实测、否决、改造或退役时，更新对应专题和 `verification-log.md`。
- 部署事实仍以 `CODEX_MEMORY.md` 为快速入口；本目录保存原因和长期设计，不重复堆积每次临时构建信息。
- 不把构想写成已实现功能，不把静态审计写成游戏内实测。
