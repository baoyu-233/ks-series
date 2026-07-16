# Claude Memory Import

> [English](claude-import.en.md) | 中文

来源：`.claude/ks-server-ops-memory.md`，最后更新时间 2026-07-11 至 2026-07-14。以下内容经过当前代码或项目记忆复核后纳入经济知识库。

## 已采纳

### 企业与银行权限架构

- 企业和银行 Access Provider 属于对应 Extra，`ks-Eco` 只持有桥接接口。
- 企业使用角色模板和个人授权，所有者拥有全部权限。
- `MANAGE_FUNDS`、`BLINDBOX_DRAW`、`MANAGE_BIDDING` 等权限职责分离。
- 银行的贷款发放与贷款审批分权，服务端必须执行权限检查。

当前 `EnterprisePermissionService`、`EnterpriseAccessProviderImpl` 和相关管理器仍符合这些原则。

### 玩家 Web 产品边界

- 玩家 Web 不需要市场购买、挂单创建、求购创建、物流或暂存箱操作。
- Web 应保留真实 API、权限和对象级授权，不补造历史或图表数据。
- 详情交互优先采用概览/列表/地图到侧边详情的结构。

### 安全审计经验

- 浏览器传入的采购总价不能被信任，服务端按单价和数量重算。
- 角色名称不能代替显式权限。
- 企业、银行、项目和采购的文本输出需要按不可信数据处理。

## 未采纳为当前事实

- 旧 JAR 大小、旧备份路径、当时“未构建/未部署”的状态。
- 旧单文件 Web 结构和旧静态预览端口。
- 已被后续修复或重新部署覆盖的编译阻塞、部署清单和待办。
- 与经济知识库无关的 Leaves、KSBot、维护模式和服务器参数。

这些信息仍保留在原 Claude 文件中供历史追溯，但不能覆盖 `CODEX_MEMORY.md` 的当前状态。
