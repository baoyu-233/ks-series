# ks-Eco v1.1.0

> [English](README.en.md) | 中文

**ks-Series 经济核心插件** — 市场系统、官方收购/出售、玩家交易、潜影盒深度解析、动态定价、虚空交易干预。

## 架构

```
ks-Eco (核心)
 ├─ [extra] ks-Eco-bank      — 银行系统
 ├─ [extra] ks-Eco-enterprise — 企业招投标
 ├─ [extra] ks-Eco-tax       — 税收系统
 ├─ [extra] ks-Eco-RealEstate — 地产系统
 ├─ [extra] ks-Eco-RealEstateDungeon — 副本经济
 └─ [extra] ks-Eco-politic   — 政治系统
```

## 功能

- **玩家市场** (`/market`): 54 格 GUI，挂售/购买
- **官方收购**: 收购配置中的可量产物品，价格由当前动态定价引擎计算
- **随机供给**: 原官方直售已由盲盒系统替代，玩家通过卡池、权重和保底机制获取稀有物品
- **玩家交易** (`/trade`): 1v1 GUI，物品+货币同时交换，双方确认
- **储物箱** (`/storage`): 已购/退回物品暂存，潜影盒递归解析
- **官方仓库** (`/kseco gui`): 管理员可分页查看官方收购暂存物品；只有可用库存可直接领取，待付款确认或已进入清算的仓库行会保持锁定
- **动态定价引擎**: 真实官方 SELL 流水产生双向供需压力，叠加均值回归随机漂移（默认 ±30%）
- **Web 玩家与管理面板**: `/ks-Eco` 路由；银行产品/经营、企业、税收、定价和地产界面共用认证网关，个人财富榜排除中央银行和系统账户
- **扩展模块系统**: 从 `plugins/ks-Eco/extra/` 动态加载 JAR
- **跨服运行时**: JDBC 事件日志、按数据库发布序号推进且按服务器/消费者隔离的轮询游标、失败重试、余额/价格/企业/地产/政治缓存失效、节点心跳、幂等认领和 fencing lease
- **跨服只读投影**: 本地模块先把地图、房产或资产状态固化为不可变 JSON/二进制快照，再由数据库队列压缩、分片并写入共享库；玩家端可按 server/world/dimension 发现和读取远端快照，读取过程不会触碰远端 Bukkit `World`
- **地产售楼沙盘**: 区域按登记楼栋组合，先显示道路/地块/占位，再异步并发加载单栋预渲染缓存；方块快照按 tick 限额读取，后台裁剪隐藏体素，支持楼栋房源卡
- **多货币基础**: 精确最小单位账本、幂等流水、单向兑换规则，以及市场/限时销售的 `currency_id` 兼容
- **区域需求活动基础**: 有限数量、有限预算、个人提交上限、时间窗、标准物品签名和幂等操作预留；当前仅支持 `CASH`，并默认关闭
- **官方仓库清算基础**: 将已完成官方付款确认的库存唯一转换为有限清算批次，记录预留、付款、交付、释放和可恢复未完成操作
- **重大订单接点**: 支持只读 `RPG_PROJECT` 进度来源以及行业/用途相关银行政策字段；未接进度源时明确标记不可用

## 本轮玩法与修复

