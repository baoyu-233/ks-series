# ks-Series 代码地图

> [English](CODEBASE_MAP.md) | 中文

最后核对：2026-07-19

跨会话维护记录：`docs/CODEX_MEMORY.md`

当前 Bug Hunt 交接点：`docs/BUG-HUNT-HANDOFF-2026-07-18.md`

经济设计与验证知识库：`docs/economy-knowledge-base/README.md`

生存 RPG 产品与系统知识库：`docs/survival-rpg-knowledge-base/README.md`

本文件是入口、职责、数据库所有权、线程边界和主要工作流的第一站索引。入口、所有权、数据库或线程契约变化时，应同步更新本文件。

## 快速入口与依赖

| 模块 | 主入口或 Extra 装载类 | 主要职责 |
| --- | --- | --- |
| `ks-core` | `org.kscore.KsCore` | 公共服务、JDBC/HikariCP 数据源、Web 网关与共享基础能力 |
| `ks-Eco` | `org.kseco.KsEco` | 经济宿主、市场、仓储、限售、盲盒、Web/GUI、Extra 发现与公共结算 |
| `ks-Eco-bank` | `org.kseco.extra.bank.BankExtra` | 银行账户、存贷款、央行、企业金融、利息与货币供应 |
| `ks-Eco-enterprise` | `org.kseco.extra.enterprise.EnterpriseExtra` | 企业成员、治理、等级、分红及企业业务 |
| `ks-Eco-tax` | `org.kseco.extra.tax.TaxExtra` | 税率、税务记录与异步审计 |
| `ks-Eco-RealEstate` | `org.kseco.extra.realestate.RealEstateExtra` | 区域、地块、信任、保护和地产 GUI/Web |
| `ks-Eco-politic` | `org.kseco.extra.politic.PoliticExtra` | 提案、职位与分阶段投票 |
| `ks-InstanceWorld` | `org.kseries.instanceworld.KsInstanceWorld` | 独立的副本世界、网格、蓝图准备与释放服务 |
| `ks-Eco-RealEstateDungeon` | `org.kseco.extra.realestatedungeon.RealEstateDungeonExtra` | 副本模板、队伍、门票、复活、Boss、奖励与地产实例归属 |
| `ks-RPG` | `org.kseries.rpg.KsRpg` | RPG 内容目录、技能、掉落、证明、解锁门槛和赛季基础 |
| `ks-RPG-Gui` | `org.kseries.rpggui.KsRpgGui` | 消费 ks-RPG API 的独立菜单与管理员内容库 |

`ks-Eco-*` Extra JAR 由宿主从 `plugins/ks-Eco/extra/` 发现并加载。常规编译依赖顺序是先构建 `ks-core`，再构建 `ks-Eco`、`ks-InstanceWorld`、`ks-RPG` 等基础模块，最后构建各 Extra、GUI 和兼容模块。

## 数据库与跨服所有权

### 公共数据源

`ks-core/src/main/java/org/kscore/KsDataStore.java` 是 HikariCP 数据源和 JDBC 配置的所有者，负责 SQLite、MySQL、MariaDB、PostgreSQL 的核心方言选择。现有调用方继续通过 `getConnection()` 获取连接。

- 默认 SQLite 是节点本地存储。
- 配置远程数据库后，连接失败默认视为致命错误；只有显式开启回退时才会落回 SQLite。
- SQLite 回退仍是节点本地数据库，不能宣传或当作跨服共享存储。

### 经济节点与跨服基础

`ks-Eco/src/main/java/org/kseco/database/EcoDatabase.java` 负责经济服的 `server_id`、进程实例身份、心跳、操作认领和带 fencing token 的租约。

跨服契约位于 `ks-Eco/src/main/java/org/kseco/crossserver/`：

