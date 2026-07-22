# Bug Hunt Handoff - 2026-07-18

> Resume here next session. Also read docs/CODEX_MEMORY.md and docs/CODEBASE_MAP.md.

## Status

- User asked to wrap up, continue, then save progress.
- Code fixes landed for batches 1-4.
- No JAR deploy. No Paper start/restart.
- Keep cross-server features disabled until transport/cursor/cache/lease and multi-db tests are done.

## Workspace

- Root: C:/Users/baoyu/Downloads/ks_series
- Git metadata may be unavailable.
- Prefer ASCII in code. Avoid PowerShell UTF-8 BOM on Java files.
- Deploy only via scripts/deploy-plugin.ps1; backups only under root backup/.

## Completed batches

### Batch 1
- Dungeon revive validation + refund on persistence failure
- Dungeon rewards reward_status NONE/PENDING/GRANTED
- Enterprise dividend recovery scoped by node/lease; PARTIAL blocks repay
- Bank loans PENDING_PAYOUT until wallet credit
- Tax still refunds after disconnect/runtime failure
- Inherit claims status before inventory delivery

### Batch 2
- HWP auth/debug/clear-cache path hardening
- ItemEditor session overwrite + template fidelity
- Title image decode size bounds
- RealEstate fail-closed cache protection

### Batch 3
- InstanceWorld finishRelease frees grid only if occupied_by still matches
- paste/cleanup uses union of arena radius and schematic bounds
- LimitedSale sold+qty<=total_stock; charge/refund pin same CashBackend
- RPG required-proofs validation; candidate config reload before catalog swap

### Batch 4
- Politic SENATE_VOTING finalizes only when remaining ballots cannot reverse absolute majority
- Sentinel requeues failed audit flush batch after rollback
- KSBot blocks Leaves infinite state actions sneak/swim/move when infinite is disabled

## Verification

- Batch 1 modules tested/packaged earlier
- Batch 3: ks-InstanceWorld test, ks-RPG test, ks-Eco compile
- Batch 4: ks-Eco-politic / ks-Sentinel / ks-Compat compile
- No deploy and no Paper start for any batch

## Docs already synced

- docs/CODEX_MEMORY.md batches 1-4
- docs/CODEBASE_MAP.md batch 3-4 contracts
- docs/BUG-HUNT-2026-07-18.md and .en.md status headers
- temp 	mp_*.py scripts cleaned

## Key files

- InstanceWorld: InstanceStore.java, InstanceWorldService.java, CanvasService.java
- LimitedSale: LimitedSaleManager.java
- RPG: ProgressionCatalog.java, KsRpg.java
- Politic: ks-Eco-politic/.../VoteManager.java
- Sentinel: ks-Sentinel/.../KsSentinel.java
- KSBot: ks-Compat/.../BotManagerModule.java

## Next open work

1. Currency idempotency case collision (JdbcCurrencyLedger)
2. Currency fee split bypass (ExchangeRule)
3. BotGuard listener restore after MMO reload
4. Maven fake-green / main tests to JUnit
5. Ticket settlement journal residual
6. Cross-server transport/cursor/cache/lease (keep disabled, last)
7. MySQL/PostgreSQL business schema gaps

## Constraints

- Do not deploy or start Paper unless user asks
- Do not half-enable cross-server work
- Small patches + module compile/test
- Chinese replies; do not expand scope without request

## Next open steps

1. Read this handoff + CODEX_MEMORY + CODEBASE_MAP
2. Continue from open work item 1 unless user redirects
3. After fixes, sync memory/map/bug-hunt status
4. On wrap-up/save-progress, only docs/verification cleanup

## Continuation Update - 2026-07-19

This section supersedes the earlier `Next open work` list while preserving it as the original handoff snapshot.

### Completed batch 5

- Currency identifiers use binary collation on MySQL/MariaDB; case-distinct idempotency keys no longer collide.
- Exchange fees round up in target minor units, and alternative-payment validation fails closed on invalid tenders,
  duplicate currencies, unbalanced postings, or idempotency-key reuse with a different tender.
- BotGuard reconciles protected HandlerLists on the server thread, prunes stale wrappers after MMO internal reload,
  deduplicates original/wrapper identities, and restores an original only while its tracked wrapper is still live.
- The ks-Eco main-based contract suites now run through JUnit/Surefire instead of producing a zero-test green build.

### Completed batch 6

