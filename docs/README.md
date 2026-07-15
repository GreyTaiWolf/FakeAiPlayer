# FakeAiPlayer 项目文档

## 文档入口

- [运行模式与特权能力](OPERATING_PROFILES.md)：`strict_survival` / `operator` 的解析、迁移和 capability matrix。
- [测试与证据](TESTING_AND_EVIDENCE.md)：JUnit、GameTest、测试 harness、不可变 evidence bundle 与 baseline pin 流程。
- [能力矩阵](CAPABILITY_MATRIX.md)：当前显式证据与 legacy 诊断的生成结果。
- [P0 Runtime Hardening](P0_RUNTIME_HARDENING.md)：运行时加固阶段与验收项。
- [AI 建筑系统设计基线](AI_BUILDING_SYSTEM.md)：BlockState、语义构件、确定性规划、投影协议与分阶段验收。
- [产品与工程路线图](../ROADMAP.md)：后续可靠性和产品规划。

## 当前工程口径

- 新安装默认 `strict_survival`；已有旧配置缺少 `profile` 时，才按 legacy compatibility 载入为 `operator` 并告警。
- 生产 jar 不包含 `/fakeaiplayer test`、`/fakeaiplayer verify` 或 GameTest 实现。测试命令只通过 `fabric/src/gametest` 与 `:fabric:runHarnessServer` 提供。
- 本地 dirty-worktree run 可以用于诊断，但必须是 `UNVERIFIED`，不能作为发布结论。
- 新式 `VERIFIED` 能力基线只能由 `reports/baselines/index.tsv` 显式选择，不能按“最好”或“最新”自动挑选。
- 当前模块住宅/建筑投影属于双加载器接线的未发布能力：服务端方案、分块传输、Fabric/NeoForge 客户端线框和人工确认已进入源码，但双加载器真实客户端与完整生存施工验收尚未完成。准确边界、命令和限制见 [AI 建筑系统设计基线](AI_BUILDING_SYSTEM.md)。
- CI 静态门禁会同时检查两端的建筑 payload、服务端处理器、按键、断线清理和渲染关键不变量，防止后续更新再次只接入单一加载器。
- 本地已用 Temurin Java 21.0.11 完成 Java 21 源码语法解析、纯生成器编译/12,800 组方案烟雾和静态门禁；本地完整 Gradle 曾因外部 Maven 网络受限而中止。随后同步提交 `dfb0ab8` 的 GitHub Actions 全矩阵已通过 common 测试、10 项 Fabric GameTest、Fabric/NeoForge 构建、产物检查、两 JVM 持久化与 strict-survival runtime/evidence。

建筑能力当前应读作以下链路，而不是“AI 可以任意造房子”：

```text
玩家描述 / AI draft_building
  → 确定性模块生成器
  → 服务端权威投影
  → 玩家移动/旋转并检查
  → 玩家显式确认
  → 服务端重新校验
  → 既有备料与建造任务
```

AI 只有 draft/status/cancel 能成功推进建筑，不开放确认；兼容注册的旧 `build_house`、`assign_task build`、`post_job build`，以及重启恢复的旧 AI build Job 和缺少摘要/锚点/维度绑定的旧 build Mission，都不能绕过这条边界。确认后任务绑定 canonical blueprint digest、锚点和维度；V2 依赖在执行前逐项复核，地基下方的干燥、稳固、可检查支撑也会在确认时和实际落块前重复核验。Fabric 与 NeoForge 的确认/取消按键默认未绑定。当前 renderer 是有距离与视锥裁剪的即时彩色线框，不是 Create/MineColonies/Litematica 等模组的代码移植，也不是已经完成的 VBO 或真实方块模型投影。

## 能力矩阵的来源

`CAPABILITY_MATRIX.md` 是生成文件，其输入有两层：

1. `reports/baselines/index.tsv` 指向不可变、校验通过的 `VERIFIED` bundle，是新式基线的唯一选择器；
2. `reports/capability_baseline_manifest.tsv` 是能力注册表与 legacy fallback。旧 TSV 缺少完整 commit、配置与 actual-seed provenance，因此保持 `UNVERIFIED`。

更新或检查矩阵：

```bash
bash scripts/capability_matrix.sh --output docs/CAPABILITY_MATRIX.md
bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md
```

不要手工修改生成结果。提交能力基线时，必须同时提交 immutable bundle、`reports/baselines/index.tsv` 与重新生成的矩阵；任何 SHA-256、场景、profile、mode 或 commit provenance 不一致都会使检查失败。
