# ks-Eco-tax v1.1.0

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
- **最低税额**: 1.0 货币单位（防止小额交易零税）
- **惩罚机制**: 逃税 30%、合同违约 20%、欺诈 50%
- **Web 面板**: `/ks-Eco/tax`

## 数据表

`ks_tax_records`, `ks_tax_penalties`, `ks_tax_rates`

## 部署

```bash
cd ks-Eco-tax && mvn clean package
cp target/ks-Eco-tax-1.1.0.jar ../test_1_21/plugins/ks-Eco/extra/
```
