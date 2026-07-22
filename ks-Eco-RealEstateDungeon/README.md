# ks-Eco-RealEstateDungeon

ks-Eco 的副本经济 Extra，负责门票、组队、实例准入、死亡与付费复活、Boss 通关判定、奖励账本和 Web/命令入口；实例世界、网格、原理图与清理由 `ks-InstanceWorld` 提供。

## Folia 边界

- 模块声明 `folia-supported=true`，通过 `EcoScheduler` 使用 global、玩家实体与区域调度器。
- 玩家消息、状态、Inventory、PDC、Vault 与跨区域传送在玩家实体所有者线程执行；传送统一使用 `teleportAsync`。
- MythicMobs 刷怪在目标区块区域线程执行，Boss 存活检查在 Boss 实体所有者线程执行。
- 奖励命令在 global 调度器执行；命令产生的玩家 proof 在目标玩家实体线程复核。SQL 结算继续由 ks-Eco 数据库工作 lane 执行。
- Folia 上使用原理图需要 Folia 兼容的 FAWE；MythicMobs、MMOItems 缺失时对应刷怪/物品奖励会明确失败并保留重试或复核状态，不影响模块装载。

## 配置

玩法参数位于模块数据目录的 `dungeon.yml`，支持现有严格校验与运行时更新模式。数据库、服务端身份和实例世界依赖属于重启边界。

## 构建

```powershell
mvn clean test package
```

本模块不单独部署；构建后的 Extra JAR 应放入 ks-Eco 的 `extra` 目录，并同时安装匹配版本的 `ks-InstanceWorld`。
