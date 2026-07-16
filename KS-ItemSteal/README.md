# KS-ItemSteal v1.0.2 —— ks-Series 通用夺取/归还兵刃辅助

> [English](README.en.md) | 中文

**ks-Series** 插件群之一 —— 无损夺取玩家物品并在夺取者死亡时归还。
后缀匹配(剑/斧/盾等)、无损保存(附魔、命名、NBT 全保留)、PvE 与 PvP 通用。

## 核心模型
- 「谁(thief)夺走了谁(owner)的什么」全部记在 thief 名下。
- thief **死亡**时(怪物或玩家都行),它名下所有物品自动归还原主。
  - 对应玩法:Boss 夺走你的武器 → 击杀 Boss 取回;玩家用窃魂之弓夺走你的武器 → 你把他杀了取回。
- 数据写盘持久化,重启/崩溃不丢装备。原主离线则上线时补发。

## 编译
需要 JDK 21 + Maven:
```
cd ks_series/KS-ItemSteal
mvn package
```
产物在 `target/KS-ItemSteal.jar`,丢进服务器 `plugins/` 重启即可。
(IDEA 也可以直接打开 pom.xml 导入后 Build Artifact。)

## 配置 `plugins/KS-ItemSteal/config.yml`
```yaml
suffixes:
  - _SWORD
  - _AXE      # 用 _AXE 不会误伤镐子
  - SHIELD
  - TRIDENT
```
改完 `/itemsteal reload`。

## Boss 用法(MythicMobs)
把 `通用缴械.yml` 放进 `MythicMobs/Skills/`,任意 Boss 加一行:
```yaml
  Skills:
  - skill{s=通用缴械_预警} @self ~onTimer:360
  - command{c="itemsteal return <caster.uuid>"} @self ~onDespawn   # 防重置丢装备
```
预警里有 3 秒倒计时提示,玩家可退出半径保命。归还不用写——Boss 一死插件自动还。

## 玩家用法(窃魂之弓)
```
/itemsteal givebow <玩家>
```
发一把一次性弓 + 1 支箭。射中玩家 → 夺走其剑/斧/盾;射出后弓碎裂(一次性)。
被夺者把射手杀掉 → 物品归还。
> 想做成副本奖励/合成品:用 MythicMobs 技能 `command{c="itemsteal givebow <target.name>"}` 在掉落或交互时发放即可。

## 管理命令
- `/itemsteal return <thiefUUID>` 强制归还某 thief 名下全部物品(应急)
- `/itemsteal reload` 重载后缀
- `/itemsteal steal <thiefUUID> <victimUUID>` (一般由 MythicMobs 后台调用)

## 说明 / 可调点
- `_AXE` 这种写法专门避开了镐子(`_PICKAXE` 不会被匹配)。
- 默认夺取主背包+快捷栏+副手,不动盔甲。
- 窃魂之弓默认**不造成伤害**(纯夺取);想让它照常打伤害,删掉 `ItemSteal.java` 里 `e.setDamage(0);` 那行。
- 归还默认还给原主;想改成"掉在夺取者尸体处让击杀者捡",把 `giveBack` 改成在 thief 死亡位置 drop 即可。
