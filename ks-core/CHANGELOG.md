# ks-core 修改日志

## 2026-07-22

- 新增 `database.password-env` 与 `database.password-file`，错误外部凭据来源失败关闭。
- 新增独立 `-Pfolia` 制品；默认与 Folia `plugin.yml` 不再混用兼容声明。
- Leaves、Paper、Folia 真实连接同一 MariaDB，只有 Leaves 启用 Web 网关。
- 9 项测试通过；两类制品均构建、备份并部署。
