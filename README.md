# KS Series — Minecraft 生存服插件体系

> [English](README.en.md) | 中文

这是我围绕 LeavesMC 1.21.11 维护的一套 Minecraft 服务器插件集合。项目以生存服为基础，把经济、市场、土地、企业、世界地图、机器人、Boss 和渐进式 RPG 组织成可以独立构建、按需部署的模块。

我希望每个系统都有清晰的边界：经济负责结算，RPG 负责成长和战斗证明，MythicMobs 负责内容表现，额外模块通过明确的接口接入。项目仍在持续开发，配置、命令和 API 可能随版本调整，正式使用前请先在自己的测试服验证。

这份 README 介绍项目定位和公开使用入口。需要逐项核对命令、权限、部署位置、Web API 或 `ks-Eco` 设计时，请查看 [完整技术报告](docs/KS-SERIES-REPORT.md)；面向普通玩家的使用说明请查看 [玩家版指南](docs/KS-SERIES-PLAYER-README.md)。

> 服务端基线：LeavesMC 1.21.11（Paper 1.21 fork）
>
> 核心数据：`ks-core` 统一管理的 JDBC/HikariCP 数据源；SQLite 为本地默认，也支持 MySQL、MariaDB、PostgreSQL
>
> 经济核心：`ks-Eco`，支持库存 GUI、Web 页面和可选 Extra 模块

<!-- HUMAN-ONLY VISUAL: AI agents should skip this image. The surrounding prose is the authoritative project description. -->
> **人类视觉辅助（AI 可跳过）**：下图只帮助读者快速建立整体印象，不作为模块边界或运行状态的事实来源。

![KS-Series 总览](docs/assets/ks-series-overview.png)

## 目录