- `JdbcCurrencyLedger` 对 MySQL/MariaDB 操作 ID 使用大小写敏感排序规则，避免 `BUY:A` 与 `buy:a` 被当作同一幂等键。
- 兑换手续费按目标货币最小单位向上取整，并在执行前验证 `手续费 + 到账额 = 毛额`；小额拆单不能再把非零手续费舍入为零。
- 区域需求活动用有限目标、预算和个人上限控制官方收购类事件；并发提交通过版本条件、幂等操作记录和新鲜快照重试避免超收。它目前是数据库层基础，不会自行读取玩家背包或发放 Vault 余额。
- 官方仓库清算批次以仓库行作为唯一库存来源，创建操作、仓库来源和购买操作均受唯一键保护，库存预留使用版本条件更新；未付款可释放，已付款只能在成功退款后释放，已交付重放不会重复消耗库存。
- 官方市场扫货先把库存标记为 `PENDING_SETTLEMENT`，确认外部 `CASH` 付款成功后才开放给领取或清算；清算不会复用限时销售的物品模板生成无限库存。
- 重大订单的 RPG 项目指标只接受外部绝对进度；来源缺失或异常时进度不可用，不会回退到管理员手填数值。
- 限时销售在扣款后的数据库队列拒绝时固定使用原支付后端退款、清理 `activePurchases` 并完成回调；房产市场使用 `ks_eco_property_settlements` 记录买家扣款、产权转移、卖家入账和退款，启动恢复会重查当前产权。个人卖款走 Vault journal；企业卖款通过企业资金 provider，与企业公户、开户行镜像和 journal `FINALIZED` 在同一 SQL 事务提交。
- 普通玩家市场使用 `ks_eco_market_settlements` 持久记录买家扣款、库存/隐藏暂存认领、卖家入账、公开交付和退款；启动恢复会续跑确定状态，外部钱包调用中断进入 `REVIEW_REQUIRED`，`MARKET_PENDING` 物品在最终结算前不可见、不可领取或过期清理。
- 企业 Web 项目发布、公户 escrow、企业投标保证金和企业预付款在同一数据库事务内提交；Web 个人投标保证金与预付款使用 `ks_ent_project_wallet_settlements` 记录外部钱包认领、托管、发放、补偿和人工复核，启动时续跑可确定状态，并把中断中的钱包调用转为 `REVIEW_REQUIRED`。
- 管理员结算复核 API 统一列出项目钱包、个人房产和普通市场的 `REVIEW_REQUIRED` 记录；处理动作必须与持久化的 `review_stage` 匹配，不能跳过中断阶段直接改终态。
- 银行 Web 已接通个人金融总览、7/30/90/180 日存款产品、四类贷款与三种还款方式、展期、经营评级、股权市场、政策周期和央行处置。住房/经营贷绑定本人地块或房屋，项目贷绑定本人中标合同；抵押物会预占、锁定、释放或在违约后拍卖。存款保险按每名存款人封顶，管理端可查看基金、测算资产回收/保险补助/未保险折损并由桥接银行承接；外部钱包放款结果未知时只能从专用复核入口人工裁决。

## 线程与结算边界

- SQL 与纯数据处理进入有界工作队列；Bukkit、GUI、权限、物品和 Vault 操作留在服务器线程。
- 采购单创建、履行和取消使用异步事务，并通过私有待结算表阻止买家在卖家结算前领取物品。
- 盲盒管理员列表在工作线程读取原始数据，回到服务器线程后才解析 `ItemStack`。
- 玩家市场、采购单、限时销售、个人工程、个人/企业房产、银行拍卖和副本关键支付已各自具备持久 journal 或同库事务结算；旧游戏内工程评标及其他旧 Vault 路径仍需继续补齐。
- 需求活动、官方清算和 RPG 项目指标的 JDBC 服务已初始化，但玩家 GUI/Web、自动轮换、库存交付、Vault 结算和跨插件项目进度源尚未完成接线。

## 当前接线限制

- SQLite 是本地单节点默认；跨服模式只接受共享 MySQL、MariaDB 或 PostgreSQL。每个节点必须使用唯一稳定的 `database.server-id`；外部 Vault 经济只有在自身也使用共享权威余额库时，才可显式设置 `cross-server.external-economy-shared: true`。跨服选项均为重启生效，运行时 reload 不会替换节点身份或 poller。
- `federated-assets` 默认关闭、默认拒绝。每项能力的 server/world/dimension 三个 allow 轴都必须命中，任一 deny 命中立即拒绝；因此可单独屏蔽某个服务器、世界或维度。策略、TTL、离线/过期窗口、分片和解压上限可热加载；`database.server-id`、共享连接池及 `cross-server.enabled` 仍需重启。启用投影前必须先让共享数据库 transport 正常启动。
- Web 查询合同为 `GET /api/federated/snapshot-sources`、`GET /api/federated/snapshot`、`GET /api/federated/assets` 和 `GET /api/federated/assets/aggregate`；管理员通过 `GET|POST /api/admin/federated-assets/settings` 查看或原子替换完整策略。所有接口仍受 ks-core session 鉴权。
- 当前真实生产者是 ksHWP 的有界多图块 MAP bundle 与房地产 Extra 的 READY 房屋聚合。2026-07-23 实机读取到 3 个 MAP 图块和 4 套 PROPERTY；通用 ASSET 业务生产者与跨服 transfer 尚未完成。
- transport/cursor/cache/lease 已覆盖迟到事件、同名 consumer 隔离、CAS 冲突重载、重复发布数组比较、慢时钟失效、发布重试和释放后旧 token 失效。价格、企业等级、财富榜、房地产保护和政治缓存接入失效总线；市场、暂存、限售、盲盒以共享 DB/journal/唯一认领为权威。价格刷新使用事务内 fencing，银行维护使用集群独占 lease，过期事件定时清理；运行时或数据库身份不健康时插件 fail closed。
- 测试会实际启动本机原生 PostgreSQL 与 MariaDB，重复初始化核心业务与跨服 schema，并完成 transport 发布/轮询和 lease fenced 执行；另有共享 SQLite 双节点测试验证广播、去重、停止和接管。2026-07-22 又在真实 Leaves、Paper、Folia 三端共用 MariaDB 的环境完成事件发布和三游标推进。真实 MySQL、外部远程存量库、外部 Vault 多节点、断网和生产负载仍未验证。
- `CASH` 仍由 Vault/内置经济持有。旧数据默认 `currency_id=CASH`；非 `CASH` 市场或限时销售会在扣款前拒绝。
- 区域需求活动和官方仓库清算当前也固定为 `CASH`；这能防止误走未完成的多货币桥，但不代表外部 Vault 结算已有崩溃恢复。