- 根目录和 `sql/`：事件、仓库与可移植 JDBC 语句契约。
- `transport/`：outbox/inbox 风格的轮询、游标和投递基础。
- `cache/`：带版本的缓存失效消息。
- `lock/`：保留 fencing token 的租约与所有权基础。

这些类是共享数据库和跨服运行的基础，不等于所有经济工作流已经接线。市场、仓储、限售、盲盒、企业和价格引擎等主要结算点仍需逐项接入仓库、轮询、缓存失效和 fenced settlement owner，真实远程数据库及多服并发也尚未实测。

### 多货币

多货币基础位于 `ks-Eco/src/main/java/org/kseco/currency/`。

- `JdbcCurrencyLedger` 使用精确最小货币单位，保存不可变流水、余额版本和全局幂等操作，并提供四种数据库方言。
- `CurrencyPaymentService` 支持独占货币或显式备选货币报价；`CurrencyExchangeService` 支持配置化单向兑换。
- 货币 ID 规范化为大写；账户 ID 和幂等操作 ID 保持大小写敏感。
- `ListingManager` 与 `LimitedSaleManager` 保存向后兼容的 `currency_id`。
- 当前只有 Vault/内置 `CASH` 是已接入的实时结算源。非 `CASH` 命令、GUI 和主要玩法结算，以及特殊货币到 `CASH` 的持久化桥仍未接线；不可用或不可消费的指定货币必须失败关闭，不能静默回退到 `CASH`。

## Paper 线程契约

只能在服务器线程访问：

- Bukkit、Paper、Leaves、MythicMobs 的 live 对象。
- `Player`、背包、`ItemStack`、物品元数据/PDC、配方注册表和 GUI。
- Vault 余额查询、扣款和入账。

工作线程只处理：

- 已在服务器线程制作的不可变快照、原始字节、UUID、文本和纯计算数据。
- 不跨越服务器线程回调持有事务的 SQL 读写。
- 排序、聚合、估值和报表等纯计算。

`ks-Eco/src/main/java/org/kseco/AsyncWorkPool.java` 中，`executeDatabase` 是有界、串行的数据库通道，`execute` 是有界计算通道。结果必须以不可变数据返回服务器线程后，才能触碰 Bukkit、Vault、物品或 GUI。禁止在工作线程反序列化或检查 live `ItemStack`，禁止服务器线程阻塞等待 Future/I/O，也禁止在事务内等待服务器线程结算。

## ks-Eco 核心经济

主生命周期与服务装配：`ks-Eco/src/main/java/org/kseco/KsEco.java`。

- `ListingManager`：市场挂单、原子数量认领及兼容 `currency_id`。
- `MarketManager`：玩家市场流程和 Vault 结算。
- `OfficialMarketSweepManager`、`MarketValueService`、`PriceEngine`：官方低价收购、保护价格和动态价格。
- `StorageManager`、`OfficialWarehouseManager`：玩家仓储与官方仓库。物品字节在数据库通道读取，`ItemStack` 在服务器线程解码。
- `LimitedSaleManager`、`BlindBoxManager`、`PurchaseOrderManager`：限售、盲盒和求购单。数据库预留、外部扣款、发货和补偿按线程边界拆分。
- `CompensationManager`：补偿计划和一次性领取。
- 官方仓库清算与需求活动已有有限库存、版本、幂等和恢复基础；需求活动默认关闭，尚未接定时任务、Vault 和玩家界面。

外部 Vault 或物品交付不能包含在 SQL 事务中。已经实现状态机的流程必须依靠幂等键、补偿或人工核对状态恢复；尚未建立持久化结算日志的调用点仍存在数据库提交与外部副作用之间的崩溃窗口。

## 银行 Extra

入口：`ks-Eco-bank/src/main/java/org/kseco/extra/bank/BankExtra.java`。

关键所有者位于同一包下：

