# 第二/第三波 RPG 内容状态

状态日期：2026-07-19。本文记录当前 source、独立内容包和运行时接线状态，不是部署说明，也不表示内容已经上线。

## 第一季与赛季基础

第一季仍以生存优先的遗物前奏为底：`KS_STANDARD`、`KS_REFINED`、`KS_RARE`、`KS_RELIC`
四档固定品质；`KS_FIELD_SCRAP`、`KS_REFINED_ALLOY`、`KS_CONDUCTIVE_COIL`、
`KS_STABILIZED_CORE`、`KS_RELIC_FRAGMENT` 组成单向材料链；玩家使用一把主武器、两枚不同戒指和一枚护符。

装备和普通材料可以交易，账号证明不能交易。基础准备可以购买，决定性能力与遗物突破仍必须同时要求材料、
`CASH`/制作消耗和战斗证明，不能通过商店、军备匣或盲盒绕过。

ks-RPG 现在还包含一个默认关闭的赛季领域与存储基础：

- `season.enabled=false` 是默认值；关闭时不创建数据库或修改玩家。
- 显式开启后只初始化本地 SQLite `season.db`，仍不会自动创建赛季或事件。
- 已有 `DRAFT -> ACTIVE -> SETTLING -> ARCHIVED` 生命周期。
- 区域声望默认周上限 `1000`、追赶上限 `3000`、迟入场每周额度 `600`。
- 事件贡献使用单调、幂等快照；社区项目使用唯一 source key 防止重复推进。
- 奖励领取有 `PENDING/DELIVERING/RETRYABLE/GRANTED/COMPENSATION_REQUIRED` 显式状态，
  归档不删除永久资产和历史记录。
- JDBC 只在单数据库工作线程运行，服务器线程通过 `RpgSeasonStatusApi` 读取缓存状态。

当前只有存储和领域基础。没有正式赛季 ID、内容目录、事件调度、奖励 payload 执行、管理员生命周期工作流、
远程数据库后端或跨服赛季协调，也没有 Paper 实机赛季验收。

## 第二波：灰烬边境与风暴崖

来源：

- ks-RPG source：`ks-RPG/src/main/resources/content/**/second-wave.yml`
- 独立源包：`deploy_package/ks-rpg-second-wave-20260718/`

源包提供 17 个 MMOItems ID、11 条确定性兑换、两个低频区域精英、两种军备匣和五类 ks-RPG 内容文件。

### 第二波 ID

| 类型 | ID |
| --- | --- |
| MATERIAL | `KS_CINDER_SCALE`, `KS_FORGE_HEART`, `KS_STORM_FEATHER`, `KS_CHARGED_CORE` |
| MISCELLANEOUS | `KS_ASHEN_ARMAMENT_CACHE`, `KS_STORM_ARMAMENT_CACHE` |
| SWORD | `KS_CINDERWARD_BLADE` |
| HAMMER | `KS_FORGEBREAKER_MAUL` |
| SPEAR | `KS_STORMRELAY_PIKE` |
| BOW | `KS_STORMWATCH_BOW` |
| TALISMAN | `KS_FORGEWARD_TALISMAN`, `KS_WAYFINDER_TALISMAN`, `KS_STORMSHELTER_TALISMAN` |
| RING | `KS_RING_OF_COALHEART`, `KS_RING_OF_TEMPER`, `KS_RING_OF_CROSSWIND`, `KS_RING_OF_STATIC_ECHO` |

### 区域玩法

- 灰烬精英：Mythic mob `KS_Cinderback_Whelp`，掉落标签 `KS_Cinderback_Whelp`；装备偏站桩、生存、
  小范围控制与短受伤窗口。
- 风暴精英：Mythic mob `KS_Stormwatch_Thunderbird`，掉落标签 `KS_Stormwatch_Thunderbird`；装备偏位移、
  穿透、猎印和不同玩家之间的接力命中。
