# 更新日志

本文件记录合入 `main` 的重要功能、修复、兼容性变化与验证结果。

测试结论必须绑定具体提交和 GitHub Actions；尚未完成验证时明确标记为“待验证”，不能把源码存在或本地检查等同于发布完成。

## [未发布]

### 2026-07-23 — P3 Mission / Skill 运行时

#### 新增

- 建立 `GoalSpec`、`MissionPlan`、`SkillSpec`、`SkillOutcome` 与组合计划游标。
- 增加唯一 Mission 仲裁入口，让玩家命令、AI 提案、自主目标与安全反应按来源和优先级竞争执行权。
- 增加 Skill 前置条件、成功谓词、风险、维度和允许修改区域的运行门禁。
- 增加可持久化的计划游标、重试/恢复预算、计划版本、上下文指纹与 Mission 身份。
- Fabric 与 NeoForge 共用 6 个 P3 GameTest 场景，其中包括零库存开始的“砍木、制作两级镐、挖石与铁、冶炼铁锭”黄金链。
- CI 门禁固定 95 个 JUnit 类/480 个测试、Fabric 55 项、NeoForge 31 项及 6 个双端 P3 共享场景；PR 验证时缺少任一结果文件会直接失败。

#### 修复

- 修复旧 activation 的迟到回调可能误完成新一轮 Skill 的问题。
- 修复重启后组合分支、重试次数、超时状态或恢复预算可能被重置的问题。
- 修复跨维度 Mission、越权世界修改和无法验证的完成结果进入执行链的问题。
- 修复安全抢占、Task 启动失败和备用分支切换时可能遗留旧 Task 或错误恢复状态的问题。
- 修复安全接管短路 Mission tick 时，`REPLAN_AFTER_SAFETY` / `CANCEL_ON_INTERRUPT` 的待处理义务可能未进入 V3 存档的问题；恢复后的 Mission 会先经过首个安全 tick，再允许 Skill 启动。
- 紧急溺水传送释放控制后会在同一协调 tick 处理 Mission 中断；TaskManager 还会隔离尚未消费中断策略的 Mission Task，杜绝旧任务多执行一 tick。
- 隔离范围进一步前移到 `Task.onResume()`：移动、返矿和掉落恢复等 Task 不会在策略交接前重启路径；只有 GoalExecutor 可显式执行 `RESUME`，`REPLAN/CANCEL` 不会唤醒旧 Task。
- 修复 DangerWatcher 在恢复被 Mission 中断门禁拒绝后仍宣称“已处理”，导致 GoalExecutor 永远拿不到策略交接机会的问题。
- 持久化的 `CANCEL_ON_INTERRUPT` 现在先于蓝图/规划、目标满足判断、维度不匹配和用户暂停终结，确保恢复后的最终状态稳定为 `CANCELLED`。
- 恢复队列不再从服务器启动回调直接启动 Task；活跃 Mission 与排队 Mission 都统一等到首个经过 NavSafety 的 Bot tick。
- 恢复 admission 和中断交接本身不消耗 Mission 时间预算；只有真实 Mission Task 获得执行权后的 tick 才计时。
- 恢复中的 `WAITING` 组合节点收到事件时只推进并保存游标，不会绕过首个安全 tick 提前启动 Skill。
- 修复权威世界状态使恢复计划删除已完成前缀后，合法的新游标被错误要求与旧计划逐字节相等，以及唯一可证明的暂停 Skill 尝试预算被重复扣减的问题。
- 恢复完成后增加同步 canonical checkpoint 屏障，确保计划 revision、重绑定游标、队列和失效 Job lease 在发布 runtime-ready 前已经原子落盘。
- canonical checkpoint 仅在 Bot、Mission、队列和 Job 的数量与身份全部核账后写入；任一条目无法恢复时，本次会话进入只读保护并保留原始 `runtime.json`，避免残缺快照造成永久数据丢失。
- Bot 的 owner、背包与记忆子状态在生成实体前执行严格结构校验，背包和记忆载入后还要通过精确往返验证；非空载荷若格式错误或被 Minecraft 静默降级，会被计为未核账并触发整会话只读保护，绝不以空状态覆盖原存档。
- 只读恢复保护升级为全会话执行闸门：格式错误、版本不支持、部分条目失败、重建抛错或 canonical 保存失败时，现有 Bot 会停止动作并进入无敌旁观隔离，之后也不能再生成新 Bot；Mission、Job、玩家/LLM 控制、空闲漫游和库存会话全部冻结，仅保留即时安全处理，Job 领取同步暂停。
- 命令、聊天控制短语和面板入口统一遵守只读恢复闸门；暂停/恢复/中止、Brain 请求与重置、运行选项、传送和删除 Bot 被拒时会明确返回失败，不再出现“实际未执行但提示成功”。
- 热重载不会再清空或主动写入尚未落盘的异步快照：存在待写活动时会明确拒绝并要求等待，写入失败则进入只读保护并要求修复后重启，避免复活刚移除的 Bot 或用旧内存状态覆盖管理员修复的 `runtime.json`。
- 护甲 Goal 改为读取权威装备槽验收；资源点、危险区和死亡记忆按维度隔离。
- 修复严格生存模式下远端矿石已破坏、`FORCED_PICKUP` 被拒后 Bot 不会自然接近掉落物，转而盲掘并最终阻塞的问题。
- 掉落追逐改用精确、只步行的拾取站位，消除导航在 1.3–2 格处提前结束却无法触发原版拾取的死区。

