# ks-core v1.1.0

> [中文](README.md) | English

**KS-Series core infrastructure**: shared Web gateway, token authentication, route dispatch, and pooled JDBC storage.

## Features

- Embedded HTTP gateway using `com.sun.net.httpserver`, normally on port 8123.
- URL-prefix routing to registered child plugins.
- Session creation, validation, renewal, and 600-second timeout.
- HikariCP storage for SQLite, MySQL, MariaDB, and PostgreSQL while preserving `KsDataStore.getConnection()`.
- Remote database initialization fails fast unless `fallback-to-sqlite` is enabled; a fallback node is local-only.
- CORS support.

| Plugin | Route | Purpose |
|---|---|---|
| ksHWP | `/kSHWP` | World map |
| ks-Eco | `/ks-Eco` | Economy |
| KS-ItemEditor | `/IE` | Item editor |

## Commands

```text
/kscore reload   Reload configuration
/kscore status   Show routes, registered plugins, and Web address
```

Alias: `/ksc`.

## Configuration

```yaml
web-gateway:
  port: 8123
  bind-address: "0.0.0.0"
  token-timeout-seconds: 600
database:
  type: sqlite
  jdbc-url: ""
  username: ""
  password: ""
  fallback-to-sqlite: false
  sqlite:
    file: "data.db"
  pool:
    maximum-pool-size: 5
```

Supported types are `sqlite`, `mysql`, `mariadb`, `postgresql`, and `auto`; PostgreSQL requires an explicit JDBC URL.
The core schema is portable, but most ks-Eco business SQL has not yet been certified against live remote databases.

Requires Paper 1.21.11+ and Java 21+. On 2026-07-18, four JUnit tests and package/install passed; Shade still reports
module-descriptor and overlapping-resource warnings.
