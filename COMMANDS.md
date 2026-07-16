# ks-Series 全部指令参考

> Web 网关: `http://localhost:8123` | 所有 Web 面板通过 ks-core 统一端口访问

---

## ks-core — 核心网关

| 指令 | 权限 | 说明 |
|------|------|------|
| `/kscore` | — | 查看网关状态 |

## Web 路由一览

| 路由 | 插件 | 说明 |
|------|------|------|
| `/IE` | KS-ItemEditor | 网页物品设计器 |
| `/kSHWP` | ksHWP | 世界地图 |
| `/ks-Eco` | ks-Eco | 经济管理面板 |
| `/ks-Eco/bank` | ks-Eco-bank | 银行系统面板 |
| `/ks-Eco/enterprise` | ks-Eco-enterprise | 企业系统面板 |
| `/ks-Eco/tax` | ks-Eco-tax | 税法系统面板 |

---

## KS-ItemEditor — 物品编辑器

### 管理员指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/itemedit` | `itemedit.admin` | 打开 GUI 编辑器（手持物品） |
| `/ie web` | `itemedit.admin` | 获取管理员 Web 面板链接 |
| `/ie reload` | `itemedit.admin` | 重载配置 |
| `/design` | `itemedit.admin` | 获取玩家 Web 设计器链接 |

### 玩家指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/itemedit` | — | 打开 GUI 编辑器（手持物品） |
| `/design` | — | 获取 Web 物品设计器链接 |
| `/design load <模板码>` | — | 加载已保存模板到手 |
| `/refine` | — | 打开精炼界面 |

### 别名
`/ie` = `/itemedit`

---

## ksHWP — 世界地图

### 管理员指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/kshwp reload` | `kshwp.admin` | 重载配置 |
| `/kshwp status` | `kshwp.admin` | 查看地图状态 |

### 玩家指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/map` | — | 获取 Web 地图链接 |
| `/mapnote add <文本>` | — | 在当前位置添加地图备注 |
| `/mapnote list` | — | 查看我的备注列表 |
| `/mapnote delete <ID>` | — | 删除指定备注 |

### Web API 管理功能
- **地图页面**: 右键选点备注、Shift+拖拽框选区域、滚轮缩放、左键拖拽平移
- **公开标注**: 管理员右键→「📢 添加公开标注」（所有人可见，红色样式）
- **批量渲染**: 侧边栏输入半径→「渲染」按钮预热区块缓存
- **区块网格**: 左下角「网格」按钮切换网格叠加

---

## ks-Eco — 经济核心

### 管理员指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/kseco web` | — | 获取 Web 经济面板链接（所有人可用） |
| `/kseco reload` | `kseco.admin` | 重载配置 |
| `/kseco status` | `kseco.admin` | 查看经济状态（挂单、暂存、Vault、Extra模块） |
| `/kseco force-price <材质> <价格>` | `kseco.admin` | 强制限价（受 max-price 上限约束） |
| `/kseco void-trade <材质> <数量> <价格> <BUY\|SELL>` | `kseco.admin` | 虚空交易干预市场（注入交易影响需求指数） |

### 玩家指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/kseco web` | — | 获取 Web 经济面板链接 |
| `/market` | `kseco.market` | 打开市场 GUI |
| `/trade <玩家名>` | `kseco.trade` | 发起玩家间交易 |
| `/storage` | `kseco.storage` | 打开物品暂存箱 |

### 别名
`/kse` = `/kseco` | `/mkt` = `/market` | `/ah` = `/market` | `/deal` = `/trade` | `/stash` = `/storage` | `/chest` = `/storage`

### Web 管理面板
打开 `/kseco web` 获取链接后：
- **市场面板**: 查看挂单统计、强制限价
- **银行系统**: 创建银行、央行调息、放贷/还款、查看银行/贷款列表
- **企业系统**: 注册企业、发布招标、投标、评标（最低价中标）、注销企业
- **税法系统**: 动态调整各税种税率、发出罚单、查看纳税记录