- 普通区域材料提供稳定积累，稀有核心限制高阶配方；2% 军备匣只是加速途径，所有装备另有确定性兑换。
- ks-RPG source 已记录真实伤害贡献者，并在合格在线参与者间轮转已掷出的掉落，不再全部固定给最后击杀者。

### 第二波当前限制

- source 与内容包存在不等于 MMOItems、MythicMobs 和配置片段已经合并到测试服或生产服。
- RandomSpawns 默认只知道 `world` 和露天条件，不知道正式灰烬边境/风暴崖边界；上线前必须收紧世界或区域条件。
- 生成频率、12000 tick TTL、Phantom 俯冲、多人掉落轮转、物品栏溢出、缓存消耗和实际材料供给未完成 Paper 实测。
- 武器、护符、戒指技能仍需验证 MMOInventory 识别、冷却、死亡/掉线清理和四人持续战斗性能。

## 第三波：炉心遗物、风暴遗物与地脉

独立包：`deploy_package/ks-rpg-third-wave-20260718/`。该包没有进入 ks-RPG source，不包含 JAR、部署脚本或
数据库迁移，当前状态为 `staging-not-deployed`。

第三波复用第二波灰烬/风暴材料与基础装备，新增炉心/风暴遗物横向件，并为地脉增加基础材料与装备草案。

### 第三波新增 ID

| 类型 | ID |
| --- | --- |
| MATERIAL | `KS_FOUNDRY_FRAGMENT`, `KS_STORMSPIRE_FRAGMENT`, `KS_DEEPSTONE_SHARD`, `KS_TERRAVORE_HEART`, `KS_EARTHVEIN_FRAGMENT` |
| HAMMER | `KS_HEARTFORGED_MAUL`, `KS_EARTHSHAPER_MAUL` |
| SPEAR | `KS_STORMSPIRE_PIKE` |
| TALISMAN | `KS_FOUNDRY_SIGIL_TALISMAN`, `KS_GROUNDING_TALISMAN`, `KS_ROOTBOUND_TALISMAN` |
| RING | `KS_RING_OF_QUENCHING`, `KS_RING_OF_AFTERSHOCK`, `KS_RING_OF_BEDROCK` |

共 14 个新 MMOItems 定义。炉心和风暴的 `KS_RELIC` 物品故意没有可执行获取路径；地脉三件基础横向件只有
设计输入，没有实际兑换接线。

### 证明与门槛

第三波片段声明四个证明：

- `frostbound_conductor_clear`
- `ashen_foundry_clear`
- `stormspire_cartographer_clear`
- `terravore_heart_clear`

七个门槛：

- 入口：`ashen_foundry_entry`, `stormspire_entry`, `earthvein_entry`
- 突破：`ashen_foundry_relic_breakthrough`, `stormspire_relic_breakthrough`, `earthvein_relic_breakthrough`
- 长期：`triune_atlas`

这些 gate 当前只能返回是否满足，不能扣除材料或 `CASH`，也不会自动阻止技能施放或副本入场。
`dungeon/reward-templates.yml` 的三个 `entry-gate` 只是设计元数据，三个 schematic 仍是 `CHANGE_ME_*` 占位。

### 第三波遭遇契约

- 炉心：复用 `AshenFoundry_Overseer` 与 `ashen_foundry_clear`，期望成功机制添加
  `KS_AshenFoundry_Exposed`。
- 风暴：草案使用 `Stormspire_Cartographer`、`stormspire_cartographer_clear` 和
  `KS_Stormspire_Exposed`，当前没有对应 MythicMobs 包。
- 地脉：草案使用精英 `Earthvein_Devourer`、Boss `Terravore_Heart`、证明
  `terravore_heart_clear` 和 `KS_Earthvein_Exposed`，当前没有对应 Boss 包。

## 三个 2026-07-18 Boss 包

以下三个包均为隔离 staging 内容，没有部署或启动 Paper：