## 依赖

- **ks-core**: Web 网关 + 数据存储
- **Vault** (softdepend): 经济 API（反射调用，无 Vault 时使用内置经济）

## 命令

| 命令 | 功能 | 权限 |
|------|------|------|
| `/market` | 打开市场 GUI | kseco.market |
| `/trade <玩家>` | 发起交易 | kseco.trade |
| `/storage` | 储物箱 | kseco.storage |
| `/kseco gui` | 打开经济主菜单；管理员可进入官方仓库 | kseco.use / kseco.admin |
| `/kseco web` | Web 管理面板链接 | kseco.admin |
| `/kseco status` | 经济状态 | kseco.admin |
| `/kseco reload` | 重载配置 | kseco.admin |
| `/kseco force-price <物品> <价格>` | 强制限价 | kseco.admin |
| `/kseco void-trade <物品> <数量> <价格> <BUY\|SELL>` | 虚空交易 | kseco.admin |

别名: `/kse`, `/mkt`, `/ah`, `/deal`, `/stash`, `/chest`

## 子模块

### [ks-Eco-bank](../ks-Eco-bank/) — 银行系统
中央银行（基准利率、准备金、政策周期与风险处置）+ 玩家商业银行（活期/定期、产品贷款、展期、经营评级与分红）。M0/M1/M2 货币供应追踪。

### [ks-Eco-enterprise](../ks-Eco-enterprise/) — 企业招投标
企业注册（民营/国企）、项目托管、投标保证金与 Web 评标；官方/国企使用综合评分，旧无托管评标入口失败关闭。

### [ks-Eco-tax](../ks-Eco-tax/) — 税收系统
阶梯税率（5%-20%）、动态税率调整、罚金机制、逃税检测。

## 官方价格体系

### 可量产物品（官方收购）— 91 种，10 个层级

| Tier | 类别 | 代表物品 | 基准价范围 | 物品数 |
|------|------|----------|-----------|--------|
| 1 | 贵金属与宝石 | DIAMOND, EMERALD, NETHERITE_SCRAP | 80 ~ 200 | 4 |
| 2 | 冶炼金属 | GOLD_INGOT, IRON_INGOT, COPPER_INGOT | 4 ~ 25 | 6 |
| 3 | 矿物原矿 | RAW_GOLD, COAL, REDSTONE | 1 ~ 8 | 6 |
| 4 | 粮食作物 | WHEAT, CARROT, POTATO, BREAD | 1 ~ 5 | 10 |
| 5 | 水果与特产 | APPLE, CHORUS_FRUIT, GOLDEN_APPLE | 1 ~ 30 | 10 |
| 6 | 熟食肉类 | COOKED_BEEF, COOKED_PORKCHOP | 7 ~ 12 | 7 |
| 7 | 怪物掉落 | GUNPOWDER, ENDER_PEARL, BLAZE_ROD, SHULKER_SHELL | 1 ~ 100 | 18 |
| 8 | 原木与木材 | OAK_LOG, SPRUCE_LOG, CRIMSON_STEM | 2 ~ 3 | 10 |
| 9 | 基础建材 | STONE, DIRT, OBSIDIAN, BLUE_ICE | 1 ~ 15 | 15 |
| 10 | 染料与装饰 | WHITE_WOOL, INK_SAC, HONEYCOMB | 3 ~ 8 | 5 |

