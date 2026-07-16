# FotiaEnchantment AI 附魔写法教程

本文件只给 AI 看，用来教 AI 在看不到插件源码的情况下，按 FotiaEnchantment 的配置格式编写可用附魔。

AI 的目标不是解释插件原理，而是根据服主需求生成正确的附魔配置和语言条目。

## 输出目标

当用户让你写一个附魔时，通常需要同时输出 3 份内容：

1. 附魔配置文件：`enchantments/<分类>/<附魔id>.yml`
2. 中文语言片段：`lang/zh_cn/enchantments.yml`
3. 英文语言片段：`lang/en_us/enchantments.yml`

如果用户只要求给出配置，就按文件路径分别输出 YAML 代码块。

## 基本规则

- 一个附魔一个 `.yml` 文件。
- 附魔 ID 使用小写英文和下划线，例如 `storm_reaver`。
- 文件名建议和附魔 ID 一致，例如 `storm_reaver.yml`。
- 每个附魔都要写中文和英文语言条目。
- 不要发明本文没有列出的触发器、条件、效果。
- 不要写“计划中”“扩展实现”“未实现”等描述。
- 不要写没有实际作用的虚假配置项。
- 不要使用旧版或不存在的字段名，例如 `item-groups`、`materials`、`enchanting-table.weight`、`codex.enabled`、`codex.weight`、效果块根级 `chance`。
- `conflicts` 只能引用已经存在、同批生成、或用户明确要求保留的附魔 ID；不确定时必须写 `conflicts: []`，不要编造冲突附魔。
- 推荐把 `enchanting-table-weight` 和 `villager-trade-price-range` 写在根级；插件兼容写在 `obtain` 里的旧格式，但 AI 生成新配置时统一使用根级写法。
- `obtain.villager-trade: false` 时，`villager-trade-price-range` 会被插件忽略；如果村民交易开启或未显式关闭，价格区间必须是两个整数。
- 数值可以用 `{level}` 表示当前附魔等级。
- `cooldown`、`duration` 等时间单位是 tick，20 tick = 1 秒。
- `chance` 的 `value` 是百分比，`25` 表示 25%。
- 高频触发、群体伤害、范围爆炸、持续效果必须设置冷却。

## 日志排查边界

当用户提供服务器日志让 AI 判断是否是附魔配置问题时，先区分“配置错误”和“插件 jar / Paper 缓存错误”。

- 如果日志包含 `[FotiaEnchantment] 配置错误`、`位置: <配置路径>`、`未定义附魔`、`conflicts 引用了未定义附魔`，按本文规则检查附魔 YAML。
- 如果日志是 `NoClassDefFoundError` 或 `ClassNotFoundException`，并且缺失类以 `gg.fotia.enchantment.` 开头，例如 `TriggerContext`、`MenuConfig`、`PacketEventsHook`、`FePaperCommand`，不要让用户修改附魔 YAML。这通常表示服务器实际加载的插件 jar 不完整、用户放错了 jar、文件名异常，或 Paper 的 `.paper-remapped` 缓存仍是坏包。
- 如果日志里的 jar 名像 `FotiaEnchantment-1.0.6 .jar` 这样版本号后有多余空格，优先提醒用户关服，删除错误 jar 和 `plugins/.paper-remapped/FotiaEnchantment*.jar`，再放入正式下载的 `FotiaEnchantment-<版本>.jar`。文件名多空格本身不是 YAML 配置错误。
- 不要把插件内部类缺失解释成触发器、条件、效果、语言文件或附魔配置写错。

## 可用分类

附魔文件放入对应分类目录：

```text
enchantments/melee/      近战武器
enchantments/ranged/     弓、弩、三叉戟等远程武器
enchantments/armor/      盔甲、防具
enchantments/tools/      工具
enchantments/universal/  通用附魔
```

配置里的 `category` 也使用同样的值：

```yaml
category: melee
```

## 可用品质

优先使用这些品质 ID：

```text
dustlight
moonlit
radiant
aureate
divine
```

品质一般从低到高理解为：

```text
dustlight < moonlit < radiant < aureate < divine
```

## 可用附魔组

优先使用这些组 ID：

```text
fire
ice
lightning
defensive
offensive
utility
movement
mining
```

## 可用物品类型

`applicable-items` 可以写物品分类：

```yaml
applicable-items:
  - SWORD
  - AXE
```

常用分类：

```text
SWORD
AXE
PICKAXE
SHOVEL
HOE
BOW
CROSSBOW
TRIDENT
FISHING_ROD
SHIELD
ELYTRA
HELMET
CHESTPLATE
LEGGINGS
BOOTS
```

也可以写具体 Bukkit 材料名：

```yaml
applicable-items:
  - DIAMOND_SWORD
  - NETHERITE_SWORD
```

除非用户要求限制具体材料，否则优先使用分类。

## 附魔配置模板

```yaml
id: example_enchant
enabled: true
curse: false
max-level: 5
rarity: radiant
group: offensive
category: melee

applicable-items:
  - SWORD
  - AXE

conflicts: []

obtain:
  enchanting-table: true
  anvil: true
  villager-trade: true

enchanting-table-weight: 10
villager-trade-price-range:
  - 16
  - 40

codex-pools:
  radiant: 10
  aureate: 4

effects:
  - trigger: MELEE_ATTACK
    cooldown: 60
    conditions:
      - type: chance
        value: "{level} * 8"
    actions:
      - type: DAMAGE_ADD
        value: "{level} * 1.5"
```

## 字段解释

```yaml
id: example_enchant
```

附魔唯一 ID，必须小写，建议和文件名一致。

```yaml
enabled: true
```

是否启用。

```yaml
curse: false
```

是否是诅咒附魔。诅咒附魔写 `true`。

```yaml
max-level: 5
```

最大等级。

```yaml
rarity: radiant
```

品质 ID。

```yaml
group: offensive
```

附魔组 ID。

```yaml
category: melee
```

分类 ID。

```yaml
applicable-items:
  - SWORD
```

允许附魔的物品。

```yaml
conflicts:
  - other_enchant
```

冲突附魔 ID。没有冲突就写 `conflicts: []`。

AI 写 `conflicts` 前必须确认目标附魔存在。如果无法从当前需求、现有配置或同批输出中确认存在，不要写进去。未定义冲突附魔会被插件在控制台和 `/fe reload` 中提示，并且 Paper 注册阶段会过滤这些无效引用；AI 不应该依赖运行时过滤来修正错误配置。

```yaml
obtain:
  enchanting-table: true
  anvil: true
  villager-trade: true
```

获取方式开关。

```yaml
enchanting-table-weight: 10
```

附魔台权重，数值越高越容易出现。

```yaml
villager-trade-price-range:
  - 16
  - 40
```

村民交易价格区间。只有 `obtain.villager-trade` 为 `true` 或未显式关闭时才必须填写两个整数列表 `[最低, 最高]`，不要写成 `min/max` 配置段。`obtain.villager-trade: false` 时可以省略该字段；即使保留为空列表也会被插件忽略。

```yaml
codex-pools:
  radiant: 10
```

随机池或图鉴池权重。key 是品质 ID，value 是权重。

## effects 写法

`effects` 是附魔真正生效的地方。

```yaml
effects:
  - trigger: MELEE_ATTACK
    cooldown: 60
    conditions:
      - type: chance
        value: "{level} * 8"
    actions:
      - type: DAMAGE_ADD
        value: "{level} * 1.5"
```

含义：

