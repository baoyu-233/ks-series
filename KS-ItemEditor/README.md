# KS-ItemEditor v1.4.0

**ks-Series 物品编辑器插件** —— GUI + 网页双模式编辑物品名称、Lore、附魔、属性修饰符等全部属性。

**支持平台**: Paper 1.21.11+ / Leaves  
**Java**: 21+  
**构建**: Maven  
**系列**: [ks-Series](../README.md) 插件群之一

---

## 快速开始

1. 将 `KS-ItemEditor-1.4.0.jar` 放入 `plugins/` 目录
2. 启动/重启服务器（或放入后执行 `/itemedit reload` 热加载）
3. 管理员输入 `/itemedit` 或 `/itemedit web`
4. 玩家输入 `/design` 或 `/refine`

---

## 功能矩阵

| 功能 | 管理员 | 玩家 |
|------|:---:|:---:|
| **GUI 物品编辑器** (`/itemedit`) | ✅ | — |
| **网页设计器** (`/itemedit web`) | ✅ | — |
| **网页设计器** (`/design`) | — | ✅ |
| 名称 / Lore 编辑 | ✅ | ✅ |
| 原版附魔（突破上限 32767） | ✅ | ✅（受限 10） |
| FotiaEnchantment 自定义附魔 | ✅ | — |
| ItemsAdder 模型套用 | ✅ | — |
| 物品属性修饰符 | ✅ | — |
| 不可破坏 / 发光开关 | ✅ | ✅ |
| 模板码生成 / 加载 / 分享 | ✅ | ✅ |
| 物品预览（名称+Lore 实时渲染）| ✅ | ✅ |
| 特殊符号插入 | ✅ | ✅ |
| 选区格式化工具栏 | ✅ | ✅ |
| **武器精炼** (`/refine`) | — | ✅ |

---

## 命令

### `/itemedit` — 管理员物品编辑器
- **权限**: `itemedit.admin`（默认 OP）
- **别名**: `/ie`, `/itemeditor`

```
/itemedit          打开 GUI 编辑器（需手持物品）
/itemedit web      打开管理员网页编辑器（附魔上限 32767，含 FE/IA/属性修饰符）
/itemedit reload   重载配置文件（热加载，支持控制台，无需重启服务器）
```

### `/design` — 网页物品设计器
- **权限**: `itemedit.design`（默认所有玩家）
- **别名**: `/designer`, `/wedit`

```
/design                获取网页编辑器链接
/design load <模板码>  加载模板到手持物品
```

### `/refine` — 玩家武器精炼
- **权限**: `itemedit.refine`（默认所有玩家）
- **别名**: `/ref`

```
/refine  消耗兑换券对武器进行精炼
```

---

## 权限

```yaml
itemedit.admin:       # 管理员物品编辑器（默认 OP）
  default: op

itemedit.design:      # 网页物品设计器（默认所有玩家）
  default: true

itemedit.refine:      # 玩家武器精炼（默认所有玩家）
  default: true
```

---

## 网页编辑器功能详解

### 👁️ 物品预览面板
位于"物品配置"区上方，**实时渲染**物品名称和每行 Lore 的实际显示效果：
- 支持全部 `&` 颜色码（`&0`~`&f`共 16 色）
- 支持格式码：`&l` 粗体、`&o` 斜体、`&n` 下划线、`&m` 删除线、`&k` 乱码
- 支持 Unicode 字符、Emoji 表情
- 名称与 Lore 用虚线分隔，居中显示

### 🎭 特殊符号面板
左侧边栏底部，点击展开，6 个分类：
- **常用**：★☆✦♥♦♣♪♫☀⚔⛏
- **箭头**：→←↑↓↔⇒➔⬆⬇
- **装饰**：▸▪▫■□◆◇◎●○
- **数学**：±∞√∑∫∏≈≠≤≥
- **货币/格式**：€£¥§«»…¹²³½¼
- **表情**：😀😎💀👑🗡️🛡️🔥💧❄️⭐💎

点击任意符号自动插入到**当前聚焦的输入框**光标位置。

### ✏️ 选区格式化工具栏
点击名称/Lore 输入框后自动出现工具栏：
- 16 个颜色小方块（`&0` ~ `&f`）
- 5 个格式按钮：**B** 粗体、*I* 斜体、<u>U</u> 下划线、<s>S</s> 删除线、O 乱码
- **重置**按钮 `&r`
- **无选区** → 直接插入格式码
- **有选区** → 格式码包裹选中文本，末尾自动加 `&r` 恢复

### 💪 物品属性修饰符（仅管理员）
27 种原版属性（适配 1.21+ 属性 ID），支持正负小数：
- **战斗**：攻击伤害/速度/击退
- **防御**：护甲/韧性/击退抗性/爆炸击退抗性
- **生命**：最大生命/吸收/摔落伤害倍率/安全摔落距离/燃烧时间
- **移动**：移动速度/飞行速度/跳跃强度/台阶高度/重力/水中移动效率
- **交互**：方块/实体交互范围/方块破坏速度
- **其他**：体型缩放/幸运/跟随范围
- **玩家特有**：挖掘效率/潜行速度/水下挖掘速度/横扫伤害比率

