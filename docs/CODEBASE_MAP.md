# ks-Series Codebase Map

> [中文](CODEBASE_MAP.zh-CN.md) | English

Last verified: 2026-07-19

Current cross-session handoff: `docs/CODEX_MEMORY.md`

Current Bug Hunt stop-point handoff: `docs/BUG-HUNT-HANDOFF-2026-07-18.md`

Economy design and validation knowledge: `docs/economy-knowledge-base/README.md`

Survival-RPG product and systems knowledge: `docs/survival-rpg-knowledge-base/README.md`

This file is the first-stop index for future maintenance. Update it when an entry point,
thread boundary, database ownership rule, or major workflow changes.

## ks-Eco Core

Main lifecycle and service wiring: `ks-Eco/src/main/java/org/kseco/KsEco.java`

Folia/Paper 调度边界：`ks-Eco/src/main/java/org/kseco/scheduler/EcoScheduler.java`。默认和 `-Pfolia` 构件分别
过滤 `folia-supported: false/true`；global、async、entity 和 timer 操作只能通过该门面。Folia 无 Vault 时
`BuiltinEconomy.setupDirect` 使用共享 JDBC，未适配 Extra 与玩家全服财富榜失败关闭。

Market listing write/read and atomic quantity claim: `ListingManager.java`

Player market workflows and Vault settlement: `MarketManager.java`

Bounded async execution: `AsyncWorkPool.java`. `execute` submits pure computation to a bounded multi-worker lane;
`executeDatabase` submits SQL/audit work to a bounded single-worker lane. Both expose queue metrics and pressure/rejection
logging. Do not submit Bukkit, ItemStack, GUI, or Vault work to either lane.

Official low-price acquisition: `OfficialMarketSweepManager.java`

Official and protected price calculation: `MarketValueService.java`

Dynamic official material prices and trade history: `PriceEngine.java`

Player storage: `StorageManager.java` and `gui/StorageMenu.java`. Official acquisition storage:
`OfficialWarehouseManager.java` and administrator-only `gui/OfficialWarehouseGui.java`. The warehouse GUI loads raw
immutable pages on the database lane, decodes ItemStacks on the server thread, atomically claims rows and restores a
claim if delivery cannot complete. Its entry is `/kseco gui` slot 30.

`StorageManager` 隔离所有 `MARKET_PENDING` 来源，写入/领取/删除失败和数据库队列拒绝进入延迟重试；领取前校验 owner、删除结果与完整背包容量，并处理 leftovers。过期挂单由 `ListingManager` 原子退回卖家，查询与购买均拒绝过期记录。

Limited-sale purchase preparation, stock transaction, and settlement callbacks: `LimitedSaleManager.java`. Finite stock commits use the conditional `sold+qty<=total_stock` update; charge and refund pin the same CashBackend (`VAULT` or `BUILTIN`) for the order. Builtin balance mutation now reports persistence failure, server-thread handoff failure enters durable compensation, and database-queue rejection restores stock/player quota before the refund stage advances.
Player single/ten/limited blind-box batch preparation and pity transaction: `BlindBoxManager.java`. Its
`loadPoolsAsync` and `loadLootViewsAsync` admin/Web read paths query on the serial database lane, return raw item bytes,
then decode ItemStack/lore and complete callbacks on the server thread. `BlindBoxAdminGui` renders loading/error states
and consumes one predecoded preview per loot row instead of issuing per-slot SQL queries.

Purchase orders: `PurchaseOrderManager.loadActiveOrderSnapshots` reads raw immutable rows on the database lane and
`materializeOrders` decodes `ItemStack` on the server thread. `createAsync`, `fulfillAsync`, and `cancelAsync` snapshot
items and Vault state on the server thread, execute order/storage reservation transactions on the serial database lane,
then return to the server thread for inventory and Vault settlement. Finite fulfillment reserves quantity and writes
invisible `ks_eco_purchase_order_pending_items` rows atomically; successful settlement promotes them into buyer storage,
while failure compensation deletes them and restores quantity or refunds a concurrently cancelled slice.
`PurchaseOrderMenu` owns per-operation in-flight guards and overload feedback.

玩家市场成交由 `MarketManager`、`ListingManager`、`StorageManager` 和 `MarketPurchaseSettlementStore` 共同负责。
`BUYER_CHARGE_CLAIMED -> BUYER_CHARGED -> RESERVED -> SELLER_PAYOUT_CLAIMED -> FINALIZED` 是成功路径；
退款使用 `REFUND_READY/REFUND_CLAIMED/COMPENSATED`，外部钱包结果未知进入 `REVIEW_REQUIRED`。库存认领与
`MARKET_PENDING:<settlement>:<listing>` 隐藏暂存同事务写入，卖家入账确认后才把来源改为可见的
`MARKET_PURCHASE:<listing>`；普通暂存列表、领取、删除和过期清理均排除 `MARKET_PENDING`。
房产挂单不复用普通物品 settlement。`PropertyMarketSettlementStore` 拥有个人与企业房产的买家扣款、条件产权
转移、卖家付款、退款、启动恢复和 `review_stage`；`MarketManager` 在 `TRANSFER_CLAIMED` 恢复时读取当前
产权，已转给买家则继续付款，仍归原卖家则恢复挂单并退款，第三方产权进入人工复核。settlement 的
`active_house_id` 唯一槽与 `ListingManager` 挂牌 claim 共同阻止同一房屋并发 ACTIVE/SETTLING；终态释放槽位。
管理员确认个人卖家付款成功后，`MarketManager` 最终化并执行 `recordTax`。企业房产通过
`EnterpriseFundSettlementProvider` 和 `MANAGE_PROPERTY` 权限接线；成交前再次复核权限，企业公户、开户行
资金镜像与 journal `FINALIZED` 在同一 SQL 事务提交，失败整笔回滚，企业房款不会打入成员个人钱包。