- Bank payout flow is `PENDING_PAYOUT -> PAYOUT_SETTLING -> ACTIVE`; uncertain restart state becomes
  `RECONCILE_REQUIRED`. Interest accrual is based on the prior balance with idempotent postings, and fixed credit
  quotes now include score/tier, risk and term adjustments.
- RealEstate supports `FLAT` and `PER_BLOCK` pricing, minimum prices, per-plot limits and player/enterprise soft and
  hard held-area limits.
- Dungeon tickets use a durable charge/admission/refund journal with restart recovery and whole-party validation.
  Completion rewards use per-player/per-reward-key grants with retry and manual-review states.
- Politics enactment-journal and electorate-snapshot work exists, but tax-schema execution, historical-vote filtering,
  Web failure propagation and final regression are still being closed; do not mark politics complete yet.

### Gameplay foundations added

- Official warehouse finite-stock liquidation lots and idempotent purchase receipts, CASH-only at this stage.
- Demand-campaign persistence with finite budgets, CAS progress, per-player limits and idempotency; disabled by default
  with no scheduler, UI or Vault settlement wiring.
- Read-only `RPG_PROJECT` major-order progress source; unavailable sources fail closed instead of accepting manual data.
- Fair participant rotation for configured RPG drops, strict mechanic-category validation and non-overwriting automatic
  extraction of all five `content/**/*.yml` categories.
- Second-wave and third-wave staging content, two low-frequency field elites, and isolated Ashen Foundry, Aurora
  Packwarden and Stormforge Overseer Boss packages.
- A default-disabled Season storage/status foundation and dedicated database worker. No season events or player
  mutations start while `season.enabled=false`.

### Verification snapshot

- Current Surefire reports: ks-Eco 64, ks-RPG 28, ks-Eco-bank 13, ks-Eco-RealEstate 9,
  ks-Eco-RealEstateDungeon 12, ks-BotGuard 2 and ks-Eco-politic 8; all have zero failures/errors/skips.
- Demand passed the complete ks-Eco suite five consecutive times in isolated copies. RPG packaging contains all five
  second-wave content categories. Boss/content packages passed strict YAML and static reference checks.
- The final politics regression and the root dependency-ordered build matrix still need the main-task closeout.
- No JAR was deployed, Paper was not started or restarted, and no live MySQL/MariaDB/PostgreSQL or in-game acceptance
  test was run.

### Remaining open work

1. Keep cross-server transport/cursor/cache/lease and P0 economy workflow wiring disabled until real multi-database
   business-schema and settlement tests pass.
2. Remaining blockers are bank auction escrow/contract transfer, market pending startup recovery and durable post-charge
   compensation, Dungeon ACTIVE-instance/offline-proof recovery, Boss instance ownership, and live remote-database tests.
3. Treat all three Boss packages as staging only until instance ownership, phase locking, helper cleanup, particle
   budgets, proof gates, breakthrough transactions and Dungeon reward hooks pass live tests.
4. The dependency-ordered Maven matrix plus Web JS, YAML, asset-reference and documentation checks are complete.
5. If deployment is later authorized, build first and use `scripts/deploy-plugin.ps1`; keep backups only under root
   `backup/`. Do not start or restart Paper without explicit user approval.

## 中文最终交接增量 - 2026-07-19

> 本节覆盖早期 `Next open work` 的执行状态，但保留原清单作为历史快照。不要修改英文 Bug Hunt 文件、PLAYER README 或 KS-SERIES-REPORT。

### 早期开放项状态

1. **已修：Currency idempotency case collision。** MySQL/MariaDB 标识列和迁移使用二进制排序规则，并已有方言/重复初始化测试。
2. **已修：Currency fee split bypass。** 手续费按目标最小单位向上取整；alternative tender、posting 守恒和幂等换币种均 fail closed。
3. **已修：BotGuard listener restore。** 主线程周期 reconcile、wrapper/original 去重和失效追踪已落地。
4. **已修：Maven fake-green。** 原 main 合同测试已转为 JUnit/Surefire；远程数据库和 Paper 实机仍未验证。
5. **已修：Ticket settlement journal。** 扣款、入场、退款、重启恢复和未知结果人工复核已落地。
6. **仍开放：Cross-server transport/cursor/cache/lease。** 保持禁用，不允许半接线。
7. **仍开放：MySQL/PostgreSQL business schema gaps。** 没有真实远程业务集成测试，不得宣称完整支持。

### 当前最高优先级

