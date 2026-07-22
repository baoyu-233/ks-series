# Economy Verification Log

> [English](verification-log.en.md) | 中文

## 2026-07-23 三端全功能与跨服资产验收

- 23/23 Maven 模块成功，391 项测试 0 failure/error/skipped；外部 Web JavaScript 22/22、严格源 YAML 341/341、插件入口 17/17、本地资源引用 25/25。
- Leaves/Folia 各 29 项 HTTP 合同返回预期状态：27 个 200、未认证管理员接口 401、缺少必填 `bankId` 400。Paper 按角色关闭 Web；三端状态、reload 和 list 命令均成功。
- MariaDB、BungeeCord、Leaves、Paper、Folia、MCSM/daemon 与双 Web 共 9 个端口监听。Extra 数量为 Leaves 6、Paper 4、Folia 6。
- 跨服 MAP bundle 实测 3 图块且非 stale/offline；PROPERTY 聚合实测 4 套、总展示价 375,000,000 最小单位。跨服 transfer 保持关闭。
- Folia 无 FAWE/WorldEdit、MythicMobs，不能把 Extra 加载等同于 schematic/Boss 全流程通过；三端均无真实在线玩家，资金/背包/GUI/崩溃窗口仍待真人验收。
- 完整矩阵、部署哈希、备份和限制见 `../KS-ECO-FULL-FUNCTION-TEST-2026-07-23.md`。

## 2026-07-16：限时销售与玩家盲盒线程分离

- `mvn test`：通过，但项目没有 Java 测试源码。
- `mvn clean package -DskipTests`：通过。
- 21 个 Web JavaScript 文件和本地引用检查：通过。
- 测试服部署 SHA-256：`51EF3C7017FF67B424CC67EACBA6B54044ED245ACC4B033D462C39E78F578CF8`。
- Paper 未启动；限时销售、玩家单抽/十连和限时盲盒只完成静态审计，尚未游戏内实测。

## 2026-07-16：企业票券只读审计

- 源码入口、schema、Web、游戏 GUI、权限和线程路径已核对。
- 测试服 SQLite 只读快照：1 个公共池、23 条抽取日志、其中 1 条企业抽取；0 票券、0 票券使用日志、0 票券审计。
- 结论：当前购买式票券没有采用证据，且存在池门控绕过、资金权限、事务和线程风险。
- 后续状态：审计建议已接受，购买式票券于同日完成退役。

## 2026-07-16：企业等级、票券退役与 Web 测试台

- 企业等级字段、核心缓存、管理员编辑、盲盒最低等级和企业地块福利倍率已编译通过。
- 票券活跃 Java/Web/GUI 入口与新表创建已移除；历史表未删除。
- `ks-Eco` 和 `ks-Eco-RealEstate` clean build 通过；两个模块都没有 Java 测试源码。
- 22 个 Web JavaScript 文件及本地资源引用检查通过。
- Browser 桌面回归验证了测试台四种场景、企业等级编辑、卡池最低等级和玩家端等级锁定；控制台无错误。游戏内 GUI 只完成静态槽位审计，未启动 Paper 实测。
- 部署哈希见 `docs/CODEX_MEMORY.md`。

## 2026-07-19 基础设施与经济收口

- 23 个 Maven 模块按依赖顺序 `clean test package` 全部成功；11 个有测试模块合计 207 项测试，0 failure/error/skipped，其中 `ks-Eco` 83、`ks-Eco-bank` 25、`ks-Eco-RealEstateDungeon` 21。
- 跨服数据库轮询改为服务器隔离的单调发布序号 cursor，补齐 CAS 冲突重载和迟到事件测试；重复发布、缓存失效重试和 lease 释放 fencing 已有合同测试。
- 普通玩家市场增加持久结算与隐藏交付恢复；企业项目发布、企业保证金、escrow 和企业预付款改为同库事务，个人外部钱包工程路径失败关闭。
- Web 外部 JavaScript 28/28、ks-Eco 内嵌脚本 6/6、严格 YAML、17 个插件入口和 45 个本地资源引用检查通过。
- 未部署 JAR，未启动或重启 Paper；未执行真实 MySQL/MariaDB/PostgreSQL、Vault、玩家市场/企业项目 GUI 或跨服运行时验收。

## 2026-07-20 最终编译与经济 SQL 收口