- `trigger`：什么时候触发。
- `cooldown`：冷却 tick，可选但推荐写。
- `conditions`：满足什么条件才触发。
- `actions`：触发后执行什么效果。
- 动作参数必须和 `type`、`value` 写在同一级，例如 `potion`、`duration`、`multiplier`；不要包一层 `extra-params`，插件不会展开这个字段。

一个附魔可以有多个触发块：

```yaml
effects:
  - trigger: MELEE_ATTACK
    cooldown: 60
    conditions:
      - type: chance
        value: "{level} * 8"
    actions:
      - type: DAMAGE_ADD
        value: "{level} * 1.5"

  - trigger: KILL
    cooldown: 100
    conditions:
      - type: chance
        value: "{level} * 10"
    actions:
      - type: HEAL
        value: "{level}"
```

## 表达式写法

可以在数值字段里使用 `{level}`：

```yaml
value: "{level} * 2"
duration: "{level} * 40"
amplifier: "{level} - 1"
```

常用设计：

```yaml
conditions:
  - type: chance
    value: "{level} * 8"
```

如果最大等级是 5，那么触发概率是：

```text
1 级 = 8%
2 级 = 16%
3 级 = 24%
4 级 = 32%
5 级 = 40%
```

## 语言文件写法

中文：

```yaml
example_enchant:
  name: "示例附魔"
  description:
    - "攻击时有概率造成额外伤害。"
```

英文：

```yaml
example_enchant:
  name: "Example Enchant"
  description:
    - "Attacks have a chance to deal bonus damage."
```

语言条目的 key 必须等于附魔 `id`。

描述只写玩家能理解的效果，不要写配置字段名。

## 可用触发器

触发器写在 `trigger`，必须大写。AI 选择触发器时先判断“附魔应该在什么时候生效”。

### 战斗触发器说明

| 触发器 | 作用 |
| --- | --- |
| `MELEE_ATTACK` | 玩家进行近战攻击时触发。 |
| `MELEE_ATTACK_CRITICAL` | 玩家打出近战暴击时触发。 |
| `MELEE_ATTACK_SWEEP` | 玩家触发横扫攻击时触发。 |
| `MELEE_ATTACK_BEHIND` | 玩家从目标背后近战攻击时触发。 |
| `MELEE_ATTACK_COMBO` | 玩家连续近战命中形成连击时触发。 |
| `MELEE_ATTACK_WHILE_AIRBORNE` | 玩家在空中进行近战攻击时触发。 |
| `BOW_ATTACK` | 弓箭命中目标并造成攻击时触发。 |
| `BOW_SHOOT` | 玩家射出弓箭时触发。 |
| `CROSSBOW_ATTACK` | 弩箭命中目标并造成攻击时触发。 |
| `CROSSBOW_SHOOT` | 玩家射出弩箭时触发。 |
| `TRIDENT_ATTACK` | 三叉戟命中目标并造成攻击时触发。 |
| `TRIDENT_THROW` | 玩家投掷三叉戟时触发。 |
| `PROJECTILE_HIT_ENTITY` | 任意弹射物命中实体时触发。 |
| `PROJECTILE_HIT_BLOCK` | 任意弹射物命中方块时触发。 |
| `HEADSHOT` | 远程攻击命中头部判定时触发。 |
| `ARROW_BOUNCE` | 箭矢反弹逻辑触发时触发。 |
| `FIRST_BLOOD` | 战斗中首次造成有效伤害时触发。 |
| `KILL` | 玩家击杀任意实体时触发。 |
| `KILL_PLAYER` | 玩家击杀其他玩家时触发。 |
| `KILL_BOSS` | 玩家击杀 Boss 类实体时触发。 |
| `KILL_STREAK` | 玩家连续击杀达到连杀逻辑时触发。 |
| `ASSIST` | 玩家参与击杀但不是最后一击时触发。 |
| `DODGE` | 玩家成功闪避伤害时触发。 |
| `SHIELD_BLOCK` | 玩家用盾牌格挡伤害时触发。 |
| `ARMOR_ABSORB` | 防具吸收或减免伤害时触发。 |
| `TAKE_DAMAGE` | 玩家受到任意伤害时触发。 |
| `TAKE_ENTITY_DAMAGE` | 玩家受到实体造成的伤害时触发。 |
| `TAKE_PLAYER_DAMAGE` | 玩家受到其他玩家伤害时触发。 |
| `TAKE_PROJECTILE_DAMAGE` | 玩家受到弹射物伤害时触发。 |
| `MAGIC_DAMAGE` | 玩家受到魔法类伤害时触发。 |
| `FIRE_DAMAGE` | 玩家受到火焰或燃烧伤害时触发。 |
| `POISON_DAMAGE` | 玩家受到中毒伤害时触发。 |
| `WITHER_DAMAGE` | 玩家受到凋零伤害时触发。 |
| `VOID_DAMAGE` | 玩家受到虚空伤害时触发。 |
| `FALL_DAMAGE` | 玩家受到摔落伤害时触发。 |
| `DROWNING_DAMAGE` | 玩家受到溺水伤害时触发。 |
| `EXPLOSION_DAMAGE` | 玩家受到爆炸伤害时触发。 |
| `FREEZE` | 玩家受到冰冻相关影响时触发。 |
| `NEAR_DEATH` | 玩家生命值很低、接近死亡时触发。 |
| `RESURRECT` | 玩家被复活或免死逻辑触发时触发。 |
| `DEATH` | 玩家死亡时触发。 |
| `RESPAWN` | 玩家重生时触发。 |

### 挖掘和方块触发器说明

| 触发器 | 作用 |
| --- | --- |
| `MINE_BLOCK` | 玩家挖掘方块时触发。 |
| `MINE_BLOCK_PROGRESS` | 玩家挖掘进度变化时触发。 |
| `MINE_ORE` | 玩家挖掘矿石时触发。 |
| `MINE_DEEPSLATE` | 玩家挖掘深板岩或深层相关方块时触发。 |
| `BREAK_SPAWNER` | 玩家破坏刷怪笼时触发。 |
| `HARVEST` | 玩家收获作物时触发。 |
| `HARVEST_TREE` | 玩家砍伐或收获树木时触发。 |
| `PLACE_BLOCK` | 玩家放置方块时触发。 |
| `INTERACT_BLOCK` | 玩家右键或交互方块时触发。 |
| `SHEAR_BLOCK` | 玩家对方块执行剪切行为时触发。 |
| `BONEMEAL_CROP` | 玩家使用骨粉催熟作物时触发。 |
| `PLANT_SEED` | 玩家种植种子时触发。 |
| `COMPOST_ITEM` | 玩家向堆肥桶投入物品时触发。 |
| `FILL_BUCKET` | 玩家用桶装入液体或实体时触发。 |
| `EMPTY_BUCKET` | 玩家倒出桶中内容时触发。 |
| `BLOCK_ITEM_DROP` | 方块产生掉落物时触发。 |
| `CAULDRON_LEVEL_CHANGE` | 炼药锅液位变化时触发。 |
| `PRESSURE_PLATE` | 玩家踩下压力板时触发。 |
| `TRIPWIRE` | 玩家触发绊线时触发。 |
| `NOTE_BLOCK_PLAY` | 音符盒播放时触发。 |
| `BELL_RING` | 钟被敲响时触发。 |

### 物品、合成、容器触发器说明

