# KS-Series 服务端性能分析报告

> [English](PERF_REPORT.en.md) | 中文

> **分析日期**: 2026-07-03  
> **Profile 文件**: `PgBQzdsnsB.sparkprofile`（82 MB，长时间录制）  
> **服务端版本**: Leaves 1.21.11-154  
> **JVM**: Azul Zulu 25.34 (OpenJDK 64-bit Server VM)  
> **CPU**: Intel Xeon E5-2680 v4 @ 2.40 GHz

---

## 一、总体帧占比（主线程）

| 来源 | 采样帧数 | 占比 | 备注 |
|------|----------|------|------|
| MC Vanilla 游戏逻辑 | 45,372 | 34% | 正常 |
| JVM / Java stdlib | 27,361 | 20% | 正常 |
| 区块加载 / 光照 | 8,395 | 6% | 偏高，见§3 |
| **实体寻路 (Pathfinding)** | 7,061 | **5%** | **可优化** |
| CraftItemStack NBT 操作 | 1,109 | 0.8% | 见§4.2 |
| ItemsAdder | 1,064 | 0.8% | 外部插件 |
| MythicMobs / MythicCrucible | 909 | 0.7% | 外部插件 |
| **SQLite 主线程调用** | 530 | **0.4%** | **可优化** |
| ks-* 插件代码 | 199 | 0.15% | 见§4 |
| Craftorithm | 65 | 0.05% | 外部插件 |
| **合计** | **130,949** | 100% | |

---

## 二、硬件瓶颈（不可软件绕过）

**CPU: E5-2680 v4 @ 2.40 GHz**（2016 年服务器 U）

Minecraft 主循环是严格单线程的，单核性能是硬上限。E5-2680 v4 的单核 Cinebench 分数约为现代 Ryzen 7000 系列的 1/3。软件层面的优化只能减少工作量，无法改变单核频率上限。

若服务器将来扩容，**换高单核频率 VPS**（Ryzen 5800X/7700X 或 Intel 12/13 代 Core i 系列）比增加 RAM/核心数更有效。

---

## 三、原版逻辑优化

### 3.1 配置错误：simulation-distance > view-distance（高优先级）

Profile 中读到的当前配置：

```
view-distance:        6
simulation-distance:  8
```

**simulation 比 view 大是错误配置**。服务器在 tick 玩家完全看不到的区块里的实体 AI、方块 tick、生怪——纯粹浪费。

受影响范围对比：

| 配置 | 被 tick 的区块数（单玩家） |
|------|--------------------------|
| simulation=8（当前） | 17×17 = **289 块** |
| simulation=4（建议） | 9×9 = **81 块** |
| simulation=6 | 13×13 = 169 块 |

**建议**：将 `simulation-distance` 改为 4，区块 tick 量减少 72%，寻路、PalettedContainer 读块、实体激活等全部跟着下降。

修改位置：`server.properties`

```properties
simulation-distance=4
```

---

### 3.2 实体 AI：Pathfinding 占 5%

Profile 热点方法：

| 方法 | 采样次数 |
|------|----------|
| `PathNavigation.createPath` | 529 |
| `WalkNodeEvaluator.getPathTypeStatic` | 306 |
| `PathFinder.findPath` | 258 |
| `PalettedContainer.get`（寻路读块） | 844 |
| `LevelChunk.getBlockState`（寻路读块） | 1,322 |

寻路本身读取大量方块状态，`PalettedContainer.get` 以 844 次排第一、`getBlockState` 合计 1,322 次，均由怪物寻路驱动。

**建议**：

1. **降低 simulation-distance**（见§3.1，最直接）
2. 降低怪物上限（`bukkit.yml`）：

```yaml
spawn-limits:
  monsters: 50   # 从默认 70 降到 50
```

3. 收紧实体激活范围（`paper-world-defaults.yml`），目前全部是 32 格默认值，与 view-distance 6 不匹配：

```yaml
entity-activation-range:
  monsters: 20     # 从 32 降
  animals:  20     # 从 32 降
  villagers: 24    # 从 32 降
  water: 10        # 从 16 降
  misc:  10        # 从 16 降
```

4. 打开 Leaves 已提供但当前关闭的优化（`leaves.yml`）：

```yaml
performance:
  inactive-goal-selector-disable: true   # 当前 false，静止实体跳过目标选择器
```

---

### 3.3 区块加载 / 光照（6%）

采样到 `StarLightInterface`、`ServerChunkCache.getChunk`、`postLoadProtoChunk` 等。这是玩家移动时触发的正常区块加载流程，在 simulation-distance 降低后会自然减少。暂无其他针对性配置。

---

### 3.4 外部插件（ItemsAdder / MythicMobs）

这两个插件合计约 2,000 帧（1.5%）：

- **ItemsAdder**：大量 `itemsadder.m.lj.*` 方法（自定义方块判断、数据包拦截），在实体追踪事件（`onTrackingStart/End`）时触发较多
- **MythicMobs / MythicCrucible**：事件监听、技能触发、物品更新（`ItemUpdateManager.updateAllItems`）

这两个是第三方插件，无法修改源码。可以的话：
- 减少 MythicMobs 怪物种类或数量
- 关闭 ItemsAdder 中不使用的功能模块

---

## 四、ks-* 插件优化

### 4.1 SQLite 主线程调用（最高优先级）

**530 个采样帧在 SQLite 操作上**，均发生在主线程。按触发来源归类：