副本付费复活由 `DungeonDeathHandler` 与 `DungeonReviveStore` 负责。数据库状态机为
`CHARGE_READY/CLAIMED -> PAID_PENDING -> RETURNED`，失败退款为
`REFUND_READY/CLAIMED -> REFUNDED/REVIEW_REQUIRED`；SQL 始终在工作线程，Vault、游戏模式、血量恢复和安全
检查点传送只在服务器线程。内存 in-flight 与数据库 CAS 共同阻止重复扣款，登录和启动恢复不会在服务器线程查库。

个人工程外部钱包结算由 `ProjectWalletSettlementStore` 和 `ProjectWalletSettlementService` 负责。无保证金与
有保证金授标分别从 `PREPAYMENT_READY` 或 `DEPOSIT_CHARGE_READY` 开始，持久化保证金托管、预付款发放、
项目/投标最终提交与确定失败补偿；启动恢复续跑 READY/HELD 状态，把中断在 Vault claim 的记录转为带
`review_stage` 的 `REVIEW_REQUIRED`。企业工程发布、公户 escrow、企业保证金和企业预付款继续使用同库事务。

管理员结算复核入口位于 `EcoWebHandler`：`GET /api/admin/settlements/review` 聚合工程、普通市场、个人房产和
银行个人贷款还款复核项，`POST /api/admin/settlements/resolve` 按 settlement 类型、动作和 `review_stage` 条件更新。普通市场
和房产的后续动作委托 `MarketManager.resolveMarketReview`/`resolvePropertyReview`；工程复核在
`EcoWebHandler.resolveProjectSettlementReview` 内处理，银行 `BANK_LOAN_REPAYMENT` 通过 `BankAccessProvider`
委托 `BankManager.resolveLoanRepaymentReview`。管理端 `app.js` 的“结算复核”页只显示当前阶段允许动作。

Database connection ownership is split deliberately. `ks-core/src/main/java/org/kscore/KsDataStore.java` owns the
HikariCP data source, JDBC configuration and SQLite/MySQL/MariaDB/PostgreSQL core-schema dialect. Existing callers keep
using `getConnection()`. `ks-Eco/src/main/java/org/kseco/database/EcoDatabase.java` owns the economy server/instance
identity, database heartbeat, operation claims and fenced leases; it does not replace a durable settlement journal.

`ks-core/src/main/java/org/kscore/KsConfig.java` 解析数据库密码来源，优先级为 YAML password、`password-env`、
`password-file`、旧 mysql.password；外部来源配置错误时失败关闭且不记录密码。相对 password-file 以插件数据目录
为根。该配置只在启动时创建连接池，`/kscore reload` 不会热替换数据源。

`ks-Eco/src/main/java/org/kseco/database/BusinessSchemaDialect.java` 是业务表的窄方言边界，当前为 market、
limited-sale、project、property、purchase-order settlement、official liquidation 和 `BuiltinEconomy` schema
提供数据库检测、PostgreSQL `DOUBLE PRECISION`/`BYTEA`、MySQL/MariaDB 元数据幂等普通/唯一索引、幂等补列和
并发初始化处理；ks-core Announcement 的自增/索引也已便携化。`PortableSqlMutation.java` 负责 Web 配置、
成员、权限、银行利率和企业公户的 update-then-insert，事务内通过 savepoint 恢复 PostgreSQL 唯一键竞争后的
事务可用性，并严格检查 affected rows。`BuiltinEconomy.java` 的余额增减同样使用原子更新，不再先查后覆盖。
这些边界仍不代表存量远程库迁移或生产锁语义全部通过；当前除 SQLite/H2 方言合同外，`ks-Eco` 测试会启动
本机原生 PostgreSQL 与 MariaDB，重复初始化核心业务、结算、跨服协调、多货币账本和需求活动表，执行余额读写、
transport 发布/轮询和 fenced lease。真实 MySQL、外部远程存量库和真实 Paper 多节点压力仍未验收。

Cross-server primitives live under `ks-Eco/src/main/java/org/kseco/crossserver/`: root event/repository contracts,
`sql/` portable statement templates, `transport/` polling and cursor contracts, `cache/` versioned invalidation, and
`lock/` retained fencing leases. SQL and transport-store calls belong on `AsyncWorkPool.executeDatabase`; immutable
results return to the server thread before Bukkit, Vault, inventory or GUI work. SQLite is node-local and must not be
advertised as shared cross-server storage.