| 包 | Boss ID | proof / gate | 核心机制 | 当前状态 |
| --- | --- | --- | --- | --- |
| `ashen_foundry_overseer-20260718` | `AshenFoundry_Overseer` | `ashen_foundry_clear`; 第三波拟用 `ashen_foundry_relic_breakthrough` | 双阀泄压、熔渣救援、炉心打断 | YAML/静态检查；未完成正式场地、奖励和 Paper 实战 |
| `stormforge_overseer-20260718` | `Stormforge_Overseer` | `stormforge_overseer_clear`; `stormforge_grounded_breakthrough` | 双人分散、三相接地、双阀与内外圈波形 | SnakeYAML、引用、奖励 JSON 通过；未做 Paper 实测 |
| `aurora_packwarden-20260718` | `Aurora_Packwarden` | `aurora_packwarden_clear`; `aurora_forged_breakthrough` | 四点分站、2+2 暖炉、三人轮挡 | 静态解析；模型状态、碰撞、人数采样和副本奖励待实测 |

Frostbound Conductor 是更早的合作机制基线，不计入上述三个新包。它使用
`frostbound_conductor_clear` / `frostbound_relic_breakthrough`；模型有历史加载记录，但 Ice Core Relay、
当前副本奖励和完整四人路径仍需维护窗口复验。

## 合并前必须解决的 ID 与契约冲突

1. 第三波风暴线使用 `Stormspire_Cartographer` / `stormspire_cartographer_clear` /
   `stormspire_relic_breakthrough`，独立雷炉包实际使用 `Stormforge_Overseer` /
   `stormforge_overseer_clear` / `stormforge_grounded_breakthrough`。两者不是同一契约，不能同时当作正式风暴 Boss。
2. 灰烬 Boss 包保留占位材料 `ASHEN_FOUNDRY_FRAGMENT`，第三波定义正式候选
   `KS_FOUNDRY_FRAGMENT`。奖励接线前必须只保留一个 ID。
3. 第三波炉心锤依赖 `KS_AshenFoundry_Exposed`，现有灰烬 Boss 成功窗口只提供 stun、WEAKNESS 和
   GLOWING，尚未添加该标签。
4. `Terravore_Heart` 与地脉 helper/场地/完成目标尚未实现；地脉奖励模板不能创建为可用副本。
5. Aurora 是独立冰原合作包，不属于当前 `triune_atlas` 四证明列表；是否纳入赛季或另设支线尚未决定。

## 仍未接线或未实测

- 三个新 Boss 包、第二波源包和第三波包均未部署；静态 YAML 成功不能替代 `/mm reload`、物品创建和战斗验收。
- Boss proof 仍通过要求在线目标的命令桥示例发放；掉线完成者需要副本侧直接、幂等调用
  `RpgProgressionApi`，当前尚未接线。
- proof gate、材料和 `CASH` 没有原子突破事务；失败恢复、重复提交和奖励状态读回未完成。
- ks-RPG 的 mark、relay、SIGNAL/PIONEER 范围效果仍不理解 Dungeon party，需防止旁观者或非队友获得团队收益。
- 副本 RPG 配装快照、重复戒指限制、实例内锁装和离开/死亡/掉线解锁尚未实现。
- Dungeon 当前模板入场不会执行第三波 gate，Stormspire/Earthvein schematic 和 Boss 完成目标不存在。
- 非 `CASH` RPG 结算、远程 MySQL/MariaDB/PostgreSQL、跨服 proof/season 同步和真实远程数据库故障恢复未实测。
- 赛季存储目前只有本地 SQLite；没有任何正式赛季、事件、声望来源、项目来源或奖励执行器接入。
- 所有区域和 Boss 仍需 2/3/4 人、纯近战、断线、超时、停服、助手清理、奖励名单、MSPT、堆内存和 GC 验收。

在这些限制关闭前，第二/第三波与 Boss 包只能作为可审阅、可解析的内容源，不应描述为正式开放玩法。
