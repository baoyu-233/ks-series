# ks-Eco-bank v1.1.0

> [English](README.en.md) | 中文

**中央银行与商业银行系统** — 玩家银行、存贷业务、央行宏观调控、M0/M1/M2 货币供应量追踪。

## 依赖

- **ks-core**: Web 网关 + 数据存储
- **ks-Eco** (v1.1.0+): 经济核心

## 功能

- **中央银行**: 全局唯一（自动创建，ID: CB-xxxxxx，初始资本 1 亿）
  - 基准利率: 3.5%
  - 准备金率: 10%
- **商业银行**: 玩家创建（最低 2 名所有者或 50,000 资本）
- **存款**: 活期存款 / 定期存款
- **贷款**: 贷款利率 8%，支持还款
- **货币供应追踪**: M0（现金）、M1（M0 + 活期存款）、M2（M1 + 定期存款）
- **Web 面板**: `/ks-Eco/bank`

## 数据表

`ks_bank_banks`, `ks_bank_accounts`, `ks_bank_loans`, `ks_bank_cb_rates`, `ks_bank_money_supply`

## 部署

编译后放入 `plugins/ks-Eco/extra/`:
```bash
cd ks-Eco-bank && mvn clean package
cp target/ks-Eco-bank-1.1.0.jar ../test_1_21/plugins/ks-Eco/extra/
```
