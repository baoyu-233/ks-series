# ks-Eco-politic 修改日志

## 2026-07-23

- 声明 `folia-supported=true`，选举和表决公告定时器改用 ks-Eco `EcoScheduler`，SQL 检查投递数据库 lane。
- Web 玩家名称、在线权限、离线候选快照和全服公告分别回交 global/entity owner，移除旧 Bukkit scheduler 与 `isPrimaryThread()`。
- 新增 Folia metadata 合同测试；模块 16 项测试通过。未部署，未启动服务器。

## 2026-07-22

- 参议院投票只在剩余选票无法逆转绝对多数时提前结束。
- 政治配置/职务缓存接入跨服失效并修复远端快照列名。
- 15 项测试通过；本轮未在 Folia 启用政治 Extra。