每行可独立设置属性类型、运算方式（加法/基础倍率/总倍率）、数值、生效插槽。

---

## 模板系统

### 模板码格式
- 玩家模板：`pl-xxxxxxxx`（8 位字母数字）
- 管理员模板：`op-xxxxxxxx`（8 位字母数字）
- 通过前缀即可一眼区分模板类型

### 模板管理功能
- **保存**：可选填写模板名称（如"神剑模板"），便于识别
- **导入**：输入任意模板码（含 `pl-`/`op-` 前缀），加载物品到编辑器
- **预览**：导入后可查看所有配置，修改并**保存为新模板**（不覆盖原模板）
- **我的模板**：列表显示名称、物品材质、创建时间、PL/OP 标签
- **加载**：点击加载按钮，物品数据填入编辑器
- **删除**：管理员可删除任意模板

### 管理员 / 玩家数据隔离

| 数据字段 | 管理员模板 | 玩家模板 |
|---------|:---:|:---:|
| 名称 / Lore / 原版附魔 | ✅ | ✅ |
| 不可破坏 / 发光 | ✅ | ✅ |
| FotiaEnchantment 附魔 | ✅ | ❌ 自动剥离 |
| ItemsAdder 模型 | ✅ | ❌ 自动剥离 |
| 物品属性修饰符 | ✅ | ❌ 自动剥离 |

> 前后端双重防护——非管理员保存模板时，FE 附魔/IA 模型/属性修饰符自动移除；管理员加载玩家模板预览时也看不到这些字段。

---

## 管理员 GUI（`/itemedit`）

```
┌───────────────────────────────────────────┐
│ [📋加载模板] [  预览  ] [📤导出模板]  ...  │
│ [ 修改名称 ] [  Lore ] [ 附魔  ] [ 模型 ] │
│ [不可破坏  ] [  关闭  ] [发光效果 ]       │
└───────────────────────────────────────────┘
```

- **修改名称** — 聊天框输入，支持 `&` 和 MiniMessage
- **修改 Lore** — 逐行增删改、上下移动
- **修改附魔** — 翻页浏览原版 + FE 附魔，左键+1、右键-1、Shift+左键+5、Shift+右键移除
- **ItemsAdder 套模型** — 左键保留本体（仅套外观）、Shift+左键替换
- **📋 加载模板** — 输入模板码加载到 GUI 预览
- **📤 导出管理模板** — 生成 `op-` 前缀模板
- **📤 导出玩家模板** — 生成 `pl-` 前缀模板

---

## 玩家精炼 GUI（`/refine`）

```
┌───────────────────────────────────────────┐
│ [兑换券] [名称] [Lore] [预览] [📋][📤]    │
│ [ 附魔1 ] ... [ 附魔9 ]                  │
│ [不可破坏] [发光] [确认] ... [取消]       │
└───────────────────────────────────────────┘
```

- 物品取出背包锁定编辑，离线/关 GUI 自动归还
- 附魔自动冲突处理
- **确认** → 消耗兑换券 + 写入物品

---

## 配置文件 (`config.yml`)

```yaml
# 网页设计器 - 内置 HTTP 服务
web-server:
  enabled: true              # 启用/禁用
  port: 8123                 # HTTP 端口
  bind-address: "127.0.0.1"  # 监听地址（本机=127.0.0.1，公网=0.0.0.0）
  public-address: ""         # ★ 对外显示地址（域名/IP），留空回退到 bind-address
  token-timeout-seconds: 600 # 会话有效期（秒）

# FE 附魔兼容性
fe-enchantments:
  admin-max-enchant-level: 32767  # 管理员原版附魔上限
  ignore-vanilla-conflicts: false # 允许互斥原版附魔共存
  ignore-fe-conflicts: false      # 忽略 FE 内部冲突检查
  show-compatibility-warnings: true # 显示冲突 ⚠️ 警告

# 玩家精炼系统
refine:
  voucher:
    mode: MYTHICMOBS         # MYTHICMOBS / MATERIAL
    mythicmobs-id: "RefineToken"
    material: "PAPER"
    name-contains: "精炼券"
    amount: 1
  max-enchant-level: 10      # 玩家精炼最高附魔等级
```

---

## 依赖插件（软依赖）

| 插件 | 用途 | 缺失时影响 |
|------|------|-----------|
| **ItemsAdder** | 自定义模型套用 | 套模型功能不可用 |
| **FotiaEnchantment** | 自定义附魔系统 | FE 附魔编辑不可用 |
| **MythicMobs** | 兑换券检测（MM 模式） | 精炼券仅支持 Material 匹配 |

> ★ v1.3.1 起，FE 和 MM 均通过**纯反射**调用，ItemEditor 不依赖任何特定版本的 jar 编译。更新 FE/MM 后无需重编译 ItemEditor。

---

## 技术要点

### 文本输入
使用**临时聊天监听**（`ChatInput`），以 `HIGHEST` 优先级捕获原始消息并取消广播，输入完成后自动注销。完全避开 Paper AnvilView API 的兼容问题。

