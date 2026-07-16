# FakeAiPlayer 测试与证据

FakeAiPlayer 将“代码通过测试”“场景在本机通过”和“可作为发布依据”分开处理。场景结果为 `PASS` 不代表 evidence state 一定是 `VERIFIED`。

## 测试分层

| 层级 | 入口 | 当前规模 | 作用 |
|---|---|---:|---|
| JUnit | `./gradlew :common:test` | 当前源码 59 个测试类、228 个 `@Test` | 验证纯 Java 策略、权限、Goal predicate/result、语义注视、暂停所有权、寻路、序列化、凭据分阶段切换/客户端存储/secret 脱敏，以及 owner-only 聊天与社交限流边界。历史 25/88 仍只是旧的固定基线。 |
| Fabric GameTest | `./gradlew :fabric:runGameTest` | 当前 24 个测试 | 在真实 Minecraft world context 中验证确定性 smoke case、语义注视、模块住宅、闲置控制、寻路动态复验、安全接管和服务端权威背包事务；不等同于完整 Bot 施工 E2E。 |
| 交互 harness | `./gradlew :fabric:runHarnessServer` | test-only 命令 | 提供 `/fakeaiplayer test`、`/fakeaiplayer verify` 与 restart probe 命令。 |
| 单次 evidence | `scripts/evidence_run.sh` | 一个 scenario/seed/profile | 启动隔离服务端并封存不可变 run bundle。 |
| 批量 evidence | `scripts/evidence_batch.sh` | 显式 seed/run matrix | 汇总多个独立 bundle，不自动选择基线。 |
| 两 JVM 重启 | `scripts/persistence_restart_test.sh` | 两个连续服务端进程 | 验证非默认 checkpoint、Mission/queue/pause 精确恢复、stale Job lease 重开，以及 resume 后达到 `COMPLETED 4/4`。 |

Fabric 交互 harness 的默认工作目录是 `fabric/build/run/harness`。脚本向 Gradle 传入的 `build/run/harness` 是相对 `fabric/` 子项目的路径；`harness_probe.sh` 可通过 `FAKEAIPLAYER_HARNESS_RUN_DIR` 覆盖该模块内相对路径。

当前源码清单是 59 个 JUnit 类、228 个 `@Test` 和 24 个 Fabric GameTest。PR #8 的历史 Java 21 CI 已确认当时的完整 JUnit、24/24 GameTest、双加载器生产构建与两 JVM persistence probe 通过；本次新增凭据/聊天代码的通过结论必须以新的全绿 run 为准。受控测试仍不替代真实 Fabric/NeoForge 客户端、真实提供商连接、完整施工或显式 pin 的发布基线。

### 当前建筑实现的本地与 CI 诊断

本次发布前检查使用 Temurin Java 21.0.11，并取得以下本地诊断结果：

- Java 21 源码 parser 扫描通过；这是语法诊断，不等于类型解析或 Minecraft/NeoForge API 编译。
- 脱离 Minecraft 运行时的纯模块住宅生成器在 Java 21 下编译并完成 2 种风格 × 10 个宽度 × 10 个深度 × 2 个墙高 × 32 个 seed，共 12,800 组公开参数烟雾；最大方案 1,459 格。
- `scripts/ci_static_check.sh` 与 `git diff --check` 通过。
- 本地 Java 21 + Gradle 8.11 曾因受限环境中的 JVM 无法访问外部 Maven，在解析 `fabric-loom:1.9.2` 时于项目源码编译前终止；随后同步提交 `dfb0ab8` 的 GitHub Actions 在可联网环境完成并通过完整构建与测试矩阵。

因此，模块住宅与确认投影仍标记为 `UNVERIFIED`，但原因已不再是缺少编译或 CI：剩余缺口是实际 Fabric/NeoForge 客户端渲染、完整生存材料链、复杂地形与长时间世界施工验收，以及可用于发布声明的显式 pin 证据。

## 生产边界

`AIBotTestSubcommand`、`AIBotVerifySubcommand`、GameTest 类和 restart harness 都位于 `fabric/src/gametest`。生产通用源码位于 `common/src/main`，不注册 `/fakeaiplayer test` 或 `/fakeaiplayer verify`，Fabric/NeoForge 生产 jar 也不应包含这些类。