- `BankManager`：账户、贷款报价、信用评分、期限/额度、发放和回收。
- `BankInterestAccrualPolicy`、`BankInterestSettlementStore`：按精确最小货币单位计算余额时间利息，保存确定性 posting、平均余额和 CAS 状态。
- `CentralBankManager`、`CbLoanManager`：央行与央行贷款。
- `EnterpriseFinanceManager`：企业金融入口。
- `MoneySupplyTracker`：货币供应读模型。

贷款发放包含 `PENDING_PAYOUT -> PAYOUT_SETTLING -> ACTIVE`，外部钱包结果不确定时进入 `RECONCILE_REQUIRED`，不能假定未付款后删除贷款并恢复资产。周期任务只负责调度，利息和状态 SQL 进入数据库通道；Vault 调用留在服务器线程且位于事务外，失败通过补偿或核对状态处理。

仍未完成的银行边界包括：利率改变时对所有账户精确切片的完整入口接线、企业拍卖托管/退款、部分企业并发还款和央行贷款共享数据库结算。Web/GUI 也尚未完整呈现新的信用与报价模型。

## 企业与税务 Extra

`ks-Eco-enterprise/.../EnterpriseManager.java` 负责加入申请、批准/拒绝、退出、解散和分红结算；`EnterpriseLevelManager.java` 负责企业等级、缓存读和地产加成。分红批次及收款人记录必须保留幂等、补偿和不确定状态，不能在结果未知时继续重复分配。

`ks-Eco-tax/.../TaxManager.java` 负责税率、行业税率和审计记录。税率兼容小数与旧百分数格式，非有限税基失败关闭，审计写入数据库通道，最终持久化失败时必须触发退款或明确失败。

企业、银行和税务虽然共享同一经济数据源，但跨服生产使用前仍需完成剩余 settlement journal、CAS/lease 接线和远程数据库并发验证。

## 地产 Extra

入口：`ks-Eco-RealEstate/src/main/java/org/kseco/extra/realestate/RealEstateExtra.java`。

- `RealEstateManager`：区域、地块、信任、房屋/行政区以及副本实例地产归属。
- `gui/PlotListMenu.java`、`gui/PlotTrustMenu.java`：数据库任务返回 DTO，服务器线程渲染 GUI，并使用 stale callback 与 in-flight guard 防止旧响应覆盖新界面。
- `PlotProtectionListener.java`：保护事件读取不可变信任缓存；缓存不可用时失败关闭，不在高频事件中同步查询数据库。

地产价格兼容旧 `FLAT` 模式，并支持按面积计价、最低价、面积上限、软硬持有上限和软上限附加费。永久地块计入持有量，副本地块不计入；坐标、面积、汇总和价格溢出均失败关闭。新的策略控制尚未完整暴露到 Web。

## 政治 Extra

入口：`ks-Eco-politic/src/main/java/org/kseco/extra/politic/PoliticExtra.java`。

`VoteManager.countVotes` 是分阶段计票所有者。`SENATE_VOTING` 只有在剩余合资格选票无法逆转全体席位绝对多数时才提前结束：赞成票达到绝对多数可不可逆通过，即使剩余票全部赞成仍达不到绝对多数则不可逆否决。可逆的暂时多数不能提前推进流程。

## 实例世界与副本 Extra

### ks-InstanceWorld

入口：`ks-InstanceWorld/src/main/java/org/kseries/instanceworld/KsInstanceWorld.java`。

`InstanceWorldApi` 是稳定服务面；`InstanceWorldService` 负责编排准备与释放；`internal/InstanceStore` 和 `internal/SchematicRepository` 是工作线程所有者；`internal/CanvasService` 与 `internal/MarkerScanner` 在服务器线程执行 WorldEdit/FAWE 和 Bukkit 操作。

实例持久化属于 `plugins/ks-InstanceWorld/instance-world.db`。旧 `plugins/ks-core/data.db` 中的 `ks_dungeon_grids` 只允许只读导入。释放网格时必须校验当前 `occupied_by`，延迟回调不能释放已经被新实例复用的网格。

