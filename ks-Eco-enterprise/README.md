# ks-Eco-enterprise v1.1.0

> [English](README.en.md) | 中文

**现代企业与招投标系统** — 企业注册/管理、招投标、分包拼包、资质校验。

## 依赖

- **ks-core**: Web 网关 + 数据存储
- **ks-Eco** (v1.1.0+): 经济核心

## 功能

- **企业注册**: 名称、类型（PRIVATE/STATE）、法人、注册资本（从所有者扣除）
- **加入审批**: 玩家申请后进入待审批队列，拥有成员管理权限的管理者批准后才写入成员关系
- **成员退出与移除**: 普通成员可二次确认退出；管理者可移除成员；所有者不能绕过所有权处理直接退出
- **企业解散**: 单所有者企业二次确认后解散；存在活动/逾期贷款或待处理贷款申请时禁止解散
- **企业分红**: 按毛额计算分红税和净额，兼容 `0.10` 与旧式 `10` 税率，记录批次、成员到账和税务流水
- **分红结算日志**: 使用 `PENDING/PAID/COMPENSATED/COMPENSATION_REQUIRED` 状态记录逐成员结果；不确定或 `PARTIAL` 批次会阻止继续分红，启动恢复只处理本节点/本实例持有的未完成记录
- **企业等级**: 管理员维护企业等级；盲盒卡池和企业地块加成可按等级限制或缩放
- **项目招标**: 发布项目（预算、预付款比例、保证金、罚金比例、截止日期、地点）；企业发布方的预付款资金进入项目托管
- **投标**: 企业提交报价，资质要求：注册资本 ≥ 预算 × 75%
- **评标与保证金**: 官方/国企项目强制按价格、资质和时效综合评分；私企可自主选择或使用综合评分，配置保证金时须缴纳后才确认中标
- **托管预付款**: Web 评标路径只从项目托管余额发放预付款；旧 `BiddingManager.awardProject` 因无法证明托管和可恢复结算而明确拒绝执行
- **企业资金事务**: 企业项目发布、公户 escrow、企业投标保证金和企业中标预付款使用同一数据库事务，不再把企业公户余额与项目状态拆成多次提交
- **个人工程结算**: Web 个人保证金/预付款使用核心模块的持久 journal；外部钱包认领、保证金托管、预付款发放、最终提交和确定失败补偿均有状态记录，启动恢复会把中断中的钱包调用送入按阶段校验的人工复核
- **企业房产结算**: `MANAGE_PROPERTY` 控制企业房产挂牌；挂牌和成交前复核权限，卖款与企业公户、开户行资金镜像及核心 property journal 终态在同一 SQL 事务提交，不会打入成员个人钱包
- **分包/联合体**: 支持 subcontract 和 consortium 模式
- **Web 面板**: `/ks-Eco/enterprise`；管理员可编辑名称、描述、类型、地区、行业、所有者、资本、余额、分红比例、状态和等级

## 数据表

`ks_ent_enterprises`, `ks_ent_members`, `ks_ent_join_requests`, `ks_ent_projects`, `ks_ent_project_escrow`,
`ks_ent_bids`, `ks_ent_bid_deposits`, `ks_ent_project_wallet_settlements`, `ks_ent_dividends`,
`ks_ent_dividend_payouts`, `ks_ent_dividend_settlements`

加入请求、退出、移除和解散均会维护成员计数及申请状态。分红税另写入核心税务流水；企业余额编辑会同步企业账户与银行资产镜像。

本轮模块级验证覆盖旧评标入口的失败关闭边界、企业资金同库事务、个人保证金/预付款 journal、企业房产公户结算、人工复核阶段约束，以及企业主表、权限/角色模板和分红 journal 的便携 schema/upsert；最终依赖顺序矩阵中本模块 3 项测试通过。

## 当前限制

- 本轮未部署企业 JAR，也未启动或重启 Paper。加入审批、退出、解散、分红补偿和保证金链路仍需游戏内验收。
- 企业发布项目时会从公户原子预留预付款；官方财政来源、撤销退款和国库对接尚未完整接线。个人保证金/预付款 journal 当前只覆盖 Web 评标路径，旧游戏内评标入口继续失败关闭。
- 项目托管与保证金目前仍是基础链路，工程里程碑、交付验收、尾款、违约处罚和项目完成结算尚未形成完整玩法闭环。
- 个人中标的保证金/预付款外部钱包窗口已有持久 journal；分红和其他旧 Vault 付款/退款路径仍可能存在需继续 journal 化的进程崩溃窗口。`REVIEW_REQUIRED` 只表示结果待管理员确认，不能视为自动恢复成功。
- 核心 schema 已在本机原生 PostgreSQL/MariaDB 进程验证，但企业模块尚未完成真实 MySQL、外部远程存量库和多节点集成验收，不能宣称跨服企业结算可用。

## 部署

如后续获准部署，必须从仓库根目录使用统一脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/deploy-plugin.ps1 `
  -Workspace . -Module ks-Eco-enterprise `
  -Artifact ks-Eco-enterprise/target/ks-Eco-enterprise-1.1.0.jar `
  -DeployJar test_1_21/plugins/ks-Eco/extra/ks-Eco-enterprise-1.1.0.jar `
  -PluginId ks-Eco-enterprise
```
