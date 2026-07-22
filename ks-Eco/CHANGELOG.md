# ks-Eco 修改日志

## 2026-07-22

- 跨服 JDBC transport、游标、缓存失效、心跳与 fenced lease 完成真实三端运行接线。
- 普通市场、个人工程、个人/企业房产、限售等关键资金路径补充持久恢复和人工复核边界。
- 新增独立 Folia 制品和 `EcoScheduler`；旧 Bukkit 调度调用清零，无 Vault 时使用 JDBC 内置经济。
- Folia 自动禁用未适配 Extra 与全服财富榜，避免以兼容声明代替真实区域线程安全。
- MariaDB 启动迁移补齐 Web 和遗留业务表；真实迁移数据完成启动验收。
- 默认版与 Folia 版各 180 项测试通过，并部署到 Leaves、Paper、Folia。
