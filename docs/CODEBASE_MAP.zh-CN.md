# ks-Series 代码地图

> [English](CODEBASE_MAP.md) | 中文

代码地图记录模块入口、职责归属、数据库所有权、Web 路由、事件监听器和 Paper 线程边界。

- `ks-core`：共享 SQLite、Web 网关、Token、路由和公共服务。
- `ks-Eco`：市场、货币、物品结算、GUI、Web 和 Extra 加载。
- `ks-RPG`：RPG 内容目录、成长、战斗证明、技能和掉落。
- Extra 模块：在 `plugins/ks-Eco/extra/` 中由宿主发现和加载。

读取 Bukkit/Paper 实体、背包、ItemStack 元数据、GUI 和 Vault 必须在服务器线程；SQL 和纯计算可以异步，
但只能处理不可变快照，不能在异步线程反序列化或检查 live `ItemStack`。