运行时门禁由 `crossserver/CrossServerRuntimeGate.java`、`KsEco.java` 和 `config.yml` 的
`cross-server.*` 共同负责。配置默认 false；当前 mutation/cache/task 接线常量为 true，只有共享
MySQL/MariaDB/PostgreSQL、唯一 `database.server-id` 和可证明共享的余额后端才允许启用。内置经济天然使用
ks-core 共享库；外部 Vault 必须显式声明 `external-economy-shared=true`。SQLite、重复节点、失联 runtime、
发布终态失败或不健康数据库身份都会 fail closed；运行时配置只在重启时生效。

轮询 cursor 使用数据库分配的单调发布序号，并以 `serverId/consumerId` 隔离每台逻辑服；扫描会越过其他目标和
过期事件，但不会越过当前服尚未到 `available_at` 的事件。cursor CAS 冲突会重载持久位置。事件重复发布按
payload 字节内容比较；缓存失效不再用跨节点 HLC 大小丢弃唯一事件，监听失败允许同 event ID 重试；lease
释放会提升 fencing token，旧事务不能在释放后续租或执行 fenced 回调。

运行时生命周期位于 `crossserver/runtime/CrossServerRuntime.java` 与 `KsEco.startCrossServerRuntime()`：独立 daemon
scheduler 只负责触发 poll，SQL 使用 `AsyncWorkPool` database lane，事件 handler 回到 Paper 主线程。handler
只能投递异步刷新：`price`、`enterprise-level`、`balance`、`real-estate`、`politic` namespace 分别刷新价格、
企业等级、财富榜、地产保护/福利和政治状态。Extra 通过 `KsEcoExtraModule.onCrossServerInvalidation` 接收信号。
本地失效先应用，publish 保持相同 event ID 最多重试 5 次；事件有 retention 并按数据库时间定时清理。

`KsEco.refreshPricesCoordinated()` 使用 `ks-eco:price-refresh` lease 与 `executeFenced` 在同一 SQL 事务生成价格，
提交后才更新本节点并广播。`PriceEngineSchema` 的 `current_buy_price`/`market_average` 是远端重载的权威当前值；
交易写共享流水，不再对单节点缓存做不可复制的即时增量。`KsEco.runClusterExclusiveTask()` 为幂等周期维护提供
集群独占 lease，当前银行利息、逾期、央行回收和违约维护使用 `ks-eco:bank-maintenance`。市场、暂存、限售与
盲盒没有共享业务缓存，其并发边界仍是数据库 journal、唯一 claim、CAS 和条件更新。

Multi-currency primitives live under `ks-Eco/src/main/java/org/kseco/currency/`. `JdbcCurrencyLedger` owns exact
minor-unit balances, immutable ledger entries, conditional balance versions and globally idempotent operations across
SQLite/MySQL/MariaDB/PostgreSQL. `CurrencyPaymentService` supports exclusive or explicit alternative-currency prices;
`CurrencyExchangeService` supports configured one-way exchanges. All ledger calls are database-lane only. Existing
Vault/builtin `CASH` remains the sole live cash source until a journaled bridge is wired. `ListingManager` and
`LimitedSaleManager` persist a backward-compatible `currency_id`; non-CASH settlement currently fails before charging.

## ks-Eco Extra Reliability Boundaries

银行基础账户、放款和还款策略位于 `ks-Eco-bank/.../BankManager.java`；可配置额度/期限/并发边界、固定审批报价、
逾期阻断、贷款/资产原子更新和外部钱包补偿仍由它负责。`PlayerLoanCollateralStore.java` 拥有个人住房、经营和
项目贷款的实物抵押状态：住房地块/房屋 LTV 75%、经营地块/房屋 LTV 60%、个人项目合同 LTV 70%；申请预占、
放款锁定、结清释放，宽限期后违约并进入已有 escrow 拍卖链路。房产挂牌会拒绝已抵押地块或房屋。

`BankGameplayManager.java` 是产品与经营层入口，负责定期存单、贷款产品报价、还款计划、展期、银行经营指标/评级
和央行政策事件。`BankEquityManager.java` 拥有发行/授权股本、持仓与预留股份台账、一级增资、二级挂牌成交撤单、
按持股比例精确分红以及控制股东同步。`BankResolutionManager.java` 拥有月度保费、按存款人合并且每人 100,000
的保障限额、资产回收—受保补助—未保险折损瀑布，以及把账户、贷款、抵押和拍卖原子迁移到桥接银行的清算批次。
`BankSchema.java` 初始化基础产品/风险/分红/计划/展期表，各专属 manager 初始化其 journal；`BankExtra` 在集群独占
维护任务中调用产品到期、政策、风险、抵押违约和保险保费维护。Vault 调用必须在服务器线程，任何数据库事务都
不得跨越 Vault 回调；纯银行内部 JDBC 划转在单个数据库事务内完成。

银行 Web 路由集中在 `ks-Eco/.../EcoWebHandler.java`：玩家读写入口包括
`/api/bank/gameplay/dashboard`、`deposit-products`、`term/open|redeem`、`loan/products|quote|apply-quoted` 和
`loan/restructure`、`loan/collateral` 与股权 portfolio/cap-table/offerings；银行经营入口包括
`operations`、`dividends`、`dividend/declare`、股权发行/成交/撤单、
`loan/restructure/requests|decide`；央行入口包括 `/api/bank/policy-events`、
`/api/admin/bank/policy-events`、`/api/admin/bank/operating-status`、`/api/admin/bank/resolution/*` 和
`/api/admin/bank/loan-payout/review|resolve`。`BankManager.listLoanPayoutReviews`/`resolveLoanPayoutReview` 只允许按 `RECONCILE_REQUIRED` 原阶段
确认放款成功或失败，并相应恢复银行流动性、申请与抵押。玩家 UI 位于 `web/player.html` 与
`assets/player-core.js`，管理 UI 位于 `web/admin.html` 与 `assets/admin-core.js`。

