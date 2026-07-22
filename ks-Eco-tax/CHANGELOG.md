# ks-Eco-tax 修改日志

## 2026-07-23

- 声明 `folia-supported=true`，税率定时刷新改用 ks-Eco `EcoScheduler`，审计退款不再调用旧 Bukkit scheduler。
- 新增统一钱包边界：外部 Vault 按玩家实体/global owner 调度，Folia 内置钱包使用 UUID 数据库接口。
- 新增 Folia metadata 合同测试；模块 7 项测试通过。未部署，未启动服务器。

## 2026-07-22

- 税率兼容小数与旧百分比存储，行业税率迁移改为方言安全探测。
- 税务审计使用幂等异步写入，最终失败会安排退款并记录退款失败。
- 6 项测试通过；已在 Leaves/Paper 的共享 MariaDB 启动。