构建后可运行带 artifact 检查的静态门禁：

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
CI_STATIC_CHECK_ARTIFACTS=1 bash scripts/ci_static_check.sh
```

该检查会扫描 `common/build/libs`、`fabric/build/libs` 与 `neoforge/build/libs` 中的 jar，发现 GameTest 或 verification command 泄漏时失败。

## 每 Bot 凭据与聊天回归边界

该能力的确定性测试不连接真实提供商，也不需要或读取 `DEEPSEEK_API_KEY`。主要回归点包括：

- `BotApiCredentialRegistryTest`：pending Key 不会提前成为活动 Key；只能 commit 当前 generation；失败 reject 保留旧 Key 或服务器 fallback；撤销/会话清理不泄漏凭据；
- `ClientCredentialStoreTest`：服务器范围与 Bot 隔离，同目录临时文件 + 原子替换，POSIX `0600`，损坏 JSON 失败关闭，以及值对象 `toString()` 脱敏；
- `ApiCredentialPayloadSafetyTest` 与 `DeepSeekApiClientErrorSafetyTest`：凭据 C2S payload 的字符串表示不包含 Key，提供商原始错误 body 不进入对外异常消息；
- `BotAiSetupSessionsTest`：setup nonce 绑定玩家/Bot/名称，一次使用并会过期；
- `LatestPerBotExecutorTest` 与 `BrainCoordinatorCredentialInvalidationTest`：有界并发、每 Bot 仅保留最新 pending 请求，以及 Key generation 变更后旧决策 fail closed；
- `BotChatPolicyTest`、`BotChatRateLimiterTest` 与 `BotSocialCoordinatorTest`：主聊天文本清洗/截断、只向 owner 展示 Bot/系统消息、限流，以及主动社交请求绝不包含工具。

`scripts/ci_static_check.sh` 还会从 `common` 中的文件名动态推导全部 C2S/S2C payload，要求 Fabric 与 NeoForge 都完成注册/处理。凭据专项门禁另外检查：NeoForge 协议 `4`、四个凭据 payload、命令接线、nonce、stage/probe/commit/reject、成功后才写客户端、`0600` 尝试、secret 脱敏、断线清理和 owner-only 聊天路由。

这些测试不能证明真实 DeepSeek/OpenAI 兼容账号权限、TLS/反向代理安全、Windows/macOS 本地文件 ACL，或 Fabric/NeoForge 真实客户端与服务端的端到端 UI/重连行为。发布前必须在私有测试服使用限额专用 Key 完成人工验收，且不得把凭据或未脱敏客户端配置放进 evidence bundle。

## 单次隔离 evidence

最小命令：

```bash
bash scripts/evidence_run.sh \
  --scenario capability_profile+runtime_control_suite \
  --seed 20260610 \
  --profile strict_survival