1. **已修 Dungeon 多 Boss/资格误发：** 只有全部登记 Boss 死亡才通关，`LEFT` 不进奖励 roster，离线/跳过/未实际写入的 proof 不标 `GRANTED`。
2. **P0 Dungeon 实际复活：** 当前付费路径仍没有重生拦截、检查点传送、血量和游戏模式恢复。
3. **P1 离线 proof：** 当前会停在复核/重试，不再丢证明，但仍需按 UUID 的 `RpgProgressionApi`/outbox 自动补发。
4. **P1 ACTIVE Dungeon 恢复：** 重启需要从数据库重建玩家、Boss objective 和 InstanceWorld 映射，并处理世界已经释放但经济行仍 ACTIVE 的不一致。
5. **已修 Season/RPG 严格性：** 服务端时间窗/周索引、归档写竞态、非法配方输入和非有限概率/权重均已有测试覆盖。
6. **P1 Boss 实例 ownership：** helper 必须绑定父 Boss/实例 ID，计数与清理不能使用裸 `NEAREST`/半径；Ashen 阶段转换和 AI 回调要统一状态机。
7. **P1 玩法闭环：** Dungeon 必须执行 entry gate；突破事务需要原子校验 proof、MMOItems 材料与 `currency_id=CASH`，并提供幂等、溢出存储和失败补偿。
8. **已修央行默认注资：** API 兼容调用与 Web 表单统一为 `LOAN`，不再默认提交已禁用的 `GRANT`。

### 最终验证矩阵

- Java 21 下全部 23 个 Maven 模块 `clean test package` 成功；11 个有测试模块合计 187 项测试，0 failure、0 error、0 skipped。
- 最终测试计数：ks-core 4、ks-Eco 71、ks-InstanceWorld 5、ks-RPG 45、ks-BotGuard 2、ks-Skill 3、ks-Eco-bank 22、ks-Eco-enterprise 1、ks-Eco-RealEstate 9、ks-Eco-politic 8、ks-Eco-RealEstateDungeon 17。
- 外部 Web JavaScript 28/28、HTML 内联脚本 6/6、严格 YAML 311/311 通过；五个新增内容包的 47 个 YAML、6 个 JSON 值及全部静态引用闭合。
- 唯一静态残余是全仓无引用的旧 `test_token.json` 格式损坏；没有修改该历史测试凭据文件。

### 验证和上线限制

- 三个 Boss 包、第二波/第三波 RPG 内容和 Season 均是 staging/default-disabled；静态 YAML/ID 检查不是实机验收。
- 未执行 `/mm reload`、多实例 Boss、死亡/断线/重连、奖励离线补发、GUI、ModelEngine 或性能验收。
- 未部署任何 JAR，未启动或重启 Paper，未运行真实 MySQL/MariaDB/PostgreSQL 业务测试。
- 下一轮优先完成 Dungeon 实际复活、ACTIVE 恢复、proof outbox 和 Boss 实例 ownership；之后再做 entry gate/突破闭环、银行拍卖 escrow 与真实远程数据库验证。

## 2026-07-19 基础设施与经济最终增量

- transport 已改为数据库单调发布序号和服务器隔离 cursor，补齐迟到事件、广播 consumer 竞争、CAS 重载和同 payload 重复发布测试。
- cache 每个唯一失效都生效，监听失败可重试，物理创建时间不继承 future HLC；cross-server/EcoDatabase lease release 均提升 fencing token。
- 普通市场新增持久 settlement 和 `MARKET_PENDING` 隐藏交付；企业 Web 的公户 escrow、保证金和企业预付款改为同库事务，个人工程外部钱包路径失败关闭。
- 银行拍卖 escrow/退款/成交 claim、项目合同交割、采购单、限时销售、副本复活/ACTIVE 恢复与离线奖励重试均已在当前源码和模块测试中覆盖。
- 23 个模块构建成功，207 项测试全绿；Web JS、内嵌 JS、严格 YAML、插件入口和本地资源引用检查通过。
- 仍未完成真实 MySQL/MariaDB/PostgreSQL 业务迁移与实测、跨服 P0 全运行时接线、个人工程/房产等旧 Vault journal 和 `REVIEW_REQUIRED` 管理工具；个人工程资金和房产市场当前失败关闭。本轮未部署 JAR，未启动或重启 Paper。

## 2026-07-20 基础设施/P0/P1/经济收口增量

> 本节只追加当前执行状态，不改写上方历史发现；RPG/Boss 开放项按用户要求暂缓。