个人贷款还款由 `LoanRepaymentSettlementStore` 持久化 `CHARGE_READY/CLAIMED`、`CHARGED`、退款、完成、补偿和
`REVIEW_REQUIRED`，启动时恢复确定状态并隔离未知 Vault 结果。`BankAccessProvider`/`BankAccessProviderImpl`
将复核列表和阶段条件裁决暴露给 ks-Eco 管理 API。企业违约副作用必须先认领 `DEFAULTING`，央行手动/定时还款
共用原子 claim；关闭银行仍可接收既有贷款还款。

Enterprise governance and dividend recovery live in `ks-Eco-enterprise/.../EnterpriseManager.java`. Dividend batches
use `PENDING`, `PAID`, `COMPENSATED` and `COMPENSATION_REQUIRED`; an uncertain batch blocks further distribution.
Bukkit/Vault work returns to the server thread, while SQL settlement stays on the database lane.

Tax persistence and audit live in `ks-Eco-tax/.../TaxManager.java`. It normalizes fractional and legacy percentage
rates, persists industry rates separately, refreshes shared-database snapshots, rejects non-finite bases and uses
idempotent asynchronous audit writes with refund on final persistence failure.

Real-estate player GUI reads/writes live in `PlotListMenu.java` and `PlotTrustMenu.java`; they use database DTO tasks,
stale-callback/in-flight guards and server-thread Bukkit work. `PlotProtectionListener.java` consumes immutable trust
cache snapshots instead of issuing SQL per protection event.

Dungeon terminal persistence lives in `ks-Eco-RealEstateDungeon/.../DungeonLifecycleStore.java`; terminal cleanup is transactional and retryable. `DungeonTicketSettlementStore` owns charge/admission/refund recovery, while completion rewards use per-player/per-reward-key durable states. `DungeonInstanceManager.java` releases pending and active `ks-InstanceWorld` handles on disable. Revive validation/refund and reward retries are fixed; live restart, ACTIVE-instance reconstruction and offline proof delivery remain acceptance boundaries.

## ks-BotGuard

Leaves fake-player filtering and MythicLib/MMOCore listener wrapping:
`ks-BotGuard/src/main/java/org/kseries/botguard/KsBotGuard.java`

Do not assume an event with `getPlayer()` extends `PlayerEvent`. In particular,
`BlockPlaceEvent` and `BlockBreakEvent` must be handled explicitly when identifying `ServerBot` actors.
`PlayerToggleSneakEvent` and `PlayerExpChangeEvent` are protected because the deployed MythicLib and
MMOCore versions perform strict player-data lookups for them. MMO plugin disable events must discard
their wrappers; never restore an original listener owned by a disabled plugin.

## ks-Compat KSBot

Command routing, ownership and action safety:
`ks-Compat/src/main/java/org/kseries/compat/bot/BotManagerModule.java`

Leaves reflection bridge, action counting and duplicate-type detection:
`ks-Compat/src/main/java/org/kseries/compat/bot/LeavesBotBridge.java`

Command blocks are supported. Do not rely on sender type for resource safety because
`/execute as <player>` can present a player sender. Enforce cooldown, interval, repetition,
concurrency and duplicate-type limits at the bot action boundary. When infinite actions are
disabled, also reject Leaves state actions (`sneak`/`swim`/`move`) because they ignore
`setDoNumber` and run until stopped.

## ks-Eco-politic

Senate voting early-close lives in `VoteManager.countVotes`. `SENATE_VOTING` sets `quorumMet`
only when remaining eligible ballots cannot reverse an absolute majority of seats; partial
majorities no longer auto-advance while the outcome is still reversible.

## ks-Sentinel

`flushPendingLogs` requeues the failed batch after rollback so a transient database error does
not permanently drop audit records.


## ks-BossCombat

MMOItems weapon-type damage adaptation for `Frostbound_Conductor`:
`ks-BossCombat/src/main/java/org/kseries/bosscombat/frostbound/FrostboundWeaponAdaptationListener.java`.
It is deliberately scoped to the Boss scoreboard tag `Frostbound_WeaponAdaptation`; team-mechanic
skills remove that tag temporarily to provide the counterplay window.

## ks-RPG

First-season material exchange foundation: `ks-RPG/src/main/java/org/kseries/rpg/KsRpg.java`.
`RpgCommand` exposes `/ksrpg catalog`, `/ksrpg exchange <id> [amount]`, and administrator reload;
`MaterialExchangeService` performs exact MMOItems/vanilla inventory validation, removal, and output on the
server thread. `MmoItemsBridge` uses the MMOItems runtime API by reflection, so an unavailable or incompatible
MMOItems installation disables exchange gracefully. The authoritative ratios live in `ks-RPG/config.yml` and
the player/economy contract lives in `docs/survival-rpg-knowledge-base/foundation-catalog.md`.

