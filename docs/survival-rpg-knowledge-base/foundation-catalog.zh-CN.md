# 第一季 RPG 基础目录

> [English](foundation-catalog.md) | 中文

目录定义基础材料、品质层级、兑换比例、首季饰品和配装限制。材料兑换由 ks-RPG 负责并在扣除前检查玩家
库存容量；MMOItems 负责装备和名称表达；MMOInventory 只提供有限的戒指与护符槽位。

新增内容优先使用 `plugins/ks-RPG/content/` 下的 YAML，并通过 `/ksrpg reload` 严格解析、原子替换；配置
错误时保留上一份有效目录。
