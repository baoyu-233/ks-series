# 修改日志

## 2026-07-23

- 跨服只读资产层接通 MAP、PROPERTY 与资产汇总：严格 server/world/dimension 策略、认证 API、心跳、TTL、压缩上限和热加载均进入真实三端运行时。
- 地产按来源聚合全部 READY 房屋；修复可空信封导致发布失败，实机得到 4 套资产与 375,000,000 最小单位展示价。
- ksHWP 使用有界多图块 bundle，并修复重启后缓存命中不重新发布的问题；实机确认 3 图块、非过期、节点在线。
- ks-InstanceWorld 和副本 Extra 完成 Folia 启动线程合同；缺少 WorldEdit/FAWE 时 API 仍启用，schematic 功能单独失败关闭。
- 银行、企业、政治、税务、地产、副本 6 个经济 Extra 已在 Folia 同时加载；清除并备份旧重复 Folia JAR 后启动日志无插件名歧义。
- 最终矩阵 23/23 模块、391 项测试、Web JavaScript 22/22、严格 YAML 341/341、插件入口 17/17、本地资源引用 25/25 全部通过。
- Leaves/Folia 各 29 项 HTTP 合同返回预期状态，三端状态/热加载命令成功，MCSM、daemon、MariaDB、代理、三后端和双 Web 共 9 个端口监听。
- 所有 JAR 替换均使用根备份脚本；完整证据与限制见 `docs/KS-ECO-FULL-FUNCTION-TEST-2026-07-23.md`。

## 2026-07-22

- 建成由 MCSM 托管的 MariaDB、BungeeCord、Leaves、Paper、Folia 五实例测试网络。
- 把 Leaves/Paper 的 139 张 SQLite 源表迁移到共享 MariaDB，并完成逐表和关键经济汇总核对。
- `ks-core` 增加密码环境变量/文件来源和独立 Folia 制品；运行时 YAML 不再保存明文数据库密码。
- `ks-Eco` 增加独立 Folia 制品、统一调度门面、无 Vault JDBC 内置经济以及 Folia 失败关闭边界。
- Leaves、Paper、Folia 三端均启动共享跨服运行时；实际事件发布序号与三个消费游标共同推进。
- 经济 P0/P1 收口覆盖市场、个人工程、个人/企业房产、银行、企业、税务、副本与人工复核 journal。
- 最终依赖矩阵 23/23 模块成功，358 项测试无失败；Web/YAML/资源静态检查无失败。
- 所有部署使用根备份脚本，制品、目标、备份文件与追加索引哈希均已复核。

## 维护约定

- 后续发布同时更新本文件与所有被修改项目目录下的 `CHANGELOG.md`。
- 可调玩法优先采用通用 API、可校验 YAML 和安全热加载；重启项必须明确标注。
- 修改日志不得包含密码、Token、运行时数据库、玩家隐私或私有内容资产。