Progression integration surface: `api/RpgProgressionApi.java`, `ProgressionService.java`, and
`ProgressionCatalog.java`. `KsRpg` registers the API with Bukkit's `ServicesManager`; it owns configured combat
proof definitions, configured proof gates, and player PDC proof flags. All API and PDC access is server-thread-only.
`ProgressionCatalog.requiredProofs` rejects missing, non-list, empty, or non-string proof requirements so gates never
fail open. `/ksrpg reload` loads and validates a candidate `config.yml` before replacing live catalogs; a syntax or
catalog failure keeps the previous runtime. `/ksrpg proof grant|revoke` provides a console/configuration bridge for
MythicMobs and dungeon rewards; its target must be online. Adding proof/gate definitions and running `/ksrpg reload`
does not require a JAR replacement.

First-wave combat runtime: `CombatCatalog.java`, `CombatSkillListener.java`, `MmoInventoryBridge.java`, and
`ConfiguredMobDropListener.java`. `CombatCatalog` strictly scans `plugins/ks-RPG/content/` by category:
`weapons/`, `talismans/`, `rings/`, `caches/`, and `world-drops/`. It is immutable and atomically replaced
only after every file validates during `/ksrpg reload`; the previous catalog remains live on failure. No Java
source owns a concrete MMOItems ID, Mythic mob identity, or Boss tag. Weapon skills trigger from sneaking
right-click with registered MMOItems; equipped talismans trigger from sneaking hand-swap and are read from
MMOInventory custom slots through reflection. All handlers run on the server thread. Configured scoreboard-tag
mob drops are delivered to the killer's inventory, with overflow dropped at the player.

## ks-RPG-Gui

Independent RPG inventory interface: `ks-RPG-Gui/src/main/java/org/kseries/rpggui/KsRpgGui.java`.
It hard-depends on `ks-RPG` and consumes `RpgProgressionApi` plus the server-thread-only `RpgContentApi` through
Bukkit's `ServicesManager`; it owns no economy, item creation, inventory delivery, or player-progress persistence.
`RpgMenu.java` creates the main/proof/gate views and the administrator-only paged item library. The library previews
the live item created by ks-RPG and delegates one-item delivery back to `RpgContentApi`; its list refreshes from the
current ks-RPG catalog after `/ksrpg reload`. `RpgMenuListener.java` cancels every click and drag inside its holder.
Layout and Chinese copy live in the independent `plugins/ks-RPG-Gui/menu.yml`; `/rpgmenu reload` parses it
asynchronously, then atomically swaps the immutable layout on the server thread. Build ks-RPG before ks-RPG-Gui because
its compile-only API dependency resolves from the current ks-RPG target JAR.

## ks-InstanceWorld

Standalone plugin entry: `ks-InstanceWorld/src/main/java/org/kseries/instanceworld/KsInstanceWorld.java`.

Stable service surface: `api/InstanceWorldApi.java`, `InstancePreparation.java`, `InstancePrepareRequest.java`,
`PreparedInstance.java`, `InstanceSnapshot.java`, and `InstanceLifecycleEvent.java`. Prepare/release mutations start
on the server thread; their futures complete on the server thread. Cached snapshot/grid queries are immutable and
safe for Web readers.

Lifecycle orchestration: `InstanceWorldService.java`. Worker-only persistence and legacy read-only import:
`internal/InstanceStore.java`. Worker-only schematic parsing and namespace-root containment:
`internal/SchematicRepository.java`. Server-thread WorldEdit/FAWE canvas operations and tick-bounded Bukkit marker
scans: `internal/CanvasService.java` and `internal/MarkerScanner.java`.
`InstanceStore.finishRelease` frees a grid only when `occupied_by` still matches the releasing instance, so a delayed
release cannot free a grid already reused by another instance. Terminal `RELEASED`/`FAILED` paths do not free a grid
that may already belong elsewhere. `CanvasService.clearAndPaste` and `InstanceWorldService.cleanup` clear the union of
arena radius and schematic paste bounds so oversized schematics leave no permanent residue.

Persistence belongs to `plugins/ks-InstanceWorld/instance-world.db` (`iw_pools`, `iw_grids`, `iw_instances`,
`iw_meta`). The optional legacy import reads but never modifies `plugins/ks-core/data.db:ks_dungeon_grids`.

`ks-Eco-RealEstateDungeon/DungeonInstanceManager.java` is the first consumer. It stores the external handle in
`ks_dungeon_instances.instance_world_id`, retains all economy/party/revive/reward/Boss behavior, and interprets
generic marker data through `MythicSpawner`. `DungeonCommand.java` and `DungeonWebHandler.java` read grid state from
`InstanceWorldApi`; Web actions that touch Bukkit or Vault are marshalled to the server thread.

## ks-Cinematic

Standalone observer-cinematic plugin: `ks-Cinematic/src/main/java/org/kseries/cinematic/KsCinematic.java`.
`CinematicService.java` owns private per-story package loading, configured item triggers, spectator sessions,
timeline actions, PDC-backed recovery, and `ks-InstanceWorld` release. `CinematicCommand.java` manages list,
reload, admin preview and editor selection. Private content is rooted at
`plugins/ks-Cinematic/stories/<id>/story.yml`; `KsCinematic` registers that root as the `ks-cinematic`
schematic namespace. PDC trigger items are generated only through `/cinematic give <story> [player]`; external
MMOItems triggers remain configuration-only. `BLOCK` timeline actions and private commands are applied only to
the prepared instance. No schematic, MythicMobs content, model, resource-pack asset, economy rule, or combat
progression belongs to the public module.

