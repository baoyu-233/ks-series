# ks-Series 插件测试指南

> [English](TESTING.en.md) | 中文

> **适用范围**: ks-core、ks-Eco、ksHWP、ks-Eco-bank、ks-Eco-enterprise、ks-Eco-tax  
> **排除插件**: KS-ItemEditor (ie)、KS-ItemSteal (is)  
> **测试环境**: `test_1_21/` (Leaves 1.21.11) + Python 模拟测试  
> **更新日期**: 2026-06-24

---

## 目录

- [1. 测试环境概览](#1-测试环境概览)
- [2. 通用测试流程](#2-通用测试流程)
- [3. ks-core — Web 网关核心](#3-ks-core--web-网关核心)
- [4. ks-Eco — 经济核心](#4-ks-eco--经济核心)
- [5. ksHWP — 世界地图](#5-kshwp--世界地图)
- [6. ks-Eco-bank — 银行系统](#6-ks-eco-bank--银行系统)
- [7. ks-Eco-enterprise — 企业系统](#7-ks-eco-enterprise--企业系统)
- [8. ks-Eco-tax — 税法系统](#8-ks-eco-tax--税法系统)

---

## 1. 测试环境概览

### 1.1 测试服务器

| 项目 | 说明 |
|------|------|
| 位置 | `test_1_21/` |
| 核心 | Leaves 1.21.11 (`server.jar`) |
| Java | 21+ |
| JVM 参数 | Aikar's Flags (4G 堆) |
| 游戏端口 | `server.properties` 中配置 |
| RCON 端口 | 25575 (密码: `ks_test_2026`) |
| 管理 Web | 25585 |

### 1.2 关键第三方插件

| 插件 | 用途 | 测试相关性 |
|------|------|-----------|
| Vault | 经济 API | ks-Eco 及其 extra 模块依赖 |
| LuckPerms | 权限管理 | 所有插件的权限测试 |
| ProtocolLib | 协议层 | GUI 菜单测试 |
| ItemsAdder | 自定义物品 | ks-Eco 物品识别测试 |
| FotiaEnchantment | 自定义附魔 | ks-Eco 附魔物品交易测试 |
| PlaceholderAPI | 变量系统 | 消息格式化测试 |

### 1.3 构建与部署

```powershell
# 一键构建全部 8 个插件并部署到测试服
.\build_all.ps1

# 构建 + 自动重启测试服
.\build_all.ps1 -Restart

# 仅构建（不部署）
.\build_all.ps1 -SkipBuild    # 跳过构建，直接部署已有 JAR
```

### 1.4 服务器控制

```powershell
# 启动 / 停止 / 重启
.\server-control.ps1 -Action Start
.\server-control.ps1 -Action Stop
.\server-control.ps1 -Action Restart

# 查看状态
.\server-control.ps1 -Action Status

# 发送 RCON 命令
.\server-control.ps1 -Action Cmd -Cmd "list"
.\server-control.ps1 -Action Cmd -Cmd "say 测试消息"

# 实时日志
.\server-control.ps1 -Action Console

# 白名单管理
.\server-control.ps1 -Action Whitelist -WlAdd PlayerName
.\server-control.ps1 -Action Whitelist -WlList
```

### 1.5 测试前检查清单

- [ ] 测试服已启动且无报错
- [ ] 目标插件 JAR 已部署到 `test_1_21/plugins/`
- [ ] LuckPerms 已配置好测试用的权限组
- [ ] Vault 已正常加载
- [ ] RCON 已启用（可通过 `-Action Status` 确认）
- [ ] 至少有一个测试玩家账号可登录

---

## 2. 通用测试流程

### 2.1 单个插件开发测试循环

```
修改代码 → mvn clean package → 部署 JAR → 重启/热重载 → 验证
```

```powershell
# 步骤 1: 构建单个插件
cd ks-core   # 或其他插件目录
mvn clean package

# 步骤 2: 部署到测试服
Copy-Item target\ks-core-*.jar ..\test_1_21\plugins\ -Force

# 步骤 3: 重启服务器
# 方式 A — RCON 热重载（如果插件支持）
..\server-control.ps1 -Action Cmd -Cmd "plugman reload ks-core"

# 方式 B — 完整重启（推荐，确保干净状态）
..\server-control.ps1 -Action Restart

# 步骤 4: 观察日志
..\server-control.ps1 -Action Console
```

> **注意**: Paper 的 `/reload` 命令可能导致内存泄漏和状态不一致。对于涉及数据库、HTTP 服务器、事件监听的插件，建议完整重启。

### 2.2 日志位置

| 日志 | 路径 |
|------|------|
| 最新日志 | `test_1_21/logs/latest.log` |
| 压缩历史 | `test_1_21/logs/2026-06-23-*.log.gz` |
| 崩溃报告 | `test_1_21/crash-reports/` |

---

## 3. ks-core — Web 网关核心

### 3.1 插件概述

ks-core 是整个 ks-Series 的 Web 中枢，负责统一 HTTP 服务器、路由分发、Token 鉴权和数据存储。所有其他 ks 插件的 Web 功能都通过它对外暴露。

**关键类**: `KsCore`, `KsRouter`, `KsAuthManager`, `KsDataStore`, `KsConfig`, `KsWebServer`, `KsPluginBridge`

**配置**: `plugins/ks-core/config.yml`

### 3.2 测试项目

#### 3.2.1 启动与基本状态

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 插件加载 | 启动服务器，观察控制台 | 看到 `ks-core 已启用` 日志，显示 Web 网关地址和端口 |
| 状态命令 | 执行 `/kscore status` | 显示运行中、活跃路由数、活跃会话数、数据存储类型、已注册子插件列表 |
| 重载命令 | 执行 `/kscore reload` | 显示 `配置已重载`，Web 服务器重启 |
| 无参数命令 | 执行 `/kscore` | 显示帮助信息 (reload / status) |

#### 3.2.2 Web 网关

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 网关首页 | 浏览器访问 `http://localhost:8123/` | 显示 ks-core 网关状态页面，列出所有已注册路由 |
| CORS 预检 | `curl -X OPTIONS http://localhost:8123/` | 返回 204，包含 CORS 头 |
| 404 路由 | 访问 `http://localhost:8123/nonexistent` | 返回网关首页（无匹配路由时回退） |
| 端口绑定 | 修改 `config.yml` 端口后 `/kscore reload` | 新端口生效，旧端口释放 |

#### 3.2.3 Token 鉴权

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| Token 创建 | 通过子插件 API 获取 token（如 ks-Eco 的 `/api/login`） | 返回 64 位十六进制 token 字符串 |
| Token 验证 | 使用有效 token 请求需鉴权的 API | 正常返回数据 |
| Token 过期 | 等待超过 `token-timeout-seconds`（默认 600s）后使用 | 返回 401 错误，token 无效 |
| Token 续期 | 在有效期内续期 | `createdAt` 刷新，token 字符串不变 |
| Token 刷新 | 调用 refresh API | 旧 token 失效，返回新 token |
| Token 移除 | 主动调用 remove | token 立即失效 |
| 无效 token | 使用随机字符串作为 token | 返回 401 |
| 空 token | 不传 token 参数 | 返回 401 |
| 会话数统计 | `/kscore status` | `活跃会话` 数字与实际一致 |
| 过期清理 | 手动调用 `cleanup()` | 仅清理超时会话，有效会话保留 |

#### 3.2.4 路由注册/取消

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 路由注册 | 子插件 `onEnable` 调用 `bridge.registerRoute()` | 日志显示 `路由已注册: /xxx → pluginId`，`/kscore status` 中可见 |
| 路由取消 | 子插件 `onDisable` 调用 `bridge.unregisterRoute()` | 路由从注册表中移除 |
| 重复注册 | 同一 pluginId 注册多次 | 后注册覆盖先注册 |
| 最长前缀匹配 | 访问 `/ks-Eco/admin/page` | 匹配到 `/ks-Eco` 而非 `/` |
| 未启用路由 | 在 `config.yml` 中禁用某子插件 (`enabled: false`) | 子插件不注册路由 |

#### 3.2.5 数据存储 (SQLite)

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 数据库初始化 | 首次启动 ks-core | `plugins/ks-core/data.db` 文件创建，表结构建立 |
| 数据读写 | 通过 `bridge.getDatabaseConnection()` 执行 INSERT/SELECT | 数据正确持久化 |
| 重启持久性 | 重启服务器后查询 | 之前写入的数据仍然存在 |
| 并发连接 | 多子插件同时读写 | 无死锁，数据一致 |

#### 3.2.6 配置管理

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 默认配置 | 删除 `config.yml` 后重启 | 自动生成带默认值的配置文件 |
| 热重载 | 修改配置 → `/kscore reload` | 新配置生效（端口、超时等） |
| 子插件启停 | 修改 `sub-plugins.xxx.enabled` → reload | 对应路由启用/禁用 |
| 无效端口 | 设置 `port: -1` 或 `port: 99999` | 启动时报错提示 |

### 3.3 Web API 测试命令速查

```bash
# 网关首页
curl http://localhost:8123/

# CORS 预检
curl -X OPTIONS -I http://localhost:8123/

# 请求不存在的路径
curl -I http://localhost:8123/nonexistent/path

# 查看子插件注册的路由（如已启用 ksHWP）
curl http://localhost:8123/kSHWP/api/worlds
```

---

## 4. ks-Eco — 经济核心

### 4.1 插件概述

ks-Eco 是经济系统的核心，提供市场挂单、官方收购/出售、玩家交易、潜影盒解析、动态定价等功能。Web 管理面板通过 ks-core 的网关暴露。

**关键类**: `KsEco`, `EcoWebHandler`, `MarketManager`, `ListingManager`, `PriceEngine`, `StorageManager`, `TradeManager`, `OfficialBuyManager`, `OfficialSellManager`, `ShulkerBoxParser`, `VaultHook`, `ExtraModuleLoader`

**配置**: `plugins/ks-Eco/config.yml`

**路由**: `/ks-Eco`（需在 ks-core 配置中启用）

### 4.2 测试项目

#### 4.2.1 插件启动与依赖检查

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 正常启动 | 确保 ks-core 先加载，启动服务器 | 日志显示 `ks-Eco 已启用`，Vault 状态 |
| ks-core 缺失 | 移除 ks-core JAR 后启动 | 日志显示 `ks-core 未找到！`，插件自动禁用 |
| Vault 缺失 | 移除 Vault JAR 后启动 | 日志显示 `Vault: 未找到`，市场功能仍可用（无货币对接） |
| 路由注册 | 确保 `ks-core/config.yml` 中 `ks-eco.enabled: true` | `http://localhost:8123/ks-Eco/` 可访问 |

#### 4.2.2 管理命令

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| `/kseco` 无参数 | 管理员执行 `/kseco` | 显示帮助：reload / status / force-price |
| `/kseco status` | 管理员执行 | 显示活跃挂单数、暂存箱物品数、Vault 状态、Extra 模块数 |
| `/kseco reload` | 管理员执行 | 配置重载 |
| `/kseco force-price IRON_INGOT 15.0` | 管理员执行 | 显示 `已更新 IRON_INGOT 的官方价格` |
| 权限不足 | 非管理员执行 `/kseco` | 显示 `权限不足`（需要 `kseco.admin`） |

#### 4.2.3 市场系统 (Market)

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| `/market` 命令 | 玩家执行 `/market` | 打开市场 GUI（如果 `gui-enabled: true`） |
| Web 挂单查询 | `curl http://localhost:8123/ks-Eco/api/listings?type=SELL` | 返回 JSON 挂单列表 |
| 挂单上限 | 玩家挂单达到 `max-listings-per-player` (默认 20) 后继续挂单 | 提示达到上限 |
| 挂单过期 | 创建挂单后等待 `listing-expire-hours` | 过期挂单自动下架 |
| 交易税 | 完成一笔交易 | 税率按 `tax-rate` (默认 2%) 扣除，不低于 `min-tax` (默认 1.0) |
| GUI 关闭 | `config.yml` 中 `gui-enabled: false` → reload | `/market` 命令按 Web 模式处理 |

#### 4.2.4 官方收购/出售 (Official Buy/Sell)

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 官方收购 | 玩家出售默认列表中的物品（如 IRON_INGOT） | 按官方价格 + 波动范围收购 |
| 动态价格 | 等待 `price-refresh-minutes` 分钟后 | 价格在 ±`price-fluctuation` 范围内波动 |
| 价格强制 | `/kseco force-price DIAMOND 50.0` | 价格固定为指定值 |
| 官方出售 | 玩家购买官方出售列表中的物品 | 价格不超过 `max-price` 和 `markup-factor` 约束 |
| 价格上限 | 验证 `markup-factor` 和 `max-price-default` 生效 | 售价 = min(收购价 × markup, max-price) |

#### 4.2.5 玩家交易 (Trade)

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 发起交易 | `玩家A` 执行 `/trade 玩家B` | 双方打开交易 GUI |
| 物品交换 | 双方放入物品确认 | 物品正确交换 |
| 货币交易 | 一方付款一方给物 | Vault 余额正确增减 |
| 对方离线 | `/trade OfflinePlayer` | 提示 `玩家不在线` |
| 控制台执行 | 控制台执行 `/trade` | 提示 `仅玩家可使用此命令` |

#### 4.2.6 暂存箱 (Storage)

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| `/storage` 命令 | 玩家执行 `/storage` | 打开暂存箱 GUI |
| 存入物品 | 放入物品 | 物品保存到暂存箱 |
| 取出物品 | 从暂存箱取回 | 物品正确返还 |
| 容量上限 | 存入超过 `max-slots` (默认 54) | 提示容量已满 |
| 过期清理 | 物品超过 `max-days` (默认 30 天) | 过期物品被清理 |
| 控制台执行 | 控制台执行 `/storage` | 提示 `仅玩家可使用此命令` |

#### 4.2.7 潜影盒解析 (ShulkerBox)

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 单层解析 | 交易含物品的潜影盒 | 盒内物品计入总价 |
| 递归深度限制 | 盒套盒套盒（> 3 层） | 超过 `max-recursion-depth` 后停止递归 |
| 空盒价值 | 交易空潜影盒 | 按 `empty-box-value` (默认 5.0) 计价 |
| 禁用解析 | `count-contents: false` | 仅计潜影盒本身价值，不递归 |

#### 4.2.8 Web 管理面板

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 管理页面 | 浏览器访问 `http://localhost:8123/ks-Eco/` | 显示管理面板 HTML 页面 |
| 市场统计 API | `curl http://localhost:8123/ks-Eco/api/market/stats` | 返回 `activeListings`, `storedItems`, `vaultAvailable`, 价格列表等 |
| 登录 API | `curl "http://localhost:8123/ks-Eco/api/login?player=TestPlayer"` | 返回 token、isAdmin、playerName |
| 玩家不存在 | `curl "http://localhost:8123/ks-Eco/api/login?player=NotFound"` | 返回 404 `玩家不在线` |
| 强制限价 API | `curl -X POST http://localhost:8123/ks-Eco/api/admin/force-price -d '{"material":"IRON_INGOT","price":15.0}'` | 返回成功消息 |
| 缺少参数 | POST force-price 缺 `material` | 返回 400 |
| 闲置物品 API | `curl "http://localhost:8123/ks-Eco/api/admin/idle-items?token=xxx"` | 需要有效 admin token，否则返回 403 |
| CORS | OPTIONS 预检请求 | 返回 204 + CORS 头 |

### 4.3 Web API 测试命令速查

```bash
# 管理面板首页
curl http://localhost:8123/ks-Eco/

# 市场统计
curl http://localhost:8123/ks-Eco/api/market/stats | python -m json.tool

# 挂单列表
curl "http://localhost:8123/ks-Eco/api/listings?type=SELL" | python -m json.tool

# 玩家登录获取 token
curl "http://localhost:8123/ks-Eco/api/login?player=Steve" | python -m json.tool

# 强制限价（需 POST + JSON body）
curl -X POST http://localhost:8123/ks-Eco/api/admin/force-price \
  -H "Content-Type: application/json" \
  -d '{"material":"IRON_INGOT","price":15.0}'

# 闲置物品（需 admin token）
curl "http://localhost:8123/ks-Eco/api/admin/idle-items?token=YOUR_TOKEN"

# 挂单列表（按类型筛选）
curl "http://localhost:8123/ks-Eco/api/listings?type=BUY"
```

---

## 5. ksHWP — 世界地图

### 5.1 插件概述

ksHWP 提供基于 Web 的 Minecraft 世界地图渲染，支持维度/世界切换和玩家专属地图备注。

**关键类**: `KsHWP`, `HwpWebHandler`, `MapRenderer`, `MapAnnotationManager`, `HwpConfig`

**配置**: `plugins/ksHWP/config.yml`

**路由**: `/kSHWP`（需在 ks-core 配置中启用 `kshwp.enabled: true`）

### 5.2 测试项目

#### 5.2.1 插件启动与依赖检查

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 正常启动 | ks-core 已加载，`kshwp.enabled: true` | 日志显示 `ksHWP 已启用`，显示 Web 地图地址和世界数量 |
| ks-core 缺失 | 移除 ks-core | 日志显示 `ks-core 未找到！`，插件禁用 |
| 路由未启用 | `kshwp.enabled: false` | 日志显示 `未在 ks-core 中启用`，路由不注册 |
| Multiverse 缺失 | 无 Multiverse-Core | 不影响基本功能，仅少维度支持 |

#### 5.2.2 管理命令

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| `/kshwp` 无参数 | 管理员执行 | 显示帮助：reload / status |
| `/kshwp status` | 管理员执行 | 显示世界数、各世界名称/维度、备注总数 |
| `/kshwp reload` | 管理员执行 | 配置重载 |
| 权限不足 | 非管理员执行 | 显示 `权限不足`（需要 `kshwp.admin`） |

#### 5.2.3 地图命令 (/map)

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| `/map` | 玩家执行 | 发送可点击的地图链接（含 token） |
| 路由未启用时 | `kshwp.enabled: false` 后执行 `/map` | 提示 `地图功能未启用` |
| 控制台执行 | 控制台执行 `/map` | 提示 `仅玩家可使用此命令` |

#### 5.2.4 地图备注 (/mapnote)

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| `/mapnote` | 玩家执行（无参数） | 显示帮助：add / list / delete |
| 添加备注 | `/mapnote add 这是一个矿洞入口` | 显示备注 ID 和坐标 |
| 查看列表 | `/mapnote list` | 显示玩家的所有备注（ID、世界、坐标、文本） |
| 删除备注 | `/mapnote delete <ID>` | 显示 `备注已删除` |
| 删除他人备注 | 尝试删除不属于自己的备注 | 提示 `备注不存在或不属于你` |
| 删除不存在 | 删除不存在的 ID | 同上 |
| 文本过长 | 添加超过限制的文本 | 按备注文本长度限制处理 |
| 备注上限 | 添加超过上限数量的备注 | 提示 `添加失败（可能已达上限）` |

#### 5.2.5 Web 地图 API

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 地图页面 | 浏览器访问 `http://localhost:8123/kSHWP/` | 显示地图 HTML 页面 |
| 世界列表 API | `curl http://localhost:8123/kSHWP/api/worlds` | 返回所有世界的名称和维度信息 |
| 在线玩家 API | `curl http://localhost:8123/kSHWP/api/players` | 返回在线玩家及其所在世界和坐标 |
| 地图图块 API | `curl "http://localhost:8123/kSHWP/api/tile?world=world&x=0&z=0&zoom=2"` | 返回指定区域的图块数据 |
| 图块渲染失败 | 请求不存在的世界 | 返回 404 `渲染失败` |
| 公开备注 API | `curl "http://localhost:8123/kSHWP/api/annotations?world=world"` | 返回该世界的公开备注列表 |
| 个人备注 API | `curl "http://localhost:8123/kSHWP/api/annotations?token=xxx&world=world"` | 额外返回 `myAnnotations` 数组 |
| 添加备注 API | `curl -X POST http://localhost:8123/kSHWP/api/annotations -H "Content-Type: application/json" -d '{"token":"xxx","world":"world","x":100,"y":64,"z":200,"text":"测试"}'` | 成功返回备注 ID |
| 无 token 添加 | POST annotations 不传 token | 返回 401 `需要 token` |
| 无效 token | POST annotations 传无效 token | 返回 401 `token 无效或已过期` |
| 空文本 | POST annotations 传空 text | 返回 400 `备注文本不能为空` |
| 删除备注 API | `curl -X DELETE "http://localhost:8123/kSHWP/api/annotations?token=xxx&id=xxx"` | 成功返回 `已删除` |
| CORS | OPTIONS 预检 | 返回 204 + CORS 头（支持 GET, POST, DELETE） |

### 5.3 Web API 测试命令速查

```bash
# 地图页面
curl http://localhost:8123/kSHWP/

# 世界列表
curl http://localhost:8123/kSHWP/api/worlds | python -m json.tool

# 在线玩家
curl http://localhost:8123/kSHWP/api/players | python -m json.tool

# 地图图块
curl "http://localhost:8123/kSHWP/api/tile?world=world&x=0&z=0&zoom=2" | python -m json.tool

# 公开备注
curl "http://localhost:8123/kSHWP/api/annotations?world=world" | python -m json.tool

# 添加备注
curl -X POST http://localhost:8123/kSHWP/api/annotations \
  -H "Content-Type: application/json" \
  -d '{"token":"YOUR_TOKEN","world":"world","x":100,"y":64,"z":200,"text":"测试备注"}'

# 删除备注
curl -X DELETE "http://localhost:8123/kSHWP/api/annotations?token=YOUR_TOKEN&id=NOTE_ID"

# 浏览器测试（含完整交互）
start http://localhost:8123/kSHWP/?token=YOUR_TOKEN
```

---

## 6. ks-Eco-bank — 银行系统

### 6.1 插件概述

ks-Eco-bank 是 ks-Eco 的 Extra 子模块，实现现代中央银行与商业银行系统，包括玩家银行、存贷业务、央行宏观调控和 M0/M1/M2 货币供应量追踪。

**关键类**: `BankExtra`, `BankManager`, `CentralBankManager`, `MoneySupplyTracker`

**加载方式**: JAR 放入 `plugins/ks-Eco/extra/`，通过 `ks-Eco/config.yml` 的 `extra-modules.enabled-modules` 启用

### 6.2 测试项目

#### 6.2.1 模块加载

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 模块加载 | 将 `ks-Eco-bank-*.jar` 放入 `plugins/ks-Eco/extra/`，配置中启用 `ks-Eco-bank` | 日志显示 `[银行系统] 模块已加载` → `模块已启用` |
| 模块未启用 | `enabled-modules` 列表中不包含 `ks-Eco-bank` | 模块不被加载 |
| JAR 缺失 | 删除 extra 目录中的 JAR | 加载时日志警告，不影响 ks-Eco 本身 |
| 模块禁用 | ks-Eco 重载/重启触发 `onDisable` | 日志显示 `[银行系统] 模块已停用` |

#### 6.2.2 玩家银行

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 创建银行 | 玩家满足资质要求后创建银行 | 银行创建成功，注册资本扣除 |
| 合资银行 | 多名玩家合资创建 | 按出资比例分配股份 |
| 存款 | 玩家向银行存款 | 余额正确入账，生成存款记录 |
| 取款 | 玩家从银行取款 | 余额正确扣除 |
| 利息计算 | 等待计息周期 | 存款按央行基准利率产生利息 |
| 贷款申请 | 玩家申请贷款 | 通过审批后发放，记录贷款信息 |
| 贷款还款 | 玩家还款 | 本金+利息正确计算 |
| 资质不足 | 不满足条件的玩家创建银行 | 提示资质不足 |

#### 6.2.3 中央银行

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 准备金率调整 | 管理员调整准备金率 | 商业银行可贷资金变化 |
| 基准利率调整 | 管理员调整基准利率 | 存贷利率随之变化 |
| 货币发行 | 央行发行货币 | M0 供应量增加 |
| 央行控制面板 | 管理员通过 Web/命令操作 | 所有调控功能可用 |

#### 6.2.4 货币供应量追踪

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| M0 统计 | 查看 M0（流通中现金） | 数值正确 = 玩家持有现金总和 |
| M1 统计 | 查看 M1（M0 + 活期存款） | 数值 ≥ M0 |
| M2 统计 | 查看 M2（M1 + 定期存款） | 数值 ≥ M1 |
| 实时更新 | 发生交易后查询 | 各指标实时更新 |

#### 6.2.5 GUI 与 Web

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 银行 GUI | 玩家打开银行界面 | 显示存取款/贷款选项 |
| Web 审批 | 管理员通过 Web 审批贷款 | 审批流程正常 |
| Web 央行控制 | 管理员通过 Web 调整政策 | 参数实时生效 |

---

## 7. ks-Eco-enterprise — 企业系统

### 7.1 插件概述

ks-Eco-enterprise 是 ks-Eco 的 Extra 子模块，实现现代企业与招投标系统，包括企业注册、招投标、资质校验、分包拼包等功能。

**关键类**: `EnterpriseExtra`, `EnterpriseManager`, `BiddingManager`, `QualificationChecker`

**加载方式**: 同 ks-Eco-bank，JAR 放入 `plugins/ks-Eco/extra/`，配置启用

### 7.2 测试项目

#### 7.2.1 模块加载

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 模块加载 | 启用 `ks-Eco-enterprise` | 日志显示 `[企业系统] 模块已加载` → `模块已启用` |
| 模块禁用 | ks-Eco 重载触发 `onDisable` | 日志显示 `[企业系统] 模块已停用` |

#### 7.2.2 企业注册

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 注册企业（区域） | 玩家上缴注册资本注册 | 企业创建成功，资金扣除 |
| 合伙办企 | 多名玩家共同注册 | 按出资比例分配股权 |
| 官方注资企业 | 管理员创建 | 注册资本隐匿（不由玩家出资） |
| 国有企业 | 管理员创建 | 特殊标识，享有政策优惠 |
| 重复注册 | 同一玩家注册第二家企业 | 按规则处理（允许/不允许） |
| 资金不足 | 玩家资本不足时注册 | 提示资金不足 |

#### 7.2.3 招投标系统

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 发布项目 | 官方/企业发布招标项目 | 项目出现在招标大厅 |
| 企业投标 | 符合资质的企业投标 | 投标记录创建 |
| 资质校验 | 注册资本不足 75% 标的企业投标 | 被 `QualificationChecker` 拒绝 |
| 开标 | 招标方查看投标并选择中标 | 中标企业获得项目 |
| 分包 | 中标企业将部分工作分包 | 分包合同创建 |
| 拼包 | 多家小企业联合投标 | 联合体资质按规则计算 |
| 预付款 | 项目启动支付预付款 | 按配置比例支付 |
| 违约金 | 中途违约 | 按合同扣除违约金 |

#### 7.2.4 GUI 与 Web

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 企业信息 GUI | 玩家查看企业信息 | 显示注册资本、成员、项目等 |
| 招标大厅 GUI | 玩家浏览招标项目 | 显示可投标项目列表 |
| Web 考勤 | 通过 Web 管理企业成员考勤 | 打卡记录正确 |
| Web 工资 | 通过 Web 发放工资 | 资金正确流转 |
| Web 权限管理 | 企业主通过 Web 设置成员权限 | 权限生效 |

---

## 8. ks-Eco-tax — 税法系统

### 8.1 插件概述

ks-Eco-tax 是 ks-Eco 的 Extra 子模块，实现税法与宏观调控系统，包括交易税征收、动态/阶梯税率、惩罚机制和逃税检测。

**关键类**: `TaxExtra`, `TaxManager`, `TaxRateManager`, `PenaltyManager`

**加载方式**: 同其他 Extra 模块

### 8.2 测试项目

#### 8.2.1 模块加载

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 模块加载 | 启用 `ks-Eco-tax` | 日志显示 `[税法系统] 模块已加载` → `模块已启用` |
| 模块禁用 | ks-Eco 重载触发 `onDisable` | 日志显示 `[税法系统] 模块已停用` |

#### 8.2.2 税收征收

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 交易税 | 玩家间完成一笔市场交易 | 税款按税率扣除，转入税收账户 |
| 企业税 | 企业完成项目收款 | 按企业税率扣税 |
| 税收拦截 | 所有经济活动触发 `TaxManager` 拦截 | 税款正确计算和收取 |
| 税收记录 | 查看税收日志 | 每笔税收有来源、金额、时间记录 |

#### 8.2.3 税率管理

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 默认税率 | 新玩家/小企业交易 | 按默认阶梯税率征收 |
| 阶梯税率 | 不同交易规模适用不同税率 | 大额交易税率更高 |
| 动态税率 | 管理员通过 Web 面板调整税率 | 新税率立即生效 |
| 税率下限 | 设置税率下限 | 不低于配置值 |
| 税率上限 | 设置税率上限 | 不超过配置值 |

#### 8.2.4 惩罚机制

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 逃税检测 | 模拟逃税行为 | 系统检测到并标记 |
| 罚金计算 | 逃税被检测后 | 按规则计算罚金 |
| 罚金税 | 违反合同/契约的行为 | 额外税率叠加 |
| 罚金缴纳 | 玩家缴纳罚金 | 罚金状态清零 |
| 多次逃税 | 重复逃税 | 罚金递增或加重处罚 |

#### 8.2.5 Web 管理面板

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 税率调整面板 | 管理员通过 Web 调整税率参数 | 参数实时生效 |
| 税收统计 | 查看税收总额、来源分布 | 数据准确 |
| 逃税记录 | 查看逃税检测记录 | 显示违规玩家和行为 |

---

## 9. 自动化市场模拟测试 (market_simulation.py)

### 9.1 概述

Python 模拟测试套件，完整复刻 ks-Eco PriceEngine.java 和 TaxManager 的定价/税收逻辑，支持自动化回归测试和图表生成。

**位置**: `test_results/market_simulation.py`
**报告**: `test_results/TEST_REPORT.md`

### 9.2 运行测试

```bash
cd test_results
pip install matplotlib numpy
PYTHONIOENCODING=utf-8 python market_simulation.py
```

### 9.3 测试套件（5 套，65 项）

| 套件 | 用例数 | 说明 |
|------|--------|------|
| 官方定价体系验证 | 34 | 价格分层合理性、波动范围、出售价上限、买卖价差、列表独立性 |
| 虚空交易市场干预 | 6 | 买入压力→涨价、卖出压力→降价、新物品注册、交易量影响 |
| 控制台价格干预 | 5 | force-price 更新、出售价约束、基准价保留、极端价格钳制 |
| 税收系统 | 11 | 基础税、最低税额、阶梯企业税（5%/8%/12%）、税收累加、多税种并行 |
| 综合场景模拟 | 9 | 200周期模拟、均值回归、市场稳定性、需求收敛 |

### 9.4 生成的图表

| 图表 | 文件 | 说明 |
|------|------|------|
| 价格波动原理 | `chart1_price_fluctuation.png` | 正弦波动模型 + 关键物品价格区间 |
| 市场价格走势 | `chart2_market_trend.png` | 300 周期模拟：钻石热潮→小麦丰收→系统冲击 |
| 需求-价格关系 | `chart3_demand_price_scatter.png` | 3 物品散点图 vs 理论正弦曲线 |
| 税收分析 | `chart4_tax_analysis.png` | 阶梯税率 + 税种对比 + 税额增长 + 税负率 |
| 干预效果对比 | `chart5_intervention_comparison.png` | 4 种策略：自由/买入/限价/混合 |
| 价格分布 | `chart6_price_distribution.png` | 6 物品价格频率直方图 |

### 9.5 虚空交易测试流程

```
1. 控制台注入买入交易 → 推高需求指数 → 观察价格上升
2. 控制台注入卖出交易 → 压低需求指数 → 观察价格下降
3. 验证 max-price 上限约束（不可量产物品）
4. 验证 force-price 行政定价的即时效果
```

### 9.6 税收测试流程

```
1. 验证默认 2% 税率 = baseAmount × 0.02
2. 验证最低税额保底（小额交易 ≥ 1.0）
3. 验证阶梯企业税：小型 5% / 中型 8% / 大型 12%
4. 验证多税种独立征收、税收累加
5. 验证 0% 税率 + min-tax 保底组合
```

### 9.7 最新测试结果

**2026-06-24 — v1.1.0: 65/65 通过 (100%)**

本地测试服的详细图表和原始结果不属于公开仓库；发布前请在自己的测试环境生成并保存测试结果。

---

## 附录 A: 快速测试脚本

### A.1 ks-core Web 网关快速测试

```bash
#!/bin/bash
# 测试 ks-core 网关基础功能
BASE="http://localhost:8123"

echo "=== 1. 网关首页 ==="
curl -s "$BASE/" | head -5

echo -e "\n=== 2. CORS 预检 ==="
curl -s -o /dev/null -w "HTTP %{http_code}" -X OPTIONS "$BASE/"

echo -e "\n=== 3. 404 路由 ==="
curl -s -o /dev/null -w "HTTP %{http_code}" "$BASE/nonexistent"
```

### A.2 ks-Eco Web API 快速测试

```bash
#!/bin/bash
BASE="http://localhost:8123"

echo "=== 1. 管理面板 ==="
curl -s -o /dev/null -w "HTTP %{http_code}" "$BASE/ks-Eco/"

echo -e "\n=== 2. 市场统计 ==="
curl -s "$BASE/ks-Eco/api/market/stats"

echo -e "\n=== 3. 挂单列表 ==="
curl -s "$BASE/ks-Eco/api/listings?type=SELL"
```

### A.3 ksHWP 地图 API 快速测试

```bash
#!/bin/bash
BASE="http://localhost:8123"

echo "=== 1. 世界列表 ==="
curl -s "$BASE/kSHWP/api/worlds"

echo -e "\n=== 2. 在线玩家 ==="
curl -s "$BASE/kSHWP/api/players"

echo -e "\n=== 3. 地图页面 ==="
curl -s -o /dev/null -w "HTTP %{http_code}" "$BASE/kSHWP/"
```

### A.4 市场模拟自动化测试

```bash
#!/bin/bash
# 测试 ks-Eco 定价引擎 + 税收系统（Python 模拟）
cd test_results

echo "=== 安装依赖 ==="
pip install matplotlib numpy -q

echo -e "\n=== 运行全部 65 项测试 ==="
PYTHONIOENCODING=utf-8 python market_simulation.py

echo -e "\n=== 查看测试报告 ==="
cat TEST_REPORT.md

echo -e "\n=== 查看生成图表 ==="
ls -la chart*.png
```

---

## 附录 B: 测试服权限配置参考

使用 LuckPerms 为测试玩家配置权限：

```
# 基础权限（所有测试玩家）
/lp group default permission set kseco.market true
/lp group default permission set kshwp.map true

# 管理员权限（测试管理员）
/lp group admin permission set kscore.admin true
/lp group admin permission set kseco.admin true
/lp group admin permission set kshwp.admin true
```

---

## 附录 C: 常见问题排查

| 问题 | 可能原因 | 排查方法 |
|------|----------|----------|
| Web 页面无法访问 | 端口被占用或防火墙阻止 | `netstat -ano \| findstr 8123` |
| Token 总是过期 | 服务器时间不同步 | 检查系统时间 |
| 路由未注册 | `config.yml` 中未启用 | 检查 `sub-plugins.xxx.enabled: true` |
| Extra 模块不加载 | JAR 未放入 extra 目录或未配置启用 | 检查 `plugins/ks-Eco/extra/` 和 `enabled-modules` 列表 |
| SQLite 数据库锁定 | 并发写入冲突 | 检查是否有多个进程访问 data.db |
| Vault 对接失败 | Vault 未安装或无经济插件 | 安装 Vault + 任意经济插件 |
