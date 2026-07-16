# ks-Inherit v1.0.0 —— ks-Series 跨版本物品继承插件

> [English](README.en.md) | 中文

**ks-Series** 插件群之一 —— 从 1.20.6 升级到 1.21.11 时无缝保留玩家物品。
GUI 箱子存储 → 完整 NBT 序列化 → SQLite 持久化 → Web 审阅 → OpenInv 发放。

## 核心流程

```
1.20.6 服务器                  1.21.11 服务器
  /inherit open                  Web 管理面板
  ↓                             ↓
  放入物品（禁止潜影盒）         管理员审阅物品详情
  ↓                             ↓
  关闭 GUI 自动保存             批准 / 拒绝
  ↓                             ↓
  所有 NBT 完整保留             发放到玩家背包 (OpenInv)
```

## 编译

需要 JDK 21 + Maven:
```bash
cd ks_series/ks-Inherit
mvn clean package
```
产物在 `target/ks-Inherit-1.0.0.jar`，丢进 `plugins/` 重启即用。

## 配置 `plugins/ks-Inherit/config.yml`

```yaml
# 默认普通玩家可用槽位数（管理员不受此限制）
default-slots: 9

# GUI 行数（1-6 行，每行 9 格）
gui-rows: 6

# 是否允许存入潜影盒（默认 false，防止无限嵌套）
allow-shulker-boxes: false
```

改完重启插件生效 (`plugman reload ks-Inherit`)。

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/inherit` | — | 显示插件版本和帮助 |
| `/inherit open` | `ksinherit.use` (默认所有人) | 打开物品保存 GUI |
| `/inherit slots <玩家名> <数量>` | `ksinherit.admin` (默认 OP) | 设置玩家可用槽位数 (1-54) |

### 示例
```mcfunction
# 玩家：打开 GUI 存物品
/inherit open

# 管理员：给玩家 Notch 分配 18 格
/inherit slots Notch 18

# 管理员：给玩家 Herobrine 分配 3 行
/inherit slots Herobrine 27
```

## 权限

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `ksinherit.use` | 所有人 | 使用 `/inherit open` |
| `ksinherit.admin` | OP | 使用 `/inherit slots`、查看全部 GUI 槽位 |

## GUI 界面说明

```
┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │ 9 │  ← 可用槽位（默认9格）
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│🔒│🔒│🔒│🔒│🔒│🔒│🔒│🔒│🔒│  ← 锁定格（灰色玻璃板）
│   ...更多锁定格...  │🔒│🔒│🔒│🔒│
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │   │   │   │   │   │   │🗑│💾│✖│  ← 操作栏
└───┴───┴───┴───┴───┴───┴───┴───┴───┘
                                  │  │  └─ ✖ 关闭（自动保存）
                                  │  └─ 💾 保存
                                  └─ 🗑 清空全部
```

- **放入物品**：把物品从背包拖到可用槽位（支持 Shift 点击）
- **取出物品**：把物品拖回背包
- **关闭 GUI**：自动保存所有槽位
- **🗑 清空**：删除该玩家的全部存储物品（不可撤销）
- **💾 保存**：手动触发保存
- **禁止操作**：潜影盒（4 种放入方式全部拦截）、锁定格（无法点击/拖拽）

## Web 管理面板（仅 1.21.11 + ks-core）

访问 `http://服务器IP:8123/ks-Inherit/`

### 功能
- **物品列表**：按状态筛选（待审/已批准/已拒绝/已发放）、按玩家 UUID 搜索
- **物品详情**：查看物品类型、名称、Lore、附魔信息
- **批量操作**：勾选多个物品 → 一键批准/拒绝/发放
- **发放**：调用 OpenInv API 把物品放入玩家背包（支持离线玩家）

### Web API

Base URL: `http://127.0.0.1:8123/ks-Inherit`

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| GET | `/` 或 `/admin` | — | 管理页面 SPA |
| GET | `/api/items?status=PENDING` | — | 查询待审物品 |
| GET | `/api/items?playerUuid=X` | — | 查询指定玩家物品 |
| GET | `/api/items` | — | 查询全部物品（最多 500 条） |
| POST | `/api/approve` | — | `{"id":1}` 批准物品 |
| POST | `/api/reject` | — | `{"id":1}` 拒绝物品 |
| POST | `/api/deliver` | — | `{"id":1}` 发放物品到玩家背包 |

### curl 测试示例

