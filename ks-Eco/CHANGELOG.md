# ks-Eco 修改日志

## 2026-07-23

- 接通跨服地图、房产与资产不可变快照运行时：schema 初始化、心跳、发布和查询全部进入数据库 lane，远端查询不会读取 Bukkit `World`。
- 新增按 server/world/dimension 的默认拒绝策略，deny 优先于 allow；策略和快照大小、TTL、离线/过期窗口支持严格校验后原子热加载，数据库身份与 transport 仍为重启项。
- 新增认证 Web API：玩家可读取获授权 MAP/PROPERTY 与资产汇总；ASSET 原始快照和资产明细仅管理员可读，策略查看/更新也仅限管理员；被禁用世界与维度返回不可见结果。
- 增加压缩快照解码硬上限与校验和验证，避免共享库异常数据造成解压内存放大。
- ks-Eco 全量 196 项测试通过，0 failure/error/skipped。
- Leaves 与 Folia 各执行 29 项同口径 HTTP 合同：27 个成功响应、1 个预期未认证 401、1 个预期缺参 400；两端均报告 federated ready、Extra 6、PROPERTY 聚合 4 套。

## 2026-07-22

- 跨服 JDBC transport、游标、缓存失效、心跳与 fenced lease 完成真实三端运行接线。
- 普通市场、个人工程、个人/企业房产、限售等关键资金路径补充持久恢复和人工复核边界。
- 新增独立 Folia 制品和 `EcoScheduler`；旧 Bukkit 调度调用清零，无 Vault 时使用 JDBC 内置经济。
- Folia 自动禁用未适配 Extra 与全服财富榜，避免以兼容声明代替真实区域线程安全。
- MariaDB 启动迁移补齐 Web 和遗留业务表；真实迁移数据完成启动验收。
- 默认版与 Folia 版各 180 项测试通过，并部署到 Leaves、Paper、Folia。
