# ks-core v1.1.0

> [English](README.en.md) | 中文

**ks-Series 核心基础设施** — 统一 Web 网关、Token 鉴权、路由分发和多数据库 JDBC 数据存储。

## 功能

- **Web 网关**: 嵌入式 HTTP 服务器（`com.sun.net.httpserver`），端口 8123
- **路由分发**: URL 前缀匹配，请求转发到注册的子插件
- **Token 鉴权**: 会话创建/验证/续期，600s 超时
- **数据存储**: HikariCP 连接池，支持 SQLite、MySQL、MariaDB、PostgreSQL
- **兼容入口**: 现有子插件继续通过 `KsDataStore.getConnection()` 获取连接
- **故障策略**: 远程数据库默认失败即停；显式开启回退后使用本地 SQLite，但该节点不再共享跨服数据
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
  jdbc-url: ""
  username: ""
  password: ""
  password-env: ""
  password-file: ""
  fallback-to-sqlite: false
  sqlite:
    file: "data.db"
  pool:
    maximum-pool-size: 5
```

`type` 可选 `sqlite`、`mysql`、`mariadb`、`postgresql`、`auto`。PostgreSQL 需要显式 `jdbc-url`。密码来源优先级为 `password`、`password-env`、`password-file`、旧 `mysql.password`；配置了但不可读取的环境变量或文件会失败关闭。相对文件路径以 `plugins/ks-core/` 为根，数据库连接与凭据来源均为重启配置。

核心表已按四种方言适配；SQLite 只能作为单节点存储。2026-07-22 测试网络的 Leaves、Paper、Folia 已用同一 MariaDB 实机启动，运行时 YAML 不再包含明文密码。

## 依赖

- Paper 1.21.11+
- Java 21+

## 构建

```bash
cd ks-core && mvn clean test && mvn install -DskipTests

# Folia 独立制品
mvn -Pfolia clean package
```

2026-07-22 Java 21 验证：9 个 JUnit 测试通过；默认/Folia 构件均成功，`plugin.yml` 分别声明 `folia-supported: false/true`。Shade 仍报告模块描述符和重复资源警告。