```

常用参数：

- `--profile strict_survival|operator`：默认为 strict；
- `--operator-capabilities all|none|<csv>`：只对 operator 生效；
- `--seed <integer>`：请求的 world seed；
- `--timeout` 与 `--startup-timeout`：scenario 与启动超时；
- `--mode deterministic`：默认，无 LLM 费用；
- `--mode llm_story --with-llm`：显式启用 LLM，要求 `DEEPSEEK_API_KEY`；
- `--fixture-log <file>`：只测试封存/解析结构，永远不能成为 `VERIFIED`。

脚本使用 `fabric/build/run/evidence-<run-id>` 下的独立 run directory、动态本地端口、进程锁和清理 trap，不执行全局 `gradlew --stop`，也不会删除 Fabric 的常规 `fabric/run/server`。已有 evidence 目录不会被覆盖。

## Bundle 结构

成功封存后，脚本输出 `EVIDENCE_DIR=...`。单次 run 位于 `artifacts/evidence/<run-id>/`，包含：

```text
manifest.tsv
result.tsv
server.log
effective-config.redacted.json
checksums.sha256
LOCKED
```

- `manifest.tsv`：commit、工作树起止状态、runtime、profile、effective capability、配置 hash、requested/actual seed、隔离目录、结果与 evidence state；
- `result.tsv`：场景结果与 `/fakeaiplayer verify` summary；
- `effective-config.redacted.json`：实际测试配置的脱敏快照；
- `checksums.sha256` 与 `LOCKED`：封存后的完整性边界；
- `server.log`：已执行 credential pattern 检查与必要脱敏的服务端日志。

Bundle 一旦发布即视为 immutable。不要在目录内手工修结果或 metadata；需要修复时重新运行。

## `PASS` 与 `VERIFIED`

`result=PASS` 只表示 scenario 的断言通过。要获得 `evidence_state=VERIFIED`，还必须同时满足 provenance 条件，包括：

- 工作树在开始和结束时均为 clean；
- 起止 commit 相同且可用；
- 从该 commit 的 `git archive` 快照执行，而不是不稳定的 live worktree；
- 服务端成功启动并正常退出；
- 请求 seed 与服务端回读的 actual seed 一致；
- isolated working directory 与 Java runtime 可核验；
- revision/build/config metadata 完整；
- 不是 fixture input；
- 封存日志中没有需要替换的 secret。

任一条件不满足时，scenario 仍可显示 `PASS`，但 evidence 会成为 `UNVERIFIED`，并在 `verification_reason` 中列出原因。当前仓库内的本地 strict/operator `7/7` run 就属于这种正确降级：工作树为 dirty，因此不能进入发布基线。

## 验证 bundle

结构与 checksum 验证：

```bash
bash scripts/evidence_validate.sh artifacts/evidence/<run-id>
```

发布门禁验证：

```bash
bash scripts/evidence_validate.sh \
  --require-verified artifacts/evidence/<run-id>
```

validator 不 source bundle 中的 metadata，并拒绝路径穿越、symlink、staging 目录、重复 manifest key、未知 schema、checksum 错配及 provenance 矛盾。可运行其安全自测：

```bash
bash scripts/evidence_validate.sh --self-test
```

## 批量运行

```bash
bash scripts/evidence_batch.sh \
  --scenario real_food \
  --seeds 12345,246810,632510390 \
  --runs 2 \
  --profile strict_survival
```

默认情况下，batch 要求每个 child run 都是 `VERIFIED`。`--allow-unverified` 只适合本地诊断，不得用于 release gate。batch 输出到 `artifacts/evidence-batches/<batch-id>/`，只引用各 child bundle，不会替用户自动挑选或 pin 结果。

## 显式 pin 基线

新式 baseline 的唯一选择器是 `reports/baselines/index.tsv`。Pin 操作必须显式提供 capability ID 与一个已通过 `--require-verified` 的 run：

```bash
bash scripts/pin_baseline.sh \
  food_from_zero \
  artifacts/evidence/<run-id>
```

Pin 流程还会验证：

- capability ID 在 `reports/capability_baseline_manifest.tsv` 中恰好注册一次；
- run scenario 与该 capability 的注册 scenario 一致；
- profile 为 `strict_survival`，mode 为 `deterministic`；
- 默认只接受 `PASS`；
- immutable bundle 与 `LOCKED` hash 一致。

脚本把 bundle 复制到 `reports/baselines/<capability-id>/<run-id>/`，再原子更新 `reports/baselines/index.tsv`。旧 run 不会因重新 pin 而删除。

`reports/capability_baseline_manifest.tsv` 仍保存 capability 注册信息和 legacy fallback。它不是“自动选最好报告”的索引；没有新式 pin 时，生成的能力矩阵会显示 legacy report，并保持 `UNVERIFIED`。

更新矩阵：

```bash
bash scripts/capability_matrix.sh --output docs/CAPABILITY_MATRIX.md
bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md
```

## CI 约束

- PR CI 与 nightly deterministic workflow 不接收 `DEEPSEEK_API_KEY`；
- nightly 覆盖 `strict_survival` 与 `operator` profile；
- LLM story evidence 只能通过手动 workflow 触发，并要求明确确认计费；
- workflow 在失败时仍上传诊断 artifact；
- release claim 只能引用校验通过、显式 pin 的 `VERIFIED` bundle。
