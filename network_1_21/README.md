# ks-Series 跨服测试网络

当前测试网络由 MCSManager 统一托管，共五个实例。玩家入口为 `127.0.0.1:25577`，后端只绑定本机回环地址。

| MCSM 实例 | 软件 | 地址 | 用途 |
|---|---|---|---|
| `ks-Network-Database` | MariaDB 11.4 | `127.0.0.1:3307` | 三端共享经济、跨服事件、游标与租约 |
| `ks-Network-Proxy` | BungeeCord | `0.0.0.0:25577` | 统一入口与 `/server` 切服 |
| `ks-Leaves-Main` | Leaves 1.21.11 | `127.0.0.1:25565` | 主生存端，直接复用 `test_1_21` 的世界、玩家与全部现有插件 |
| `ks-Paper-RPG` | Paper 1.21.11 | `127.0.0.1:25571` | RPG、Boss、副本、剧情和必要依赖生态 |
| `ks-Folia-Lab` | Folia 1.21.11 | `127.0.0.1:25573` | ks-Series Folia 实验端；独立 Web `127.0.0.1:58578`，当前加载 6 个经济 Extra |

实例清单和期望运行状态记录在 `mcsm-instances.json`，模块分层记录在 `roles.yml`。正式运维以 MCSM 面板为准；`scripts/network-control.ps1` 只用于不经过面板启动本目录内的独立副本。

## 数据库与跨服经济

- MariaDB 数据目录和凭据都位于工作区外的 `F:\ks-series-database`。仓库配置不保存明文密码；`ks-core` 通过受 ACL 限制的 `ks_network.password` 读取凭据。
- Leaves、Paper、Folia 分别使用稳定节点 ID `survival`、`rpg`、`folia`，共同连接 `ks_network`。
- 旧 Leaves/Paper SQLite 的 139 张源表已事务迁移并逐表核对行数；原文件和迁移前备份均保留。
- 三端都使用 ks-Eco JDBC 内置经济，因此余额后端一致。跨服运行时已启用，负责事件日志、轮询游标、缓存失效与 fencing lease。
- 2026-07-22 实机烟测把 `DIAMOND` 保持为原配置价 `100.0` 并发布一次价格失效事件；数据库发布序号从 2 增至 3，`survival/rpg/folia` 三个 consumer cursor 均推进到 3。

数据库连接参数属于重启配置。`/kscore reload` 和 `/kseco-admin reload` 不会替换连接池、节点身份或跨服 poller。

## 跨服地图、房产与资产

- `federated-assets` 在三端启用，MAP、PROPERTY 和资产汇总按 server/world/dimension 执行默认拒绝策略，deny 优先；`world_private` 明确拒绝。
- Leaves 的 ksHWP 只发布已冻结 PNG 字节。2026-07-23 实测同一世界/维度 bundle 保留 3 个图块，非 stale、非 offline；bundle 有载荷上限，不是完整世界归档。
- 地产只发布完成预渲染的 READY DTO；实测同一来源聚合 4 套房产、总展示价 375,000,000 最小货币单位，不再由后一栋覆盖前一栋。
- 资产 API 全部需要会话；ASSET 原始快照/明细与策略管理仅管理员可用。跨服 transfer 仍关闭。

## Folia 边界

Folia 使用 `-Pfolia` 构建的独立 `ks-core`、`ks-Eco` 和 `ks-InstanceWorld` 制品，三者的 `plugin.yml` 均声明 `folia-supported: true`；部署文件名统一为标准名，不能用默认 Paper/Leaves 制品替代。旧的并存 `*-folia.jar` 已在建立根备份索引后清除，避免 Paper remapper 把同一插件加载两次。

ks-Eco 的 global、async、entity 与 region 调度边界已经适配，无 Vault 时直接使用共享 JDBC 内置经济。银行、企业、政治、税务、地产和副本 6 个 Extra 已完成本轮 Folia 适配并实机加载。`ks-InstanceWorld` 在缺少 WorldEdit/FAWE 时仍提供 API，但 schematic prepare/cleanup 失败关闭；实验端未安装的 MythicMobs/MM 系列能力不计为通过。

## 代理与认证

三个后端启用 BungeeCord legacy forwarding，不能从外部绕过代理直连。当前测试网络沿用离线认证：代理 `online_mode: false`，后端 `online-mode=false`。正式公网服必须把代理改为正版认证，并同步调整三个后端的 Bungee 在线模式；否则不能把当前拓扑视为公网安全配置。

## 构建、部署和验证

所有 ks-Series JAR 必须通过根目录 `scripts/deploy-plugin.ps1` 部署，旧制品只备份到 `backup/<plugin-id>/` 并追加索引。Folia 构件使用 Maven `folia` profile。

2026-07-23 最终验证：23/23 Maven 模块依赖顺序成功，391 项测试无失败；默认与 Folia 构件定向复验成功。Leaves/Folia 各 29 项 Web 合同返回预期状态，三端状态与热加载命令成功。MCSM、daemon、MariaDB、代理、Leaves、Paper、Folia 和双 Web 共 9 个端口全部监听；三端日志显示 `Done` 与跨服运行时运行，Folia Extra 6 个且无 ks-Series ERROR、插件名歧义或区域线程异常。完整矩阵见 `../docs/KS-ECO-FULL-FUNCTION-TEST-2026-07-23.md`。

当前非阻断警告包括幂等补列时 MariaDB 驱动输出的重复列提示，以及 Leaves/Paper 现有 PlaceholderAPI、MMOItems、MMOCore、MCPets 示例或外部网络配置警告。它们没有禁用本轮 ks-Series 模块。