### ks-Eco-RealEstateDungeon

入口：`ks-Eco-RealEstateDungeon/src/main/java/org/kseco/extra/realestatedungeon/RealEstateDungeonExtra.java`。

- `DungeonInstanceManager`：模板、队伍、实例生命周期、复活、Boss 跟踪、名单冻结和奖励编排。
- `DungeonLifecycleStore`：终态、清理和整体奖励状态。
- `DungeonTicketSettlementStore`：门票 charge/admission/refund 日志，保存 owner server/instance 身份。状态包括 `CHARGE_READY`、`CHARGE_IN_PROGRESS`、`CHARGED`、`ADMITTED`、`REFUND_READY`、`REFUND_IN_PROGRESS`、`REFUNDED`、`REJECTED`、`CANCELLED` 和 `REVIEW_REQUIRED`。
- `DungeonRewardGrantStore`：按玩家、按 reward key 保存 `NONE`、`PENDING`、`GRANTED`、`RETRY_REQUIRED`，用稳定内容哈希和幂等状态防止成功奖励重放。
- `DungeonRpgBridge`：发现并调用 `RpgProgressionApi`，编排金钱、命令、MythicItems、MMOItems 和证明交付。
- `DungeonDeathHandler`：副本死亡、复活与退出相关的服务器线程行为。

`ks-InstanceWorld` 只拥有世界/网格准备和释放；副本 Extra 保留经济、队伍、门票、Boss、复活和奖励所有权。外部 Vault/物品结果未知时必须进入人工核对或保持待处理，不能猜测成功。

当前未完成边界：离线玩家的持久化证明交付仍需直接、耐久地接入 `RpgProgressionApi`；第三波内容声明的进入门槛尚未由副本运行时强制执行；真实 Paper 环境中的清场、超时、重启恢复和并发实例仍需验收。

## ks-RPG 与赛季

入口：`ks-RPG/src/main/java/org/kseries/rpg/KsRpg.java`。

### 运行时 API 与内容

- `api/RpgContentApi.java`：列出、预览和交付当前内容目录中的 RPG 物品。
- `api/RpgProgressionApi.java`：声明证明、门槛及玩家 PDC 证明；全部访问只能在服务器线程。
- `api/RpgSeasonStatusApi.java`：只读、缓存化的赛季运行状态。
- `content/`：武器、护符、戒指、宝箱和世界掉落的文件化内容。热重载先完整校验候选目录，成功后才原子替换不可变目录。

第一波和第二波内容已经进入源码/内置资源。世界掉落会根据本次战斗贡献者筛选在线、同世界、近距离、非旁观玩家，并通过收件人轮换避免永远只奖励最后一击。所有物品、PDC、MythicMobs 和玩家访问仍属于服务器线程。

### 赛季基础

赛季领域位于 `ks-RPG/src/main/java/org/kseries/rpg/season/`，默认 `season.enabled=false`。显式启用后，`SeasonRuntime` 只通过单独的 `ks-rpg-season-db` 工作线程初始化 `plugins/ks-RPG/season.db`，调用方读取缓存状态，不在服务器线程执行数据库 I/O。

已有领域基础包括生命周期、区域声望与追赶、幂等绝对事件快照、项目、奖励领取状态和非破坏性归档。`SeasonService` 按服务端时间校验赛季窗口并计算周次，InMemory/JDBC 进度写入与归档在同一季节状态锁/事务内复核，阻止伪造周次和归档后继续写入。仅开启配置不会自动创建赛季、排程事件、发放奖励或修改玩家数据。

远程赛季数据库、跨服赛季协调、事件调度、奖励执行和实服验收仍未接线或未验证。

### 内容包位置