`InstancePrepareRequest` accepts a contained relative schematic path such as `gaze/gaze.schem`, rejects absolute
and traversal paths, and `SchematicRepository` enforces the registered-root boundary again before file access.

## ks-Eco Web UI

Page structure only:

- `ks-Eco/src/main/resources/web/admin.html`
- `ks-Eco/src/main/resources/web/player.html`

Styles and behavior live under `web/assets/`. Start with `admin-core.js` or `player-core.js`,
then read only the named feature module (`smart-inputs`, `consoles`, `drill`, `shell`,
`side-sheet`, `entity-drawers`, `dungeon-drawers`, or `cards`).

Static assets are served by `EcoWebHandler.serveWebAsset`. Admin listing actions use delegated
`data-listing-action` buttons; do not put IDs or player names inside generated `onclick` strings.

Player bootstrap intersects feature gates with the enabled Extra module set. `ExtraModuleLoader`
publishes a module only after `onEnable` succeeds and removes it from Web visibility before
`onDisable`, so pages must treat missing module APIs as an offline/empty state.

Local full-page Web regression entry: `web/test.html`, `web/assets/test.js`, and `web/assets/test.css`.
It embeds the production admin/player pages and selects localhost-only normal, empty, API-error, or slow scenarios.

Real-estate district map and sales-office sandbox entry:
`player.html`, `web/assets/player-core.js` (`openBrowseDistrictEntity`, `openZoneVoxelViewer`,
`initZoneVoxelScene`, `addCitySandboxSkeleton`, `addCityBuildingModel`, `showCityBuildingCard`), and `web/assets/player.css`
(`#houseVoxelModal.zone-mode`). `/api/realestate/city/manifest` returns plots and registered buildings;
the client draws roads/plot pads/placeholders first, then loads up to three building models concurrently.
`initZoneVoxelScene` owns the orthographic isometric camera and the rotate/pan/zoom/keyboard/reset controls;
building-index actions also focus the selected building before opening its card.
With at least three registered buildings the skeleton also draws internal cross roads. `ks_re_houses`
owns display-only `showcase_price` and `showcase_marker`; marker colors are rendered both as 3D pins and
building-index accents, while the side card exposes dimensions, footprint, bounding volume and display/formal
price. A formal property-market listing always takes precedence over showcase metadata; showcase price is not
a settlement instruction.
`ksVoxelBuildMaterial` must return a single `Material` for single-material/fallback blocks; returning
`[Material]` leaves five `BoxGeometry` material groups unrendered. Only genuine per-face textures return the
six-entry material array. Full glass blocks use an opaque low-roughness display material to avoid transparent
instance sorting artifacts, while glass panes retain their thin custom geometry.
`RealEstateManager.exportHouseVoxels` owns the five-minute per-building cache: Bukkit block reads are
spread across ticks, immutable rows move to an async hidden-voxel culling pass, and polling observes
`PREPARING -> READY`. `/api/realestate/region/voxels` remains the bounded ad-hoc selection/plot preview,
not the district composition path.

The live four-building acceptance fixture is generated by
`test_1_21/world/datapacks/ks-estate-demo/data/ks/function/estate_city_build.mcfunction` at
`test_world (128,96,32)`. It is runtime test data, not a distributable production-city template.

## Compensation Flow

Persistence and atomic once-per-player claim: `CompensationManager.java`.

Player claim and same-surface admin configuration: `gui/CompensationGui.java`. Main-menu entry:
`gui/EcoGuiMainMenu.java` slot 29, gated by feature key `compensation`.

Runtime contract:

1. The server thread snapshots the held `ItemStack` into immutable bytes and primitive metadata.
2. Plan reads/writes and claim settlement run on `AsyncWorkPool` without Bukkit objects.
3. A claim transaction inserts both the unique `(plan_id, player_uuid)` row and legal-size stacks in
   `ks_eco_storage`; transaction rollback prevents duplicate or partial delivery.
4. Worker results return to the server thread before messages, sounds, item decoding, or GUI refresh.

## Official Market Flow

1. The server thread validates inventory and creates immutable item/listing snapshots.
2. A worker inserts the listing row.
3. A committed normal SELL listing immediately calls `evaluateNewListing`.
4. A worker reloads the latest ACTIVE row.
5. The server thread decodes the item and creates `PreparedItem` plus a price session.
6. A worker calculates the protected price from immutable data only.
7. A worker atomically claims the listing and writes the official warehouse row.
8. The server thread performs only Vault settlement and player notification.
9. A worker records the trade or atomically rolls back an unpaid acquisition.
10. The periodic sweep remains a randomized fallback and shares per-ID deduplication.

`MarketValueService` snapshots Bukkit recipes into an immutable recipe graph at startup and
runtime reload. Recipe valuation has cycle detection, a depth limit, and session caching.

## Thread Contract

Server thread only:

- Bukkit recipe registry, Player, Inventory, ItemStack metadata/PDC and GUI operations
- Vault balance checks, withdrawals and deposits
- Creating `MarketValueService.PreparedItem`
- Refreshing the recipe graph

Worker threads:

- SQL reads/writes that do not hold a transaction across a server-thread callback
- Pure valuation through `MarketFloorSession` and `PreparedItem`
- Pure sorting, aggregation and report construction

Use `AsyncWorkPool.executeDatabase` for SQL/audit tasks submitted through this pool and `execute` only for pure computation. Both
queues are bounded; rejection is logged and surfaced to the submitter. Interactive callers still need explicit
operation-specific rejection cleanup before queue saturation can be treated as a fully handled user-facing state.

Never deserialize or inspect Bukkit ItemStack state on a worker. Snapshot it on the server
thread first. Never hold a database transaction while waiting for the server thread.

## Limited Sale And Player Blind Box Flow

1. A server-thread GUI or a Web request starts an operation using UUID, player name, sale/pool ID, and quantity.
2. A worker loads sale/pool rows, raw item bytes, limits, stock, and pity state and performs pure RNG/validation.
3. The server thread decodes item bytes, verifies the online player, and performs Vault withdrawal.
4. Limited sales recheck price/stock/limit and atomically update stock, player count, and sale log on a worker.
5. The server thread creates shulkers or legal item stacks and applies inventory delivery; overflow is snapshotted
   into the asynchronous storage queue.
6. Blind-box pull logs and final per-rarity pity counters are written together in one worker transaction before
   the server-thread callback refreshes the GUI or completes the Web future.

Entry points: `gui/LimitedSaleGui.java` (`purchaseAsync`, `purchaseBoxAsync`), `gui/BlindBoxGui.java`
(`pullAsync`, `pullTenAsync`), and `EcoWebHandler.java` (`handleBbPull`, `handleBbPullTen`).

## Enterprise Levels And Blind Boxes

`EnterpriseLevelManager` is the sole owner of persisted enterprise levels, cached reads, configured bounds and land-perk
multipliers. `BlindBoxManager` owns `ks_bb_pools.min_enterprise_level` and validates the enterprise level before charging.
`LandPerkManager.getBlockPerkValue` applies the cached multiplier only to enterprise-owned percentage perks.

Purchased enterprise tickets are retired. Old ticket tables may still exist in deployed databases but have no active manager,
route, GUI, table-creation or reset entry. Do not reintroduce the old shared-counter model.

## Enterprise Governance And Dividends

`ks-Eco-enterprise/EnterpriseManager.java` owns join requests, approval/rejection, voluntary leave, dissolution and both
configured/custom dividend settlement. `ks_ent_join_requests` is the approval source of truth; only an approved request
creates `ks_ent_members`. Leave/removal cancels its approved row so reapplication remains possible. `EnterpriseGui.java`
owns the in-game approval list and second-click leave/dissolve confirmation.

Dividend headers live in `ks_ent_dividends`; recipient gross/tax/net details live in `ks_ent_dividend_payouts`; tax audit
lives in `ks_tax_records` under `DIVIDEND_TAX`. Rates may be stored as `0.10` or legacy `10` and are normalized before
settlement. `EcoWebHandler.handleEnterpriseAdminEdit` owns the full administrator edit transaction and mirrors corporate
balance changes into the selected corporate bank; `EnterpriseLevelManager` applies the post-transaction level/cache update.

## Threading Audit Backlog

Highest priority:

- Extend durable settlement coverage to the remaining external Vault/item crash windows. Personal projects, ordinary
  market, personal property, Dungeon tickets and several bank paths now have journals; limited-sale and other legacy
  paths still need operation-specific recovery instead of a repository-wide claim of completion.
- Remove synchronous SQL and legacy settlement from enterprise blind-box GUI paths and limited-sale detail reads;
  player batches plus admin/Web pool and loot-list reads are already separated.
- Convert bank, enterprise, bidding, invites, real-estate, tax and enterprise blind-box GUIs to
  loading views backed by async DTO queries.

Infrastructure:

- Add operation-specific rejection cleanup and user/Web errors for bounded executor saturation.
- Make EconomyResetManager run as an explicit maintenance operation outside the server thread.

All 13 current ks-Eco/ks-Eco-RealEstate async chat handlers snapshot only UUID/text/cancellation state before
returning Player, permission, message, GUI, inventory, ItemStack, Bukkit lookup, and manager work to the server thread.

## Maintenance Rule

Before changing ks-Eco, read this file, search only the named entry points, and expand scope only
when a discovered caller requires it. Use one focused audit agent at most unless broad parallel
work is explicitly requested.

## Plugin Backup Contract

All future ks-Series plugin deployments use `scripts/deploy-plugin.ps1`. It builds the module, copies the replaced
JAR to `backup/<plugin-id>/<unique-backup-id>.jar`, appends a JSON record to that plugin's `index.jsonl`, and checks
the source/deployed SHA-256 match. Stable plugin IDs and the index format are defined by `backup/README.md`.
Do not create future adjacent `*.jar.bak.*` files or move/delete historical adjacent backups without user approval.

## 2026-07-22 验证快照

Java 21 下按依赖顺序执行全部 Maven 模块的 `test/package`，共 347 项测试，0 failure、0 error、0 skipped；
其中 `ks-Eco-bank` 为 51 项。银行新增覆盖实物抵押、放款复核、股权交易/分红、保险瀑布和桥接清算。

