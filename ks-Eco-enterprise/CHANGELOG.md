# ks-Eco-enterprise 修改日志

## 2026-07-23

- 声明 `folia-supported=true`，逐玩家 Vault 调用按实体/global owner 调度并保留十秒超时取消。
- Folia 内置钱包改用 UUID 直连数据库接口，批量分红按收款人分别取得合法调度 owner。
- 新增 Folia metadata 合同测试；模块 4 项测试通过。未部署，未启动服务器。

## 2026-07-22

- 企业加入、审批、退出、解散和权限边界改为持久治理流程。
- 企业项目保证金、escrow、预付款和企业房产收入使用同库事务。
- 分红增加逐收款人 journal、税额明细、节点归属和未知结果阻断。
- 修复 MariaDB journal 索引类型与旧税率列兼容；3 项测试通过。
