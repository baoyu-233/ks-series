# 跨服测试网络修改日志

## 2026-07-22

- 新增 MCSM MariaDB、BungeeCord、Leaves、Paper、Folia 五实例清单。
- Leaves 复用现有主服；Paper 安装 RPG/ks 栈；Folia 仅安装 ks-core/ks-Eco 独立制品。
- 迁移 139 张 SQLite 源表并完成真实三端 MariaDB、跨服事件和游标烟测。
- 运行时配置改用工作区外密码文件，公开网络文档不包含凭据。
- 当前测试网络五实例保持运行，玩家通过 `127.0.0.1:25577` 进入。
