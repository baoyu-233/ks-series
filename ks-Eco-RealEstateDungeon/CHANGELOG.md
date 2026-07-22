# ks-Eco-RealEstateDungeon 修改日志

## 2026-07-23

- 完成 Folia 调度适配并声明 `folia-supported=true`；移除 BukkitScheduler、`isPrimaryThread` 旧路径。
- 玩家消息、Vault、Inventory、PDC、复活与奖励改由玩家实体调度；刷怪与 Boss 检查归属区域/实体线程。
- 跨区域传送改用 `teleportAsync`，奖励命令在 global 调度器执行，proof 在玩家实体线程复核。
- 增加 Folia 元数据合同测试；FAWE、MythicMobs、MMOItems 缺失时对应功能保持清晰失败与可恢复状态。
- 修复启用期向 `ks-InstanceWorld` 注册 schematic namespace 被错误 global-thread 守卫拒绝的问题；注册本身只更新并发路径表，不触碰 Bukkit 世界。
- 29 项测试通过；Folia 实机启动已加载该 Extra，但因实验端未安装 FAWE/WorldEdit 与 MythicMobs，尚未宣称 schematic 粘贴和 Boss 全流程通过。

## 2026-07-22

- 门票扣款、准入、退款和付费复活增加持久状态机、恢复与人工复核。
- 多 Boss 完成、逐玩家奖励计划、幂等奖励重试和实例清理边界得到加强。
- 继续通过 `ks-InstanceWorld` 管理网格、原理图与释放生命周期。
- 28 项测试通过；离线 proof 和真实崩溃窗口仍需游戏内验收。
