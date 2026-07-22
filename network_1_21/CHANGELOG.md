# 跨服测试网络修改日志

## 2026-07-23

- Leaves、Paper、Folia 三端完成跨服资产、Folia Extra 与 InstanceWorld 收口部署；双 Web 验收端口为 8123/58578。
- MAP 实测 bundle 包含 3 个图块；PROPERTY 实测聚合 4 套房产，策略拒绝 `world_private`，transfer 保持关闭。
- Folia 加载银行、企业、政治、税务、地产、副本 6 个 Extra；旧重复 `*-folia.jar` 已按备份索引封存后清理，启动日志无插件名歧义。
- MCSM、daemon、MariaDB、BungeeCord、三后端和双 Web 共 9 个端口最终全部监听。

## 2026-07-22

- 新增 MCSM MariaDB、BungeeCord、Leaves、Paper、Folia 五实例清单。
- Leaves 复用现有主服；Paper 安装 RPG/ks 栈；Folia 仅安装 ks-core/ks-Eco 独立制品。
- 迁移 139 张 SQLite 源表并完成真实三端 MariaDB、跨服事件和游标烟测。
- 运行时配置改用工作区外密码文件，公开网络文档不包含凭据。
- 当前测试网络五实例保持运行，玩家通过 `127.0.0.1:25577` 进入。