- 23 个 Maven 模块按依赖顺序执行 `clean test package`，全部成功；12 个有测试模块合计 319 项测试，0 failure、0 error、0 skipped。`ks-Eco` 165 项、`ks-Eco-bank` 38 项、`ks-Eco-politic` 15 项、`ks-Eco-RealEstateDungeon` 24 项。
- 内置经济余额表移除 SQLite 专用 upsert/时间函数，入账采用并发安全 update-then-insert，扣款采用余额条件原子更新，创建账户不再覆盖已有余额；H2 MySQL/PostgreSQL 兼容模式及并发/回滚测试通过。
- Web 关键配置、成员、权限、银行利率和企业公户写入统一使用便携 mutation 边界；事务内唯一键竞争使用 savepoint，非约束异常不会被并发重试吞掉。核心迁移失败会在业务管理器和恢复任务启动前停用插件。
- 外部 Web JavaScript 22/22、HTML 内联脚本 6/6、Java 生成脚本 12/12、严格源 YAML 311/311、插件入口 17/17、85 个源资源和 25 个本地引用全部通过。
- 经济核心、Web、银行、企业、政治、税、地产和副本目标 schema/upsert 通过 SQLite、H2 MySQL/PostgreSQL 兼容测试；副本生命周期/门票表和模板 upsert 也已移除 SQLite 专用实现。
- 未部署 JAR，未启动或重启 Paper；H2 兼容模式不是实际 MySQL/MariaDB/PostgreSQL 验收，真实驱动、存量迁移、锁语义、跨服业务接线、Vault/GUI/崩溃恢复仍需实机验证。

## 2026-07-20 P0/P1 与经济最终复跑

- 23 个 Maven 模块按依赖顺序执行 `clean install`（包含 `test/package`），全部成功；12 个有测试模块合计 331 项测试，0 failure、0 error、0 skipped。
- 模块测试数：`ks-core` 5、`ks-Eco` 171、`ks-InstanceWorld` 6、`ks-RPG` 45、`ks-BotGuard` 2、`ks-Skill` 3、`ks-Eco-bank` 38、`ks-Eco-enterprise` 3、`ks-Eco-politic` 15、`ks-Eco-RealEstate` 9、`ks-Eco-tax` 6、`ks-Eco-RealEstateDungeon` 28；其余 11 个模块没有 Surefire 测试。

| 模块 | 结果 | 测试 | 残余非失败警告 |
|---|---:|---:|---|
| ks-core | 成功 | 5 | JDK native-access；`missing.test.Driver` 与队列拒绝故障注入日志 |
| ks-Eco | 成功 | 171 | JDK native-access；SLF4J NOP |
| ks-InstanceWorld | 成功 | 6 | Mockito/Byte Buddy/CDS 测试 JVM 警告 |
| ks-RPG | 成功 | 45 | Mockito/Byte Buddy/CDS 测试 JVM 警告 |
| ks-BossCombat | 成功 | 0 | 无 |
| ks-BotGuard | 成功 | 2 | 无 |
| ks-Skill | 成功 | 3 | 无 |
| KS-ItemEditor | 成功 | 0 | 无 |
| KS-ItemSteal | 成功 | 0 | 无 |
| ks-Maintenance | 成功 | 0 | 无 |
| ks-Title | 成功 | 0 | 无 |
| ksHWP | 成功 | 0 | 无 |
| ks-Compat | 成功 | 0 | 无 |
| ks-Inherit | 成功 | 0 | 无；Web 转义修复后已单独复跑 |
| ks-Cinematic | 成功 | 0 | 无 |
| ks-RPG-Gui | 成功 | 0 | 无 |
| ks-Sentinel | 成功 | 0 | 无 |
| ks-Eco-bank | 成功 | 38 | Mockito/Byte Buddy/CDS 测试 JVM 警告 |
| ks-Eco-enterprise | 成功 | 3 | SLF4J NOP |
| ks-Eco-politic | 成功 | 15 | Mockito/Byte Buddy/CDS 测试 JVM 警告 |
| ks-Eco-RealEstate | 成功 | 9 | Mockito/Byte Buddy/CDS 测试 JVM 警告 |
| ks-Eco-tax | 成功 | 6 | SLF4J NOP |
| ks-Eco-RealEstateDungeon | 成功 | 28 | Mockito/Byte Buddy/CDS 测试 JVM 警告 |

- `ks-Eco` 测试实际启动本机原生 PostgreSQL 与 MariaDB 进程，重复初始化核心业务、Web、结算、跨服协调、多货币账本和需求活动表并完成余额入账/扣款；不再只依赖 H2 兼容模式。真实 MySQL、外部远程存量迁移和多节点锁语义仍未验证。
- 企业房产新增权限复核与企业公户/开户行/journal 同事务卖款结算；副本付费复活新增异步 journal、主线程 Vault/传送、重复请求幂等和未知结果人工复核。
- 静态检查：外部 Web JavaScript 22/22、HTML 内联脚本 6/6、Java 运行时生成脚本 12/12、严格源 YAML 310/310、插件入口 17/17、85 个源资源、25 个本地引用全部通过。检查过程中修复 `ks-Inherit` 两处 Java 文本块单引号转义回归，并重新构建受影响模块。
- 非失败日志仅为 JDK native-access、Mockito/Byte Buddy 动态代理、CDS class-sharing、SLF4J NOP 和测试故障注入警告；没有编译/测试残余失败。
- 未部署任何 JAR，未启动或重启 Paper；真实 Paper/Vault/GUI/玩家死亡重生、崩溃注入和跨服业务运行时仍未验收。

## 2026-07-20 跨服运行时最终接线