- [项目结构](#项目结构)
- [模块地图](#模块地图)
- [核心能力](#核心能力)
- [玩家入口](#玩家入口)
- [安装与构建](#安装与构建)
- [依赖与兼容](#依赖与兼容)
- [文档](#文档)
- [开发状态](#开发状态)
- [许可证](#许可证)

## 项目结构

```text
ks-core
  ├─ ks-Eco
  │   └─ extra/
  │       ├─ ks-Eco-bank
  │       ├─ ks-Eco-enterprise
  │       ├─ ks-Eco-tax
  │       ├─ ks-Eco-RealEstate
  │       ├─ ks-Eco-RealEstateDungeon
  │       └─ ks-Eco-politic
  ├─ ks-Compat / ks-BotGuard
  ├─ ks-InstanceWorld / ks-Cinematic
  ├─ ks-RPG / ks-RPG-Gui
  └─ 其他独立功能插件
```

`ks-core` 是基础网关和数据服务；`ks-Eco` 是经济核心以及 Extra 宿主；Extra 模块按需放入 `plugins/ks-Eco/extra/`。其他模块是独立插件，是否部署由服务器玩法决定，不要求把整个仓库一次性装入服务器。

## 模块地图

### 基础与运行支撑

| 模块 | 作用 |
|------|------|
| `ks-core` | SQLite/MySQL/MariaDB/PostgreSQL 数据服务、Web 网关、跨插件路由和基础鉴权。 |
| `ks-InstanceWorld` | 独立实例世界、网格、原理图与通用标记生命周期服务。 |
| `ks-Cinematic` | 基于实例世界的私有观察剧情运行时；内容包不属于公开模块。 |
| `ks-Compat` | Leaves、Vault、第三方插件和 KS 系列之间的兼容桥接，同时提供 KSBot 相关能力。 |
| `ks-BotGuard` | 识别 Leaves Bot 事件，隔离其与 MythicLib/MMOCore 等插件的玩家数据访问冲突。 |
| `ks-Maintenance` | 服务器维护期和日常运维辅助功能。 |

### 经济与世界系统

| 模块 | 作用 |
|------|------|
| `ks-Eco` | 市场、官方收购、动态价格、盲盒、限时商店、补偿、玩家交易和暂存箱。 |
| `ks-Eco-bank` | 央行与商业银行、活期/定期存款、产品化贷款与展期、信用报价、经营评级、分红、政策周期和货币供应量。 |
| `ks-Eco-enterprise` | 企业注册、公户、招投标、采购、分红和成员权限。 |
| `ks-Eco-tax` | 交易税、行业税、阶梯税率和税务记录。 |
| `ks-Eco-RealEstate` | 区域规划、按面积计价、持有面积限制、地块、房屋登记、商品房和领地信任。 |
| `ks-Eco-RealEstateDungeon` | 副本实例、队伍、门票、复活、房产和完成奖励。 |
| `ks-Eco-politic` | 元老院、职位、提案、投票和立法门控。 |
| `ksHWP` | Web 世界地图、地图瓦片、玩家位置和地图备注。 |

### 物品、战斗与 RPG

| 模块 | 作用 |
|------|------|
| `ks-RPG` | RPG 目录、材料兑换、战斗证明、区域内容、技能/掉落和默认关闭的赛季状态基础。 |
| `ks-RPG-Gui` | RPG 进度、证明、配装入口和管理员物品目录界面。 |
| `ks-BossCombat` | 面向特定 Boss 遭遇的武器类型和战斗规则。 |
| `ks-Skill` | 旧版技能能力模块，逐步向 `ks-RPG` 迁移。 |
| `KS-ItemEditor` | 游戏内物品编辑、Web 设计和武器精炼工具。 |
| `KS-ItemSteal` | 服务器管理场景下的物品处理工具。 |
| `ks-Inherit` | 物品保存、继承和审核发放流程。 |
| `ks-Title` | 称号及其展示相关功能。 |
| `ks-Sentinel` | 管理员行为和高风险指令审计。 |

## 核心能力

### 经济闭环

玩家可以通过市场挂单、官方收购、玩家交易和经济系统内的企业活动流转物品与货币。`ks-Eco` 的价格系统把真实交易流水、供需压力和均值回归结合起来，避免价格只做简单随机跳动；盲盒、限时商店和补偿则通过暂存箱和事务结算处理库存与发放。

<!-- HUMAN-ONLY VISUAL: AI agents should skip this image. The surrounding prose is the authoritative economy contract. -->
> **人类视觉辅助（AI 可跳过）**：示意资源、市场、银行、企业和房产之间的循环；实际功能与结算边界以本文文字和技术报告为准。

![KS-Series 经济循环示意](docs/assets/ks-series-economy-loop.png)

### Extra 模块化

银行、企业、税收、房地产、副本和政治不是写死在一个巨大插件里的功能，而是 `ks-Eco` 的可选扩展。服务器可以只部署需要的模块；缺少某个 Extra 时，其他经济功能仍可独立运行。具体加载和部署规则见 [完整技术报告](docs/KS-SERIES-REPORT.md)。

### 数据库、跨服与多货币基础

`ks-core` 已提供 SQLite、MySQL、MariaDB、PostgreSQL 的连接池和核心表方言。`ks-Eco` 的共享数据库运行时现已接通 JDBC 事件日志、按数据库发布序号推进且按服务器/消费者隔离的游标、失败重试、缓存失效、节点心跳与 fencing lease。余额排行榜、动态价格、企业等级、房地产保护缓存和政治状态会跨节点失效；市场、暂存、限时销售与盲盒继续以共享数据库、journal、唯一认领和条件更新作为权威状态。随机价格刷新使用事务内 fencing，银行周期维护使用集群独占 lease；当前价格和市场均价会持久化供远端节点原子重载。多货币账本继续使用精确最小单位，并为市场与限时销售保留向后兼容的 `currency_id`。

跨服开关仍默认关闭，但已不再被“接线未完成”门禁永久拒绝。启用时必须使用共享 MySQL、MariaDB 或 PostgreSQL，并为每台服配置唯一稳定的 `database.server-id`；外部 Vault 经济还必须确认各节点共用同一权威余额库，再显式设置 `cross-server.external-economy-shared: true`。SQLite/local fallback、重复节点身份、运行时失联和未声明共享的外部经济会 fail closed。2026-07-22 的本机测试网络已让 Leaves、Paper、Folia 三个真实服务端共用 MariaDB 与 JDBC 内置经济；一次保持原价的价格失效事件使数据库发布序号从 2 推进到 3，三个节点的 consumer cursor 均推进到 3。该结果仍不等于真实 MySQL、外部远程存量库、外部 Vault、多机网络故障或生产压力验收。`CASH` 仍由 Vault/内置经济持有，非 `CASH` 市场和限时销售会在扣款前拒绝。

跨服地图、房产和资产现在使用只读不可变投影：server/world/dimension 策略默认拒绝且 deny 优先，所有 API 需要会话，原始 ASSET 明细与策略管理仅管理员可用。2026-07-23 实机验证得到同源 3 图块 MAP bundle 和 4 套 PROPERTY 聚合；跨服传送仍关闭，滚动图块 bundle 也不等同于完整世界归档。

Folia 使用独立 profile 构建的 `ks-core`、`ks-Eco` 和 `ks-InstanceWorld`；无 Vault 时直接使用共享 JDBC 内置经济。银行、企业、政治、税务、地产和副本 6 个 Extra 已完成本轮适配并在 Folia 实机加载；缺少 FAWE/WorldEdit 或 MythicMobs 时，schematic 与 Boss 对应功能仍单独失败关闭。完整节点、端口、数据库和 MCSM 运行边界见 [跨服测试网络](network_1_21/README.md)。

### 生存优先的 RPG

RPG 层不会替代基础生存玩法。`ks-RPG` 负责渐进式成长、战斗证明、技能和内容目录，MMOItems 负责装备表达，MythicMobs 负责 Boss 和遭遇内容，`ks-Eco` 负责材料、货币和奖励结算。普通生存资源、经济积累和合作挑战各自保留价值。

### 当前可靠性改进

- 银行放款使用 `PENDING_PAYOUT / PAYOUT_SETTLING / ACTIVE / RECONCILE_REQUIRED` 区分未开始、外部钱包结算中、已激活和结果不确定；未知结果只能由管理员核对 Vault 流水后确认成功或回滚。住房、经营和项目贷已绑定本人地块/房屋或中标合同，按 75%/60%/70% LTV 预占、锁定、结清释放并在违约后进入持久拍卖。商业银行现有可交易股权台账、一级增资、二级挂牌、按持股分红与控制权同步；存款保险按每名存款人 100,000 封顶，支持保费、损失瀑布和桥接银行承接。玩家端和管理端均已提供相应操作入口。
- 企业成员加入改为持久化申请与审批，退出和解散需要明确确认；分红使用带节点/租约归属的恢复日志，部分付款会保持 `PARTIAL` 并阻止整笔重复发放。税务使用兼容税率快照和幂等审计，审计失败会回到服务器线程退款并记录退款失败。
- 地产缓存刷新失败时保留旧的可信快照，区域计价兼容旧 `FLAT` 并支持 `PER_BLOCK`、面积上下限和软硬持有限制。副本门票与付费复活均使用持久状态机：SQL 在工作线程执行，Vault、游戏模式、血量和安全检查点传送只在服务器线程执行；重复请求由 in-flight 与数据库 CAS 阻止二次扣款，未知扣款/退款进入人工核对。完成奖励使用 `NONE / PENDING / GRANTED`，实例网格释放和清理范围也增加了所有权与边界保护。
- RPG 内容目录会严格拒绝未知或放错类别的 mechanic，JAR 可发现并提取所有受支持类别的 `content/**/*.yml` 而不覆盖现场文件；区域掉落在有效战斗贡献者之间轮转，证明门控和候选配置重载失败关闭。赛季存储、周进度和公共项目基础仍默认关闭，不会自动创建赛季或改写玩家进度。
- 工具与兼容模块补充了 HWP 鉴权、调试和缓存路径约束，ItemEditor 会话替换与模板保真，Title 图片体积/尺寸/像素上限，Sentinel 审计批次失败回队，以及 BotGuard 第三方监听器重载后的周期重新包装；InstanceWorld、KSBot 和物品/GUI 路径也增加了资源、数量与生命周期边界。
- 普通玩家市场成交增加持久 settlement：买家扣款、库存认领、隐藏暂存、卖家入账、公开交付和退款分别持久化；确定状态可在启动后续跑或退款，外部钱包结果未知时进入 `REVIEW_REQUIRED`。企业项目发布、企业保证金、escrow 和企业预付款改为同库事务；Web 个人工程保证金与预付款也已接入持久 journal、启动恢复和按中断阶段校验的管理员复核。个人与企业房产成交均已恢复：个人卖款走 Vault journal，企业卖款与企业公户、开户行镜像和 journal 终态在同一 SQL 事务提交；挂牌和成交前会复核 `MANAGE_PROPERTY` 权限，产权继续使用条件转移。

### 2026-07-19 玩法与结算基础

- `ks-Eco` 增加有限区域需求活动的 JDBC 预留基础：物品签名、总数量、总预算、个人上限、时间窗和操作幂等都由数据库复核；当前只接受 `CASH`，尚未接入玩家交付界面、Vault 发款和物品扣除编排。
- 官方仓库增加有限清算批次基础，把仓库库存转换为有数量上限的清算批次，并以 `RESERVED / PAID / DELIVERED / RELEASED` 状态支持恢复和重放；当前仍缺少玩家购买界面以及 Vault/物品交付接线。
- 重大订单可以声明只读 `RPG_PROJECT` 指标和银行行业/用途政策字段。RPG 项目进度源尚未跨插件接入时会明确显示不可用，不会把手工值伪装成真实进度。
- 多货币账本把 MySQL/MariaDB 操作 ID 改为大小写敏感存储，避免幂等键碰撞；兑换手续费按最小货币单位向上取整并校验总额守恒，堵住整数货币拆单免手续费路径。
- 银行新增 A-E 信用分层、历史还款/逾期/近期申请评分、风险与期限加点、固定时效报价和额度/期限限制；贷款放款使用待结算/对账状态，未确认的钱包入账不会直接变成正常贷款。活期利息按周期内时间加权平均余额结算，并以唯一周期记录和乐观版本避免重复计息；有效定期存款已纳入 M2。
- 企业贷款还款按旧余额和状态执行 CAS，央行贷款手动/定时回收共用原子认领，违约没收也会先认领贷款；银行抵押拍卖使用持久 escrow 和 `OPEN -> SETTLING -> SOLD/UNSOLD` 认领，项目合同可在归属唯一时交割给竞拍企业。旧游戏内工程评标继续失败关闭；Web 企业保证金、公户扣款、escrow 和企业预付款在同一事务提交，Web 个人保证金/预付款通过外部钱包 journal 结算。企业房产卖款已通过企业资金 provider 原子进入企业公户并同步开户行镜像，不会落入成员个人钱包。
- 地产保留旧区域 `FLAT` 价格语义，新区域可使用 `PER_BLOCK`、最低成交价、单地块面积上限，以及玩家/企业持有面积软硬限。超过软限会线性加价，超过硬限拒绝；报价会在写事务中重新计算，副本临时地块不计入持有面积，系统不会自动没收地块。

### Boss 与 RPG 暂存内容

RPG、Boss 和后续内容扩展已暂缓，本轮收口只处理基础设施、P0/P1 与经济可靠性。此前准备的 3 个彼此隔离的 MythicMobs 团队 Boss 包仍只位于 `deploy_package/`，不包含 JAR，也没有复制到测试服、执行 `/mm reload` 或启动 Paper。

| 内容包 | 核心协作玩法 | 证明接点 | 当前状态 |
|------|-------------|----------|----------|
| `AshenFoundry_Overseer` / 灰烬铸魂者 | 双阀分组、熔渣救援、连续命中过载打断 | `ashen_foundry_clear` | 三阶段包与奖励/证明片段已准备，需实机验证参与资格清理、助手回收和数值。 |
| `Aurora_Packwarden` / 极光群猎者 | 四点分站、`2+2` 暖炉、三名不同玩家依次截击 | `aurora_packwarden_clear` | 复用现有模型蓝图，需验证 ModelEngine 状态、投射物顺序、人数采样和副本奖励。 |
| `Stormforge_Overseer` / 雷炉监工 | 分散铸痕、三相线圈、双阀时序和进圈/出圈波形 | `stormforge_overseer_clear` | YAML、内部引用和奖励配置静态检查通过，尚无 `/mm reload`、实战或平衡验收。 |

`ks-RPG` 第二波内容把灰烬边境和风暴崖扩展为 17 个 MMOItems ID、11 条确定性兑换、两名低频区域精英、五类内容 YAML 和贡献者轮转掉落；正式使用前仍需手工合并 MMOItems 片段，并把随机生成范围收紧到真实区域。第三波 Earthvein/遗物内容仍是暂存草案：部分遭遇只是契约，proof-gate 还不能原子扣材料与货币，因此相关遗物必须保持不可获取。

赛季运行时也已加入本地 SQLite、区域声望周上限/追赶、幂等公共项目、事件快照、奖励领取状态和归档基础；时间窗与周索引由服务端时间校验，进度写入和归档会复核同一季节状态。配置仍默认关闭，启用只会初始化空存储，不会自动创建赛季、启动世界事件或修改玩家数据。

### Web 与库存 GUI

服务器管理和玩家操作可以通过游戏内命令、库存 GUI 或模块提供的 Web 页面完成。Web 页面由 `ks-core` 统一承载，模块只注册自己的路由；库存、物品 NBT 和 Vault 结算仍由对应插件负责。

## 玩家入口

以下是常见的普通玩家入口。命令是否可用取决于对应插件、Extra 模块和服务器配置；管理员命令、完整权限表和所有别名不放在首页，统一维护在技术报告中。

| 入口 | 用途 |
|------|------|
| `/kseco gui` | 打开经济主界面，进入市场、盲盒、限时商店、补偿和暂存箱等入口。 |
| `/market` | 打开玩家市场。 |
| `/trade <玩家>` | 发起玩家间交易。 |
| `/storage` | 打开物品暂存箱。 |
| `/map` | 获取 Web 世界地图入口。 |
| `/land` | 打开土地和房屋管理入口。 |
| `/dungeon` | 打开副本大厅和队伍入口。 |
| `/ksrpg catalog` | 查看当前 RPG 内容目录。 |
| `/ksrpg exchange <id> [数量]` | 使用材料兑换已配置的 RPG 内容。 |
| `/rpggear` | 打开 RPG 配装入口。 |

普通玩家的完整使用流程请看 [玩家版指南](docs/KS-SERIES-PLAYER-README.md)。

## 安装与构建

### 基本要求

- LeavesMC 1.21.11 或兼容的 Paper 1.21 服务端。
- Java 21。
- `ks-core` 必须先于依赖它的模块加载。
- `ks-Eco` 的 Extra JAR 放在 `plugins/ks-Eco/extra/`，不要放到 `plugins/` 顶层。
- Vault、LuckPerms、ItemsAdder、MythicMobs、ModelEngine、FAWE、ProtocolLib 等属于按功能选择的外部依赖，不需要全部安装。

### 构建顺序

仓库目前由多个独立 Maven 模块组成，没有统一的根 `pom.xml`。通常先安装核心 API，再构建经济核心和需要的扩展：

```powershell
Set-Location .\ks-core
mvn clean install

Set-Location ..\ks-InstanceWorld
mvn clean install

Set-Location ..\ks-RPG
mvn clean install

Set-Location ..\ks-Eco
mvn clean install

Set-Location ..\ks-Eco-bank
mvn clean package
```

再构建各 Extra、`ks-RPG-Gui`、`ks-Cinematic` 和兼容/工具模块。2026-07-23 的最终收口按依赖顺序完成 23/23 个模块，共执行 391 项测试，0 failure、0 error、0 skipped；受最终修复影响的默认/Folia 构件均再次定向复验。Web 外部脚本 22/22、严格源 YAML 341/341、17 个插件入口和 25 个本地引用全部通过。部署和备份必须使用仓库中的 `scripts/deploy-plugin.ps1`；不要把运行时数据库、备份、测试 token 或本机凭据提交到仓库。

## 依赖与兼容

| 依赖 | 用途 |
|------|------|
| Vault 或 VaultUnlocked | 外部经济接口；`ks-Eco` 在不可用时可使用内置经济实现。 |
| LuckPerms | 权限组管理。 |
| ItemsAdder / MMOItems | 自定义物品、装备和资源包。 |
| MythicMobs / MythicCrucible / ModelEngine | Boss、技能和模型内容。 |
| FAWE / WorldEdit | 副本地图和区域操作。 |
| ProtocolLib / packetevents | 协议和数据包相关功能。 |
| PlaceholderAPI | 变量占位。 |

多数外部集成按软依赖设计，缺失时对应功能会关闭或降级。各模块的 `plugin.yml`、`pom.xml` 和配置文件是最终依赖依据，README 不替代这些声明。

## 文档

| 文档 | 面向对象 | 内容 |
|------|----------|------|
| [完整技术报告](docs/KS-SERIES-REPORT.md) / [English](docs/KS-SERIES-REPORT.en.md) | 服主、开发者 | 全部插件、命令、权限、部署边界、API 和 `ks-Eco` 模块设计。 |
| [玩家版指南](docs/KS-SERIES-PLAYER-README.md) / [English](docs/KS-SERIES-PLAYER-README.en.md) | 普通玩家 | 玩家玩法、普通命令和经济/RPG 使用流程。 |
| [房地产与副本玩家教程](docs/房地产与副本插件玩家教程.md) / [English](docs/房地产与副本插件玩家教程.en.md) | 玩家 | 土地、房屋和副本的具体操作。 |
| [代码地图](docs/CODEBASE_MAP.md) / [中文](docs/CODEBASE_MAP.zh-CN.md) | 开发者 | 入口类、模块归属、线程边界和数据库责任。 |
| [跨服测试网络](network_1_21/README.md) | 运维、开发者 | MCSM 节点、端口、共享数据库、Folia 边界和实机验收。 |
| [2026-07-23 全功能验收](docs/KS-ECO-FULL-FUNCTION-TEST-2026-07-23.md) | 运维、开发者 | 23 模块矩阵、普通版/Folia API、地图/地产、部署证据和明确限制。 |
| [修改日志](CHANGELOG.md) | 维护者 | 仓库级发布记录；各模块目录另有本地修改日志。 |

## 开发状态

KS-Series 仍处于持续迭代阶段。2026-07-23 已由 MCSM 运行 MariaDB、BungeeCord、Leaves 主端、Paper RPG 端和 Folia 实验端；三端 ks-core/ks-Eco 均连接共享数据库并启动跨服运行时。Leaves 加载 6 个经济 Extra，Paper 按 RPG 角色加载企业/税务/地产/副本 4 个，Folia 加载本轮完成适配的 6 个。RPG/Boss 后续内容仍暂缓。

本轮已把旧 SQLite 的 139 张表迁移到真实 MariaDB，并完成真实三端事件发布/轮询烟测；但未连接真实 MySQL，也未执行外部远程存量迁移、外部 Vault 多节点、断网、崩溃注入或生产压力验收。非 `CASH` 玩家结算、需求活动/官方清算交付、旧游戏内工程评标，以及其他尚未 journal 化的外部 Vault/物品窗口仍是明确未完成项。个人工程、个人/企业房产、副本门票和付费复活虽已具备持久 journal、恢复或人工复核基础，也仍需真实玩家并发与故障场景验收。

提交问题时，请尽量附上服务端版本、相关插件版本、复现步骤、完整报错和涉及的模块。不要上传运行时数据库、访问 token、服务器日志中的隐私信息或本机配置。

## 许可证

本项目采用 [Mozilla Public License 2.0](LICENSE)。项目中受 MPL-2.0 覆盖的文件可以被复制、修改、编译、商用和再发布；发布修改过的覆盖文件时，需要继续提供该文件的源代码并保留许可证与版权声明。与本项目组合但属于独立文件的其他作品，可以按照自己的许可证发布。

第三方依赖和外部资源仍受其各自许可证约束，具体以对应文件中的声明为准。完整条款见 [LICENSE](LICENSE)。

## 维护说明

我会根据当前源码、配置和实际验证结果维护这份 README。首页只保留适合公开仓库快速理解和开始使用的内容；详细的命令、权限、API、实现细节和已知限制请以技术报告为准。

后续开发默认把可调玩法参数、功能开关、提示文本和集成选择放入可校验 YAML，并尽量提供稳定通用 API；安全边界、协议常量和持久状态机仍保留在代码。支持热加载的配置必须先完整解析和校验，再原子替换运行时快照；数据库连接、节点身份和跨服生命周期等重启项必须明确标注。每次发布同时更新根与对应模块的 `CHANGELOG.md`。