| 触发器 | 作用 |
| --- | --- |
| `ENCHANT_ITEM` | 玩家在附魔台附魔物品时触发。 |
| `ANVIL_USE` | 玩家使用铁砧操作物品时触发。 |
| `GRIND_ITEM` | 玩家使用砂轮处理物品时触发。 |
| `SMITH_ITEM` | 玩家使用锻造台处理物品时触发。 |
| `CRAFT` | 玩家合成物品时触发。 |
| `SMELT` | 玩家烧炼物品完成时触发。 |
| `BREW` | 玩家酿造药水时触发。 |
| `REPAIR_ITEM` | 玩家修复物品时触发。 |
| `ITEM_BREAK` | 玩家物品损坏到破裂时触发。 |
| `DROP_ITEM` | 玩家丢出物品时触发。 |
| `PICK_UP_ITEM` | 玩家拾取物品时触发。 |
| `CONSUME` | 玩家消耗食物、药水等可消耗物时触发。 |
| `THROW_EGG` | 玩家投掷鸡蛋时触发。 |
| `THROW_SNOWBALL` | 玩家投掷雪球时触发。 |
| `DAMAGE_ITEM` | 玩家持有或使用的物品耐久受损时触发。 |
| `OPEN_CONTAINER` | 玩家打开容器时触发。 |
| `CLOSE_CONTAINER` | 玩家关闭容器时触发。 |
| `LOOM_USE` | 玩家使用织布机时触发。 |
| `CARTOGRAPHY_USE` | 玩家使用制图台时触发。 |
| `STONECUTTER_USE` | 玩家使用切石机时触发。 |
| `CHANGE_ARMOR` | 玩家更换盔甲时触发。 |
| `HOLD` | 玩家持续持有附魔物品时触发。 |
| `HOLD_ITEM_CHANGE` | 玩家切换手持物品时触发。 |
| `WEAR` | 玩家穿戴带有附魔的装备时触发。 |

### 移动和环境触发器说明

| 触发器 | 作用 |
| --- | --- |
| `JUMP` | 玩家跳跃时触发。 |
| `DOUBLE_JUMP` | 玩家执行二段跳逻辑时触发。 |
| `LAND` | 玩家落地时触发。 |
| `SPRINT_START` | 玩家开始疾跑时触发。 |
| `SPRINT_STOP` | 玩家停止疾跑时触发。 |
| `SNEAK_START` | 玩家开始潜行时触发。 |
| `SNEAK_STOP` | 玩家停止潜行时触发。 |
| `SWIM` | 玩家游泳时触发。 |
| `ENTER_WATER` | 玩家进入水中时触发。 |
| `EXIT_WATER` | 玩家离开水中时触发。 |
| `ENTER_LAVA` | 玩家进入岩浆时触发。 |
| `CATCH_FIRE` | 玩家着火时触发。 |
| `EXTINGUISH` | 玩家身上的火焰熄灭时触发。 |
| `TELEPORT` | 玩家传送时触发。 |
| `MOUNT_ENTITY` | 玩家骑乘实体时触发。 |
| `DISMOUNT_ENTITY` | 玩家离开骑乘实体时触发。 |
| `ENTER_VEHICLE` | 玩家进入载具时触发。 |
| `EXIT_VEHICLE` | 玩家离开载具时触发。 |
| `DEPLOY_ELYTRA` | 玩家展开鞘翅时触发。 |
| `ELYTRA_GLIDE` | 玩家使用鞘翅滑翔时触发。 |
| `ELYTRA_BOOST` | 玩家鞘翅加速时触发。 |
| `TOGGLE_FLIGHT` | 玩家切换飞行状态时触发。 |
| `RIPTIDE` | 玩家使用激流三叉戟移动时触发。 |
| `THROW_ENDER_PEARL` | 玩家投掷末影珍珠时触发。 |
| `USE_FIREWORK` | 玩家使用烟花火箭时触发。 |
| `CHANGE_WORLD` | 玩家切换世界时触发。 |
| `CHANGE_BIOME` | 玩家所在生物群系变化时触发。 |
| `ENTER_REGION` | 玩家进入区域时触发。 |
| `EXIT_REGION` | 玩家离开区域时触发。 |

### 钓鱼、实体、村民触发器说明

| 触发器 | 作用 |
| --- | --- |
| `CAST_ROD` | 玩家抛出鱼竿时触发。 |
| `BITE` | 钓鱼时鱼咬钩触发。 |
| `REEL_IN` | 玩家收杆时触发。 |
| `CATCH_FISH` | 玩家钓到鱼时触发。 |
| `CATCH_TREASURE` | 玩家钓到宝藏时触发。 |
| `CATCH_JUNK` | 玩家钓到垃圾时触发。 |
| `CATCH_ENTITY` | 鱼钩钩住实体时触发。 |
| `HOOK_IN_GROUND` | 鱼钩落在地面或方块上时触发。 |
| `INTERACT_ENTITY` | 玩家与实体交互时触发。 |
| `COLLIDE_WITH_ENTITY` | 玩家与实体发生碰撞时触发。 |
| `LEASH_ENTITY` | 玩家拴住实体时触发。 |
| `UNLEASH_ENTITY` | 玩家解除实体拴绳时触发。 |
| `SHEAR_ENTITY` | 玩家剪羊毛或剪切实体时触发。 |
| `MILK_COW` | 玩家给牛或哞菇挤奶时触发。 |
| `TAME_ANIMAL` | 玩家驯服动物时触发。 |
| `BREED_ANIMAL` | 玩家繁殖动物时触发。 |
| `ENTITY_SPAWN_NEAR` | 玩家附近有实体生成时触发。 |
| `ENTITY_TARGET_ME` | 实体将玩家设为目标时触发。 |
| `VILLAGER_TRADE` | 玩家与村民交易时触发。 |
| `BUY_ITEM` | 玩家购买物品时触发。 |
| `SELL_ITEM` | 玩家出售物品时触发。 |

### 状态、服务器、计时器触发器说明

| 触发器 | 作用 |
| --- | --- |
| `JOIN_SERVER` | 玩家进入服务器时触发。 |
| `LEAVE_SERVER` | 玩家离开服务器时触发。 |
| `SEND_CHAT` | 玩家发送聊天消息时触发。 |
| `RUN_COMMAND` | 玩家执行命令时触发。 |
| `GAIN_XP` | 玩家获得经验时触发。 |
| `LEVEL_UP` | 玩家经验等级提升时触发。 |
| `GAIN_HUNGER` | 玩家饥饿值恢复或增加时触发。 |
| `LOSE_HUNGER` | 玩家饥饿值减少时触发。 |
| `GAIN_ABSORPTION` | 玩家获得伤害吸收生命时触发。 |
| `HEAL` | 玩家恢复生命值时触发。 |
| `NATURAL_REGEN` | 玩家自然回血时触发。 |
| `POTION_EFFECT` | 玩家获得药水效果时触发。 |
| `LOSE_POTION_EFFECT` | 玩家失去药水效果时触发。 |
| `COMPLETE_ADVANCEMENT` | 玩家完成进度时触发。 |
| `WIN_RAID` | 玩家赢得袭击事件时触发。 |
| `ENTER_BED` | 玩家上床时触发。 |
| `LEAVE_BED` | 玩家离开床时触发。 |
| `WAKE_UP` | 玩家睡醒时触发。 |
| `LIGHTNING_STRIKE_NEAR` | 玩家附近发生雷击时触发。 |
| `TIMER_1S` | 每 1 秒周期性检查持有或穿戴附魔的玩家。 |
| `TIMER_5S` | 每 5 秒周期性检查持有或穿戴附魔的玩家。 |
| `TIMER_10S` | 每 10 秒周期性检查持有或穿戴附魔的玩家。 |
| `TIMER_30S` | 每 30 秒周期性检查持有或穿戴附魔的玩家。 |
| `TIMER_60S` | 每 60 秒周期性检查持有或穿戴附魔的玩家。 |
| `TIMER_CUSTOM` | 自定义周期计时器触发，只有明确知道周期配置时才使用。 |

