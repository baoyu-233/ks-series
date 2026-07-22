# ks-InstanceWorld 修改日志

## 2026-07-23

- 新增独立 `-Pfolia` 构件：默认 JAR 声明 `folia-supported: false`，`ks-InstanceWorld-0.1.0-folia.jar` 声明为 `true`。
- WorldEdit/FAWE 改为反射加载的可选 adapter；依赖缺失时插件仍启用并注册 `InstanceWorldApi`，仅 schematic prepare/cleanup 失败关闭，不再在生命周期类中提前解析 WorldEdit 类型。
- schematic namespace 根目录注册/注销改为显式线程安全的纯路径操作，不再错误要求 Folia global tick owner；Extra 可在自身启用线程完成注册。
- 标记扫描按区块所属 region 分片；实体清理由各区块 region 执行。Folia 下 schematic 粘贴与清空只允许 FastAsyncWorldEdit，WorldEdit-only 模式失败关闭。
- 默认与 Folia profile 均执行 11 项测试；尚未完成 Folia 实机粘贴、跨 region 标记、清理和重启恢复验收。

## 2026-07-22

- 延迟释放只有在网格仍归当前实例时才能清空占用，避免释放后来复用者。
- 清理范围覆盖 arena 与 schematic 实际边界的并集。
- 实例 API、持久化与服务器线程边界保持独立；7 项测试通过。
