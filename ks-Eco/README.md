# ks-Eco v1.1.0

> [English](README.en.md) | 中文

**ks-Series 经济核心插件** — 市场系统、官方收购/出售、玩家交易、潜影盒深度解析、动态定价、虚空交易干预。

## 架构

```
ks-Eco (核心)
 ├─ [extra] ks-Eco-bank      — 银行系统
 ├─ [extra] ks-Eco-enterprise — 企业招投标
 └─ [extra] ks-Eco-tax       — 税收系统
```

## 功能

- **玩家市场** (`/market`): 54 格 GUI，挂售/购买
- **官方收购**: 收购配置中的可量产物品，价格由当前动态定价引擎计算
- **随机供给**: 原官方直售已由盲盒系统替代，玩家通过卡池、权重和保底机制获取稀有物品
- **玩家交易** (`/trade`): 1v1 GUI，物品+货币同时交换，双方确认
- **储物箱** (`/storage`): 已购/退回物品暂存，潜影盒递归解析
- **动态定价引擎**: 真实官方 SELL 流水产生双向供需压力，叠加均值回归随机漂移（默认 ±30%）
- **Web 管理面板**: `/ks-Eco` 路由，银行/企业/税收/定价 CRUD
- **扩展模块系统**: 从 `plugins/ks-Eco/extra/` 动态加载 JAR

## 依赖

- **ks-core**: Web 网关 + 数据存储
- **Vault** (softdepend): 经济 API（反射调用，无 Vault 时使用内置经济）

## 命令

| 命令 | 功能 | 权限 |
|------|------|------|
| `/market` | 打开市场 GUI | kseco.market |
| `/trade <玩家>` | 发起交易 | kseco.trade |
| `/storage` | 储物箱 | kseco.storage |
| `/kseco web` | Web 管理面板链接 | kseco.admin |
| `/kseco status` | 经济状态 | kseco.admin |
| `/kseco reload` | 重载配置 | kseco.admin |
| `/kseco force-price <物品> <价格>` | 强制限价 | kseco.admin |
| `/kseco void-trade <物品> <数量> <价格> <BUY\|SELL>` | 虚空交易 | kseco.admin |

别名: `/kse`, `/mkt`, `/ah`, `/deal`, `/stash`, `/chest`

## 子模块

### [ks-Eco-bank](../ks-Eco-bank/) — 银行系统
中央银行（基准利率 3.5%，准备金率 10%）+ 商业银行（存款/贷款）。M0/M1/M2 货币供应追踪。

### [ks-Eco-enterprise](../ks-Eco-enterprise/) — 企业招投标
企业注册（民营/国企）、项目招标/投标/中标（最低价自动中标）、分包/联合体。

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
```

## 测试

自动化测试位于 `test_results/market_simulation.py`：**65/65 通过 (100%)**