## 可用条件

条件写在 `conditions`，条件 ID 必须小写。AI 选择条件时先判断“触发前需要满足什么限制”。

常用写法：

```yaml
conditions:
  - type: chance
    value: "{level} * 8"
  - type: target_is_living
```

### 基础条件说明

| 条件 | 作用 | 常用参数 |
| --- | --- | --- |
| `altitude` | 判断玩家当前高度。 | `value` |
| `behind_target` | 判断玩家是否在目标背后。 | 无 |
| `chance` | 按百分比随机通过。 | `value` |
| `consecutive_hits` | 判断连续命中次数。 | `value` |
| `cooldown_check` | 判断某个冷却状态。 | `key`、`value` |
| `distance_to_target` | 判断玩家到目标的距离。 | `value` |
| `exposure_to_sky` | 判断玩家位置是否露天。 | 无 |
| `food_level` | 判断玩家饥饿值。 | `value` |
| `health_below` | 判断玩家生命值是否低于数值。 | `value` |
| `in_biome` | 判断玩家是否在指定生物群系。 | `value` |
| `in_combat` | 判断玩家是否处于战斗状态。 | 无 |
| `in_water` | 判断玩家是否在水中。 | 无 |
| `in_world` | 判断玩家是否在指定世界。 | `value` |
| `kill_streak` | 判断玩家连杀数量。 | `value` |
| `last_damage_interval` | 判断距离上次受伤的间隔。 | `value` |
| `not_attacked_for` | 判断玩家多久没有攻击。 | `value` |
| `on_fire` | 判断玩家是否着火。 | 无 |
| `permission` | 判断玩家是否有指定权限。 | `value` |
| `players_nearby` | 判断附近玩家数量。 | `radius`、`value` |
| `target_armor_points` | 判断目标护甲值。 | `value` |
| `target_armor_type` | 判断目标穿戴的护甲类型。 | `value` |
| `target_has_enchant` | 判断目标物品或装备是否有指定附魔。 | `value` |
| `target_health` | 判断目标生命值。 | `value` |
| `target_is_blocking` | 判断目标是否正在格挡。 | 无 |
| `target_is_player` | 判断目标是否是玩家。 | 无 |
| `target_is_sprinting` | 判断目标是否正在疾跑。 | 无 |
| `target_potion_effect` | 判断目标是否拥有指定药水效果。 | `value` |
| `time` | 判断世界时间。 | `value` |
| `velocity_above` | 判断玩家速度是否高于数值。 | `value` |
| `weather` | 判断当前天气。 | `value` |

### 玩家属性条件说明

| 条件 | 作用 | 常用参数 |
| --- | --- | --- |
| `health_percent_above` | 判断玩家生命百分比是否高于数值。 | `value` |
| `health_percent_below` | 判断玩家生命百分比是否低于数值。 | `value` |
| `health_percent_between` | 判断玩家生命百分比是否在范围内。 | `min`、`max` |
| `xp_level_at_least` | 判断玩家经验等级是否至少达到数值。 | `value` |
| `xp_level_below` | 判断玩家经验等级是否低于数值。 | `value` |
| `food_percent_above` | 判断饥饿值百分比是否高于数值。 | `value` |
| `food_percent_below` | 判断饥饿值百分比是否低于数值。 | `value` |
| `saturation_above` | 判断饱和度是否高于数值。 | `value` |
| `oxygen_above` | 判断剩余氧气是否高于数值。 | `value` |
| `oxygen_below` | 判断剩余氧气是否低于数值。 | `value` |
| `absorption_above` | 判断伤害吸收生命是否高于数值。 | `value` |
| `armor_points_above` | 判断护甲值是否高于数值。 | `value` |
| `armor_toughness_above` | 判断盔甲韧性是否高于数值。 | `value` |
| `luck_above` | 判断幸运属性是否高于数值。 | `value` |
| `stat_value_above` | 判断指定统计值是否高于数值。 | `stat`、`value` |

### 玩家状态条件说明

| 条件 | 作用 | 常用参数 |
| --- | --- | --- |
| `is_on_ground` | 判断玩家是否在地面。 | 无 |
| `is_in_air` | 判断玩家是否在空中。 | 无 |
| `is_falling` | 判断玩家是否正在下落。 | 无 |
| `is_flying` | 判断玩家是否正在飞行。 | 无 |
| `is_gliding` | 判断玩家是否正在鞘翅滑翔。 | 无 |
| `is_swimming` | 判断玩家是否正在游泳。 | 无 |
| `is_sneaking` | 判断玩家是否正在潜行。 | 无 |
| `is_sprinting` | 判断玩家是否正在疾跑。 | 无 |
| `is_riding` | 判断玩家是否正在骑乘。 | 无 |
| `is_climbing` | 判断玩家是否正在攀爬。 | 无 |
| `is_submerged` | 判断玩家是否完全浸没在液体中。 | 无 |
| `is_frozen` | 判断玩家是否处于冰冻状态。 | 无 |
| `velocity_below` | 判断玩家速度是否低于数值。 | `value` |
| `fall_distance_above` | 判断玩家摔落距离是否高于数值。 | `value` |
| `movement_speed_above` | 判断玩家移动速度是否高于数值。 | `value` |
| `looking_at_block` | 判断玩家是否看向指定方块。 | `value` |

### 物品和背包条件说明

| 条件 | 作用 | 常用参数 |
| --- | --- | --- |
| `mainhand_is` | 判断主手物品类型。 | `value` |
| `offhand_is` | 判断副手物品类型。 | `value` |
| `wearing_helmet` | 判断是否佩戴头盔。 | 可选 `value` |
| `wearing_chestplate` | 判断是否穿戴胸甲。 | 可选 `value` |
| `wearing_leggings` | 判断是否穿戴护腿。 | 可选 `value` |
| `wearing_boots` | 判断是否穿戴靴子。 | 可选 `value` |
| `wearing_full_set` | 判断是否穿戴完整套装。 | 可选 `value` |
| `item_has_lore` | 判断物品是否有 lore。 | 可选 `value` |
| `item_has_name` | 判断物品是否有指定名称。 | 可选 `value` |
| `item_has_model_data` | 判断物品是否有指定 CustomModelData。 | `value` |
| `item_has_custom_data` | 判断物品是否有自定义数据。 | `key`、`value` |
| `item_durability_above` | 判断物品剩余耐久是否高于数值。 | `value` |
| `item_durability_below` | 判断物品剩余耐久是否低于数值。 | `value` |
| `item_has_vanilla_enchant` | 判断物品是否有指定原版附魔。 | `value` |
| `item_has_custom_enchant` | 判断物品是否有指定自定义附魔。 | `value` |
| `inventory_contains` | 判断背包是否包含指定物品。 | `value`、`amount` |
| `inventory_has_space` | 判断背包是否有空位。 | 无 |
| `slot_empty` | 判断指定槽位是否为空。 | `slot` |

### 目标条件说明

