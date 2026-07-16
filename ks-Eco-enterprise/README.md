# ks-Eco-enterprise v1.1.0

> [English](README.en.md) | 中文

**现代企业与招投标系统** — 企业注册/管理、招投标、分包拼包、资质校验。

## 依赖

- **ks-core**: Web 网关 + 数据存储
- **ks-Eco** (v1.1.0+): 经济核心

## 功能

- **企业注册**: 名称、类型（PRIVATE/STATE）、法人、注册资本（从所有者扣除）
- **企业解散**: 剩余资产按比例退还所有者
- **项目招标**: 发布项目（预算、预付款比例、罚金比例、截止日期、地点）
- **投标**: 企业提交报价，资质要求：注册资本 ≥ 预算 × 75%
- **最低价中标**: 自动评标，中标企业获预付款
- **分包/联合体**: 支持 subcontract 和 consortium 模式
- **Web 面板**: `/ks-Eco/enterprise`

## 数据表

`ks_ent_enterprises`, `ks_ent_members`, `ks_ent_projects`, `ks_ent_bids`

## 部署

```bash
cd ks-Eco-enterprise && mvn clean package
cp target/ks-Eco-enterprise-1.1.0.jar ../test_1_21/plugins/ks-Eco/extra/
```
