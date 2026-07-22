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
  - 流动性支持仅允许形成可追踪、需偿还的 `LOAN`，不提供无审计的 `GRANT`
- **商业银行**: 玩家创建（最低 2 名所有者或 50,000 资本），提供经营驾驶舱、A-E 评级和 `NORMAL/WATCH/RESTRICTED/RESOLUTION` 处置状态
- **存款产品**: 活期账户，以及 7/30/90 日定期和 180 日大额存单；提前支取只扣减已产生的利息，不侵蚀本金，到期可自动续存
- **存款保险与处置**: 商业银行按月缴纳保费，保障按存款人合并活期与有效定期、每人最高 100,000；处置时按资产回收、受保缺口补助、未保险余额折损的瀑布计算，并由桥接银行承接账户、贷款、抵押物和拍卖
- **贷款产品**: 消费、住房、经营、项目四类目录，支持到期一次还本付息、等额本息和等额本金报价；住房可抵押地块/房屋（75% LTV），经营可抵押地块/房屋（60% LTV），项目贷抵押个人项目合同（70% LTV）
- **信用与报价**: 根据已还、在贷、当前/历史逾期和近期申请计算 A-E 信用档；档位控制额度系数、最长期限与风险加点，期限加点和报价有效期均可配置
- **贷款展期**: 借款人可申请 7/14/30 日展期，银行审批后原子更新到期日、剩余应还、展期次数并重建还款计划；最多两次并收取明确费用
- **引导贷款**: 独立引导银行提供受总额/每日额度、准备金和逾期状态约束的新手贷款，不绕过正常负债记录
- **贷款可靠性**: 可配置额度/期限/并发上限，申请时固定审批报价；贷款先进入 `PENDING_PAYOUT`/`PAYOUT_SETTLING`，钱包确认入账后才激活，启动时不确定状态转为 `RECONCILE_REQUIRED`；管理端可按原阶段确认放款成功或失败，并恢复贷款、银行流动性、申请与抵押状态
- **企业与央行还款**: 企业贷款按旧余额/旧状态 CAS，央行贷款使用 `OPEN -> CLAIMED -> REPAID` 原子认领；违约没收在产权或拍卖副作用前先认领 `DEFAULTING`
- **抵押与拍卖结算**: 个人抵押在申请时预占、放款后锁定、结清后释放；逾期宽限结束后进入违约并生成抵押拍卖。竞价使用持久 escrow、版本 CAS 和退款 journal，流拍按折价重新挂牌；成交先原子认领 `SETTLING`，再消费中标 escrow、增加银行资产并交割抵押物
- **周期计息**: 以周期内时间加权平均余额计息；存取款前先结清已完成周期，唯一周期流水和状态版本阻止重复计息或并发覆盖；跨服启用时，利息、逾期、央行回收和违约维护由集群独占 lease 保证单节点执行
- **经营核算**: 汇总流动性、存款负债、贷款资产、权益、资本/流动性比率、坏账率、利息收入、活期/定期利息成本、损失准备和留存收益
- **股权与控制权**: 商业银行拥有发行总量、授权股本、股东持仓与预留股份台账；支持一级增资和股东二级挂牌/成交/撤单，成交后同步控制股东；分红按实际持股比例精确分配，批次和逐股东入账在同一数据库事务完成
- **保险基金与桥接清算**: 管理端可查看/补充保险基金、预览处置损失瀑布并执行带二次确认的桥接清算；失败银行须先进入 `RESOLUTION`，桥接行必须正常经营，清算批次和逐存款人赔付记录可追溯
- **央行周期事件**: 管理端可创建利率、流动性、地产、违约潮和存款竞争事件；事件按时间窗自动进入 `SCHEDULED/ACTIVE/ENDED`，只影响后续报价与风险评估
- **线程边界**: Vault 调用回到服务器线程，数据库事务不跨服务器线程回调
- **货币供应追踪**: M0（现金）、M1（M0 + 活期存款）、M2（M1 + 有效定期存款）
- **Web 面板**: `/ks-Eco/bank`

## 数据表

`ks_bank_banks`, `ks_bank_accounts`, `ks_bank_loans`, `ks_bank_loan_requests`, `ks_bank_guidance_config`,
`ks_bank_guidance_claims`, `ks_bank_interest_state`, `ks_bank_interest_postings`, `ks_bank_cb_rates`,
`ks_bank_money_supply`, `ks_bank_deposit_products`, `ks_bank_term_deposits`, `ks_bank_loan_schedules`,
`ks_bank_risk_state`, `ks_bank_policy_events`, `ks_bank_dividend_batches`, `ks_bank_dividend_payouts`,
`ks_bank_restructure_requests`, `ks_bank_player_collateral`, `ks_bank_equity_state`, `ks_bank_share_ledger`,
`ks_bank_share_offerings`, `ks_bank_share_transactions`, `ks_bank_insurance_fund`,
`ks_bank_insurance_membership`, `ks_bank_resolution_cases`, `ks_bank_resolution_claims`

本轮验证覆盖信用分层与固定报价、个人实物抵押锁定/释放/违约拍卖、定期到期/提前支取、放款与还款复核、平均余额计息、股权发行/交易/加权分红、存款保险损失瀑布、桥接清算、企业还款 CAS、央行贷款原子认领和拍卖 escrow。`ks-Eco-bank` 共 51 项测试通过；全仓依赖顺序验证共 347 项测试通过。

## 当前限制

- `RECONCILE_REQUIRED` 只表示外部 Vault 放款结果无法自动证明；管理端提供显式裁决工具，但仍必须依据实际钱包流水人工确认，不能把未知结果自动当作成功或失败。
- 银行 schema、配置迁移、账户/利率/权限 upsert 和关键结算已通过 SQLite 与兼容方言测试；真实 MySQL、远程存量库迁移、真实 Paper 双节点和生产锁语义仍未完整实测。
- 保险赔付、未保险存款折损与桥接清算已实现为游戏内数据库处置模型，但尚未对真实存量数据执行不可逆清算验收；资产回收折价和保险基金参数仍是简化的玩法规则。
- 抵押拍卖、放款与还款都对确定状态提供持久恢复；外部 Vault 在进程崩溃瞬间仍存在无法由数据库单独证明结果的窗口，此类记录必须进入人工复核。

## 部署

本轮已从仓库根目录使用统一脚本部署到测试服并完成备份、复制和哈希校验。后续部署仍必须使用同一入口：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/deploy-plugin.ps1 `
  -Workspace . -Module ks-Eco-bank `
  -Artifact ks-Eco-bank/target/ks-Eco-bank-1.1.0.jar `
  -DeployJar test_1_21/plugins/ks-Eco/extra/ks-Eco-bank-1.1.0.jar `
  -PluginId ks-Eco-bank
```

2026-07-22 当前测试服银行 JAR SHA-256 为
`5025BF69C9B25CE8EE7CCD6F838CC9CADECFEABAACAA6AEC6FF38B1B5C3EAF9F`，备份 ID 为
`ks-Eco-bank-20260722T085105042Z-223603d2022a`。与之配套的最终 ks-Eco SHA-256 为
`4973DCCF548F7E9F5A4CD9CFC75D8D5B8BDA9D5D4C55988D32A7E4A0651DD195`，备份 ID 为
`ks-Eco-20260722T090153739Z-57e58c5ab8b3`。Paper 于 17:08:53 完成启动，ks-Eco、银行 Extra 与其余五个 Extra 正常启用。内置浏览器已实际点击玩家抵押贷款、股权区、管理员风险与保险、桥接清算和放款复核入口，两端控制台均无错误；没有执行不可逆清算或真实资金操作。