- 上一节所述个人工程、个人房产和统一 `REVIEW_REQUIRED` 管理工具已完成；企业房产仍失败关闭，直到企业公户收款与产权结算接线完成。
- 内置经济已修复并发入账覆盖、创建账户清零余额和 SQLite-only upsert/时间函数；Web 关键配置、成员、权限、银行利率与企业公户 DML 已改为带 savepoint 和 affected-row 校验的便携 mutation。
- `KsEco` 核心业务迁移改为业务组件启动前 fail closed；补列不再吞掉非重复列 SQL 异常，央行默认配置不会在重启时覆盖管理员值。
- 最终矩阵为 23/23 模块、319 项测试全绿；外部 JS 22/22、HTML 内联 6/6、Java 生成脚本 12/12、严格源 YAML 311/311、插件入口 17/17、85 个源资源和 25 个本地引用通过。
- 经济核心、Web、银行、企业、政治、税、地产和副本的目标 schema/upsert 已改为方言辅助；最终审计残余命中只位于明确的 SQLite/PostgreSQL 方言分支。真实 MySQL/MariaDB/PostgreSQL 仍未连接，H2 兼容模式不能证明存量迁移、真实锁语义或跨服结算可用。
- 跨服业务 fencing/invalidation、真实 Vault/产权/物品崩溃窗口和 Paper 实机流程仍不得宣称完成。
- 本轮未部署任何 JAR，未启动或重启 Paper。

## 2026-07-20 最终 P0/P1/经济修复状态

> 本节继续只追加状态，不改写上方历史发现；RPG/Boss 内容仍按用户要求暂缓。

- **已修：Dungeon 实际付费复活。** `DungeonDeathHandler`/`DungeonReviveStore` 已实现异步 SQL journal、主线程 Vault/血量/GameMode/安全检查点传送、in-flight + CAS 双重幂等、登录/启动恢复和未知结果人工复核；重复请求不会再次扣款。
- **已修：企业房产结算。** `MANAGE_PROPERTY` 控制挂牌，挂牌和成交前复核权限；企业卖款、公户、开户行资金镜像与 property journal 终态同一 SQL 事务提交，失败回滚，不再把企业房款打入成员个人钱包。
- **已验证：本机原生 PostgreSQL/MariaDB。** `ks-Eco` 集成测试会启动实际数据库进程，重复初始化核心经济、Web、普通/房产/限时结算、跨服协调、多货币账本和需求活动表并完成余额读写。真实 MySQL、外部远程存量迁移、生产锁语义和多节点压力仍未验收。
- **最终矩阵：** 23/23 个 Maven 模块按依赖顺序执行 `clean install`（包含 `test/package`），331 项测试，0 failure/error/skipped。外部 JS 22/22、HTML 内联 6/6、Java 运行时生成脚本 12/12、严格源 YAML 310/310、插件入口 17/17、85 个源资源和 25 个本地引用通过。
- **最终 Web 回归修复：** `ks-Inherit` 两个 Java 文本块的单引号改为双层转义，避免 Java 生成页面时吃掉反斜杠并使详情点击脚本语法错误；受影响模块已重新 `clean install`。
- **仍开放：** 跨服业务 mutation 的 fencing/invalidation 全运行时接线、真实 MySQL/外部远程存量库、多节点锁语义、旧游戏内工程评标、非 `CASH` 玩家结算、需求活动/官方清算交付，以及真实 Paper/Vault/GUI/死亡重生/崩溃窗口验收。
- 本轮未部署任何 JAR，未启动或重启 Paper。

## 2026-07-20 跨服运行时追加状态

> 本节只追加当前状态，不改写上方交接历史。

- 跨服 P0 运行时接线已完成：数据库 poller 生命周期、发布重试、价格/企业等级/财富榜/地产保护与政治状态失效、价格事务 fencing、银行周期维护 lease、过期事件清理和身份/运行时健康 fail-closed 已启用；配置默认仍为 false。
- 启用条件为共享 MySQL/MariaDB/PostgreSQL、各节点唯一 `database.server-id`，以及内置共享经济或显式声明共享的外部 Vault 经济。SQLite、本地 fallback、重复身份和未声明共享的外部经济会拒绝启动。
- 共享 SQLite 双节点测试及真实 PostgreSQL/MariaDB transport/lease 测试通过。最终 23/23 模块、333 项测试和全部 Web/YAML/资源静态检查通过；真实 MySQL、外部远程存量迁移、真实 Paper/Vault 双节点和生产故障/压力仍未验收。
- 本轮未部署任何 JAR，未启动或重启 Paper。