| 条件 | 作用 | 常用参数 |
| --- | --- | --- |
| `target_exists` | 判断当前触发上下文是否存在目标。 | 无 |
| `target_is_living` | 判断目标是否是活体实体。 | 无 |
| `target_is_monster` | 判断目标是否是怪物。 | 无 |
| `target_is_boss` | 判断目标是否是 Boss。 | 无 |
| `target_is_tamed` | 判断目标是否已被驯服。 | 无 |
| `target_is_named` | 判断目标是否有自定义名称。 | 无 |
| `target_on_fire` | 判断目标是否着火。 | 无 |
| `target_in_water` | 判断目标是否在水中。 | 无 |
| `target_health_percent_above` | 判断目标生命百分比是否高于数值。 | `value` |
| `target_health_percent_below` | 判断目标生命百分比是否低于数值。 | `value` |
| `target_distance_above` | 判断目标距离是否高于数值。 | `value` |
| `target_distance_below` | 判断目标距离是否低于数值。 | `value` |
| `target_has_permission` | 判断目标玩家是否有指定权限。 | `value` |
| `target_in_region` | 判断目标是否在指定区域。 | `value` |
| `target_is_same_world` | 判断目标是否和玩家在同一世界。 | 无 |
| `target_line_of_sight` | 判断玩家和目标之间是否有视线。 | 无 |
| `damage_above` | 判断本次伤害是否高于数值。 | `value` |
| `damage_below` | 判断本次伤害是否低于数值。 | `value` |

### 世界和区域条件说明

| 条件 | 作用 | 常用参数 |
| --- | --- | --- |
| `in_region` | 判断玩家是否在指定区域。 | `value` |
| `not_in_region` | 判断玩家是否不在指定区域。 | `value` |
| `in_claim` | 判断玩家是否在领地或声明区域内。 | 可选 `value` |
| `in_safe_zone` | 判断玩家是否在安全区。 | 无 |
| `in_pvp_zone` | 判断玩家是否在 PVP 区域。 | 无 |
| `in_light_level_above` | 判断当前位置光照是否高于数值。 | `value` |
| `in_light_level_below` | 判断当前位置光照是否低于数值。 | `value` |
| `standing_on_block` | 判断玩家脚下方块。 | `value` |
| `inside_block` | 判断玩家所在方块。 | `value` |
| `near_block` | 判断附近是否有指定方块。 | `value`、`radius` |
| `near_entity_type` | 判断附近是否有指定实体类型。 | `value`、`radius` |
| `near_player_count` | 判断附近玩家数量。 | `radius`、`value` |
| `moon_phase` | 判断月相。 | `value` |
| `season_is` | 判断季节。需要对应季节系统支持。 | `value` |

### 经济、职业、技能、任务条件说明

这些条件通常依赖对应插件或外部系统。AI 只有在用户明确使用相关系统时才应该主动使用。

| 条件 | 作用 | 常用参数 |
| --- | --- | --- |
| `balance_above` | 判断玩家经济余额是否高于数值。 | `value` |
| `balance_below` | 判断玩家经济余额是否低于数值。 | `value` |
| `points_above` | 判断玩家点券或积分是否高于数值。 | `value` |
| `points_below` | 判断玩家点券或积分是否低于数值。 | `value` |
| `has_job` | 判断玩家是否拥有指定职业。 | `value` |
| `job_level_above` | 判断玩家职业等级是否高于数值。 | `job`、`value` |
| `skill_level_above` | 判断玩家技能等级是否高于数值。 | `skill`、`value` |
| `mcmmo_level_above` | 判断玩家 mcMMO 技能等级是否高于数值。 | `skill`、`value` |
| `aura_skill_level_above` | 判断玩家 AuraSkills 技能等级是否高于数值。 | `skill`、`value` |
| `placeholder_equals` | 判断 PlaceholderAPI 变量是否等于指定值。 | `placeholder`、`value` |
| `placeholder_contains` | 判断 PlaceholderAPI 变量是否包含文本。 | `placeholder`、`value` |
| `placeholder_greater_than` | 判断 PlaceholderAPI 变量数值是否大于指定值。 | `placeholder`、`value` |
| `quest_active` | 判断玩家是否正在进行指定任务。 | `value` |
| `quest_completed` | 判断玩家是否完成指定任务。 | `value` |
| `town_role_is` | 判断玩家城镇身份。 | `value` |
| `lands_role_is` | 判断玩家 Lands 身份。 | `value` |

### 逻辑和上下文条件说明

| 条件 | 作用 | 常用参数 |
| --- | --- | --- |
| `any_of` | 子条件任意一个通过即可。 | `conditions` |
| `all_of` | 子条件全部通过。 | `conditions` |
| `none_of` | 子条件全部不通过。 | `conditions` |
| `at_least_of` | 至少指定数量的子条件通过。 | `amount`、`conditions` |
| `expression_true` | 表达式结果为真时通过。 | `value` |
| `expression_false` | 表达式结果为假时通过。 | `value` |
| `cooldown_ready` | 判断指定冷却已结束。 | `key` |
| `cooldown_active` | 判断指定冷却仍在进行。 | `key` |
| `random_weight_passed` | 按权重随机通过。 | `weight`、`total` |
| `trigger_value_above` | 判断触发上下文主数值是否高于数值。 | `value` |
| `trigger_value_below` | 判断触发上下文主数值是否低于数值。 | `value` |
| `alt_value_present` | 判断触发上下文是否有备用数值。 | 无 |
| `context_has_block` | 判断触发上下文是否包含方块。 | 无 |
| `context_has_projectile` | 判断触发上下文是否包含弹射物。 | 无 |

### 账号、权限和客户端条件说明

| 条件 | 作用 | 常用参数 |
| --- | --- | --- |
| `is_op` | 判断玩家是否是 OP。 | 无 |
| `is_online_longer_than` | 判断玩家本次在线时长是否超过数值。 | `value` |
| `joined_before` | 判断玩家是否曾经加入过服务器。 | 无 |
| `has_playtime_above` | 判断玩家总游玩时长是否高于数值。 | `value` |
| `in_permission_group` | 判断玩家是否在指定权限组。 | `value` |
| `has_scoreboard_tag` | 判断玩家是否拥有指定计分板标签。 | `value` |
| `has_advancement` | 判断玩家是否拥有指定进度。 | `value` |
| `has_recipe` | 判断玩家是否解锁指定配方。 | `value` |
| `language_is` | 判断玩家客户端语言。 | `value` |
| `client_brand_is` | 判断玩家客户端品牌。 | `value` |
| `ping_below` | 判断玩家延迟是否低于数值。 | `value` |
| `ping_above` | 判断玩家延迟是否高于数值。 | `value` |
| `worldguard_flag_allowed` | 判断 WorldGuard 区域 flag 是否允许。 | `flag` |
| `blacklist_exempt` | 判断玩家是否绕过黑名单限制。 | 可选 `value` |

### 组合条件示例

```yaml
conditions:
  - type: all_of
    conditions:
      - type: target_is_living
      - type: chance
        value: "{level} * 6"
```

```yaml
conditions:
  - type: any_of
    conditions:
      - type: in_water
      - type: weather
        value: STORM
```

## 可用效果

效果写在 `actions`，效果 ID 通常大写。AI 选择效果时先判断“触发后应该产生什么结果”。

### 直接效果说明

