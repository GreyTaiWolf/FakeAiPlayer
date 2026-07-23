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
- CI 门禁固定 95 个 JUnit 类/479 个测试、Fabric 55 项、NeoForge 31 项及 6 个双端 P3 共享场景；PR 验证时缺少任一结果文件会直接失败。

#### 修复

- 修复旧 activation 的迟到回调可能误完成新一轮 Skill 的问题。
- 修复重启后组合分支、重试次数、超时状态或恢复预算可能被重置的问题。
- 修复跨维度 Mission、越权世界修改和无法验证的完成结果进入执行链的问题。
- 修复安全抢占、Task 启动失败和备用分支切换时可能遗留旧 Task 或错误恢复状态的问题。
- 护甲 Goal 改为读取权威装备槽验收；资源点、危险区和死亡记忆按维度隔离。

#### 验证

- 完整 P3 代码提交：[`1965137`](https://github.com/GreyTaiWolf/FakeAiPlayer/commit/19651378d049099d8ee7d7bf5aecda3e68529d0a)。
- Java 21 JUnit、Fabric 55/55 GameTest、NeoForge 31/31 GameTest、双端生产构建、JAR 隔离、双 JVM 恢复与 strict-survival evidence：等待本次 `main` 集成 PR 的提交绑定 CI。

#### 已知限制

- 黄金链使用受控测试场景中的树、石头和暴露铁矿，只证明生产执行链，不代表远距离洞穴搜索或所有地形找矿已经完成。
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