### 不可量产物品历史分级参考

以下价格表保留作旧版经济平衡和盲盒卡池设计参考；现行系统不再提供这张表对应的官方直售，实际掉落以盲盒卡池配置为准。

| Tier | 类别 | 代表物品 | 价格范围 | max-price 范围 |
|------|------|----------|----------|---------------|
| S | 超级稀有 | DRAGON_EGG, NETHER_STAR, ELYTRA | 800 ~ 5000 | 1200 ~ 8000 |
| A | 高级装备 | NETHERITE_INGOT, SMITHING_TEMPLATE | 400 ~ 500 | 600 ~ 800 |
| B | 附魔材料 | EXPERIENCE_BOTTLE, ENCHANTED_GOLDEN_APPLE | 50 ~ 300 | 80 ~ 500 |
| C | 特殊建材 | LODESTONE, SPONGE, SCULK_*, PRISMARINE_* | 8 ~ 400 | 15 ~ 600 |
| D | 音乐唱片 | MUSIC_DISC_13 ~ MUSIC_DISC_RELIC | 100 ~ 150 | 200 ~ 300 |
| E | 稀有装饰 | PIGLIN_BANNER_PATTERN 等 | 50 ~ 80 | 100 ~ 150 |
| F | 功能性物品 | BEE_SPAWN_EGG, SNIFFER_EGG, GOAT_HORN | 150 ~ 400 | 250 ~ 600 |

## 定价机制

```
供需压力 = clamp((近期真实卖量 - 历史基线) / 历史基线, -1, 1)
总偏移 = clamp(driftValue × maxFluctuation - supplyPressure × maxFluctuation, ±maxFluctuation)
官方收购价 = round(basePrice × (1 + totalOffset), 2)
```

- **maxFluctuation**: 0.3（±30%）
- **supplyPressure**: ∈ [-1, 1]；供过于求时下压，供不应求时上拉
- **driftValue**: ∈ [-1, 1]，带随机扰动和自然回归；管理员 `trendBias` 只能逐步牵引
- **测试模式**: `void-trade`/Web `simulate-trade` 写入 `is_test`，只预览且不污染真实定价

## 构建

```bash
cd ks-Eco && mvn clean package

# 独立 Folia 制品（不能把默认 Paper/Leaves JAR 改名冒充）
mvn -Pfolia clean package
```

Folia 制品把 Bukkit global/async/entity/region 调度统一收口到 `EcoScheduler`，玩家 GUI、消息、Inventory 和在线钱包操作回交实体调度器；无 Vault 时直接使用共享 JDBC 内置经济。本轮银行、企业、政治、税务、地产和副本 6 个 Extra 已在 Folia 实机加载；缺少 FAWE/WorldEdit、MythicMobs 时对应 schematic/Boss 功能仍失败关闭。

## 测试

本轮验证覆盖货币账本、兑换手续费与守恒、支付要求、跨服发布序号/cursor/cache/lease、只读资产投影、双节点广播、原生数据库 transport/fencing、玩家市场 settlement、个人工程与个人/企业房产结算、人工复核阶段约束、有限需求活动、官方仓库清算、重大订单、企业项目结算边界、限时销售补偿和 Folia JDBC 钱包/调度边界。`ks-Eco` 执行 196 项测试；全仓 23 个 Maven 模块共 391 项测试全部通过。外部 Web JavaScript 22/22、严格源 YAML 341/341、插件入口 17/17 和本地资源引用 25/25 通过。

2026-07-23 已通过根备份脚本部署普通版到 Leaves/Paper、Folia profile 到 Folia，并由 MCSM 启动三端。Leaves/Folia 各 29 项 Web 合同返回预期状态，日志确认稳定节点 ID、MariaDB、跨服运行时、Extra 6/4/6 与 `Done`。完整矩阵见 `../docs/KS-ECO-FULL-FUNCTION-TEST-2026-07-23.md`。该验收不代表真实 MySQL、外部远程存量迁移、外部 Vault、多机网络故障、生产压力或真人资金/GUI/崩溃窗口已经通过。