### curl API 测试
```bash
# 获取 token
curl "http://localhost:8123/ks-Eco/api/login?player=你的ID"

# 市场统计
curl "http://localhost:8123/ks-Eco/api/market/stats"

# 银行统计
curl "http://localhost:8123/ks-Eco/api/bank/stats"

# 查看税率
curl "http://localhost:8123/ks-Eco/api/tax/rates"

# 设置市场交易税为 3%
curl -X POST "http://localhost:8123/ks-Eco/api/tax/rates/set" \
  -H "Content-Type: application/json" \
  -d '{"category":"MARKET_TRADE","rate":0.03}'

# 创建银行（admin token 需要从 /api/login 获取）
curl -X POST "http://localhost:8123/ks-Eco/api/bank/create" \
  -H "Content-Type: application/json" \
  -d '{"name":"测试银行","ownerUuids":["玩家UUID"],"initialCapital":50000}'
```

---

## ks-Eco-bank — 银行系统（Extra 模块）

无独立指令，通过 `/ks-Eco/bank` Web 面板或 API 管理。

### Web API
```
GET  /ks-Eco/api/bank/stats
GET  /ks-Eco/api/bank/list
POST /ks-Eco/api/bank/create
GET  /ks-Eco/api/bank/cb/rates
POST /ks-Eco/api/bank/cb/set-rates
GET  /ks-Eco/api/bank/loans
POST /ks-Eco/api/bank/loan/issue
POST /ks-Eco/api/bank/loan/repay
```

---

## ks-Eco-enterprise — 企业系统（Extra 模块）

无独立指令，通过 `/ks-Eco/enterprise` Web 面板或 API 管理。

### Web API
```
GET  /ks-Eco/api/enterprise/stats
GET  /ks-Eco/api/enterprise/list
GET  /ks-Eco/api/enterprise/get?id=
POST /ks-Eco/api/enterprise/register
POST /ks-Eco/api/enterprise/dissolve
GET  /ks-Eco/api/enterprise/projects
POST /ks-Eco/api/enterprise/project/publish
POST /ks-Eco/api/enterprise/bid/submit
POST /ks-Eco/api/enterprise/project/award
```

---

## ks-Eco-tax — 税法系统（Extra 模块）

无独立指令，通过 `/ks-Eco/tax` Web 面板或 API 管理。

### Web API
```
GET  /ks-Eco/api/tax/stats
GET  /ks-Eco/api/tax/rates
POST /ks-Eco/api/tax/rates/set
GET  /ks-Eco/api/tax/records
GET  /ks-Eco/api/tax/penalties
POST /ks-Eco/api/tax/penalty/issue
```

---

## 权限一览

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `itemedit.admin` | OP | ItemEditor 管理权限 |
| `kshwp.admin` | OP | 地图管理权限 |
| `kseco.admin` | OP | 经济管理权限 |
| `kseco.market` | ALL | 使用市场 |
| `kseco.trade` | ALL | 玩家间交易 |
| `kseco.storage` | ALL | 使用暂存箱 |

---

## 常用测试流程

### 1. 测试 Web 物品编辑器
```
/design              → 获取玩家版设计器链接
/ie web              → 获取管理员版链接（完整功能）
/itemedit            → 打开 GUI 编辑器
```

### 2. 测试地图
```
/map                 → 获取地图链接
/mapnote add 主城    → 添加个人备注
右键地图 → 选点/框选 → 通过 Web 面板添加备注
（管理员）右键 → 📢 添加公开标注
```

### 3. 测试经济
```
/kseco web           → 获取经济面板链接
/market              → 打开市场 GUI
/trade 玩家名        → 发起交易
/storage             → 打开暂存箱
/kseco status        → 查看经济状态（管理员）

# 市场干预测试（管理员）
/kseco void-trade DIAMOND 64 120 BUY   → 虚空买入钻石，推高需求
/kseco void-trade WHEAT 64 1.5 SELL    → 虚空卖出小麦，压低价格
/kseco force-price IRON_INGOT 15       → 强制设定铁锭价格（受上限约束）

# 自动化市场模拟测试
cd test_results
PYTHONIOENCODING=utf-8 python market_simulation.py   → 65 项自动化测试 + 6 张图表
```

### 4. 测试银行系统（通过 Web 面板或 API）
1. 打开 `/ks-Eco/bank`
2. 填入银行名称、所有者 UUID、初始资本 → 创建银行
3. 央行调控区调整利率
4. 发放贷款：填入银行 ID、借款人 UUID、金额、期限

### 5. 测试企业招投标
1. 打开 `/ks-Eco/enterprise`
2. 注册企业（需要足够资金）
3. 发布招标项目
4. 企业投标
5. 评标（最低价自动中标）