#### 验证

- 完整 P3 代码提交：[`1965137`](https://github.com/GreyTaiWolf/FakeAiPlayer/commit/19651378d049099d8ee7d7bf5aecda3e68529d0a)。
- 首轮集成 [CI #143](https://github.com/GreyTaiWolf/FakeAiPlayer/actions/runs/29985224408) 成功暴露并阻止了严格生存黄金链的掉落拾取缺陷。
- 第二轮集成 [CI #145](https://github.com/GreyTaiWolf/FakeAiPlayer/actions/runs/29986196354) 已确认 Java 21、JUnit、Fabric 55/55、NeoForge 31/31、双端生产 JAR 与严格生存黄金链通过，并进一步阻止了权威重规划后的 V3 游标/尝试恢复缺陷；最终修复仍待新的提交绑定 CI 验证。
- [CI #146](https://github.com/GreyTaiWolf/FakeAiPlayer/actions/runs/29992158438) 在 Java 21 编译阶段发现并阻止了面板恢复拒绝辅助方法的实例/静态归属错误。
- 修复提交 [`f4289ad`](https://github.com/GreyTaiWolf/FakeAiPlayer/commit/f4289add63010619891b284c84acad493d2d0409) 的 [CI #149](https://github.com/GreyTaiWolf/FakeAiPlayer/actions/runs/29992705897) 已通过 Java 21、95 个 JUnit 类/480 项测试、Fabric 55/55、NeoForge 31/31、双端生产构建与 JAR 隔离、双 JVM V3 恢复、严格生存黄金链和封装证据验证。

#### 已知限制

- 黄金链使用受控测试场景中的树、石头和暴露铁矿，只证明生产执行链，不代表远距离洞穴搜索或所有地形找矿已经完成。
- V3 精确持久化 Mission、组合计划游标与全局预算；旧 Task 适配器的私有阶段和局部超时仍采用“至少执行一次 + 权威后置验收”恢复，不保证 Task 对象内部状态逐字段续跑。持久化的 Mission 总时间预算仍限制整体运行时长。
- 在线修复 `runtime.json` 前必须先等待异步写入活动完全结束；写入器已经报错的会话采用保守粘滞只读，修复文件后需要重启服务器，不能在同一 JVM 内强制解锁。
- 完整生产/挖矿任务迁移属于 P5；统一世界事实属于 P4；分阶段大型建筑 Mission 属于 P7。

### 2026-07-22 — P2 正式导航契约

#### 新增

- 增加 `NavGoal`、`NavigationHandle`、结构化导航结果和确定性搜索预算。
- 增加单 frontier 多目标 A*、交互站位选择、路径证明、动态失效与同一请求内局部重规划。
- Fabric 与 NeoForge 共用 14 个 P2 导航 GameTest 场景。

#### 验证

- 代码提交 [`2450e13`](https://github.com/GreyTaiWolf/FakeAiPlayer/commit/2450e1352f27e3768e0f2d0ceeed98c7d7a71c4a) 的 [CI #129](https://github.com/GreyTaiWolf/FakeAiPlayer/actions/runs/29879516182) 已通过 Java 21、312 个 JUnit 测试、Fabric 49/49、NeoForge 25/25、双端生产构建和持久化检查。
- 该结果尚未在 `reports/baselines/index.tsv` 中显式 pin，因此属于提交绑定 CI 记录，不是 `VERIFIED` 发布基线。

## 维护规则

- 每个对玩家、运行时、配置、存档、兼容性或安全边界有影响的主线更新，都要同步更新本文件。
- 每条记录应包含日期、主要变化、验证证据、已知限制以及关联提交或 PR。
- 纯拼写或无行为影响的格式整理可以不单独记录。
- 正式发布时，将对应内容从“未发布”移动到带版本号和日期的标题下。