外部 Web JavaScript 22/22、HTML 内联脚本 6/6、Java 运行时生成脚本 12/12、严格源 YAML 319/319、
插件入口 17/17、85 个源资源与 25 个 HTML 本地引用全部通过。玩家与管理员银行 Web 已在内置浏览器实际点击，
抵押选择、股权区、风险与保险、桥接清算和放款复核入口均能加载，两个页面控制台 0 error。
本机原生 PostgreSQL/MariaDB 的 transport/lease 集成与共享 SQLite 双节点运行时通过；真实 MySQL、外部远程
存量迁移、真实 Paper/Vault 双节点、不可逆清算、网络故障和崩溃注入仍未验收。

经济数据库边界新增 `CoreBusinessSchema`、`EcoWebBusinessSchema`、`PriceEngineSchema`、
`EconomicFeatureSchema`、`CompensationSchema` 及各 Extra 的银行/企业/政治/地产/副本 schema initializer。
它们统一通过 `BusinessSchemaDialect`/`PortableSqlMutation` 处理自增键、二进制/浮点类型、幂等补列与索引、
配置保留字迁移和并发 upsert。副本门票与生命周期表也已纳入同一边界。本机原生 PostgreSQL/MariaDB 结果
仍不能替代真实 MySQL、外部远程存量迁移和生产多节点锁语义验收。跨服 transport/cache/lease 已完成运行时
接线但配置默认关闭；满足共享数据库、唯一节点身份和共享余额前提后可启用。

## ksHWP 地图渲染线程边界

地图瓦片入口位于 `ksHWP/src/main/java/org/kshwp/HwpWebHandler.java` 的 `/api/tile`，渲染与缓存所有权位于
`MapRenderer.java`。同一 `world/zoom/x/z` 的并发请求由 `renderInFlight` 合并。主线程只允许分批读取已经加载
区块的 `ChunkSnapshot`，每 tick 最多 8 个基础区块；方块颜色计算、zoom=2/4/8 直接合成、PNG 编解码、
内存缓存和 `TileStore` 磁盘 I/O 都属于 `ksHWP-Renderer` 工作线程。禁止把 `renderTile`、`ImageIO` 或父图块
递归合成重新放进 Paper 主线程。

地产玩家地图的客户端限流位于
`ks-Eco/src/main/resources/web/assets/player-core.js` 的第二个 `KsMapEngine`：最多同时请求 2 张 ksHWP
瓦片，视口中心优先，世界切换用 epoch 隔离旧响应，离开视口的未启动请求会被裁剪。单瓦片错误不能关闭整个
地形层；API 响应必须先按文本读取并显式验证 JSON，避免服务重启页或代理 HTML 变成
`Unexpected token '<'`。地产 3D 数据本身仍由
`/ks-Eco/api/realestate/region/voxels` 提供，地图瓦片和体素预览是两条独立数据链路。

## 2026-07-22 银行源码与运行时边界

银行源码已加入 `ks_bank_player_collateral`、股权 state/ledger/offering/transaction 以及保险基金、成员、处置批次和
逐存款人赔付表。住房/经营/项目产品使用真实房产或项目合同引用；股份可一级增资、二级交易并按持仓决定分红与
控制权；`RESOLUTION` 可预览损失瀑布并由正常桥接行原子承接。`RECONCILE_REQUIRED` 放款有管理端人工裁决入口。

这些能力已通过 `ks-Eco-bank` 51 项测试及全仓 347 项测试，但真实 MySQL、外部远程存量库、真实 Paper/Vault
双节点和外部 Vault 崩溃窗口尚未完整实测；桥接清算会不可逆改变存量账户与资产，本轮没有在真实业务数据上执行
不可逆清算验收。不得把兼容方言测试或处置预览等同于上述生产验收。

最终测试服部署：`ks-Eco-bank` SHA-256
`5025BF69C9B25CE8EE7CCD6F838CC9CADECFEABAACAA6AEC6FF38B1B5C3EAF9F`，备份 ID
`ks-Eco-bank-20260722T085105042Z-223603d2022a`；`ks-Eco` SHA-256
`4973DCCF548F7E9F5A4CD9CFC75D8D5B8BDA9D5D4C55988D32A7E4A0651DD195`，备份 ID
`ks-Eco-20260722T090153739Z-57e58c5ab8b3`。Paper 于 `17:08:53` 完成启动，银行与其余五个 Extra 正常启用。

## 2026-07-22 三端运行边界

MCSM 权威拓扑记录在 `network_1_21/mcsm-instances.json`，角色与插件白名单记录在
`network_1_21/roles.yml`。Leaves `test_1_21` 是生存主端并拥有唯一 Web 端口 8123；Paper RPG 和 Folia 都禁用
ks-core Web。三个 ks-Eco 节点 ID 分别为 `survival`、`rpg`、`folia`，共同使用工作区外 MariaDB 与 JDBC 内置
经济。transport 烟测的数据库发布序号和三个 cursor 已共同推进到 3。不得在 Folia 安装 Extra 或以默认 JAR
替代 `-folia` JAR；不得把工作区外凭据、MariaDB data、MCSM API key 或运行时配置同步到公开仓库。