### FotiaEnchantment 兼容
- **100% 反射调用** — 无编译期依赖，FE 任意版本均可，更新无需重编译 ItemEditor
- FE 通过 PacketEvents + 事件监听器管理 Lore 生成/合并
- ItemEditor 只写入物品 NBT，不主动调用 `applyGeneratedLore()`，避免 FE 生成行被永久烘焙
- 附魔读写通过 FE 的 `PDCManager` API（反射）
- 中文名称/描述通过 FE 的 `LanguageManager` API（反射）
- FE 附魔**不可突破自然等级上限**（PDCManager 内部强制），达到上限显示「已达上限」徽章

### MythicMobs 兼容
- **100% 反射调用** — 无编译期依赖，MM 任意版本均可，更新无需重编译 ItemEditor

### ItemsAdder 套模型
- **保留本体**：复制 `item_model` 组件（1.21.4+）和 `custom_model_data`，保留原武器属性
- **替换为 IA 物品**：以 IA 物品为新本体，迁移名称/Lore/附魔

### 属性修饰符（1.21+ 适配）
- Minecraft 1.21 将属性 ID 从 `generic.attack_damage` 改为 `attack_damage`
- 前端属性列表已适配 1.21+ 格式
- ★ v1.3.1 修复：`addAttribute()` 默认值不再使用旧格式

### Lore 居中对齐
- 工具栏 ⊞ 按钮：基于 Minecraft 字体像素宽度表（精确到每字符 px）计算空格填充
- 使文本在游戏内实际居中显示（非网页 CSS 模拟）
- 保留格式码前缀（`&6&l` 等放在空格后、文字前）
- 每条 Lore 行均可独立居中

### &k 乱码格式码保持
- ★ v1.3.1 修复：回读物品 Lore 改用 `LegacyComponentSerializer` 替代 `PlainTextSerializer`
- 颜色/粗体/斜体/乱码等格式码完整保留，不再被静默丢弃

---

## 构建

需要 JDK 21 + Maven。

```bash
cd ks_series/KS-ItemEditor
mvn clean package
# 或使用自动化脚本
.\deploy.ps1
```

`deploy.ps1` 自动完成：Maven 构建 → 备份旧 JAR → 部署 → 清理旧版本 → 同步 config.yml。
测试服务器位于 `../test_1_21/`（ks_series 共享测试环境）。

---

## 版本历史

| 版本 | 内容 |
|------|------|
| **1.4.0** | **并入 ks-Series**：重命名为 KS-ItemEditor，groupId 迁移至 org.kseries，测试服移入 ks_series 统一管理 |
| 1.0.5 | ChatInput 替代 AnvilInput，修复 Leaves 兼容 |
| 1.0.7 | 玩家精炼系统（`/refine`） |
| 1.0.9 | `config.yml` 配置文件 |
| 1.1.0 | 网页物品设计器（HTTP + REST API + 模板码） |
| 1.1.1 | 管理/玩家网页分离、FE 五分类、原版附魔中文名 |
| 1.1.2 | 玩家模板 IA/FE 数据泄露修复（前后端双重剥离） |
| 1.2.0 | 物品属性修饰符、FE 兼容性配置、`deploy.ps1` |
| 1.2.1 | FE 测试附魔（雷刃/生机涌动）、属性修饰符调试日志 |
| 1.2.2 | 属性 ID 1.21 适配（去除 `generic.` 前缀） |
| 1.2.3 | 附魔突破上限 UI（突破徽章/已达上限提示）、FE 上限真实化 |
| **1.3.0** | **物品预览面板**（名称+Lore 实时渲染 & 颜色码/Unicode/乱码）、**特殊符号面板**（6 分类点击插入）、**选区格式化工具栏**（16 色+5 格式码智能包裹）、**模板前缀**（pl-/op-区分）、模板自定义名称、导入/预览/修改/导出工作流、属性修饰符仅管理员、旧模板自动清理 |
| **1.3.1** | **`/itemedit reload`** 热重载命令（支持控制台，重载 config.yml + 重启 Web 服务器）、**`public-address` 配置**（bind-address 与对外显示地址分离，解决面板服绑定 0.0.0.0 链接不可用）、**Lore 居中工具**（Minecraft 字体像素宽度表 + 空格填充，工具栏 ⊞ 按钮 + 每行独立居中）、**Bug 修复**: ① 属性修饰符默认值 `generic.attack_damage`→`attack_damage`（1.21 适配） ② Lore 回读格式码丢失（`PlainTextSerializer`→`LegacyComponentSerializer`，&k 乱码/颜色等完整保留） ③ 管理→玩家模板附魔未截断上限（新增 `capEnchantmentLevels()`，GUI+Web 双路径防护） ④ Lore 预览左对齐（匹配 Minecraft 实际渲染） ⑤ `/itemedit web` 链接生成改用 `public-address`、**架构改进**: FotiaEnchantment + MythicMobs 均改为 100% 反射调用，零编译期依赖，更新 FE/MM 无需重编译 ItemEditor |

---

## 许可

内部使用，保留所有权利。