| 触发位置 | 采样次数 | 类型 |
|----------|----------|------|
| `KsDataStore.getConnection` | **88** | 反复建连接 |
| `BlindBoxManager.getLootItemStack` | 31 | 同步 SQL |
| `BlindBoxAdminGui.buildAddLoot` | 24 | 同步 SQL |
| `BuiltinEconomy.setBalance` | 20 | 同步写余额 |
| `BlindBoxManager.getPityCount` | 19 | 同步 SQL |
| `BlindBoxManager.getPool` | 14 | 同步 SQL |
| `VaultHook.withdraw` | 14 | 经由 Vault |
| `PriceEngine.recordTrade` | 12 | 同步写记录 |
| `BlindBoxManager.listLoot` | 10 | 同步 SQL |
| `StorageManager.getPlayerItems` | 9 | 同步 SQL |
| `BlindBoxManager.listPools` | 8 | 同步 SQL |
| `BlindBoxManager.addLootWithData` | 8 | 同步 SQL |
| `VaultHook.has / format` | 7+7 | 同步读余额 |
| `InheritListener.onCursorPut` | 6 | 同步 SQL |

**问题一：`KsDataStore.getConnection` 被采样 88 次**  
该方法被所有模块共用，每次 DB 操作都会进入此方法。如果连接池/单例没有正确缓存，实际上是反复 `DriverManager.getConnection()` 开新连接，代价极高（需经过 SQLite 文件 open + pragma 设置）。需确认 `getConnection` 内部是否真的返回缓存连接。

**问题二：BlindBoxManager 每次抽奖多轮同步 SQL**  
单次抽奖涉及：`getPool` → `getPityCount` → `getLootItemStack`（循环）→ `addLootWithData` → 写保底数据，每步都是独立同步 SQL，卡主线程叠加明显。

**已有异步改造**（本轮已写，未部署）：  
`MarketMenu.open` / `buyListing` / `listItemForSale` / `recordTax` 已改异步，部署后市场相关 SQLite 帧将大幅减少。

**仍需异步化**：
- `BlindBoxManager`（抽奖全流程）
- `BuiltinEconomy.setBalance`（余额写入）
- `VaultHook.has / withdraw`（余额读取路径）

---

### 4.2 getItemMeta 高频调用

| 方法 | 采样次数 |
|------|----------|
| `CraftItemStack.getItemMeta` | **813** |
| `CraftItemStack.hasItemMeta` | 143 |

这两个方法每次调用都会从 NMS `ItemStack` 反序列化出一个新的 `ItemMeta` 对象，代价不低。

**触发来源（推断）**：`MarketMenu.open` → `buildListingItem` 对每条挂单都调用一次 `getItemMeta`。若市场有 50 条挂单，一次 `open` 就产生 50 次调用；多个玩家同时开界面叠加更高。

**建议**：`buildListingItem` 的结果（`ItemStack` 展示品）按 `listingId` 缓存，挂单内容不变时直接复用，避免每次 `open` 重复构建。

---

### 4.3 ks-* 直接代码帧（199 帧）

| 方法 | 次数 |
|------|------|
| `BlindBoxAdminGui.build` | 9 |
| `KsDataStore.getConnection` | 8 |
| `MarketMenu.open` | 8 |
| `BuiltinEconomy.setBalance` | 5 |
| `BlindBoxAdminGui.buildLootList` | 5 |
| `BlindBoxManager.listPools` | 5 |
| `BlindBoxManager.getLootItemStack` | 6 |
| `BuiltinEconomy$EconomyHandler.invoke` | 6 |

BlindBoxAdminGui 在管理员操作时构建 GUI 也涉及多轮 SQL（`buildAddLoot` 24 次），这些是管理员操作触发，频率低，但每次操作都会短暂卡主线程。

---

## 五、优化优先级汇总

| 优先级 | 项目 | 改动量 | 预期效果 |
|--------|------|--------|----------|
| ★★★ | 修 `simulation-distance: 8 → 4` | 改 1 行配置 | 寻路/区块 tick 减少 ~70% |
| ★★★ | 部署已有异步 DB 改造（MarketMenu 等） | 已完成待部署 | 市场 SQLite 帧清零 |
| ★★★ | 确认 `KsDataStore.getConnection` 是否真正缓存连接 | 查代码 | 88 帧最高单点 |
| ★★ | `BlindBoxManager` 全流程改异步 | 中等改动 | 盲盒抽奖不卡主线程 |
| ★★ | `inactive-goal-selector-disable: true` | 改 1 行配置 | 降低静止怪物 AI 开销 |
| ★★ | 怪物上限 70 → 50 | 改 1 行配置 | 线性减少寻路帧 |
| ★ | 收紧 `entity-activation-range` | 改配置 | 减少远距怪物唤醒 |
| ★ | `buildListingItem` 结果缓存 | 小改动 | 减少 813 次 getItemMeta |
| ★ | `BuiltinEconomy.setBalance` 改异步 | 小改动 | 余额写入不卡主线程 |

---

## 六、无需处理

- **ItemsAdder、MythicMobs 帧**：第三方插件，不改代码，只能减少怪/物品数量
- **JVM stdlib 帧（20%）**：HashMap/ArrayList 等是正常 Java 开销，不是瓶颈
- **Craftorithm（0.05%）**：采样次数极少，不值得干预

---

*报告基于 Spark profiler 长时间采样，帧计数反映主线程实际执行时间分布。*