```bash
# 查看所有待审物品
curl "http://127.0.0.1:8123/ks-Inherit/api/items?status=PENDING"

# 批准物品 #1
curl -X POST "http://127.0.0.1:8123/ks-Inherit/api/approve" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"reviewerUuid":"admin","reviewerName":"管理员"}'

# 发放物品 #1 到玩家背包
curl -X POST "http://127.0.0.1:8123/ks-Inherit/api/deliver" \
  -H "Content-Type: application/json" \
  -d '{"id":1}'
```

## 数据库

SQLite 文件：`plugins/ks-Inherit/items.db`

### 物品表 `ks_inherit_items`

| 列名 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 自增 ID |
| player_uuid | TEXT | 玩家 UUID |
| player_name | TEXT | 玩家名 |
| slot | INTEGER | GUI 槽位号 |
| item_json | TEXT | 完整物品 JSON（`ItemStack.serialize()` 输出） |
| item_type | TEXT | 物品类型（如 `DIAMOND_SWORD`） |
| item_name | TEXT | 自定义名称 |
| item_lore | TEXT | Lore（JSON 数组） |
| enchantments | TEXT | 附魔（JSON 对象，key=附魔名，value=等级） |
| status | TEXT | PENDING / APPROVED / REJECTED / DELIVERED |
| submitted_at | INTEGER | 提交时间（Unix 秒） |
| reviewed_by | TEXT | 审阅者 UUID |
| reviewed_at | INTEGER | 审阅时间 |
| delivered_at | INTEGER | 发放时间 |

### 配置表 `ks_inherit_config`

| 列名 | 类型 | 说明 |
|------|------|------|
| key | TEXT PK | 键（如 `slot_<UUID>`） |
| value | TEXT | 值 |

## 潜影盒防护

插件从 **4 个入口** 完全阻止玩家将潜影盒存入 GUI：

| 入口 | 拦截方式 |
|------|----------|
| 左键/右键放入 | `InventoryClickEvent` 光标检测 |
| Shift + 点击 | `InventoryClickEvent` 当前物品检测 |
| 数字键切换 | `InventoryClickEvent` Hotbar 检测 |
| 拖拽 | `InventoryDragEvent` 旧光标检测 |

检测方法：`Material.name().contains("SHULKER_BOX")`，覆盖所有染色潜影盒。

## 物品序列化

```
ItemStack（内存）
  ↓ item.serialize()
Map<String, Object>（标准 Bukkit 序列化，含所有 NBT）
  ↓ Gson.toJson()
String JSON（UTF-8 文本）
  ↓ INSERT INTO ks_inherit_items
SQLite TEXT 列
```

反序列化：
```
SQLite → Gson.fromJson() → Map<String, Object> → ItemStack.deserialize()
```

此方法**保留全部数据**：自定义名称、Lore、附魔、耐久度、AttributeModifiers、CustomModelData、自定义 NBT 标签等。

## 1.20.6 vs 1.21.11

| 特性 | 1.20.6 | 1.21.11 |
|------|--------|---------|
| `/inherit` 命令 | ✅ | ✅ |
| GUI 箱子 | ✅ | ✅ |
| 物品保存 | ✅ | ✅ |
| 槽位限制 | ✅ | ✅ |
| Web 管理面板 | ❌ 不需要 | ✅ |
| Web API | ❌ 自动跳过 | ✅ 反射注册 |
| OpenInv 发放 | ❌ | ✅ |
| ks-core 集成 | ❌ 自动降级 | ✅ |

1.20.6 上加载时插件会输出：
```
[ks-Inherit] 未检测到 ks-core，跳过 Web 路由注册（1.20.6 模式）
```

1.21.11 上加载时插件会输出：
```
[ks-Inherit] Step 1: ks-core found, class=org.kscore.KsCore
[ks-Inherit] Step 2: bridge obtained, class=org.kscore.KsPluginBridge
[ks-Inherit] Step 3: 已注册 Web 路由 /ks-Inherit（1.21.11 模式）
```

## 注意事项

1. **同名 JAR 不会覆盖**：部署前先删除旧的 `ks-Inherit-*.jar`
2. **槽位数据库绑定 UUID**：改名不影响数据
3. **1.20.6 → 1.21.11 迁移**：把 `plugins/ks-Inherit/items.db` 复制到新服务器即可
4. **OpenInv 必需**：离线发放依赖 OpenInv 5.x，如未安装则只能在线发放
5. **物品兼容性**：跨大版本物品可能存在材质/Meta 差异，由 Minecraft 自行处理
