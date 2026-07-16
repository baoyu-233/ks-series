# KS Series — Minecraft RPG 综合服务器插件体系

这是我为 LeavesMC 1.21.11 维护的一套 Minecraft 服务器插件集合。项目围绕生存服的经济、市场、土地、企业、Boss、机器人和 RPG 扩展组织成多个模块，目标是让各个系统能够独立构建、按需部署，并在长期运营中保持清晰的边界。

这份 README 负责介绍项目结构、主要能力和常用入口。命令、权限、部署位置、模块设计以及 `ks-Eco` 的实现细节，统一放在 [完整技术报告](docs/KS-SERIES-REPORT.md)；面向玩家的玩法和普通命令见 [玩家版指南](docs/KS-SERIES-PLAYER-README.md)。

> **服务端基线**: LeavesMC 1.21.11（Paper 1.21 fork）  
> **核心数据**: SQLite，由 `ks-core` 统一管理  
> **经济核心**: `ks-Eco`，支持 GUI、Web 和可选 Extra 模块  
> **玩家入口**: 游戏内命令、库存 GUI，以及按模块提供的 Web 页面

---

## 目录

- [架构总览](#架构总览)
- [插件详解](#插件详解)
  - [ks-core — 核心网关](#ks-core--核心网关)
  - [ks-Eco — 经济核心](#ks-eco--经济核心)
  - [ks-Eco-bank — 银行系统](#ks-eco-bank--银行系统-extra)
  - [ks-Eco-enterprise — 企业系统](#ks-eco-enterprise--企业系统-extra)
  - [ks-Eco-tax — 税收系统](#ks-eco-tax--税收系统-extra)
  - [ks-Eco-RealEstate — 土地与房地产](#ks-eco-realestate--土地与房地产-extra)
  - [ks-Eco-RealEstateDungeon — 副本与硬核房产](#ks-eco-realestatedungeon--副本与硬核房产-extra)
  - [ks-Eco-politic — 元老院共和政治](#ks-eco-politic--元老院共和政治-extra)
  - [KS-ItemEditor — 物品编辑器](#ks-itemeditor--物品编辑器)
  - [KS-ItemSteal — 物品窃取](#ks-itemsteal--物品窃取)
  - [ksHWP — Web 世界地图](#kshwp--web-世界地图)
  - [ks-Inherit — 物品继承](#ks-inherit--物品继承)
  - [ks-Sentinel — 管理员行为审计](#ks-sentinel--管理员行为审计)
- [游戏命令大全](#游戏命令大全)
  - [经济体系命令](#经济体系命令)
  - [副本命令](#副本命令)
  - [房地产命令](#房地产命令)
  - [地图与物品命令](#地图与物品命令)
  - [物品继承命令](#物品继承命令)
  - [权限一览](#权限一览)
- [Web 面板与 API 参考](#web-面板与-api-参考)
  - [面板入口汇总](#面板入口汇总)
  - [ks-core 路由分发](#ks-core-路由分发)
  - [ks-Eco 核心 API](#ks-eco-核心-api)
  - [银行 API](#银行-api)
  - [企业 API](#企业-api)
  - [税收 API](#税收-api)
  - [房地产 API](#房地产-api)
  - [副本 API](#副本-api)
  - [政治 API](#政治-api)
  - [地图 API](#地图-api)
  - [物品继承 API](#物品继承-api)
  - [Token 鉴权说明](#token-鉴权说明)
- [测试指南](#测试指南)
  - [测试环境概览](#测试环境概览)
  - [通用测试流程](#通用测试流程)
  - [ks-core 测试项](#ks-core-测试项)
  - [ks-Eco 测试项](#ks-eco-测试项)
  - [ksHWP 测试项](#kshwp-测试项)
  - [银行系统测试项](#银行系统测试项)
  - [企业系统测试项](#企业系统测试项)
  - [税收系统测试项](#税收系统测试项)
  - [市场模拟自动化测试](#市场模拟自动化测试)
  - [测试服权限配置参考](#测试服权限配置参考)
- [编译与部署](#编译与部署)
- [第三方依赖](#第三方依赖)
- [架构决策与设计复盘](#架构决策与设计复盘)
  - [核心选型](#核心选型)
  - [经济模型设计](#经济模型设计)
  - [数据库可靠性设计](#数据库可靠性设计)
  - [历次迭代关键修复](#历次迭代关键修复)
- [服务端目录结构](#服务端目录结构)
- [常见问题排障](#常见问题排障)
- [设计理念](#设计理念)

---

## 架构总览

```
ks-core (HTTP 网关 + SQLite + Token 鉴权)
  ├── ks-Eco (经济核心)
  │   └── plugins/ks-Eco/extra/
  │       ├── ks-Eco-bank-1.1.0.jar              (银行)
  │       ├── ks-Eco-enterprise-1.1.0.jar         (企业)
  │       ├── ks-Eco-tax-1.1.0.jar                (税收)
  │       ├── ks-Eco-RealEstate-1.1.0.jar         (房地产)
  │       ├── ks-Eco-RealEstateDungeon-1.0.0.jar  (副本)
  │       └── ks-Eco-politic-1.0.0.jar            (政治)
  ├── KS-ItemEditor (物品编辑)
  ├── KS-ItemSteal (物品窃取)
  ├── ksHWP (世界地图)
  ├── ks-Inherit (物品继承)
  └── ks-Sentinel (行为审计)
```

**依赖链**: 所有插件强依赖 `ks-core`（Web 网关 + 数据库）。ks-Eco 的 extra 子模块通过 `ExtraModuleLoader` 反射从 `plugins/ks-Eco/extra/*.jar` 运行时加载，互相独立——缺失某个不影响其他功能。

**路由分发**: ks-core 内嵌 HTTP 服务器统一端口，通过 `KsPluginBridge` 将 `/ks-Eco`、`/kSHWP`、`/ks-Inherit`、`/IE`、`/announce` 等路由分发给各插件。

**构建顺序**: ks-core → ks-Eco → 6 个 extra 子模块 → KS-ItemEditor → KS-ItemSteal → ksHWP → ks-Inherit → ks-Sentinel。⚠️ 必须先 `mvn install` ks-core，其他模块通过 Maven `provided` 依赖本地 `.m2` 中的 ks-core。

**当前模块补充**: 仓库还包含 `ks-Compat`、`ks-BotGuard`、`ks-BossCombat`、`ks-Maintenance`、`ks-Skill`、`ks-Title` 六个独立插件；它们的功能、命令、权限和部署位置以[完整总报告](docs/KS-SERIES-REPORT.md)为准。

---

## 插件详解

### ks-core — 核心网关

**基本功能**: KS 系列的中枢神经。提供内嵌 HTTP 服务器、SQLite 数据库连接池、Token 鉴权体系、跨插件路由分发、以及公开公告栏。

| 项目 | 说明 |
|------|------|
| 版本 | 1.1.0 |
| 主类 | `org.kscore.KsCore` |
| 依赖 | 无（独立加载） |
| 端口 | 58578（生产）/ 8123（测试） |
| 数据库 | `plugins/ks-core/data.db`（SQLite，30+ 张业务表） |

**权限**:

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `kscore.admin` | OP | 核心管理（重载配置、查看状态） |
| `kscore.web` | true | 访问 Web 服务 |

**命令**:

| 命令 | 别名 | 说明 |
|------|------|------|
| `/kscore` | `/ksc` | 查看网关状态（运行中、活跃路由数、活跃会话数、已注册子插件列表） |
| `/kscore reload` | — | 重载配置（Web 服务器重启，新端口/超时生效） |

**核心机制**:

- **Token 鉴权**: `POST /api/test-token`（仅 localhost）获取 admin token；`GET /api/login?player=<name>` 获取玩家 token。受保护 API 需 `Authorization: Bearer <token>`。默认有效期 600s，支持续期/刷新/主动移除。
- **路由注册**: `router.register("plugin-id", "/prefix", handler)` — 各插件 onEnable 时注册自己的 URL 前缀。最长前缀匹配，同一 pluginId 重复注册会覆盖。
- **公告栏**: `AnnouncementManager` 管理 `ks_announcements` 表。`GET /announce` 公开页（15s 自动刷新），分 VOTING / LAW / GENERAL 三类。`KsPluginBridge.postAnnouncement()` 供其他插件推送公告（如政治系统推送立法动态）。
- **跨插件桥接**: `KsPluginBridge` 提供 `sendJson` / `sendHtml` / `parseQuery` / `readBody` / `createToken` / `postAnnouncement` / `getDatabaseConnection` 等方法。子插件 onDisable 时调用 `bridge.unregisterRoute()` 清理路由。

**设计意义**: 将所有插件的 Web 入口统一到一个端口，避免了每个插件各自开 HTTP 端口的管理噩梦。SQLite 集中管理使跨插件数据查询成为可能（如政治系统直读税率表做法案执行）。Token 体系将 Web 面板的权限控制与游戏内权限打通。

---

### ks-Eco — 经济核心

**基本功能**: KS 系列的经济引擎。提供市场交易系统、盲盒抽奖、官方收购、动态定价、玩家间交易、物品暂存箱，以及覆盖全部子系统的 Web 管理 SPA（admin.html ~100KB + player.html ~48KB，30+ 功能 Tab）。

| 项目 | 说明 |
|------|------|
| 版本 | 1.2.0 |
| 主类 | `org.kseco.KsEco` |
| 依赖 | ks-core（硬依赖）、Vault（软依赖） |
| Web 路由 | `/ks-Eco` |
| 关键源文件 | `EcoWebHandler.java`（~3500 行，全部 REST 端点 + 内联 HTML） |

**权限**:

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `kseco.admin` | OP | 经济管理（重载、强制限价、虚空交易、查看状态） |
| `kseco.market` | true | 使用市场 GUI |
| `kseco.trade` | true | 发起玩家间交易 |
| `kseco.storage` | true | 使用物品暂存箱 |

**命令**:

| 命令 | 别名 | 说明 |
|------|------|------|
| `/kseco web` | `/kse`, `/eco` | 获取 Web 经济面板链接 |
| `/kseco-admin reload\|status` | `/ecoadmin` | 管理员面板（需 `kseco.admin`） |
| `/kseco-admin force-price <材质> <价格>` | — | 强制限价 |
| `/kseco-admin void-trade <材质> <数量> <价格> <BUY\|SELL>` | — | 虚空交易干预市场 |
| `/market` | `/mkt`, `/ah` | 打开市场 GUI |
| `/trade <玩家名>` | `/deal` | 发起玩家间交易 |
| `/storage` | `/stash`, `/chest` | 打开物品暂存箱 |
| `/politic` | `/kspolitic`, `/senate` | 查看政治身份（politic extra 注入） |
| `/dungeon` | `/ksdungeon`, `/raid` | 副本大厅（dungeon extra 注入） |

**核心子系统**:

1. **市场系统**: 玩家挂单买卖，动态定价引擎根据供需自动调整参考价。GUI + Web 双入口。挂单上限 `max-listings-per-player`（默认 20），过期时间 `listing-expire-hours`，交易税率 `tax-rate`（默认 2%，不低于 `min-tax` 1.0）。
2. **盲盒系统** (`BlindBoxManager`, ~510 行): 替代旧官方直售。三池（ITEM/MATERIAL/EQUIPMENT）+ 五级稀有度 + 权重抽签 + pity 保底。支持企业盲盒（公户扣款、行业白名单、票券制、10 连抽）。发放入背包优先 → 满则入暂存箱（NBT 完整保留）。
3. **官方收购**: `OfficialBuyManager` 按材质直接收购物品，作为经济托底。价格由真实 SELL 流水的双向供需压力和均值回归漂移共同决定；`price-refresh-minutes` 可在 Web 端热重载，旧的 `price-fluctuation` 仅作为兼容配置保留。
4. **物品暂存箱**: `StorageManager` — 物品 `serializeAsBytes()` → Base64 → SQLite BLOB，完整保留全部 NBT。默认 54 格，30 天过期清理。
5. **BuiltinEconomy**: Vault 不可用时的 SQL 兜底经济实现，支持 withdraw/deposit/getBalance 事务操作。
6. **潜影盒解析**: 交易时递归解析盒内物品计入总价，最大深度 3 层，空盒按 `empty-box-value`（默认 5.0）计价。
7. **排行榜**: 个人财富 / 企业资产 / 银行资产 Top50。
8. **审计日志**: `ks_audit_log` 表记录 12 种操作类型（BANK_CREATE / LOAN_ISSUE / CB_INJECT / ENTERPRISE_REGISTER 等）。

**扩展体系**: ks-Eco 是 extra 子系统的宿主和路由中枢。Extra 模块不直接暴露 HTTP 端点——全部通过 `EcoWebHandler` 内的 `callExtraManager()` 反射调用。这意味着某个 extra 模块的 bug 不会拖垮整个经济系统（返回 null 而非抛异常），且服主可以按需选择装哪些子系统。

**Web API**: 共 60+ REST 端点，覆盖认证、市场、银行、企业、税收、房地产、副本、盲盒、玩家搜索、排行榜、审计。详见 [Web 面板与 API 参考](#web-面板与-api-参考)。

**设计意义**: 整个体系中最复杂的插件。它既是经济模拟器，也是 extra 子系统的宿主和路由中枢。反射调用架构使 extra 模块可以独立开发、独立部署、独立崩溃而不拖垮整个经济系统。Web SPA 是运营核心界面，覆盖全部子系统的可视化操作。

---

### ks-Eco-bank — 银行系统 (Extra)

**基本功能**: 央行-商业银行双层银行体系。央行调控基准利率和货币供应，商业银行吸存放贷，完整 M0/M1/M2 货币供应量追踪。

| 项目 | 说明 |
|------|------|
| 版本 | 1.1.0 |
| 入口类 | `org.kseco.extra.bank.BankExtra` |
| 主类 | `BankManager`, `CentralBankManager`, `CbLoanManager`, `MoneySupplyTracker` |
| 部署位置 | `plugins/ks-Eco/extra/ks-Eco-bank-1.1.0.jar` |
| 数据库表 | 10 张（banks, accounts, loans, cb_rates, cb_config, cb_loans, rates, members, permissions, money_supply） |

**无独立命令**，全部通过 `/ks-Eco` Web 面板或 API 操作。

**核心功能**:

- **商业银行**: 创建/注销、成员管理（OWNER → MANAGER → MEMBER 三级角色）、6 种权限（APPROVE_LOAN / MANAGE_MEMBERS / VIEW_FINANCE / SET_RATES / MANAGE_BIDDING / ACCEPT_PROJECT）。合资银行按出资比例分配股份。
- **央行调控**: 设定存贷基准利率区间（rateMin/rateMax），商业银行利率不得超出央行区间。利率范围 -100% 到 +100%（支持负利率模拟量化宽松）。
- **流动性注入**: `injectLiquidity(bankId, amount, mode)` — GRANT（无偿拨款）和 LOAN（固定利率借贷，到期催收）。
- **浮动利率**: 商业银行在央行区间内自定义存贷利率，受利率浮动限制防止恶性竞争。
- **存贷款**: 存款/取款、贷款发放/还款、贷款审批权限检查。
- **货币供应量追踪**: M0（流通现金）/ M1（M0+活期存款）/ M2（M1+定期存款），按小时快照。
- **CORP-BANK**: 系统内置商业银行，所有企业公户统一托管于此，初始资产 10 亿。

**设计意义**: 复刻真实央行-商业银行二级银行体系。央行通过利率区间和流动性注入间接调控经济，而非直接操控每家银行。货币供应量追踪为宏观经济决策提供数据支撑。CORP-BANK 作为企业资金的法定托管行，实现企业资产与个人资产隔离。

---

### ks-Eco-enterprise — 企业系统 (Extra)

**基本功能**: 完整的企业法人体系——注册、招投标、采购、分红、公户管理，支持国企/私企双轨治理。

| 项目 | 说明 |
|------|------|
| 版本 | 1.1.0 |
| 入口类 | `org.kseco.extra.enterprise.EnterpriseExtra` |
| 主类 | `EnterpriseManager`, `BiddingManager` |
| 部署位置 | `plugins/ks-Eco/extra/ks-Eco-enterprise-1.1.0.jar` |
| 数据库表 | 10 张（enterprises, members, projects, bids, dividends, invites, permissions, corporate_accounts, procurements, procurement_bids） |

**无独立命令**，全部通过 `/ks-Eco` Web 面板或 API 操作。

**核心功能**:

- **企业注册**: STATE_OWNED（国企）/ PRIVATE（私企），支持 industry 行业分类（INDUSTRY / AGRICULTURE / REAL_ESTATE / OTHER）。注册资本扣除：私企从所有者钱包均摊扣除，国企系统注资。
- **企业公户**: 统一托管于 CORP-BANK。注册时自动创建并注入注册资本。项目中标预付款、分红、采购均走公户。
- **招投标**: 发布项目 → 企业/个人投标 → 评标定标。最低价中标（`ORDER BY bid_amount ASC`）。企业投标需注册资本 ≥ 项目预算 × 75%（防止空壳围标）。个人投标无资质限制。
- **双轨评标**: 国企/官方项目强制综合评分（价格 50% + 资质 30% + 时效 20%），私企可选自主指定。
- **企业采购**: 发布采购需求 → 供应商投标 → 最低价自动定标（或手动），预算不可超公户余额。
- **合资邀请**: 邀请玩家加入企业/银行，接受/拒绝机制。
- **分红**: 从企业公户扣除，按持股比例分配。
- **预付款机制**: 中标即获预付款（项目预算 × 预付比例），激励投标；违约金机制防止恶意弃标。企业中标预付款均分给企业所有者，个人中标直接打入玩家账户。
- **权限管理**: CEO → MANAGER → EMPLOYEE 三级角色 + 4 种可授予权限（MANAGE_BIDDING / VIEW_FINANCE / DECLARE_DIVIDEND / MANAGE_MEMBERS）。

**设计意义**: 将企业从"聊天频道+牌子商店"升级为完整法人实体。公户隔离杜绝公款私用。双轨评标区分国企（强制综合评分防腐败）和私企（自主指定灵活经营），模拟了真实经济中所有制对治理结构的影响。个人可投标打破企业垄断，降低市场准入门槛。

---

### ks-Eco-tax — 税收系统 (Extra)

**基本功能**: 行业差异化税率 + 阶梯税率 + 税务处罚，为财政政策提供精细化工具。

| 项目 | 说明 |
|------|------|
| 版本 | 1.1.0 |
| 入口类 | `org.kseco.extra.tax.TaxExtra` |
| 主类 | `TaxRateManager` |
| 部署位置 | `plugins/ks-Eco/extra/ks-Eco-tax-1.1.0.jar` |
| 数据库表 | 4 张（tax_records, tax_rates, tax_brackets, tax_penalties） |

**无独立命令**，全部通过 `/ks-Eco` Web 面板或 API 操作。

**核心功能**:

- **多税种**: 按 category 分类（INDUSTRY_TAX / TURNOVER_TAX / DIVIDEND_TAX / MARKET_TRADE 等，共 8 个税种），所有经济活动触发 `TaxManager` 拦截计税。
- **行业差异化**: `ks_tax_rates` 的 PK 为 (category, industry)，industry=NULL 为通用税率，可针对特定行业设不同税率。
- **阶梯税率**: `ks_tax_brackets` 表，按利润区间 (profit_min, profit_max) 设不同税率。`getBracketRate(industry, scope, profit)` 查询适用档位。示例：小型企业 5% / 中型 8% / 大型 12%。
- **罚单系统**: 管理员可对玩家发出税务罚单，多次逃税罚金递增。
- **完整纳税记录**: 每笔税收记录追踪来源、金额、时间。

**设计意义**: 差异化+阶梯税率使财税政策成为精细调控工具——可以鼓励特定行业（低税率）、抑制过热行业（高税率）、对高利润企业课以重税（阶梯）。与元老院 `legislative_mode` 门控形成闭环：开启后税率调整必须走立法程序。

---

### ks-Eco-RealEstate — 土地与房地产 (Extra)

**基本功能**: 区域规划 + 地块买卖（先买地）+ 房屋登记（后建房，消耗容积率）+ 商品房市场交易 + 地块领地保护 + 原生地图选地组件。

| 项目 | 说明 |
|------|------|
| 版本 | 1.1.0 |
| 入口类 | `org.kseco.extra.realestate.RealEstateExtra` |
| 主类 | `RealEstateManager` |
| 部署位置 | `plugins/ks-Eco/extra/ks-Eco-RealEstate-1.1.0.jar` |
| 数据库表 | 4 张（ks_re_zones, ks_re_plots, ks_re_houses, ks_re_house_trust, ks_re_plot_trust）（已删除死表 ks_re_taxes） |
| 游戏命令 | `/house`、`/land`（aliases: `myland`, `myplot`） |

**两段式产权**: 买地（`plot`）只取得土地使用权，不扣容积率；在已购地块内用"测量棒"圈出实际建筑范围登记成"房屋"（`house`），登记这一步才消耗区域容积率（仅对 RESIDENTIAL 生效）。一地可分多套房登记（联排/公寓分层出售），未登记部分（院子/空地）保护权限仍归地主。

**命令**:

| 命令 | 说明 |
|------|------|
| `/house wand` | 领取测量棒（左键/右键点方块记两角点，含 Y 轴） |
| `/house info` | 查看当前选区 |
| `/house register <名称>` | 登记选区为房屋（校验重叠+容积率，单事务） |
| `/house unregister <房屋ID>` | 退registration，释放容积率名额（市场挂牌中需先撤单） |
| `/house area [坐标]` | 查任意范围内涉及区域的容积率占用情况 |
| `/house list` | 我的房屋列表 |
| `/house sell <房屋ID> <价格>` | 商品房挂牌出售（走 `/market` 「🏠 商品房」Tab） |
| `/land` | 地块管理 GUI（信任名单：破坏/容器/互动三项权限独立开关） |

**核心功能**:

- **区域管理**: admin 划定国有土地区域（world + 坐标范围），设定类型（INDUSTRIAL / AGRICULTURAL / RESIDENTIAL）、基准价、容积率（max_plots，仅 RESIDENTIAL 生效）
- **地块买卖**: 玩家/企业在 FOR_SALE 区域内框选坐标购地，自动区分 PLAYER（Vault 扣个人）和 ENTERPRISE（CORP-BANK 公户扣款），购地不扣容积率
- **房屋登记**: 3D AABB 范围重叠校验 + 容积率校验 + INSERT 单事务包裹（`conn.setAutoCommit(false)`），任何异常自动 rollback，避免"提示失败但容积率仍被扣"的不一致状态
- **商品房市场**: 房屋可挂牌出售（不卖地，只转移房屋产权+对应领地/副本权限），`/market` GUI 用 `OAK_DOOR` 图标渲染，独立筛选 Tab + 购买预览
- **契税**: `tax_rate` 是产权**转移时收一次的流转税**（非持有期维护费），登记时从地块继承，转移成交时按房屋自己的税率结算
- **地块领地保护** (`/land`): `ks_re_plot_trust` 信任名单（破坏/容器/互动三项权限独立授予），PLAYER 地块本人完全权限+信任名单按权限放行，ENTERPRISE 地块按企业成员系统判定（不分角色，成员即放行）；保护范围覆盖方块破坏/放置、容器开箱、方块互动、爆炸（TNT/凋零等）；OP 不受限；内存缓存 `plotCache` 避免高频方块事件直接打 SQLite
- **网页 3D 体素查看器**: 商品房市场可一键查看房屋 3D 渲染图（纯只读浏览，购买仍需在游戏内 `/market` 完成）。后端用 `Bukkit.getScheduler().callSyncMethod()` 从 HTTP 线程跳主线程同步取方块数据；前端按需从 CDN 加载 three.js，`InstancedMesh` 渲染体素，真实贴图 + 楼梯/台阶/栅栏/门等异形方块专用几何体 + 4 档光照预设 + 尺寸显示
- **原生地图组件**: `KsReMap(canvasId, opts)` — 自包含 Canvas 瓦片组件，调 `GET /kSHWP/api/tile` 拿地形，zone/plot 自绘叠加，Shift+拖拽框选地址
- **共享表**: `ks_re_plots` 被副本模块通过 `instance_id` 列共享使用

**设计意义**: 容积率（而非物理空间）作为核心调控工具，模拟了现实城市规划中"土地供给不是物理有没有地，而是政府批不批"的逻辑。先买地后建房的两段式产权把"土地使用权"和"建筑物产权"解耦，让一地多房分层出售成为可能。地块领地保护把"买地"从纯经济概念变成真正能保护财产安全的游戏机制，与企业成员系统打通避免重复造一套权限体系。与副本模块共享 `ks_re_plots` 表实现统一产权登记。原生地图组件（非 iframe）提供了干净的选地体验。

---

### ks-Eco-RealEstateDungeon — 副本与硬核房产 (Extra)

**基本功能**: 虚空世界网格副本生成 + FAWE 地图粘贴 + MythicMobs 告示牌标记刷怪 + 组队系统 + 指数复活费 + 副本内房产置业。

| 项目 | 说明 |
|------|------|
| 版本 | 1.0.0 |
| 入口类 | `org.kseco.extra.realestatedungeon.RealEstateDungeonExtra` |
| 主类 | `DungeonInstanceManager`, `DungeonGridAllocator`, `DungeonDeathHandler`, `DungeonPartyManager`, `SchematicService`, `MythicSpawner` |
| 部署位置 | `plugins/ks-Eco/extra/ks-Eco-RealEstateDungeon-1.0.0.jar` |
| 数据库表 | 7 张（templates, instances, grids, participants, revivals, log, 共享 ks_re_plots） |
| 游戏命令 | `/dungeon`（aliases: `ksdungeon`, `raid`） |
| 配置文件 | `plugins/ks-Eco/dungeon.yml`（13 项白名单热更新，失败自动回滚） |

**权限**: 无独立权限节点，玩家默认可用 `/dungeon` 指令。

**命令**:

| 命令 | 说明 |
|------|------|
| `/dungeon` | 副本面板（查看房产/模板/网格/个人状态） |
| `/dungeon invite <玩家>` | 邀请玩家加入队伍 |
| `/dungeon accept` | 接受组队邀请 |
| `/dungeon party` | 查看队伍信息 |
| `/dungeon start <模板ID>` | 开始副本（队长付费，校验 min/max 人数） |
| `/dungeon leave` | 离开当前副本 |
| `/dungeon revive` | 复活（支付指数复活费） |

**核心功能**:

- **副本模板**: name / difficulty / ticketPrice / minPlayers / maxPlayers / timeLimit / monsterLevel / schematic（地图图名）
- **虚空网格**: `ks-dungeon-world`（WorldType.FLAT + air），网格间距 5000，max_grids=64，优先复用 FREE 网格（按 last_used_at ASC）
- **地图系统** (FAWE + MythicMobs):
  - FAWE 异步贴 `.schem` 地形文件（`SchematicService` 收口所有 WE 引用，缺 FAWE 优雅降级）
  - 告示牌标记刷怪：`[mm]` 行 + `怪物名:等级` 行 → `MythicSpawner` 纯反射调用 MM API
  - `[spawn]` 标记出生点覆盖默认坐标
  - Schematic 目录：`plugins/ks-Eco/dungeon_schematics/` → 回退 FAWE schematics 目录
- **组队系统** (`DungeonPartyManager`): 纯内存 leader→members(LinkedHashSet)，邀请 TTL 2min。start 时校验 n ∈ [minPlayers, maxPlayers]
- **指数复活费** (`DungeonDeathHandler`): cost = base × exp^(n-1)，默认 base=200, exp=1.8
  - 第 1/2/3/5/10 次 = 200/360/648/2099/4640。第 11 次及以上拒绝复活（达到 max_revives_per_player 上限）
- **副本内房产**: 与主世界共享 `ks_re_plots` 表（通过 `instance_id` 区分），`property_function` 标识用途（RESIDENTIAL / DUNGEON_PORTAL / SAFEHOUSE / SHOP / INDUSTRIAL），副本销毁时级联删除
- **生命周期**: WAITING → ACTIVE → COMPLETED/ABANDONED → 清场（杀实体+清地形）→ CLEANING → 120s 后 FREE + 清空该副本房产
- **Admin 授图**: 搭房间 → 放 `[mm]`/`[spawn]` 牌 → FAWE `//copy` → `//schem save <名>` → 放 schematics 目录 → 后台模板填图名

**设计意义**: 将副本从"固定地图+固定怪"升级为"网格化虚空世界+程序化地图注入+标记驱动刷怪"的工业化流水线。一套 schematic + 告示牌标记就能产出无限副本实例。房产功能让副本不只是打怪——玩家可以置业（安全屋、商店、传送门），死了要花钱复活，创造了高风险高回报的硬核经济循环。

---

### ks-Eco-politic — 元老院共和政治 (Extra)

**基本功能**: 四级职务体系 + 互斥规则 + 9 状态提案状态机 + 全票覆议 + 定时选举 + 经济参数门控。

| 项目 | 说明 |
|------|------|
| 版本 | 1.0.0 |
| 入口类 | `org.kseco.extra.politic.PoliticExtra` |
| 主类 | `PoliticManager`, `ProposalManager`, `VoteManager`, `ElectionEngine` |
| 部署位置 | `plugins/ks-Eco/extra/ks-Eco-politic-1.0.0.jar` |
| 路由 | `/ks-Eco/politic`（独立 HttpHandler，20+ API） |
| 数据库表 | 5 张（offices, proposals, votes, election_votes, config） |
| 游戏命令 | `/politic`（aliases: `kspolitic`, `senate`） |

**权限**: 无独立权限节点，所有玩家可用 `/politic` 查看身份与法案。

**命令**:

| 命令 | 说明 |
|------|------|
| `/politic` | 查看执政官/元老院/保民官/骑士/自己身份/最新法案 |

**四级职务与互斥**:

| 职务 | 职权 | 互斥规则 |
|------|------|----------|
| SENATOR（元老） | 投票立法 | 可兼任骑士 |
| CONSUL（执政官） | 提案权 | 必须同时是元老（否则自动开除） |
| TRIBUNE（保民官） | 审查权，一票否决 | 与骑士互斥（先到先得，后就任被免职+级联补位） |
| EQUESTRIAN（骑士） | 荣誉身份 | 与保民官互斥 |

`insertOffice()` 有唯一性守卫——同人同职务不重复插入。

**提案状态机（9 状态）**:

```
PROPOSED → SENATE_VOTING → TRIBUNE_REVIEW → APPROVED → ENACTED
              ↓(≤50%)          ↓(否决)          ↑
            REJECTED         VETOED → SENATE_OVERRIDE → OVERRIDDEN → ENACTED
                                        ↓(非全票)
                                      ABANDONED
```

- **全票覆议**: 全体在册元老必须全投 YES（0 弃权 0 反对）→ OVERRIDDEN → 自动颁布
- **执政官去重计票**: 用 LinkedHashSet 按 UUID 去重（修复 consul+SENATOR 双重计票 bug）

**法案执行** (`enact()` 直写业务表):

| 提案类型 | 效果 |
|----------|------|
| GENERAL | 一般性法案（记录） |
| SET_TAX_RATE | 修改税率 |
| SET_TAX_BRACKET | 修改阶梯税率 |
| SET_CB_RATES | 修改央行基准利率 |
| CB_INJECT | 央行注资（GRANT/LOAN） |
| SET_OFFICIAL_PRICE | 修改官方定价 |
| RE_ZONE_ADMIN | 房地产区域管理（创建/改价/改状态） |

**门控系统**: `checkPoliticGate()` 包裹 7 个经济参数端点。`legislative_mode=true` 时拒绝 admin 直接修改 → 403 要求走元老院提案流程。默认 `false` 向后兼容。

**定时选举** (`ElectionEngine`): Bukkit 30min 定时器——执政官轮选、保民官补位、骑士排行刷新、全量互斥巡检。

**前端与公告集成**:
- player.html「🏛 元老院」Tab：身份/议会构成/提案列表+投票+审查+覆议
- admin.html「🏛 元老院」管理：职务任命+提案监管+议会配置（席位数 + legislative_mode 开关）
- 提案创建改为表单化编辑器，覆盖全部 7 种 enact 类型，无需手写 JSON
- 提案进入表决/覆议 → 自动 upsert VOTING 公告；颁布 → 撤提案公告 + 发 LAW 公告

**设计意义**: 不是装饰性 RP——是真正的立法-行政-审查三权游戏化。经济参数修改权可通过 `legislative_mode` 从 admin 手中移交给元老院，实现"独裁↔共和"的可切换治理模式。提案表单化编辑器大幅降低参与门槛（无需懂 JSON）。公告栏集成让政治决策对全服透明。全票覆议防止简单多数架空保民官否决权。

---

### KS-ItemEditor — 物品编辑器

**基本功能**: GUI + Web 双模物品编辑器，支持名称/Lore/附魔（突破原版上限）/精炼/ItemsAdder 模型套用。

| 项目 | 说明 |
|------|------|
| 版本 | 1.5.0 |
| 主类 | `org.itemedit.ItemEditor` |
| 依赖 | ks-core、ItemsAdder、FotiaEnchantment、MythicMobs（均为软依赖） |
| Web 路由 | `/IE` |
| 附魔配置 | `FotiaEnchantment/` 目录（~70 种自定义附魔） |

**权限**:

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `itemedit.admin` | OP | 管理员物品编辑器（全功能） |
| `itemedit.refine` | true | 玩家武器精炼 |
| `itemedit.design` | true | 玩家 Web 物品设计器 |

**命令**:

| 命令 | 别名 | 说明 |
|------|------|------|
| `/itemedit` | `/ie`, `/itemeditor` | 打开 GUI 编辑器（手持物品） |
| `/ie web` | — | 获取管理员 Web 面板链接 |
| `/ie reload` | — | 重载配置 |
| `/design` | `/designer`, `/wedit` | 获取 Web 物品设计器链接 |
| `/design load <模板码>` | — | 加载已保存模板到手 |
| `/refine` | `/ref` | 打开武器精炼界面（消耗兑换券） |

**核心功能**:

- **GUI 编辑器**: 手持物品执行 `/itemedit`，图形化编辑名称、Lore、附魔（突破原版上限）
- **Web 设计器**: 浏览器端可视化编辑，保存模板码，游戏内 `/design load <码>` 取回
- **精炼系统**: 消耗兑换券升级武器属性
- **ItemsAdder 集成**: 套用自定义模型
- **FotiaEnchantment**: 70 种自定义附魔配置

**设计意义**: GUI+Web 双模降低物品定制门槛——普通玩家用 GUI 快速改，进阶玩家用 Web 精细调。精炼系统提供武器成长曲线。模板码机制让物品设计可在玩家间分享传播。

---

### KS-ItemSteal — 物品窃取

**基本功能**: 无损物品夺取与归还，用于 Boss 战缴械机制或 PvP 惩罚。

| 项目 | 说明 |
|------|------|
| 版本 | 1.0.2 |
| 主类 | `com.steal.ItemSteal` |
| 依赖 | 无 |

**权限**:

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `itemsteal.admin` | OP | 管理命令（givebow/return/reload） |

**命令**:

| 命令 | 说明 |
|------|------|
| `/itemsteal steal` | 夺取目标玩家手中物品 |
| `/itemsteal givebow` | 发放夺取弓 |
| `/itemsteal return` | 归还被夺取的物品 |
| `/itemsteal reload` | 重载配置 |

**设计意义**: 轻量级单一职责插件。为 Boss 设计者提供"缴械"机制——Boss 可暂时夺走玩家武器，战斗结束后归还。无损设计确保不会因游戏机制导致玩家永久损失物品。

---

### ksHWP — Web 世界地图

**基本功能**: Canvas 瓦片渲染的 Web 世界地图，支持备注标注、公开标注、在线玩家追踪、区域框选、多世界切换。

| 项目 | 说明 |
|------|------|
| 版本 | 1.2.0 |
| 主类 | `org.kshwp.KsHWP` |
| 依赖 | ks-core（硬依赖）、Multiverse-Core（软依赖） |
| Web 路由 | `/kSHWP` |
| 瓦片 API | `GET /kSHWP/api/tile?world=&x=&z=&zoom=`（返回 base64 PNG） |

**权限**:

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `kshwp.admin` | OP | 地图管理（重载、强制渲染、公开标注） |
| `kshwp.use` | true | 使用 Web 地图 |
| `kshwp.note` | true | 使用地图备注 |
| `kshwp.hidden` | OP | 隐藏地图位置（`/map hidden`） |

**命令**:

| 命令 | 别名 | 说明 |
|------|------|------|
| `/map` | `/worldmap` | 获取 Web 地图链接（含 token） |
| `/map hidden` | — | 切换隐藏模式（不在线上地图显示） |
| `/mapnote add <文本>` | `/mn add` | 在当前位置添加地图备注 |
| `/mapnote list` | `/mn list` | 查看我的备注列表 |
| `/mapnote delete <ID>` | `/mn delete` | 删除指定备注 |
| `/kshwp reload\|status` | `/hwp` | 管理命令（重载配置/查看状态） |
| `/kshwp prerender\|cache` | — | 预热渲染指定区域 |

**核心功能**:

- **瓦片渲染**: Canvas 分块渲染，滚轮缩放、左键拖拽平移
- **个人备注**: 玩家右键选点添加私有备注
- **公开标注**: 管理员右键添加红色公开标注（所有人可见）
- **在线玩家追踪**: 实时显示在线玩家头像位置
- **区域框选**: Shift+拖拽框选坐标范围（服务于房地产购地）
- **多世界支持**: Multiverse-Core 集成，切换不同世界地图
- **外部复用**: 瓦片 API 被房地产 `KsReMap` 组件调用，实现地图图层复用

**设计意义**: 脱离 Dynmap 的臃肿依赖，自建轻量瓦片渲染。瓦片 API 的开放设计使其他插件（房地产）可复用地图图层而不引入整个 ksHWP UI。框选坐标功能直接服务于房地产购地流程。

---

### ks-Inherit — 物品继承

**基本功能**: 跨版本（1.20.6 → 1.21.11）物品保存与转移。GUI 箱子存物品（完整 NBT），Web 管理端审阅/批准/发放，支持 OpenInv 离线发放。

| 项目 | 说明 |
|------|------|
| 版本 | 1.0.0 |
| 主类 | `org.ksinherit.KsInherit` |
| 依赖 | ks-core、OpenInv（均为软依赖） |
| Web 路由 | `/ks-Inherit` |
| 数据库 | `plugins/ks-Inherit/items.db`（独立 SQLite） |

**权限**:

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `ksinherit.use` | true | 使用物品继承系统 |
| `ksinherit.admin` | OP | 管理物品继承系统 |

**命令**:

| 命令 | 说明 |
|------|------|
| `/inherit open` | 打开物品保存 GUI（锁定格箱子） |
| `/inherit token` | 生成 Web 访问 token + 可点击链接（admin→管理页，player→状态页） |
| `/inherit slots <player> <N>` | 设置玩家可用槽位数（1-54，管理员） |
| `/inherit reload` | 热导入：检测 items_new.db → 逐行 INSERT → 删除新文件 |
| `/inherit testitem` | 获取复杂测试物品（验证 NBT 完整性，管理员） |

**核心功能**:

- **GUI 箱子**: 锁定格箱子，玩家放入要转移的物品，关闭时自动保存全部 NBT
- **物品序列化**: `ItemStack.serializeAsBytes()` → Base64 → SQLite BLOB（附魔/Lore/属性/CustomModelData/unbreakable 完整保留）
- **Web 审阅**: 管理员浏览器查看物品详情（display_name、lore 中英、附魔中英、属性修饰符），批准/拒绝/发放
- **OpenInv 离线发放**: 反射调用 `OpenInv.loadPlayer(OfflinePlayer)` 离线发物品
- **跨版本迁移**: 1.20.6 → 复制 items.db 为 items_new.db → `/inherit reload` 热导入（独立 JDBC 连接，避免文件锁）
- **玩家自助**: `/inherit token` 获取个人 token → 浏览器查看提交状态（待审/已批准/已拒绝/已发放）

**设计意义**: 解决了 Minecraft 大版本升级时玩家物品丢失的痛点。GUI 箱子操作简单直观，NBT 完整保留确保附魔/属性/模型不丢失。Web 审阅让管理团队分工处理大量迁移请求。离线发放（OpenInv）解决了玩家不在线时无法发放物品的问题。

---

### ks-Sentinel — 管理员行为审计

**基本功能**: 全服指令审计系统。记录全体玩家（含控制台）执行的每一条指令，按内置高危规则（give/op/ban/kick/gamemode 等 25+ 条）自动判定风险等级，独立 SQLite 存储，异步批量写入不拖主线程。提供 Web 管理面板（日志筛选、规则增删、排除列表）和 `/sentinel log` 游戏内兜底查看。

| 项目 | 说明 |
|------|------|
| 版本 | 1.0.0 |
| 主类 | `org.kssentinel.KsSentinel` |
| 依赖 | ks-core（软依赖，通过反射接入 Web 鉴权 + 路由注册） |
| Web 路由 | `/ks-Sentinel` |
| 数据库 | `plugins/ks-Sentinel/sentinel.db`（独立 SQLite，3 张表） |
| 关键源文件 | `KsSentinel.java`（核心 + 异步 flush）、`RiskEvaluator.java`（纯逻辑风险评估）、`CommandAuditListener.java`（事件监听）、`SentinelWebHandler.java`（Web API + 内联 HTML 面板） |

**权限**:

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `kssentinel.admin` | OP | 审计管理（查看日志/管理规则/获取 Web token） |

**命令**:

| 命令 | 说明 |
|------|------|
| `/sentinel token` | 获取 Web 管理面板链接（可点击，需 ks-core） |
| `/sentinel log [玩家] [条数]` | 查看最近审计记录（无 ks-core 时仍可用） |
| `/sentinel exclude list` | 列出排除规则（不记录的指令前缀） |
| `/sentinel exclude add <指令前缀>` | 添加排除规则（如 `tpa` 完全跳过记录） |
| `/sentinel exclude remove <id>` | 删除排除规则 |

**核心机制**:

- **全量监听**: `CommandAuditListener` 监听 `PlayerCommandPreprocessEvent` + `ServerCommandEvent`，MONITOR 优先级、不忽略已取消事件——即便被权限插件拦截的尝试（如无权限玩家尝试 `/op`）也会被记录，因为"尝试本身"就是审计信号。
- **风险评估** (`RiskEvaluator`, 纯逻辑不依赖 Bukkit 事件):
  - 内置 25 条高危规则：`give`、`gamemode`、`tp`、`effect`、`enchant`、`kill`、`op`、`deop`、`ban`、`ban-ip`、`stop`、`kick`、`reload`、`fill`、`summon` 等
  - 智能目标检测：`checkTargetArg=true` 的规则会解析参数中是否引用了"另一个在线玩家"——对自己操作降级为 INFO，对别人操作才标 HIGH/MEDIUM
  - 排除列表优先：匹配排除规则的指令完全不写入数据库
- **异步批量写入**: `ConcurrentLinkedQueue` 入队 → 每 100 ticks（5 秒）`runTaskTimerAsynchronously` 批量 flush，`conn.setAutoCommit(false)` 事务包裹，失败 rollback
- **Web 管理面板** (`SentinelWebHandler` 内联 HTML):
  - 日志表格：按风险等级/执行者/指令关键字筛选，分页浏览，MONO 字体展示原始指令
  - 规则管理：增删高危规则（指令前缀 + 是否检查目标参数 + 风险等级）
  - 排除管理：增删排除规则（如 `tpa`、`msg` 等高频无害指令）
  - 鉴权分级：`POST /api/test-token` 仅 localhost 获取 admin token；全部 `/api/*` 需 Bearer token + admin 身份

**关键 API** (路由 `/ks-Sentinel`):

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/admin` | 任意 | 管理面板 HTML 页面 |
| POST | `/api/test-token` | 仅 localhost | 获取测试 admin token |
| GET | `/api/logs?riskLevel=&executor=&command=&page=&pageSize=` | admin | 查询审计日志（支持多维筛选+分页） |
| GET | `/api/rules` | admin | 高危规则列表 |
| POST | `/api/rules` | admin | 新增/更新规则 `{"commandPrefix":"give","checkTargetArg":true,"riskLevel":"HIGH"}` |
| DELETE | `/api/rules?id=` | admin | 删除规则 |
| GET | `/api/exclusions` | admin | 排除规则列表 |
| POST | `/api/exclusions` | admin | 新增排除 `{"commandPrefix":"tpa","note":"高频传送指令"}` |
| DELETE | `/api/exclusions?id=` | admin | 删除排除 |

**设计意义**: Minecraft 服务器长期运营的最大隐患之一是无法追溯"谁在什么时候执行了什么高危指令"。ks-Sentinel 解决了这个问题——它不依赖任何日志文件解析，直接在指令执行层拦截记录，独立 SQLite 存储确保审计数据不被服务器日志轮转覆盖。当玩家举报"管理员乱给东西"或"有人偷偷改了模式"时，打开 Web 面板按玩家名+指令关键字一秒定位证据。排除规则机制防止高频无害指令（如 `/tpa`、`/home`）淹没真正需要关注的高危操作。规则完全可配置——服主可以把自己服务器的自定义高危指令加进去。

---

## 游戏命令大全

### 经济体系命令

| 命令 | 别名 | 权限 | 说明 |
|------|------|------|------|
| `/kscore` | `/ksc` | — | 查看网关状态 |
| `/kscore reload` | — | `kscore.admin` | 重载配置 |
| `/kseco web` | `/kse`, `/eco` | — | 获取 Web 经济面板链接 |
| `/kseco-admin reload\|status` | `/ecoadmin` | `kseco.admin` | 管理员面板 |
| `/kseco-admin force-price <材质> <价格>` | — | `kseco.admin` | 强制限价（受 max-price 上限约束） |
| `/kseco-admin void-trade <材质> <数量> <价格> <BUY\|SELL>` | — | `kseco.admin` | 虚空交易干预市场（注入交易影响需求指数） |
| `/market` | `/mkt`, `/ah` | `kseco.market` | 打开市场 GUI |
| `/trade <玩家名>` | `/deal` | `kseco.trade` | 发起玩家间交易 |
| `/storage` | `/stash`, `/chest` | `kseco.storage` | 打开物品暂存箱 |
| `/politic` | `/kspolitic`, `/senate` | — | 查看政治身份/元老院/最新法案 |

### 副本命令

| 命令 | 说明 |
|------|------|
| `/dungeon` | 副本面板（查看房产/模板/网格/个人状态） |
| `/dungeon invite <玩家>` | 邀请玩家加入队伍（TTL 2min） |
| `/dungeon accept` | 接受组队邀请 |
| `/dungeon party` | 查看队伍信息 |
| `/dungeon start <模板ID>` | 开始副本（队长付费，校验 min/max 人数） |
| `/dungeon leave` | 离开当前副本 |
| `/dungeon revive` | 复活（支付指数复活费，公式：200 × 1.8^(n-1)） |

别名：`/ksdungeon` = `/raid` = `/dungeon`

### 房地产命令

| 命令 | 别名 | 说明 |
|------|------|------|
| `/house wand` | — | 领取测量棒（左键/右键记两角点，含 Y 轴） |
| `/house info` | — | 查看当前选区 |
| `/house register <名称>` | — | 登记选区为房屋（校验重叠+容积率，单事务） |
| `/house unregister <房屋ID>` | — | 退registration，释放容积率名额 |
| `/house area [坐标]` | — | 查任意范围内涉及区域的容积率占用情况 |
| `/house list` | — | 我的房屋列表 |
| `/house sell <房屋ID> <价格>` | — | 商品房挂牌出售（走 `/market`「🏠 商品房」Tab） |
| `/land` | `/myland`, `/myplot` | 地块管理 GUI（信任名单三个权限独立开关） |

### 地图与物品命令

| 命令 | 别名 | 权限 | 说明 |
|------|------|------|------|
| `/map` | `/worldmap` | — | 获取 Web 地图链接 |
| `/map hidden` | — | `kshwp.hidden` | 切换隐藏模式 |
| `/mapnote add <文本>` | `/mn add` | — | 添加地图备注 |
| `/mapnote list` | `/mn list` | — | 查看我的备注列表 |
| `/mapnote delete <ID>` | `/mn delete` | — | 删除指定备注 |
| `/kshwp reload\|status` | `/hwp` | `kshwp.admin` | 管理命令（重载/状态） |
| `/kshwp prerender\|cache` | — | `kshwp.admin` | 预热渲染指定区域 |
| `/itemedit` | `/ie`, `/itemeditor` | `itemedit.admin` | 打开 GUI 物品编辑器（手持物品） |
| `/ie web` | — | `itemedit.admin` | 获取管理员 Web 面板链接 |
| `/ie reload` | — | `itemedit.admin` | 重载配置 |
| `/design` | `/designer`, `/wedit` | — | 获取 Web 物品设计器链接 |
| `/design load <模板码>` | — | — | 加载已保存设计到手 |
| `/refine` | `/ref` | `itemedit.refine` | 打开武器精炼界面 |
| `/itemsteal steal` | — | `itemsteal.admin` | 夺取目标玩家手中物品 |
| `/itemsteal givebow` | — | `itemsteal.admin` | 发放夺取弓 |
| `/itemsteal return` | — | `itemsteal.admin` | 归还被夺取的物品 |
| `/itemsteal reload` | — | `itemsteal.admin` | 重载配置 |

### 物品继承命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/inherit open` | `ksinherit.use` | 打开物品保存 GUI（锁定格箱子，关闭即保存） |
| `/inherit token` | `ksinherit.use` | 生成 Web 访问 token + 可点击链接 |
| `/inherit slots <player> <N>` | `ksinherit.admin` | 设置玩家可用槽位数（1-54） |
| `/inherit reload` | `ksinherit.admin` | 热导入：检测 items_new.db → 逐行 INSERT → 删除新文件 |
| `/inherit testitem` | `ksinherit.admin` | 获取复杂测试物品（验证 NBT 完整性） |

### 审计命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/sentinel token` | `kssentinel.admin` | 获取 Web 审计面板链接（可点击） |
| `/sentinel log [玩家] [条数]` | `kssentinel.admin` | 查看最近审计记录（无 ks-core 时仍可用） |
| `/sentinel exclude list` | `kssentinel.admin` | 列出排除规则 |
| `/sentinel exclude add <指令前缀>` | `kssentinel.admin` | 添加排除规则（如 `tpa` 跳过记录） |
| `/sentinel exclude remove <id>` | `kssentinel.admin` | 删除排除规则 |

### 权限一览

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `kscore.admin` | OP | 核心网关管理（重载配置、查看状态） |
| `kscore.web` | true | 访问 Web 服务 |
| `kseco.admin` | OP | 经济管理（重载、强制限价、虚空交易、查看状态） |
| `kseco.market` | true | 使用市场 GUI |
| `kseco.trade` | true | 发起玩家间交易 |
| `kseco.storage` | true | 使用物品暂存箱 |
| `itemedit.admin` | OP | 管理员物品编辑器（全功能） |
| `itemedit.refine` | true | 玩家武器精炼 |
| `itemedit.design` | true | 玩家 Web 物品设计器 |
| `itemsteal.admin` | OP | 物品窃取管理 |
| `kshwp.admin` | OP | 地图管理（重载、强制渲染、公开标注） |
| `kshwp.use` | true | 使用 Web 地图 |
| `kshwp.note` | true | 添加地图备注 |
| `kshwp.hidden` | OP | 隐藏地图位置 |
| `ksinherit.use` | true | 使用物品继承系统 |
| `ksinherit.admin` | OP | 管理物品继承系统 |
| `kssentinel.admin` | OP | 审计管理（查看日志/管理规则/获取 Web token） |

---

## Web 面板与 API 参考

### 面板入口汇总

所有面板通过 ks-core 统一端口访问（测试服 8123，生产服 58578）：

| 面板 | 地址 | 权限 | 说明 |
|------|------|------|------|
| 公开公告栏 | `/announce` | 无 | 15s 自动刷新，VOTING / LAW / GENERAL |
| 经济管理 SPA | `/ks-Eco/admin?token=<token>` | admin | 30+ Tab |
| 玩家经济面板 | `/ks-Eco/player?token=<token>` | 玩家 | 个人资产/交易/投标 |
| 政治面板 | `/ks-Eco/politic` | 玩家 | 元老院/提案/投票/审查 |
| Web 地图 | `/kSHWP` | 玩家 | 瓦片地图/备注/玩家追踪 |
| 物品设计器 | `/IE` | 玩家 | Web 可视化物品编辑 |
| 物品继承审阅 | `/ks-Inherit/admin` | admin | 物品审阅/批准/发放 |
| 物品继承状态 | `/ks-Inherit/` | 玩家 | 自己物品状态 |
| 指令审计面板 | `/ks-Sentinel/admin` | admin | 全服指令审计日志/规则管理 |

### ks-core 路由分发

| 路由 | 插件 | 说明 |
|------|------|------|
| `/IE` | KS-ItemEditor | 网页物品设计器 |
| `/kSHWP` | ksHWP | 世界地图 |
| `/ks-Eco` | ks-Eco | 经济管理面板 |
| `/ks-Eco/bank` | ks-Eco-bank | 银行系统面板 |
| `/ks-Eco/enterprise` | ks-Eco-enterprise | 企业系统面板 |
| `/ks-Eco/tax` | ks-Eco-tax | 税法系统面板 |
| `/ks-Eco/politic` | ks-Eco-politic | 政治系统面板 |
| `/ks-Inherit` | ks-Inherit | 物品继承面板 |
| `/ks-Sentinel` | ks-Sentinel | 指令审计面板 |
| `/announce` | ks-core | 公开公告栏 |

### ks-Eco 核心 API

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/ks-Eco/api/login?player=<name>` | 无 | 玩家登录获取 token（返回 token / isAdmin / playerName） |
| GET | `/ks-Eco/api/market/stats` | 无 | 市场统计（activeListings / storedItems / vaultAvailable / 价格列表） |
| GET | `/ks-Eco/api/listings?type=SELL` | 无 | 挂单列表（支持 BUY/SELL 筛选） |
| POST | `/ks-Eco/api/admin/force-price` | admin | 强制限价 `{"material":"IRON_INGOT","price":15.0}` |
| GET | `/ks-Eco/api/admin/idle-items?token=xxx` | admin | 闲置物品查询 |
| GET | `/ks-Eco/api/leaderboard/personal` | 无 | 个人财富排行榜 Top50 |
| GET | `/ks-Eco/api/leaderboard/enterprise` | 无 | 企业资产排行榜 |
| GET | `/ks-Eco/api/leaderboard/bank` | 无 | 银行资产排行榜 |
| GET | `/ks-Eco/api/audit/list` | admin | 审计日志列表 |

### 银行 API

路由前缀 `/ks-Eco/api/bank`：

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/stats` | 无 | 银行统计 |
| GET | `/list` | 无 | 银行列表 |
| POST | `/create` | 玩家 | 创建银行 `{"name":"...","ownerUuids":["..."],"initialCapital":50000}` |
| GET | `/cb/rates` | 无 | 央行利率 |
| POST | `/cb/set-rates` | admin | 设置央行利率 |
| POST | `/cb/inject` | admin | 央行注资（GRANT / LOAN） |
| GET | `/loans` | 无 | 贷款列表 |
| POST | `/loan/issue` | 玩家 | 发放贷款 |
| POST | `/loan/repay` | 玩家 | 还款 |
| POST | `/deposit` | 玩家 | 存款 |
| POST | `/withdraw` | 玩家 | 取款 |
| GET/POST | `/permissions` | 玩家 | 银行权限管理 |

### 企业 API

路由前缀 `/ks-Eco/api/enterprise`：

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/stats` | 无 | 企业统计 |
| GET | `/list` | 无 | 企业列表 |
| GET | `/get?id=` | 无 | 企业详情 |
| POST | `/register` | 玩家 | 注册企业 |
| POST | `/dissolve` | 玩家 | 注销企业 |
| GET | `/projects` | 无 | 项目列表 |
| POST | `/project/publish` | 玩家 | 发布招标项目 |
| POST | `/bid/submit` | 玩家/企业 | 投标（支持 bidderType: PLAYER / ENTERPRISE） |
| POST | `/project/award` | 玩家 | 评标定标（最低价中标） |
| GET | `/corporate/balance` | 玩家 | 企业公户余额 |
| GET | `/procurements` | 无 | 采购列表 |
| POST | `/procurement/publish` | 玩家 | 发布采购 |
| POST | `/dividend/declare` | 玩家 | 宣布分红 |

### 税收 API

路由前缀 `/ks-Eco/api/tax`：

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/stats` | 无 | 税收统计 |
| GET | `/rates` | 无 | 税率列表（含 base + industry + brackets） |
| POST | `/rates/set` | admin | 设置税率 `{"category":"MARKET_TRADE","rate":0.03,"industry":"INDUSTRY"}` |
| GET | `/brackets?industry=` | 无 | 阶梯税率列表 |
| POST | `/bracket/upsert` | admin | 增改阶梯税率 |
| POST | `/bracket/delete` | admin | 删除阶梯税率 |
| POST | `/bracket/calc` | admin | 试算税款 |
| GET | `/records` | 无 | 纳税记录 |
| GET | `/penalties` | 无 | 罚单列表 |
| POST | `/penalty/issue` | admin | 发出税务罚单 |

### 房地产 API

路由前缀 `/ks-Eco/api/realestate`：

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/zones` | 无 | 区域列表（含容积率/已登记数） |
| POST | `/admin/realestate/zone` | admin | 创建区域 |
| POST | `/admin/realestate/zone/price` | admin | 设基准价 |
| POST | `/admin/realestate/zone/status` | admin | 设状态（FOR_SALE/SOLD） |
| POST | `/admin/realestate/zone/type` | admin | 改规划类型 |
| POST | `/admin/realestate/zone/max-plots` | admin | 调整容积率 |
| POST | `/admin/realestate/zone/delete` | admin | 删除区域 |
| GET | `/plots` | 无 | 地块列表 |
| POST | `/plot/purchase` | 在线 | 购买地块 |
| GET | `/my-plots` | 在线 | 我的地块 |
| GET | `/houses-for-sale` | 无 | 商品房在售列表（聚合挂单+房屋详情，无需 token） |
| GET | `/house/voxels?houseId=` | 无 | 3D 体素数据（上限 60000 方块） |

### 副本 API

路由前缀 `/ks-Eco/api/realestate-dungeon`，共 13 个端点：

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/templates` | 任意 token | 模板列表 |
| POST | `/templates` | admin | 创建/更新模板 |
| GET | `/instances` | 任意 token | 活跃实例列表 |
| POST | `/instances` | 在线 | 创建单人实例 |
| GET | `/instances/{id}` | 任意 token | 实例详情（含事件日志） |
| POST | `/instances/{id}/leave` | 在线 | 离开实例 |
| POST | `/revive` | 在线 | 复活 |
| GET | `/my-status` | 任意 token | 我的副本状态（hasActive 布尔字段） |
| GET | `/my-properties?instanceId=` | 任意 token | 我的副本内房产 |
| POST | `/properties` | 在线 | 购买副本内房产 |
| POST | `/properties/{id}/develop` | 在线 | 缴纳开发费 |
| GET/POST | `/config` | admin | 获取/热更新配置（13 项白名单，失败自动回滚） |
| GET | `/grids` | admin | 网格池状态 |
| POST | `/admin/instance/{id}/force-end` | admin | 强制结束副本 |

### 政治 API

路由前缀 `/ks-Eco/politic/api`，共 20+ 端点：

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/offices` | 任意 | 全部职务列表 |
| GET | `/proposals` | 任意 | 提案列表 |
| GET | `/proposal/{id}` | 任意 | 提案详情 |
| POST | `/proposal/create` | 元老/执政官 | 创建提案（表单化，覆盖 7 种 enact 类型） |
| POST | `/proposal/start-vote` | 执政官 | 启动表决 |
| POST | `/proposal/vote` | 元老 | 投票（YES/NO/ABSTAIN） |
| POST | `/proposal/tribune-review` | 保民官 | 审查（批准/否决，一票否决权） |
| POST | `/proposal/override` | 元老 | 覆议投票（需全体在册元老全票 YES） |
| GET | `/my-office` | 任意 | 我的职务 |
| GET | `/my-votes` | 任意 | 我的投票记录 |
| GET/POST | `/config` | admin | 议会配置（席位数 + legislative_mode 开关） |
| GET | `/elections/status` | 任意 | 选举状态 |
| POST | `/admin/senator/add` | admin | 任命元老 |
| POST | `/admin/senator/remove` | admin | 移除元老 |
| POST | `/admin/election/trigger` | admin | 手动触发电选 |

### 地图 API

路由前缀 `/kSHWP`：

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/` | 无 | 地图 HTML 页面 |
| GET | `/api/worlds` | 无 | 世界列表（名称 + 维度） |
| GET | `/api/players` | 无 | 在线玩家位置 |
| GET | `/api/tile?world=&x=&z=&zoom=` | 无 | 瓦片数据（base64 PNG） |
| GET | `/api/annotations?world=` | 无 | 公开备注列表 |
| GET | `/api/annotations?token=xxx&world=` | 玩家 | 额外返回 `myAnnotations` 数组 |
| POST | `/api/annotations` | 在线 | 添加备注 `{"token":"...","world":"...","x":100,"y":64,"z":200,"text":"..."}` |
| DELETE | `/api/annotations?token=xxx&id=xxx` | 在线 | 删除备注 |

### 物品继承 API

路由前缀 `/ks-Inherit`：

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/` | 任意 token | 玩家页面（自己的物品状态） |
| GET | `/admin` | admin | 管理员审阅页面 |
| GET | `/api/items` | admin | 查询物品（支持 status / playerUuid 过滤） |
| GET | `/api/my-items` | 任意 token | 当前玩家自己的物品 |
| POST | `/api/test-token` | 仅 localhost | 获取测试 admin token |
| POST | `/api/approve` | admin | 批准物品 |
| POST | `/api/reject` | admin | 拒绝物品 |
| POST | `/api/deliver` | admin | 发放物品到玩家背包 |

### Token 鉴权说明

- **获取 admin token**: `POST /api/test-token`（仅限 localhost 访问）
- **获取玩家 token**: `GET /api/login?player=<name>`
- **使用方式**: 受保护 API 需 `Authorization: Bearer <token>` 头或 `?token=xxx` 查询参数
- **有效期**: 默认 600s，支持续期（createdAt 刷新，token 字符串不变）、刷新（旧 token 失效，返回新 token）、主动移除
- **过期处理**: 超时后返回 401
- **测试示例**:
```bash
# 获取 admin token（仅 localhost）
curl -X POST http://localhost:8123/api/test-token

# 获取玩家 token
curl "http://localhost:8123/ks-Eco/api/login?player=Steve"

# 使用 token 访问受保护 API
curl "http://localhost:8123/ks-Eco/api/admin/idle-items?token=YOUR_TOKEN"

# 市场统计（无需鉴权）
curl http://localhost:8123/ks-Eco/api/market/stats

# 挂单列表
curl "http://localhost:8123/ks-Eco/api/listings?type=SELL"

# 强制限价（需 admin token + POST JSON body）
curl -X POST http://localhost:8123/ks-Eco/api/admin/force-price \
  -H "Content-Type: application/json" \
  -d '{"material":"IRON_INGOT","price":15.0}'
```

---

## 测试指南

### 测试环境概览

| 项目 | 说明 |
|------|------|
| 测试服位置 | `test_1_21/` |
| 核心 | LeavesMC 1.21.11 (`server.jar`) |
| Java | JDK 21.0.10 |
| JVM 参数 | `-Xms4G -jar server.jar nogui` |
| 游戏端口 | 58576 |
| Web 网关 | 8123（测试）/ 58578（生产） |
| 数据库 | SQLite `plugins/ks-core/data.db` |

**关键第三方依赖**: Vault（经济 API）、LuckPerms（权限管理）、ProtocolLib（协议层）、ItemsAdder（自定义物品）、FotiaEnchantment（自定义附魔）、PlaceholderAPI（变量系统）。

**测试前检查清单**:
- [ ] 测试服已启动且无报错
- [ ] 目标插件 JAR 已部署到 `test_1_21/plugins/`
- [ ] LuckPerms 已配置好测试用的权限组
- [ ] Vault 已正常加载
- [ ] 至少有一个测试玩家账号可登录

### 通用测试流程

```
修改代码 → mvn clean package → 部署 JAR → 重启/热重载 → 验证
```

```powershell
# 步骤 1: 构建单个插件
cd ks-core   # 或其他插件目录
mvn clean package

# 步骤 2: 部署到测试服
Copy-Item target\ks-core-*.jar ..\test_1_21\plugins\ -Force

# 步骤 3: 重启服务器（推荐完整重启，确保干净状态）
..\server-control.ps1 -Action Restart

# 步骤 4: 观察日志
..\server-control.ps1 -Action Console
```

> **注意**: Paper 的 `/reload` 可能导致内存泄漏和状态不一致。Extra 模块的 classloader 不会被 plugman reload 重置——涉及数据库、HTTP 服务、事件监听的插件，必须完整重启。

**日志位置**:

| 日志 | 路径 |
|------|------|
| 最新日志 | `test_1_21/logs/latest.log` |
| 压缩历史 | `test_1_21/logs/2026-XX-XX-*.log.gz` |
| 崩溃报告 | `test_1_21/crash-reports/` |

### ks-core 测试项

**启动与基本状态**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 插件加载 | 启动服务器，观察控制台 | 看到 `ks-core 已启用` 日志，显示 Web 网关地址和端口 |
| 状态命令 | 执行 `/kscore status` | 显示运行中、活跃路由数、活跃会话数、数据存储类型、已注册子插件列表 |
| 重载命令 | 执行 `/kscore reload` | 显示 `配置已重载`，Web 服务器重启 |
| 无参数命令 | 执行 `/kscore` | 显示帮助信息 (reload / status) |

**Web 网关**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 网关首页 | 浏览器访问 `http://localhost:8123/` | 显示 ks-core 网关状态页面，列出所有已注册路由 |
| CORS 预检 | `curl -X OPTIONS http://localhost:8123/` | 返回 204，包含 CORS 头 |
| 404 路由 | 访问 `http://localhost:8123/nonexistent` | 返回网关首页（无匹配路由时回退） |
| 端口绑定 | 修改 `config.yml` 端口后 `/kscore reload` | 新端口生效，旧端口释放 |

**Token 鉴权**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| Token 创建 | 通过子插件 API 获取 token | 返回 64 位十六进制 token 字符串 |
| Token 验证 | 使用有效 token 请求需鉴权的 API | 正常返回数据 |
| Token 过期 | 等待超过默认 600s 后使用 | 返回 401 错误 |
| Token 续期 | 在有效期内续期 | `createdAt` 刷新，token 字符串不变 |
| Token 刷新 | 调用 refresh API | 旧 token 失效，返回新 token |
| 无效 token | 使用随机字符串作为 token | 返回 401 |
| 空 token | 不传 token 参数 | 返回 401 |

**路由注册/取消**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 路由注册 | 子插件 `onEnable` 调用 `bridge.registerRoute()` | 日志显示 `路由已注册: /xxx → pluginId` |
| 路由取消 | 子插件 `onDisable` 调用 `bridge.unregisterRoute()` | 路由从注册表中移除 |
| 重复注册 | 同一 pluginId 注册多次 | 后注册覆盖先注册 |
| 最长前缀匹配 | 访问 `/ks-Eco/admin/page` | 匹配到 `/ks-Eco` 而非 `/` |

### ks-Eco 测试项

**启动与依赖检查**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 正常启动 | 确保 ks-core 先加载，启动服务器 | 日志显示 `ks-Eco 已启用`，Vault 状态 |
| ks-core 缺失 | 移除 ks-core JAR 后启动 | 日志显示 `ks-core 未找到！`，插件自动禁用 |
| Vault 缺失 | 移除 Vault JAR 后启动 | 日志显示 `Vault: 未找到`，市场功能仍可用（BuiltinEconomy 兜底） |

**市场系统**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| `/market` 命令 | 玩家执行 | 打开市场 GUI |
| 挂单上限 | 挂单达到默认 20 后继续挂 | 提示达到上限 |
| 挂单过期 | 等待 `listing-expire-hours` | 过期挂单自动下架 |
| 交易税 | 完成一笔交易 | 税率默认 2%，不低于 min-tax 1.0 |
| GUI 关闭 | `config.yml` 中 `gui-enabled: false` → reload | `/market` 按 Web 模式处理 |

**官方收购/出售**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 官方收购 | 玩家出售默认列表中的物品 | 按官方价格 + 波动范围收购 |
| 动态价格 | 等待刷新周期或修改 Web 设置 | 价格按双向供需压力 + 漂移值更新，范围受 `max-fluctuation` 限制 |
| 价格强制 | `/kseco force-price DIAMOND 50.0` | 价格固定为指定值 |
| 出售价上限 | 验证 `markup-factor` 和 `max-price-default` | 售价 = min(收购价 × markup, max-price) |

**玩家交易与暂存箱**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 发起交易 | 玩家A 执行 `/trade 玩家B` | 双方打开交易 GUI |
| 对方离线 | `/trade OfflinePlayer` | 提示 `玩家不在线` |
| 存入物品 | 放入暂存箱 | 物品保存（NBT 完整） |
| 容量上限 | 存入超过默认 54 格 | 提示容量已满 |
| 过期清理 | 物品超过 30 天 | 过期物品被清理 |

**Web 管理面板**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 管理页面 | 浏览器访问 `http://localhost:8123/ks-Eco/` | 显示管理面板 HTML 页面 |
| 市场统计 API | `curl http://localhost:8123/ks-Eco/api/market/stats` | 返回 JSON 统计数据 |
| 登录 API | `curl "http://localhost:8123/ks-Eco/api/login?player=TestPlayer"` | 返回 token / isAdmin / playerName |
| 玩家不存在 | 登录不存在的玩家 | 返回 404 `玩家不在线` |
| 缺少参数 | POST force-price 缺 `material` | 返回 400 |

### ksHWP 测试项

**启动与依赖**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 正常启动 | ks-core 已加载，`kshwp.enabled: true` | 日志显示 `ksHWP 已启用` |
| ks-core 缺失 | 移除 ks-core | 日志显示 `ks-core 未找到！`，插件禁用 |
| 路由未启用 | `kshwp.enabled: false` | 路由不注册 |
| Multiverse 缺失 | 无 Multiverse-Core | 不影响基本功能 |

**地图命令**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| `/map` | 玩家执行 | 发送可点击的地图链接（含 token） |
| 路由未启用时 | 执行 `/map` | 提示 `地图功能未启用` |

**地图备注**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 添加备注 | `/mapnote add 这是一个矿洞入口` | 显示备注 ID 和坐标 |
| 查看列表 | `/mapnote list` | 显示玩家的所有备注（ID、世界、坐标、文本） |
| 删除备注 | `/mapnote delete <ID>` | 显示 `备注已删除` |
| 删除他人备注 | 尝试删除不属于自己的备注 | 提示 `备注不存在或不属于你` |
| 文本过长 | 添加超过限制的文本 | 按文本长度限制处理 |

**Web 地图 API**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 地图页面 | 浏览器访问 `http://localhost:8123/kSHWP/` | 显示地图 HTML 页面 |
| 世界列表 API | `curl http://localhost:8123/kSHWP/api/worlds` | 返回所有世界信息 |
| 添加备注 API | POST annotations 含 token/坐标/文本 | 成功返回备注 ID |
| 无 token 添加 | POST annotations 不传 token | 返回 401 `需要 token` |
| 无效 token | POST annotations 传无效 token | 返回 401 `token 无效或已过期` |
| 空文本 | POST annotations 传空 text | 返回 400 `备注文本不能为空` |
| 删除备注 API | DELETE annotations?token=xxx&id=xxx | 成功返回 `已删除` |

### 银行系统测试项

**模块加载**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 模块加载 | 将 `ks-Eco-bank-*.jar` 放入 `plugins/ks-Eco/extra/`，配置启用 | 日志显示 `[银行系统] 模块已加载` → `模块已启用` |
| 模块未启用 | `enabled-modules` 列表中不包含 `ks-Eco-bank` | 模块不被加载 |
| JAR 缺失 | 删除 extra 目录中的 JAR | 加载时日志警告，不影响 ks-Eco 本身 |

**核心操作**:

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 创建银行 | 玩家满足资质后创建 | 银行创建成功，注册资本扣除 |
| 合资银行 | 多名玩家合资创建 | 按出资比例分配股份 |
| 存款/取款 | 存取款操作 | 余额正确入账/扣除 |
| 贷款申请+还款 | 申请贷款并还款 | 本金+利息正确计算 |
| 央行利率调整 | 管理员调整基准利率 | 存贷利率随之变化，不超出央行区间 |
| 流动性注入 | 央行 GRANT/LOAN 注资 | M0 供应量变化 |
| M0/M1/M2 统计 | 查询货币供应量 | 数值正确，实时更新 |

### 企业系统测试项

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 注册企业 | 玩家上缴注册资本注册 | 企业创建成功，资金扣除 |
| 合伙办企 | 多名玩家共同注册 | 按出资比例分配股权 |
| 资金不足 | 玩家资本不足时注册 | 提示资金不足 |
| 发布项目 | 官方/企业发布招标 | 项目出现在招标大厅 |
| 企业投标 | 符合资质的企业投标 | 投标记录创建 |
| 个人投标 | 个人玩家投标 | bidderType: PLAYER，无资质限制 |
| 资质校验 | 注册资本不足 75% 标的企业投标 | 被 `QualificationChecker` 拒绝 |
| 开标 | 招标方选择中标 | 中标企业/个人获得项目 |
| 预付款 | 项目启动支付预付款 | 按配置比例支付 |

### 税收系统测试项

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 交易税 | 玩家间完成一笔市场交易 | 税款按税率扣除，转入税收账户 |
| 阶梯税率 | 不同交易规模适用不同税率 | 大额交易税率更高 |
| 动态税率 | 管理员通过 Web 面板调整税率 | 新税率立即生效 |
| 税收记录 | 查看税收日志 | 每笔税收有来源、金额、时间记录 |
| 罚单系统 | 管理员对玩家发出罚单 | 罚单创建，按规则计算罚金 |

### 市场模拟自动化测试

Python 模拟测试套件，完整复刻 ks-Eco `PriceEngine.java` 和 `TaxManager` 的定价/税收逻辑。

**位置**: `test_results/market_simulation.py`

```bash
cd test_results
pip install matplotlib numpy
PYTHONIOENCODING=utf-8 python market_simulation.py
```

**测试套件（5 套，65 项）**:

| 套件 | 用例数 | 说明 |
|------|--------|------|
| 官方定价体系验证 | 34 | 价格分层合理性、波动范围、出售价上限、买卖价差、列表独立性 |
| 虚空交易市场干预 | 6 | 买入压力→涨价、卖出压力→降价、新物品注册、交易量影响 |
| 控制台价格干预 | 5 | force-price 更新、出售价约束、基准价保留、极端价格钳制 |
| 税收系统 | 11 | 基础税、最低税额、阶梯企业税（5%/8%/12%）、税收累加、多税种并行 |
| 综合场景模拟 | 9 | 200周期模拟、均值回归、市场稳定性、需求收敛 |

**生成图表**: `chart1_price_fluctuation.png` ~ `chart6_price_distribution.png`（价格波动原理、市场走势、需求-价格散点、税收分析、干预效果对比、价格分布）。

**最新结果**: v1.1.0 — **65/65 通过 (100%)**。

### 测试服权限配置参考

使用 LuckPerms 为测试玩家配置权限：

```
# 基础权限（所有测试玩家）
/lp group default permission set kseco.market true
/lp group default permission set kshwp.use true

# 管理员权限（测试管理员）
/lp group admin permission set kscore.admin true
/lp group admin permission set kseco.admin true
/lp group admin permission set kshwp.admin true
```

---

## 编译与部署

### 一键编译部署

```powershell
.\build_all.ps1           # 当前构建脚本构建 18 个模块 + 部署到测试服（不含 ks-BossCombat）
.\build_all.ps1 -Restart  # 构建 + 自动重启测试服
.\build_all.ps1 -SkipBuild  # 跳过构建，直接部署已有 JAR
```

构建顺序：以 `build_all.ps1` 当前 `$plugins` 列表为准；当前列表包含 18 个模块，`ks-BossCombat` 尚未纳入一键脚本。

**⚠️ 必须先 `mvn install` ks-core**——其他模块通过 Maven `provided` 依赖本地 `.m2` 中的 ks-core。

### 部署目录规则

| JAR 类型 | 部署位置 |
|----------|----------|
| 独立插件（13 个，含 ks-core、ks-Eco、ksHWP、ks-Compat、ks-BotGuard、ks-BossCombat、ks-Maintenance、ks-Sentinel、ks-Skill、ks-Title、ks-Inherit、KS-ItemEditor、KS-ItemSteal） | `test_1_21/plugins/` 顶层 |
| Extra 子模块（bank / enterprise / tax / RealEstate / RealEstateDungeon / politic） | `test_1_21/plugins/ks-Eco/extra/` |

### ⚠️ 关键陷阱

1. **Extra JAR 必须放 `plugins/ks-Eco/extra/`**，放 `plugins/` 顶层会被 Bukkit 当独立插件扫描并报错。
2. **必须整服 restart**，`plugman reload` 不重置 extra JAR 的 classloader。
3. **新增 extra 模块必须检查** `src/main/resources/META-INF/ks-eco-extra.properties` 是否存在并指向正确主类。
4. **每次重新构建后**确认 `plugins/ks-Eco/extra/<jar>` 文件时间戳/大小确实刷新（`build_all.ps1` 已用 `Move-Item -Force` 强制覆盖）。
5. **`build_all.ps1` 含本机 MCSM API Key**，不要部署到生产面板服。

### 部署包

`build_all.ps1` 产出 `ks-series-deploy-<时间戳>.zip`，当前包含脚本清单中的 18 个 JAR + 各插件源码默认 config + README；`ks-BossCombat` 当前未纳入该脚本，需要单独构建。排除 `build_all.ps1`（含密钥）、`data.db*` 运行时数据、测试 token、备份文件。

---

## 第三方依赖

| 插件 | 依赖类型 | 说明 |
|------|----------|------|
| ItemsAdder 4.0.16 | 软依赖 | 自定义物品/方块/资源包 |
| MythicMobs + MythicCrucible | 软依赖 | 自定义怪物/技能 |
| ModelEngine R4.0.9 | 软依赖 | 自定义模型 |
| LuckPerms | 独立 | 权限管理 |
| PlaceholderAPI | 独立 | 变量占位符 |
| VaultUnlocked | 软依赖 | 经济前置（ks-Eco BuiltinEconomy 兜底） |
| AuthMe (FORK) | 独立 | 登录认证 |
| FastAsyncWorldEdit | 软依赖 | 副本地图粘贴（缺则降级为虚空网格） |
| WorldEdit 7.3.6 | provided | FAWE 的 API 基础（编译期依赖） |
| OpenInv | 软依赖 | 离线玩家物品发放（缺则需在线领取） |
| Multiverse-Core | 软依赖 | 多世界管理 |
| ProtocolLib | 独立 | 协议层库 |
| packetevents | 独立 | 数据包事件监听 |

所有第三方集成均为软依赖，缺失时对应功能优雅降级，不会导致插件加载失败。

---

## 架构决策与设计复盘

### 核心选型

| 决策 | 选择 | 理由 |
|------|------|------|
| **微内核 vs 单体** | ks-core + 子插件 | 插件按需装卸；独立迭代不影响核心；生产服可选择性部署 |
| **Extra 模块化** | JAR 运行时加载 | 银行/企业/税收作为 ks-Eco 的扩展，不强制依赖；`ExtraModuleLoader` 实现热插拔；某个 extra 的 bug 不会拖垮整个经济系统 |
| **SQLite vs MySQL** | SQLite + WAL | 面板服零配置部署；WAL 模式支持并发读；无额外数据库进程开销 |
| **内嵌 HTTP vs 外部服务** | Java `com.sun.net.httpserver` | 零依赖；插件即服务；与服务器生命周期一致 |
| **Web SPA vs 仅指令** | Chart.js 仪表盘 + REST API | 管理员可视化操作；玩家自助查询；API 可脚本化测试 |

### 经济模型设计

**央行-商行二级银行体系**:

```
┌─────────┐  基准利率/准备金率   ┌──────────┐
│ 中央银行 │ ◄────────────────► │ 商业银行  │
│  (CB-)   │   注入流动性        │ (玩家创建)│
└────┬────┘                     └────┬─────┘
     │                               │
     │ M0: 流通货币                  │ 存款/贷款
     ▼                               ▼
┌─────────┐                    ┌──────────┐
│  玩家   │ ◄── 交易/工资 ───► │   玩家   │
│ (个人)  │                    │  (个人)  │
└────┬────┘                    └────┬─────┘
     │                              │
     │ 注册企业/投标                 │ 合资/投资
     ▼                              ▼
┌─────────┐                    ┌──────────┐
│  企业   │ ◄── 招投标 ──────► │  企业    │
│ (私营)  │                    │  (国有)  │
└─────────┘                    └──────────┘

┌──────────────────────────────────────────┐
│              税法系统 (全程覆盖)          │
│  交易税 │ 企业税(阶梯) │ 分红税 │ 罚单   │
└──────────────────────────────────────────┘

M0 = 玩家手持现金
M1 = M0 + 银行活期存款
M2 = M1 + 定期存款 ≈ M1 (当前简化)
```

| 设计 | 原理 | 模拟目标 |
|------|------|----------|
| 央行-商行二级制 | 央行控制货币总量，商行面向玩家 | 真实央行职能：利率调控、准备金率、流动性注入 |
| M0/M1/M2 分层次 | 不同流动性层次的货币统计 | 宏观经济监控，辅助央行决策 |
| 存款=负债 | 银行存款计入 M1/M2 | 体现货币乘数效应 |
| 负利率支持 | 央行可设 -100%~+100% 利率 | 模拟非常规货币政策（量化宽松） |
| 利率浮动限制 | 商行利率 = 基准利率 ± 浮动限制 | 防止恶性竞争，维护金融稳定 |

### 数据库可靠性设计

三层保障机制确保表创建不会遗漏：

1. **第一层 — Extra 模块 init()**: 各模块 `BankManager.createTables()` / `EnterpriseManager` / `TaxExtra` 各自建表。
2. **第二层 — `EcoWebHandler.ensureAllTables()`**: 首次 API 调用时创建全部 20+ 张业务表。双重检查锁定（volatile + synchronized），线程安全，确保只执行一次。
3. **第三层 — ALTER TABLE 兼容旧库**: 检测新列是否已存在 → `ALTER TABLE ADD COLUMN`（try-catch 忽略重复列）。旧数据库无缝升级，无需手动删除。

### 历次迭代关键修复

| # | 问题 | 根因 | 解决方案 |
|---|------|------|----------|
| 1 | 邀请按钮缺失 | 银行/企业列表无直接邀请入口 | 每行增加「邀请」「权限」操作按钮 |
| 2 | 银行无权限系统 | 银行只有角色系统，无细粒度权限 | 新建 `ks_bank_permissions` 表 + 6 种权限 + API + UI |
| 3 | 个人无法投标 | 投标仅支持企业 | 新增 `bidderType: PLAYER` + `bidderUuid` 字段 |
| 4 | SQLITE_ERROR on `ks_bank_money_supply` | 表创建延迟 | 三层保障机制 |
| 5 | Extra JAR 被 Bukkit 当独立插件 | 放错目录 | 严格分离 `plugins/` 顶层 vs `plugins/ks-Eco/extra/` |
| 6 | plugman reload 不重置 extra classloader | Paper 的 reload 不清理自定义 ClassLoader | 必须整服 restart |
| 7 | Gson null 序列化返回 `{}` | `new Gson()` 默认不序列化 null | 加 `hasActive` 布尔字段让前端明确区分 |
| 8 | consul+SENATOR 双重计票 | 同一 UUID 在 SENATOR 和 consul 列表中各计一票 | LinkedHashSet 按 UUID 去重 |

---

## 服务端目录结构

```
test_1_21/                         # 测试服根目录
├── server.jar                     # LeavesMC 1.21.11 核心
├── start-leaves.bat               # 启动脚本 (JDK 21 + 4G)
│
├── server.properties              # 服务器配置
├── bukkit.yml                     # Bukkit 配置
├── spigot.yml                     # Spigot 配置
├── leaves.yml                     # Leaves 专属配置
│
├── plugins/                       # 插件目录（35+）
│   ├── ks-core/                   #   KS 网关 + 数据库 (data.db ~30表)
│   ├── ks-Eco/                    #   KS 经济核心 + dungeon_schematics/
│   │   └── extra/                 #   Extra 子模块 JAR (6 个)
│   ├── KS-ItemEditor/             #   KS 物品编辑器
│   ├── KS-ItemSteal/              #   KS 物品窃取
│   ├── ks-Inherit/                #   KS 物品继承 (items.db)
│   ├── ItemsAdder/                #   自定义资源包
│   ├── MythicMobs/                #   自定义怪物配置
│   └── LuckPerms/                 #   权限配置
│
├── world/                         # 主世界
├── world_nether/                  # 地狱
├── world_the_end/                 # 末地
├── test_world/                    # 测试世界
├── ks-dungeon-world/              # 副本虚空世界（FLAT+air）
│
├── logs/                          # 运行日志
├── crash-reports/                 # 崩溃报告
└── libraries/                     # 依赖库
```

---

## 常见问题排障

| 问题 | 可能原因 | 排查方法 |
|------|----------|----------|
| Web 页面无法访问 | 端口被占用或防火墙阻止 | `netstat -ano \| findstr 8123`（测试）/ `58578`（生产） |
| Token 总是过期 | 服务器时间不同步 | 检查系统时间 |
| 路由未注册 | `config.yml` 中未启用 | 检查 `sub-plugins.xxx.enabled: true` |
| Extra 模块不加载 | JAR 未放入 `plugins/ks-Eco/extra/` 或未在 config 中启用 | 检查 extra 目录和 `ks-Eco/config.yml` 的 `enabled-modules` 列表 |
| SQLite 数据库锁定 | 并发写入冲突 | 检查是否有多个进程访问 data.db |
| Vault 对接失败 | Vault 未安装或无经济插件 | 安装 Vault + ks-Eco BuiltinEconomy 兜底 |
| 副本 start 无反应 | FAWE 未安装或 `dungeon_schematics/` 缺 `.schem` 文件 | `/dungeon` → 确认模板 schematic 字段文件名 |
| `build_all.ps1` 部署后未生效 | Extra JAR 需整服 restart（plugman reload 不重置 classloader） | 完整重启服务器 |
| `/market` GUI 商品房 Tab 无内容 | `ks_re_houses` 表缺少数据 | 先通过 `/house register` 登记房屋 |
| MCSM 10.x API 404 | API 路径变更 | 新路径是 `/api/protected_instance/*`，旧版无 `/api/` 前缀 |

---

## 设计理念

### 1. Web 优先的运营界面

传统 Minecraft 运营依赖游戏内指令和聊天栏——效率低、信息密度差。KS 系列将所有管理功能搬到 Web SPA，管理员在浏览器中操作银行、企业、税率、房地产、副本，实时 Chart.js 仪表盘可视化经济数据。

### 2. Extra 模块化 = 崩溃隔离 + 按需装配

ks-Eco 的 extra 子模块不直接暴露 HTTP 端点，全部通过 `EcoWebHandler` 反射调用 `callExtraManager()`。这意味着：
- 某个 extra 模块的 bug 不会拖垮整个经济系统（返回 null 而非抛异常）
- 服主可以按需选择装哪些子系统（不需要房地产就不放对应 JAR）
- 模块间通过共享数据库表（如 `ks_re_plots` 被房地产和副本共用）实现松耦合

### 3. 模拟真实经济治理闭环

从央行利率调控 → 商业银行存贷 → 企业公户托管 → 阶梯税率 → 元老院立法门控，构成完整的"经济政策 → 立法 → 执行 → 反馈"链条。这不是简单的"杀怪掉钱买东西"，而是一个可调控的模拟经济生态。

### 4. NBT 完整性零丢失

物品序列化统一使用 `ItemStack.serializeAsBytes()` → Base64 → SQLite BLOB（盲盒战利品、暂存箱、物品继承），确保自定义附魔、Lore、属性修饰符、CustomModelData、unbreakable 等全部 NBT 标签完整保留。

### 5. 优雅降级

所有第三方集成（Vault、FAWE、MythicMobs、OpenInv）均为软依赖。缺失时对应功能降级（如缺 FAWE 副本无地图但仍可生成虚空网格、缺 Vault 自动走 BuiltinEconomy SQL 兜底），不会导致插件加载失败。

### 6. 测试驱动迭代

65 项 Python 市场模拟自动化测试（100% 通过率）+ 16 章节 shell API 测试脚本（94% 通过率）构成回归基线。每次迭代后跑测试确认无回归，再部署生产。

---

## 维护说明

我会根据当前源码、配置和测试结果持续更新这份文档。README 主要保留项目定位、模块入口和常用操作；需要逐项核对命令、权限、部署边界或 `ks-Eco` 设计时，请直接查看 [完整技术报告](docs/KS-SERIES-REPORT.md)。

玩家使用场景请看 [玩家版指南](docs/KS-SERIES-PLAYER-README.md)。
## 许可证

本项目采用 [Mozilla Public License 2.0](LICENSE)。KS-Series 的源代码、配置和文档可以被复制、修改、编译、商用和再发布。

MPL-2.0 采用文件级 copyleft：修改了本项目中受 MPL-2.0 覆盖的文件时，发布该文件或包含该文件的版本，需要继续提供该文件的源代码并保留许可证与版权声明；与本项目组合但属于独立文件的其他作品，可以按照自己的许可证发布。具体义务以 [LICENSE](LICENSE) 中的正式条款为准。
