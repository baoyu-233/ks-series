# Boss 平台基线

> [English](platform.md) | 中文

本文件记录 Boss 内容所依赖的 MythicMobs、ModelEngine、MMOItems、MythicLib、MMOCore、ItemsAdder 和 ProtocolLib
版本，以及 ks-BossCombat 与 ks-RPG 的职责边界。

Boss 的控制效果必须有清晰的模型或粒子预警，并提供约 1.5 至 2.5 秒的反应窗口。Boss 内容配置由
MythicMobs 负责；跨插件武器适应和战斗规则由 ks-BossCombat 或未来的 ks-RPG 负责。
