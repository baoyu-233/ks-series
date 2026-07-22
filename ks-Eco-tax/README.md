# ks-Eco-tax v1.1.0

> [English](README.en.md) | 中文

**税法与宏观调控系统** — 阶梯税率、动态税率、惩罚机制、逃税检测。

## 依赖

- **ks-core**: Web 网关 + 数据存储
- **ks-Eco** (v1.1.0+): 经济核心

## 税种与税率

| 税种 | 默认税率 | 说明 |
|------|---------|------|
| MARKET_TRADE | 2% | 市场交易税 |
| OFFICIAL_TRADE | 0% | 官方交易（免税） |
| ENTERPRISE_SMALL | 5% | 小型企业 (<10万注册资本) |
| ENTERPRISE_MEDIUM | 8% | 中型企业 (10-50万) |
| ENTERPRISE_LARGE | 12% | 大型企业 (>50万) |
| BANK_INTEREST | 10% | 银行利息税 |
| PENALTY_TAX | 20% | 罚金税 |

## 功能

- **阶梯税率**: 按企业规模分级（小型/中型/大型）
- **动态税率**: 管理员通过 Web 面板实时调整各税种税率
- **兼容与一致性**: 兼容小数/旧百分比税率，行业税率独立持久化，共享数据库快照可刷新
- **审计与退款**: 校验有限税基，幂等异步写入审计；最终写入失败时退款
- **Paper/Folia 钱包边界**: 外部 Vault 按纳税玩家的实体/global owner 调度，Folia 内置钱包使用 UUID 数据库接口；税率刷新和审计继续使用数据库工作 lane
- **最低税额**: 1.0 货币单位（防止小额交易零税）
- **惩罚机制**: 逃税 30%、合同违约 20%、欺诈 50%
- **Web 面板**: `/ks-Eco/tax`

## 数据表

`ks_tax_records`, `ks_tax_penalties`, `ks_tax_rates`, `ks_tax_industry_rates`, `ks_tax_brackets`

本轮验证覆盖税率规范化、行业/阶梯税率持久化、有限税基校验、幂等审计写入、最终写入失败退款流程和 Folia metadata 契约；本模块当前 7 项测试通过。

## 当前限制

- 本轮未部署税务 JAR，也未启动或重启 Paper；动态税率、企业税、罚金和 Web 管理仍需游戏内验收。
- 审计队列拒绝或最终写入失败会尝试退款，但 Vault 退款失败和进程在扣款后崩溃仍可能需要人工核对；这不是完整的外部结算日志。
- 当前业务 SQL 和缓存刷新未在真实 MySQL/MariaDB/PostgreSQL 上完成集成验证，跨服税务结算尚未接线。
- Folia 运行要求匹配的 ks-Eco Folia 制品，并由 Extra loader 校验 `folia-supported=true`；本轮未部署、未启动服务器，征税、退款、罚金和动态税率尚未完成 Folia 实机验收。
- `collect`、`collectEnterpriseTax` 与 `issue` 是同步结算接口，当前只供 Web/数据库工作线程调用；未来事件或 GUI 接线必须新增异步状态机，不能从 Folia 玩家实体线程直接执行同步 JDBC 流程。

## 部署

如后续获准部署，必须从仓库根目录使用统一脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/deploy-plugin.ps1 `
  -Workspace . -Module ks-Eco-tax `
  -Artifact ks-Eco-tax/target/ks-Eco-tax-1.1.0.jar `
  -DeployJar test_1_21/plugins/ks-Eco/extra/ks-Eco-tax-1.1.0.jar `
  -PluginId ks-Eco-tax
```