| 效果 | 作用 | 常用参数 |
| --- | --- | --- |
| `ADD_POTION_SELF` | 给自己添加药水效果。 | `potion`、`duration`、`amplifier` |
| `ADD_POTION_TARGET` | 给目标添加药水效果。 | `potion`、`duration`、`amplifier` |
| `BONUS_DROP` | 倍增方块掉落物数量；只在 `BLOCK_ITEM_DROP` 触发器中处理真实掉落实体。 | `multiplier` |
| `DAMAGE_ADD` | 在本次伤害上追加额外伤害。 | `value` |
| `DAMAGE_MULTIPLY` | 按倍率调整本次伤害。 | `value` |
| `DAMAGE_REDUCE` | 减少本次受到的伤害。 | `value` |
| `DODGE` | 闪避或取消本次伤害。 | 可选 `value` |
| `EXPLODE` | 在目标位置制造爆炸；没有目标时退回玩家位置。方块位置爆炸用 `BLOCK_EXPLOSION`。 | `power`、`fire`、`break-blocks` |
| `HEAL` | 治疗自己。 | `value` |
| `IGNITE_TARGET` | 点燃目标。 | `duration` |
| `LAUNCH` | 推动或击飞实体。 | `value`、`direction` |
| `LIFESTEAL` | 根据伤害或数值吸血。 | `value` |
| `LIGHTNING` | 在目标位置召唤雷电；没有目标时不生效。方块位置雷电用 `BLOCK_LIGHTNING`。 | 可选 `damage` |
| `PARTICLE` | 在目标或玩家位置播放粒子；`at` 只支持 `SELF` / `TARGET`，不支持 `BLOCK`。 | `particle`、`count`、`offset`、`at` |
| `REMOVE_POTION` | 移除药水效果。 | `potion`、`target` |
| `SMELT` | 将掉落物或方块结果自动烧炼。 | 可选 `value` |
| `SOUND` | 在目标或玩家位置播放声音；`at` 只支持 `SELF` / `TARGET`，不支持 `BLOCK`。 | `sound`、`volume`、`pitch`、`at` |
| `SPEED_BOOST` | 提升移动速度。 | `value`、`duration` |
| `THORNS` | 反弹伤害给攻击者。 | `value` |
| `TRUE_DAMAGE` | 造成无视护甲的真实伤害。 | `value` |
| `VEIN_MINE` | 连锁挖掘同类方块。 | `max-blocks` |

### 直接效果写法示例

```yaml
actions:
  - type: DAMAGE_ADD
    value: "{level} * 1.5"
```

```yaml
actions:
  - type: ADD_POTION_SELF
    potion: SPEED
    duration: "{level} * 60"
    amplifier: 0
```

```yaml
actions:
  - type: ADD_POTION_TARGET
    potion: SLOWNESS
    duration: "{level} * 40"
    amplifier: 0
```

```yaml
actions:
  - type: PARTICLE
    particle: ELECTRIC_SPARK
    count: 20
    offset: 0.35
    at: TARGET
```

```yaml
actions:
  - type: SOUND
    sound: ENTITY_PLAYER_ATTACK_SWEEP
    volume: 0.8
    pitch: 1.2
    at: SELF
```

```yaml
actions:
  - type: IGNITE_TARGET
    duration: "{level} * 30"
```

```yaml
actions:
  - type: LAUNCH
    value: "{level} * 0.25"
    direction: UP
```

```yaml
actions:
  - type: VEIN_MINE
    max-blocks: "{level} * 4"
```

## 组合效果

组合效果格式：

```text
<目标>_<操作>
```

例如 `TARGET_TRUE_DAMAGE` 表示“对目标造成真实伤害”，`SELF_HEAL` 表示“治疗自己”，`HELD_ITEM_REPAIR` 表示“修复手持物品”。

### 组合效果目标说明

| 目标 | 作用 |
| --- | --- |
| `SELF` | 效果作用于玩家自己。 |
| `TARGET` | 效果作用于当前目标。 |
| `NEARBY_ALLY` | 效果作用于附近友方。 |
| `NEARBY_ENEMY` | 效果作用于附近敌方。 |
| `HELD_ITEM` | 效果作用于玩家手持物品。 |
| `TARGET_ITEM` | 效果作用于目标相关物品。 |
| `INVENTORY` | 效果作用于玩家背包。 |
| `DROP` | 效果作用于掉落物。 |
| `BLOCK` | 效果作用于当前方块。 |
| `AREA` | 效果作用于一片区域。 |
| `SPHERE` | 效果作用于球形范围。 |
| `LINE` | 效果作用于直线范围。 |
| `PROJECTILE` | 效果作用于弹射物。 |

### 生物操作说明

| 操作 | 作用 |
| --- | --- |
| `DAMAGE_ADD` | 增加伤害。 |
| `DAMAGE_MULTIPLY` | 按倍率调整伤害。 |
| `DAMAGE_REDUCE` | 减少伤害。 |
| `TRUE_DAMAGE` | 造成真实伤害。 |
| `HEAL` | 恢复生命。 |
| `LIFESTEAL` | 吸血。 |
| `ABSORB` | 添加或增强伤害吸收。 |
| `BLEED` | 施加流血类持续伤害。 |
| `POISON` | 施加中毒效果。 |
| `WITHER` | 施加凋零效果。 |
| `BURN` | 点燃或造成燃烧。 |
| `FREEZE` | 冰冻或减缓目标。 |
| `EXECUTE` | 对低生命目标执行斩杀类效果。 |
| `REFLECT` | 反射伤害。 |
| `SHIELD` | 添加护盾或减伤保护。 |
| `ARMOR_PIERCE` | 穿透护甲。 |
| `CRIT_BOOST` | 提升暴击效果。 |
| `DAMAGE_CAP` | 限制最大伤害。 |
| `PUSH` | 推开目标。 |
| `PULL` | 拉近目标。 |
| `KNOCKUP` | 向上击飞目标。 |
| `TELEPORT` | 传送目标。 |
| `DASH` | 让目标冲刺位移。 |
| `BLINK` | 短距离闪现。 |
| `HOMING` | 让弹射物或效果追踪目标。 |
| `ROOT` | 定身。 |
| `STUN` | 眩晕或短暂控制。 |
| `SILENCE` | 沉默或限制释放类行为。 |
| `SLOW` | 减速。 |
| `SPEED` | 加速。 |
| `GRAVITY` | 改变重力或下坠效果。 |
| `GLIDE_BOOST` | 增强滑翔能力。 |
| `SAFE_FALL` | 减免或取消摔落伤害。 |
| `SWAP_POSITION` | 交换位置。 |
| `ROTATE` | 改变朝向。 |
| `VORTEX` | 产生旋涡牵引。 |

### 物品操作说明

| 操作 | 作用 |
| --- | --- |
| `REPAIR` | 修复物品耐久。 |
| `DAMAGE` | 消耗或损坏物品耐久。 |
| `DUPLICATE` | 复制物品或掉落。 |
| `CONSUME` | 消耗物品。 |
| `TRANSFORM` | 将物品转换为另一种物品。 |
| `ADD_LORE` | 添加物品 lore。 |
| `SET_NAME` | 设置物品名称。 |
| `SET_MODEL` | 设置物品模型数据。 |
| `ADD_ENCHANT` | 添加附魔。 |
| `REMOVE_ENCHANT` | 移除附魔。 |
| `TRANSFER_ENCHANT` | 转移附魔。 |
| `AUTO_SMELT` | 自动烧炼掉落或结果。 |
| `AUTO_PICKUP` | 自动拾取掉落物。 |
| `MULTIPLY_DROPS` | 倍增掉落物。 |
| `FILTER_DROPS` | 过滤掉落物。 |
| `TELEKINESIS` | 让掉落物直接进入背包。 |

### 方块和世界操作说明

| 操作 | 作用 |
| --- | --- |
| `BREAK` | 破坏方块。 |
| `PLACE` | 放置方块。 |
| `REPLACE` | 替换方块。 |
| `AGE_CROP` | 催熟作物。 |
| `REPLANT` | 自动补种。 |
| `TILL` | 耕地方块。 |
| `MELT` | 融化方块。 |
| `FREEZE_WATER` | 冻结水。 |
| `LIGHTNING` | 召唤雷电。 |
| `EXPLOSION` | 产生爆炸。 |
| `SHOCKWAVE` | 产生冲击波。 |

组合效果示例：

