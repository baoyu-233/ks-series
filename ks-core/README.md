# ks-core v1.0.0

**ks-Series 核心基础设施** — 统一 Web 网关、Token 鉴权、路由分发、SQLite 数据存储。

## 功能

- **Web 网关**: 嵌入式 HTTP 服务器（`com.sun.net.httpserver`），端口 8123
- **路由分发**: URL 前缀匹配，请求转发到注册的子插件
- **Token 鉴权**: 会话创建/验证/续期，600s 超时
- **数据存储**: SQLite 连接池，供所有子插件共享
- **CORS**: 跨域访问支持

## 子插件

| 插件 | 路由 | 说明 |
|------|------|------|
| ksHWP | `/kSHWP` | 世界地图 |
| ks-Eco | `/ks-Eco` | 经济系统 |
| KS-ItemEditor | `/IE` | 物品编辑器 |

## 命令

```
/kscore reload   重载配置
/kscore status   查看状态（路由数、注册插件、Web 地址）
```

别名: `/ksc`

## 配置

`plugins/ks-core/config.yml` 关键项:
```yaml
web-gateway:
  port: 8123
  bind-address: "0.0.0.0"
  token-timeout-seconds: 600

sub-plugins:
  kshwp:    { enabled: true, route: "/kSHWP" }
  ks-eco:   { enabled: true, route: "/ks-Eco" }
  KS-ItemEditor: { enabled: true, route: "/IE" }

database:
  type: sqlite
  sqlite.file: "data.db"
```

## 依赖

- Paper 1.21.11+
- Java 21+

## 构建

```bash
cd ks-core && mvn clean package
```
