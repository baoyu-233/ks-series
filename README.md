# KS Series — Minecraft 生存服插件体系

这是我围绕 LeavesMC 1.21.11 维护的一套 Minecraft 服务器插件集合。项目以生存服为基础，把经济、市场、土地、企业、世界地图、机器人、Boss 和渐进式 RPG 组织成可以独立构建、按需部署的模块。

我希望每个系统都有清晰的边界：经济负责结算，RPG 负责成长和战斗证明，MythicMobs 负责内容表现，额外模块通过明确的接口接入。项目仍在持续开发，配置、命令和 API 可能随版本调整，正式使用前请先在自己的测试服验证。

这份 README 介绍项目定位和公开使用入口。需要逐项核对命令、权限、部署位置、Web API 或 `ks-Eco` 设计时，请查看 [完整技术报告](docs/KS-SERIES-REPORT.md)；面向普通玩家的使用说明请查看 [玩家版指南](docs/KS-SERIES-PLAYER-README.md)。

> 服务端基线：LeavesMC 1.21.11（Paper 1.21 fork）
>
> 核心数据：SQLite，由 `ks-core` 统一管理
>
> 经济核心：`ks-Eco`，支持库存 GUI、Web 页面和可选 Extra 模块

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
  ├─ ks-RPG / ks-RPG-Gui
  └─ 其他独立功能插件
```

`ks-core` 是基础网关和数据服务；`ks-Eco` 是经济核心以及 Extra 宿主；Extra 模块按需放入 `plugins/ks-Eco/extra/`。其他模块是独立插件，是否部署由服务器玩法决定，不要求把整个仓库一次性装入服务器。

## 模块地图

### 基础与运行支撑

| 模块 | 作用 |
|------|------|
| `ks-core` | SQLite 数据服务、Web 网关、跨插件路由和基础鉴权。 |
| `ks-Compat` | Leaves、Vault、第三方插件和 KS 系列之间的兼容桥接，同时提供 KSBot 相关能力。 |
| `ks-BotGuard` | 识别 Leaves Bot 事件，隔离其与 MythicLib/MMOCore 等插件的玩家数据访问冲突。 |
| `ks-Maintenance` | 服务器维护期和日常运维辅助功能。 |

### 经济与世界系统

| 模块 | 作用 |
|------|------|
| `ks-Eco` | 市场、官方收购、动态价格、盲盒、限时商店、补偿、玩家交易和暂存箱。 |
| `ks-Eco-bank` | 央行与商业银行、存贷款、利率、流动性和货币供应量。 |
| `ks-Eco-enterprise` | 企业注册、公户、招投标、采购、分红和成员权限。 |
| `ks-Eco-tax` | 交易税、行业税、阶梯税率和税务记录。 |
| `ks-Eco-RealEstate` | 区域规划、地块、房屋登记、商品房和领地信任。 |
| `ks-Eco-RealEstateDungeon` | 副本实例、队伍、门票、复活、房产和完成奖励。 |
| `ks-Eco-politic` | 元老院、职位、提案、投票和立法门控。 |
| `ksHWP` | Web 世界地图、地图瓦片、玩家位置和地图备注。 |

### 物品、战斗与 RPG

| 模块 | 作用 |
|------|------|
| `ks-RPG` | RPG 目录、材料兑换、战斗证明、内容配置、技能和掉落。 |
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

### Extra 模块化

银行、企业、税收、房地产、副本和政治不是写死在一个巨大插件里的功能，而是 `ks-Eco` 的可选扩展。服务器可以只部署需要的模块；缺少某个 Extra 时，其他经济功能仍可独立运行。具体加载和部署规则见 [完整技术报告](docs/KS-SERIES-REPORT.md)。

### 生存优先的 RPG

RPG 层不会替代基础生存玩法。`ks-RPG` 负责渐进式成长、战斗证明、技能和内容目录，MMOItems 负责装备表达，MythicMobs 负责 Boss 和遭遇内容，`ks-Eco` 负责材料、货币和奖励结算。普通生存资源、经济积累和合作挑战各自保留价值。

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

Set-Location ..\ks-Eco
mvn clean install

Set-Location ..\ks-Eco-bank
mvn clean package
```

其他模块可以进入各自目录执行 `mvn clean package`。部署和备份请使用仓库中的 `scripts/deploy-plugin.ps1`，并在测试服完成验证后再替换正式 JAR。不要把运行时数据库、备份、测试 token 或本机凭据提交到仓库。

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
| [完整技术报告](docs/KS-SERIES-REPORT.md) | 服主、开发者 | 全部插件、命令、权限、部署边界、API 和 `ks-Eco` 模块设计。 |
| [玩家版指南](docs/KS-SERIES-PLAYER-README.md) | 普通玩家 | 玩家玩法、普通命令和经济/RPG 使用流程。 |
| [房地产与副本玩家教程](docs/房地产与副本插件玩家教程.md) | 玩家 | 土地、房屋和副本的具体操作。 |
| [代码地图](docs/CODEBASE_MAP.md) | 开发者 | 入口类、模块归属、线程边界和数据库责任。 |

## 开发状态

KS-Series 仍处于持续迭代阶段。部分模块已经在 LeavesMC 测试环境中构建和验证，部分跨插件流程仍需要根据实际服务器负载、第三方插件版本和玩家反馈继续调整。发布新版本时，我会优先更新源码、配置和技术报告，再同步 README 的模块入口与兼容说明。

提交问题时，请尽量附上服务端版本、相关插件版本、复现步骤、完整报错和涉及的模块。不要上传运行时数据库、访问 token、服务器日志中的隐私信息或本机配置。

## 许可证

本项目采用 [Mozilla Public License 2.0](LICENSE)。项目中受 MPL-2.0 覆盖的文件可以被复制、修改、编译、商用和再发布；发布修改过的覆盖文件时，需要继续提供该文件的源代码并保留许可证与版权声明。与本项目组合但属于独立文件的其他作品，可以按照自己的许可证发布。

第三方依赖和外部资源仍受其各自许可证约束，具体以对应文件中的声明为准。完整条款见 [LICENSE](LICENSE)。

## 维护说明

我会根据当前源码、配置和实际验证结果维护这份 README。首页只保留适合公开仓库快速理解和开始使用的内容；详细的命令、权限、API、实现细节和已知限制请以技术报告为准。