```yaml
actions:
  - type: TARGET_TRUE_DAMAGE
    value: "{level} * 2"
  - type: SELF_HEAL
    value: "{level}"
  - type: NEARBY_ENEMY_PUSH
    radius: 4
    value: "{level} * 0.35"
  - type: HELD_ITEM_REPAIR
    value: "{level} * 2"
  - type: AREA_EXPLOSION
    radius: 3
    power: "{level} * 0.4"
```

组合效果要保守使用。范围越大，冷却越长。

## 常见附魔模板

### 近战增伤附魔

```yaml
id: blade_fury
enabled: true
curse: false
max-level: 5
rarity: radiant
group: offensive
category: melee

applicable-items:
  - SWORD
  - AXE

conflicts: []

obtain:
  enchanting-table: true
  anvil: true
  villager-trade: true

enchanting-table-weight: 12
villager-trade-price-range:
  - 16
  - 38

codex-pools:
  radiant: 12
  aureate: 5

effects:
  - trigger: MELEE_ATTACK
    cooldown: 50
    conditions:
      - type: chance
        value: "{level} * 8"
      - type: target_is_living
    actions:
      - type: DAMAGE_ADD
        value: "{level} * 1.4"
      - type: PARTICLE
        particle: CRIT
        count: 16
        offset: 0.25
        at: TARGET
```

语言：

```yaml
blade_fury:
  name: "刃怒"
  description:
    - "近战攻击时有概率造成额外伤害。"
```

```yaml
blade_fury:
  name: "Blade Fury"
  description:
    - "Melee attacks have a chance to deal bonus damage."
```

### 防具减伤附魔

```yaml
id: guardian_shell
enabled: true
curse: false
max-level: 4
rarity: moonlit
group: defensive
category: armor

applicable-items:
  - CHESTPLATE
  - LEGGINGS

conflicts: []

obtain:
  enchanting-table: true
  anvil: true
  villager-trade: true

enchanting-table-weight: 14
villager-trade-price-range:
  - 14
  - 34

codex-pools:
  moonlit: 14
  radiant: 6

effects:
  - trigger: TAKE_ENTITY_DAMAGE
    cooldown: 100
    conditions:
      - type: chance
        value: "{level} * 7"
    actions:
      - type: DAMAGE_REDUCE
        value: "{level} * 1.2"
      - type: SOUND
        sound: ITEM_SHIELD_BLOCK
        volume: 0.7
        pitch: 1.1
        at: SELF
```

### 工具掉落倍增附魔

```yaml
id: drop_surge
enabled: true
curse: false
max-level: 3
rarity: radiant
group: mining
category: tools

applicable-items:
  - PICKAXE

conflicts: []

obtain:
  enchanting-table: true
  anvil: true
  villager-trade: true

enchanting-table-weight: 10
villager-trade-price-range:
  - 18
  - 42

codex-pools:
  radiant: 10
  aureate: 4

effects:
  - trigger: BLOCK_ITEM_DROP
    cooldown: 40
    conditions:
      - type: chance
        value: "{level} * 9"
    actions:
      - type: BONUS_DROP
        multiplier: "1 + {level} * 0.5"
      - type: PARTICLE
        particle: HAPPY_VILLAGER
        count: 10
        offset: 0.25
        at: SELF
```

### 诅咒附魔

```yaml
id: brittle_curse
enabled: true
curse: true
max-level: 3
rarity: moonlit
group: defensive
category: universal

applicable-items:
  - SWORD
  - AXE
  - PICKAXE
  - SHOVEL
  - HOE
  - BOW
  - CROSSBOW
  - TRIDENT
  - HELMET
  - CHESTPLATE
  - LEGGINGS
  - BOOTS

conflicts: []

obtain:
  enchanting-table: true
  anvil: true
  villager-trade: false

enchanting-table-weight: 6

codex-pools:
  moonlit: 6

effects:
  - trigger: DAMAGE_ITEM
    cooldown: 20
    conditions:
      - type: chance
        value: "{level} * 10"
    actions:
      - type: HELD_ITEM_DAMAGE
        value: "{level}"
```

## 当前版本必须注意的附魔描述规则

附魔书、已附魔物品和图鉴里的说明优先使用语言文件 `description`。`description` 可以写动态占位符，插件会按当前附魔等级和 `effects` 里的实际参数计算后显示；如果语言描述为空，才会回退到根据 `effects` 自动生成。

语言描述必须写成玩家能直接看懂的效果，并且需要显示等级缩放数值时优先使用占位符，不要手动写死某个等级的数值：

```yaml
resilience:
  name: "韧性"
  description:
    - "受到攻击时有 {chance}% 概率恢复 {amount} 点生命。"
    - "冷却 {cooldown_seconds} 秒，范围 {range} 格，持续 {seconds} 秒。"
```

真正影响占位符数值的是效果块，例如：

```yaml
effects:
  - trigger: TAKE_DAMAGE
    cooldown: 160
    conditions:
      - type: chance
        value: "{level} * 2 + 8"
    actions:
      - type: HEAL
        value: "{level} * 0.5"
      - type: ADD_POTION_SELF
        potion: RESISTANCE
        amplifier: "{level} - 1"
        duration: "{level} * 30 + 10"
        range: "{level} + 3"
```

上面的语言描述在 5 级会被插件渲染成类似：

```text
韧性 V
    - 受到攻击时有 18% 概率恢复 2.5 点生命。
    - 冷却 8 秒，范围 8 格，持续 8 秒。
```

`description` 支持的占位符：

| 占位符 | 来源 |
| --- | --- |
| `{level}` | 当前附魔等级。 |
| `{cooldown}` / `{cooldown_ticks}` / `{cooldown-ticks}` | 效果块根级 `cooldown` 的原始 tick 数。 |
| `{cooldown_seconds}` / `{cooldown-seconds}` | 效果块根级 `cooldown / 20` 后的秒数。 |
| `{chance}` | `chance` 条件的 `value` 百分比。 |
| `{value}` | 动作的根级 `value`。 |
| `{duration}` | 动作参数 `duration` 的原始 tick 数。 |
| `{seconds}` | 动作参数 `duration / 20` 后的秒数；`IGNITE_TARGET` 会从动作 `value` 生成。 |
| `{amount}` | 伤害、治疗、修复等动作数量；`DAMAGE_ADD`、`TRUE_DAMAGE`、`HEAL`、`HELD_ITEM_REPAIR`、未知动作会从动作 `value` 生成。 |
| `{damage}` | 伤害类动作数值；`DAMAGE_ADD`、`TRUE_DAMAGE`、未知动作会从动作 `value` 生成。 |
| `{percent}` | 百分比数值；`DAMAGE_REDUCE`、`LIFESTEAL`、`THORNS` 来自动作 `value`，`HEAL` 会按 `value * 5` 换算为生命百分比。 |
| `{multiplier}` | 倍率数值；可来自动作参数 `multiplier`，`DAMAGE_MULTIPLY` / `BONUS_DROP` 也可从动作 `value` 生成。 |
| `{power}` | 强度数值；可来自动作参数 `power`，`LAUNCH` 也会从动作 `value` 生成。 |
| `{amplifier}` | 药水或速度等级显示值；`ADD_POTION_SELF` / `ADD_POTION_TARGET` 的 `amplifier: 0` 显示为 1 级，`SPEED_BOOST` 会从动作 `value + 1` 生成。 |
| `{potion}` | 药水类型参数 `potion` 的原文。 |
| `{radius}` | 动作或条件参数 `radius`；如果是 `max-blocks`，也会生成同值 `radius`。 |
| `{range}` | 动作或条件参数 `range`，常用于直线距离、搜索范围、影响范围等自定义范围数值。 |
| `{max-blocks}` / `{max_blocks}` / `{blocks}` | 连锁挖掘数量。 |
| `{<参数名>}` | 任意动作参数或条件额外参数都可以按同名占位符读取，例如 `distance`、`key`、`weight`、`total`、`required`、`material`、`permission`、`range`。 |