- 第二波：`deploy_package/ks-rpg-second-wave-20260718/`
- 第三波设计包：`deploy_package/ks-rpg-third-wave-20260718/`
- Boss staging：`deploy_package/ashen_foundry_overseer-20260718/`
- Boss staging：`deploy_package/stormforge_overseer-20260718/`
- Boss staging：`deploy_package/aurora_packwarden-20260718/`
- Frostbound：`deploy_package/frostbound_conductor/`

这些目录是源码/设计或 staging 交付物，不代表已经复制到测试服、由 MythicMobs 重载或完成实战平衡。第三波证明、门槛、突破兑换和副本模板中仍有只存在于元数据/设计契约、尚未由运行时原子执行的部分。

## GUI、兼容与工具模块

- `ks-RPG-Gui`：入口 `org.kseries.rpggui.KsRpgGui`，只消费 `RpgContentApi` 与 `RpgProgressionApi`，不拥有经济、物品创建或进度持久化。
- `ks-BotGuard`：入口 `org.kseries.botguard.KsBotGuard`，识别 Leaves ServerBot，包装并周期校正 MythicLib/MMOCore 监听器；方块事件不能假定继承 `PlayerEvent`。
- `ks-Compat`：入口 `org.kseries.compat.KsCompat`，`bot/BotManagerModule.java` 与 `LeavesBotBridge.java` 负责 KSBot 命令、所有权、节流、重复类型和并发限制。
- `ks-BossCombat`：入口 `org.kseries.bosscombat.KsBossCombat`，当前 Frostbound 武器适应规则只作用于明确 Boss scoreboard tag，团队机制可以暂时移除该 tag 形成反制窗口。
- `ks-Sentinel`：入口 `org.kssentinel.KsSentinel`，批量审计写失败回滚后必须重新入队，避免瞬时数据库错误永久丢日志。
- `ks-Cinematic`：入口 `org.kseries.cinematic.KsCinematic`，涉及玩家、实体、镜头和调度的操作遵守服务器线程边界。
- 其他独立入口：`ks-Maintenance` 为 `org.kseries.maintenance.KsMaintenance`，`KS-ItemEditor` 为 `org.itemedit.ItemEditor`，`KS-ItemSteal` 为 `com.steal.ItemSteal`，`ks-Inherit` 为 `org.ksinherit.KsInherit`，`ks-Skill` 为 `org.ksskill.KsSkill`，`ks-Title` 为 `org.kstitle.KsTitle`，`ksHWP` 为 `org.kshwp.KsHWP`。

`ks-Skill` 和 `ks-BossCombat` 中的旧能力正在由 `ks-RPG` 逐步吸收；在迁移、服务依赖和重复监听器核对完成前，不能仅凭新模块存在就删除旧部署。

## 当前运行时与验证限制

- 默认 SQLite、多数据库方言、跨服 JDBC/outbox/inbox/cache/lease 和多货币账本属于已实现基础，但真实 MySQL、MariaDB、PostgreSQL、多服竞争、迁移和锁语义尚未完成端到端验证。
- `CASH` 之外的实时支付、跨服 P0 结算所有权、完整外部 Vault/物品崩溃恢复仍未全面接线。
- 银行、企业、税务、地产、副本、RPG 和赛季的状态机改进不能替代 Paper 实服中的 GUI、重启、离线玩家、并发和第三方插件验收。
- staging Boss 和 RPG 内容的静态 YAML/JSON/引用检查只证明格式与引用闭合，不证明 MythicMobs、ModelEngine、MMOItems、Vault 或 Paper 的运行行为。
- 当前收口没有部署 JAR，也没有启动或重启 Paper。

## 维护规则

修改入口、职责所有权、数据库边界、跨服契约或线程规则后更新本地图，并同步核对 `docs/CODEX_MEMORY.md`。部署任何 ks-Series JAR 前必须使用 `scripts/deploy-plugin.ps1`，备份只能写入根 `backup/<plugin-id>/` 及其追加索引；除非用户明确要求，不启动或重启 Paper。
