# ks-core v1.0.0

> [中文](README.md) | English

**KS-Series core infrastructure**: shared Web gateway, token authentication, route dispatch, and SQLite storage.

## Features

- Embedded HTTP gateway using `com.sun.net.httpserver`, normally on port 8123.
- URL-prefix routing to registered child plugins.
- Session creation, validation, renewal, and 600-second timeout.
- Shared SQLite connection pool for child plugins.
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
  sqlite.file: "data.db"
```

Requires Paper 1.21.11+ and Java 21+. Build with `cd ks-core && mvn clean package`.