同一个附魔里如果有多个同类数值，插件会按 `effects` 中从上到下、每个动作从前到后的出现顺序生成编号占位符。未编号占位符永远等同于第一个值：

| 写法 | 含义 |
| --- | --- |
| `{chance}` / `{chance1}` | 第一个 `chance` 条件。 |
| `{chance2}` | 第二个 `chance` 条件。 |
| `{chance3}` | 第三个 `chance` 条件。 |
| `{value}` / `{value1}` | 第一个动作 `value`。 |
| `{value2}` | 第二个动作 `value`。 |
| `{seconds}` / `{seconds1}` | 第一个 `duration` 换算后的秒数。 |
| `{seconds2}` | 第二个 `duration` 换算后的秒数。 |
| `{cooldown_seconds}` / `{cooldown_seconds1}` | 第一个带冷却效果块的冷却秒数。 |
| `{cooldown_seconds2}` | 第二个带冷却效果块的冷却秒数。 |
| `{amount2}`、`{damage2}`、`{radius2}`、`{range2}`、`{multiplier2}`、`{amplifier2}`、`{potion2}` | 同类数值的第二次出现，规则同上。 |

例如一个附魔有三个独立概率时，语言描述应该逐条引用对应编号，不要把所有文字都写成 `{chance}`：

```yaml
sacred_blessing:
  name: "圣光祝福"
  description:
    - "跳跃时有 {chance}% 概率获得 {seconds} 秒生命恢复 I。"
    - "受到攻击时，有 {chance2}% 概率获得瞬间治疗 II。"
    - "受到攻击时，有 {chance3}% 概率获得 {seconds2} 秒抗性提升 I。"
```

如果某一条效果没有 `duration`，它不会生成 `{seconds}` 编号；编号只统计实际存在并能计算的同类数值。AI 必须让描述里的编号和 `effects` 里实际出现的顺序一致。

效果块根级 `cooldown` 也可以用于描述：`{cooldown}` 显示 tick，`{cooldown_seconds}` 显示秒。动作或条件里的额外参数也可以用同名占位符读取，例如 `range: "{level} + 3"` 可以在描述里写 `{range}`。带连字符的参数同时支持下划线写法，例如 `{max-blocks}` 和 `{max_blocks}`、`{cooldown-seconds}` 和 `{cooldown_seconds}` 都可以。未知占位符会原样显示，所以 AI 不要写本文没定义、配置里也不存在的占位符。

公式只支持 `{level}`、数字、空格、括号和 `+ - * /` 四则运算；不要写 `floor()`、`ceil()`、`min()`、`max()` 这类函数。

AI 写配置时要优先使用能表达实际数值的参数名：

| 效果 | 推荐参数 | 说明 |
| --- | --- | --- |
| `HEAL` | `value` | 恢复生命值点数；如果描述要写百分比，可用 `{percent}`，插件按 20 点最大生命 = 100% 换算。 |
| `ADD_POTION_SELF` / `ADD_POTION_TARGET` | `potion`、`amplifier`、`duration` | `duration` 是 tick，显示时会转成秒；`amplifier: 0` 表示 1 级药水。 |
| `BONUS_DROP` | `multiplier` | 掉落倍数必须写 `multiplier`，并搭配 `BLOCK_ITEM_DROP` 使用；不要写在 `MINE_BLOCK` / `MINE_ORE` 这类方块破坏触发里。 |
| `VEIN_MINE` | `max-blocks` | 连锁挖掘数量必须写 `max-blocks`，不要写成没有实际意义的范围。 |
| `DAMAGE_ADD` | `amount` 或 `value` | 额外伤害。 |
| `DAMAGE_MULTIPLY` | `multiplier` 或 `value` | 伤害倍率。 |
| `DAMAGE_REDUCE` | `percent` 或 `value` | 减伤百分比。 |
| `SPEED_BOOST` | `amplifier`、`duration` | 移速提升等级和持续时间。 |

如果一个附魔是概率触发，必须写 `chance` 条件，并让概率值可以被计算：

```yaml
conditions:
  - type: chance
    value: "{level} * 5 + 5"
```

不要只在 `description` 写“有概率”，但在 `effects.conditions` 里不写 `chance`。那样插件无法在附魔书 lore 中自动显示概率。

## 语言与客户端语言规则

AI 至少要写 `zh_cn` 和 `en_us`。当前插件还内置了 `zh_tw`、`ja_jp`、`ko_kr` 语言目录；如果用户要求完整多语言，新增附魔也应该补这些目录的 `enchantments.yml` 条目。

客户端语言处理规则：

- 玩家客户端语言存在对应目录时，优先使用该语言。
- 客户端语言不存在时，回退到默认语言。
- 某个附魔 key 在玩家语言中缺失时，应回退到默认语言或附魔 ID。

所以 AI 写新附魔时，不能只写一个语言目录。至少保证：

```text
lang/zh_cn/enchantments.yml
lang/en_us/enchantments.yml
```

可选补齐：

```text
lang/zh_tw/enchantments.yml
lang/ja_jp/enchantments.yml
lang/ko_kr/enchantments.yml
```

## 配置文件路径提示

AI 只需要面向附魔配置和语言条目输出内容，通常使用这些路径：

```text
src/main/resources/enchantments/   附魔配置
src/main/resources/lang/           多语言文本
src/main/resources/gui/            GUI 布局配置
src/main/resources/items/          自定义物品配置
```

不要输出 Java 源码修改、工程流程、运维脚本等内容，除非用户明确要求的任务已经超出“写附魔配置”范围。

## 生成前检查清单

AI 每次输出前检查：

1. 文件路径分类是否正确。
2. 附魔 `id`、文件名、语言 key 是否一致。
3. `rarity` 是否是可用品质。
4. `group` 是否是可用附魔组。
5. `category` 是否是可用分类。
6. `applicable-items` 是否适合这个附魔。
7. `conflicts` 中每个附魔 ID 是否真实存在；不确定时是否已经改成 `conflicts: []`。
8. 如果村民交易开启或未显式关闭，`villager-trade-price-range` 是否是两个整数列表，而不是 `min/max` 配置段；如果 `obtain.villager-trade: false`，是否已省略或确认该字段会被忽略。
9. 每个效果块是否有 `trigger`。
10. 每个效果块是否有至少一个 `actions`。
11. 触发器、条件、效果是否都来自本文列表。
12. 概率、伤害、范围、冷却是否合理。
13. 是否同时写了中文和英文语言条目。
14. 描述是否是玩家能看懂的实际效果。
15. 如果附魔有概率，是否在 `conditions` 里写了 `chance`，而不是只写在文字描述里。
16. 附魔书 lore 需要显示的数值，是否都能从 `effects` 参数和 `{level}` 公式计算出来。
17. 掉落倍数是否使用 `multiplier`，连锁挖掘数量是否使用 `max-blocks`。
18. 输出内容是否只围绕附魔配置和语言条目，没有混入源码修改或无关工程流程。

## 给用户输出时的格式

如果用户要配置，按这个格式输出：

```text
文件：enchantments/melee/example_enchant.yml
```

```yaml
# YAML 内容
```

```text
文件：lang/zh_cn/enchantments.yml
```

```yaml
# 中文语言片段
```

```text
文件：lang/en_us/enchantments.yml
```

```yaml
# 英文语言片段
```