- 23 个 Maven 模块按依赖顺序执行 `clean install`。首轮只有 `ks-Eco` 的旧门禁文案断言失败；更新过时断言后仅复跑该模块并成功，最终 23/23 成功、333 项测试、0 failure/error/skipped。
- 测试数变化仅在 `ks-Eco`：171 → 173。新增共享 SQLite 双节点运行时测试验证本地应用、远端广播、echo 去重和 clean stop；新增 JDBC lease 测试验证单 owner、接管 token 递增和旧 token fenced 拒绝。
- 原生 PostgreSQL/MariaDB 集成测试扩展为实际创建 transport store、发布/轮询事件并执行 fenced lease；两种真实数据库进程均通过。真实 MySQL、外部远程存量迁移和真实 Paper 双节点仍未执行。
- 运行时接通价格、企业等级、财富榜、地产保护/福利和政治状态失效；价格刷新事务内 fenced，银行周期维护使用集群独占 lease。共享 DB/journal 仍是市场、暂存、限售和盲盒权威边界。
- 静态检查：外部 JavaScript 22/22、HTML 内联脚本 6/6、Java 生成脚本 12/12、严格源 YAML 319/319、插件入口 17/17、85 个源资源、HTML 本地引用 25/25。
- 残余非失败警告仍只有 JDK native-access、SLF4J NOP、Mockito/Byte Buddy/CDS 和测试故障注入日志。未部署 JAR，未启动或重启 Paper。

## 后续验证要求

- 服务器重启后实测企业等级编辑、低等级拒绝、高等级企业抽取和五类地块福利实际倍率。
- 若未来重做授权额度，必须覆盖权限、同企业校验、数量上限、过期、并发使用、崩溃恢复、完整物品和背包满场景。

## 2026-07-22 银行玩法与部署收口

- 23 个 Maven 模块按依赖顺序全部成功，347 项测试 0 failure、0 error、0 skipped；`ks-Eco` 174 项、`ks-Eco-bank` 51 项。非失败日志仍为 JDK native-access、Mockito/Byte Buddy/CDS、SLF4J NOP 和测试故障注入日志。
- 外部 Web JavaScript 22/22、HTML 内联脚本 6/6、Java 运行时生成脚本 12/12、严格 YAML 319/319、插件入口 17/17、85 个源资源和 25 个 HTML 本地引用通过。
- 银行新增个人实物抵押、违约拍卖、股权发行/交易/按股分红、存款保险、损失瀑布、桥接清算和放款人工复核；最终实际点击发现央行保险与复核卡片没有可见入口，补充“风险与保险”“放款复核”按钮后针对性重新打包部署。
- 内置浏览器玩家端实测了资金网络、全部银行、住房抵押贷和 7 个真实抵押候选；管理端实测了商业银行、保险基金（余额 10,000,050.56）、风险状态、桥接清算和空放款复核清单。两端控制台错误均为 0，没有执行真实扣款、注资或不可逆清算。
- 最终部署哈希：ks-Eco `4973DCCF548F7E9F5A4CD9CFC75D8D5B8BDA9D5D4C55988D32A7E4A0651DD195`（备份 `ks-Eco-20260722T090153739Z-57e58c5ab8b3`）；ks-Eco-bank `5025BF69C9B25CE8EE7CCD6F838CC9CADECFEABAACAA6AEC6FF38B1B5C3EAF9F`（备份 `ks-Eco-bank-20260722T085105042Z-223603d2022a`）。Paper 于 17:08:53 完成启动，六个 Extra 正常启用。
- 未完成：真实 MySQL、外部远程存量迁移、真实 Paper/Vault 双节点、Vault 崩溃注入、生产网络故障/并发压力，以及真实存量数据上的不可逆银行清算。

## 2026-07-22 MariaDB 三端与 Folia 最终验收

- 最终依赖顺序矩阵为 23/23 模块成功、358 项测试、0 failure/error/skipped；`ks-core` 9 项、`ks-Eco` 180 项，默认与 Folia 两套构件均成功。
- Leaves/Paper 的 139 张 SQLite 源表已迁移到真实 MariaDB，逐表行数与关键经济汇总一致。运行时配置通过工作区外密码文件取凭据，没有在 YAML 保存明文密码。
- MCSM 启动 MariaDB、BungeeCord、Leaves、Paper 和 Folia；三个 ks-Eco 节点 `survival/rpg/folia` 均完成迁移、启用和跨服运行时启动。
- 保持 `DIAMOND=100.0` 的失效烟测让 transport 发布序号从 2 增至 3，三个 consumer cursor 均推进至 3；数据库、代理、三个后端和 Leaves Web 端口均监听。
- Folia 只安装独立 ks-core/ks-Eco 制品，无 Vault 时使用 JDBC 内置经济；所有 Extra 与全服财富榜失败关闭。启动边界无 ks-Series ERROR 或区域线程异常。
- 未完成：真实 MySQL、外部远程存量迁移、外部 Vault 多节点、网络故障/延迟/压力、崩溃注入和不可逆桥接清算。MariaDB 幂等补列仍会打印非失败 duplicate-column warning。
