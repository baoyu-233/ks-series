# ks-Series 跨服测试网络

当前测试网络由 MCSManager 统一托管，共五个实例。玩家入口为 `127.0.0.1:25577`，后端只绑定本机回环地址。

| MCSM 实例 | 软件 | 地址 | 用途 |
|---|---|---|---|
| `ks-Network-Database` | MariaDB 11.4 | `127.0.0.1:3307` | 三端共享经济、跨服事件、游标与租约 |
| `ks-Network-Proxy` | BungeeCord | `0.0.0.0:25577` | 统一入口与 `/server` 切服 |
| `ks-Leaves-Main` | Leaves 1.21.11 | `127.0.0.1:25565` | 主生存端，直接复用 `test_1_21` 的世界、玩家与全部现有插件 |
| `ks-Paper-RPG` | Paper 1.21.11 | `127.0.0.1:25571` | RPG、Boss、副本、剧情和必要依赖生态 |
| `ks-Folia-Lab` | Folia 1.21.11 | `127.0.0.1:25573` | 仅安装 Folia 版 `ks-core`、`ks-Eco` 的兼容实验端 |

实例清单和期望运行状态记录在 `mcsm-instances.json`，模块分层记录在 `roles.yml`。正式运维以 MCSM 面板为准；`scripts/network-control.ps1` 只用于不经过面板启动本目录内的独立副本。

## 数据库与跨服经济

- MariaDB 数据目录和凭据都位于工作区外的 `F:\ks-series-database`。仓库配置不保存明文密码；`ks-core` 通过受 ACL 限制的 `ks_network.password` 读取凭据。
- Leaves、Paper、Folia 分别使用稳定节点 ID `survival`、`rpg`、`folia`，共同连接 `ks_network`。
- 旧 Leaves/Paper SQLite 的 139 张源表已事务迁移并逐表核对行数；原文件和迁移前备份均保留。
- 三端都使用 ks-Eco JDBC 内置经济，因此余额后端一致。跨服运行时已启用，负责事件日志、轮询游标、缓存失效与 fencing lease。
- 2026-07-22 实机烟测把 `DIAMOND` 保持为原配置价 `100.0` 并发布一次价格失效事件；数据库发布序号从 2 增至 3，`survival/rpg/folia` 三个 consumer cursor 均推进到 3。

数据库连接参数属于重启配置。`/kscore reload` 和 `/kseco-admin reload` 不会替换连接池、节点身份或跨服 poller。

## Folia 边界

Folia 使用单独的 `ks-core-1.1.0-folia.jar` 和 `ks-Eco-1.1.0-folia.jar`，两者的 `plugin.yml` 均声明 `folia-supported: true`。默认 Paper/Leaves 制品仍声明 false，不能混装。

ks-Eco 的全局、异步、实体调度边界已经适配；无 Vault 时直接使用共享 JDBC 内置经济。尚未完成区域线程适配的全部 Extra 会在 Folia 自动禁用，全服财富榜也暂时失败关闭。银行、企业、税收、地产、副本和第三方 MMO/RPG 插件不得放入 Folia 实验端。

## 代理与认证

三个后端启用 BungeeCord legacy forwarding，不能从外部绕过代理直连。当前测试网络沿用离线认证：代理 `online_mode: false`，后端 `online-mode=false`。正式公网服必须把代理改为正版认证，并同步调整三个后端的 Bungee 在线模式；否则不能把当前拓扑视为公网安全配置。

## 构建、部署和验证

所有 ks-Series JAR 必须通过根目录 `scripts/deploy-plugin.ps1` 部署，旧制品只备份到 `backup/<plugin-id>/` 并追加索引。Folia 构件使用 Maven `folia` profile。

2026-07-22 最终验证：23/23 Maven 模块依赖顺序成功，358 项测试无失败；默认与 Folia 两套 ks-core/ks-Eco 构件均成功。MariaDB、代理、Leaves、Paper、Folia 和 Leaves Web 六个端口全部监听，三端日志均显示 `Done`、MariaDB 数据源就绪、ks-Eco 启用和跨服运行时启动。

当前非阻断警告包括幂等补列时 MariaDB 驱动输出的重复列提示，以及 Leaves/Paper 现有 PlaceholderAPI、MMOItems、MMOCore、MCPets 示例或外部网络配置警告。它们没有禁用 ks-core/ks-Eco；Folia 当前启动日志没有 ks-Series ERROR 或区域线程异常。
