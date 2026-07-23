# FakeAiPlayer

> 让一个真正加入 Minecraft 服务端世界的 AI 玩家，像普通玩家一样观察、规划、移动、采集、合成、建造、战斗和生存。

[![Minecraft 1.21.3](https://img.shields.io/badge/Minecraft-1.21.3-62B47A?style=flat-square)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-DBB69B?style=flat-square)](https://fabricmc.net/)
[![NeoForge](https://img.shields.io/badge/Loader-NeoForge-EA4B2B?style=flat-square)](https://neoforged.net/)
[![Java 21](https://img.shields.io/badge/Java-21-E76F00?style=flat-square)](https://adoptium.net/)
[![License MIT](https://img.shields.io/badge/License-MIT-64F5A0?style=flat-square)](LICENSE)

FakeAiPlayer 是 [`zoyluoblue/mc_aiplayer`](https://github.com/zoyluoblue/mc_aiplayer) 的衍生移植与魔改项目。当前仓库把原 Fabric 单加载器工程重组为 `common + fabric + neoforge` 多加载器结构，并完成项目身份重命名：

| 项目字段 | 当前值 |
|---|---|
| 项目名称 / 模组显示名 | `FakeAiPlayer` |
| 模组 ID | `fakeaiplayer` |
| Java / Maven 域 | `io.github.greytaiwolf.fakeaiplayer` |
| GitHub | `GreyTaiWolf/FakeAiPlayer` |
| 当前版本 | `0.1.0-alpha.1` |
| Minecraft | `1.21.3` |
| Java | `21` |
| 加载器 | Fabric、NeoForge |
| 映射 | Mojang 官方映射 + Parchment `2024.12.07` |
| 许可证 | MIT；上游归属见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) |

> [!IMPORTANT]
> 当前版本仍是 Alpha 开发版，不是生产兼容性认证版。仓库中的运行结论都绑定具体提交。Bot 智能重构 P0/P1 在提交 [`9155c9c`](https://github.com/GreyTaiWolf/FakeAiPlayer/commit/9155c9ca18cb1c8021bf50d453bbafe84f2c1489) 的 [CI #106](https://github.com/GreyTaiWolf/FakeAiPlayer/actions/runs/29862817627) 中通过；P2 导航在代码提交 [`2450e13`](https://github.com/GreyTaiWolf/FakeAiPlayer/commit/2450e1352f27e3768e0f2d0ceeed98c7d7a71c4a) 的 [CI #129](https://github.com/GreyTaiWolf/FakeAiPlayer/actions/runs/29879516182) 中通过 Java 21、当前 79 个 JUnit 类/312 个 `@Test`、Fabric 49/49、NeoForge 25/25、双端生产构建与产物检查、两进程持久化及 strict-survival runtime/evidence。14 个 P2 场景在两端使用相同共享实现、batch 和阈值通过。上述结果尚未由 `reports/baselines/index.tsv` 显式 pin，因此属于提交绑定的 CI 通过记录，不称为 `VERIFIED` 发布基线。半透明真实 BlockState 模型投影仍标记为 `UNVERIFIED`，因为双加载器客户端实机、完整生存备料、复杂地形和长时间施工尚未完成验收。

## English summary

FakeAiPlayer is an experimental Minecraft 1.21.3 mod that combines a real server-side player entity, an OpenAI-compatible planner, and deterministic task state machines. This fork uses `common` code with Fabric and NeoForge adapters. Existing release claims remain commit-specific. Bot-intelligence P0/P1 passed on commit `9155c9c` in CI #106. P2 navigation passed Java 21, the current 79 JUnit classes/312 `@Test` suite, Fabric 49/49, NeoForge 25/25, both production builds and artifact checks, two-process persistence, and strict-survival runtime/evidence on code commit `2450e13` in CI #129; its 14 P2 scenarios use the same shared implementation on both loaders. Neither run is pinned in `reports/baselines/index.tsv`, so these are commit-bound CI results rather than a `VERIFIED` release baseline.

## 目录

- [项目目标](#项目目标)
- [当前实现状态](#当前实现状态)
- [它是如何工作的](#它是如何工作的)
- [主要功能](#主要功能)
- [Fabric 与 NeoForge 的区别](#fabric-与-neoforge-的区别)
- [安装](#安装)
- [快速开始](#快速开始)
- [模型与 API Key 配置](#模型与-api-key-配置)
- [完整配置参考](#完整配置参考)
- [指令、聊天与客户端面板](#指令聊天与客户端面板)
- [权限与安全模型](#权限与安全模型)
- [持久化与目录](#持久化与目录)
- [网络协议](#网络协议)
- [源码架构与每个区域](#源码架构与每个区域)
- [构建、开发与测试](#构建开发与测试)
- [从上游 aibot 数据迁移](#从上游-aibot-数据迁移)
- [已知限制](#已知限制)
- [路线图](#路线图)
- [故障排查](#故障排查)
- [许可证与上游归属](#许可证与上游归属)

## 项目目标

FakeAiPlayer 的目标不是让大语言模型每一刻直接控制按键，更不是允许模型任意修改世界。项目采用“模型负责意图，确定性代码负责执行”的边界：

1. 玩家使用聊天、命令或面板表达目标。
2. 服务端收集受限制的世界感知、Bot 状态和记忆。
3. OpenAI 兼容模型从注册工具中选择意图。
4. 类型化 Goal 把大目标拆成可验证步骤。
5. Task 状态机、动作层和寻路器在服务端逐 Tick 执行。
6. 完成条件由后置条件重新检查，而不是只相信模型说“完成了”。
7. 权限、运行模式、生存保护、TPS 保护和持久化围绕执行链提供边界。

项目希望最终做到：

- AI 玩家是真实的服务端 `ServerPlayer` 派生实体，而不是外部 Mineflayer 账号或 Python 鼠标键盘脚本。
- 玩家可以把 AI 当成同伴，用中文或英文交代任务。
- 模型提供商可以通过 OpenAI 兼容的 `chat/completions` + function/tool calling 接口替换。
- Fabric 和 NeoForge 共享同一套核心行为，加载器差异被限制在薄适配层。
- 无 API Key 时，管理员仍可使用确定性的 `/fakeaiplayer task ...` 命令测试核心玩法。
- 默认模式尽量遵守生存规则；需要“作弊式恢复”的能力必须由管理员显式选择。

当前明确不把以下内容冒充为已完成目标：

- 通用人类级 Minecraft 智能；
- 对所有模组方块、配方、GUI 和维度的自动理解；
- 任意 Minecraft 版本或任意 Fabric/NeoForge 版本兼容；
- 加密或操作系统钥匙链级别的 API Key 保管、玩家配额与费用归属；
- 所有地形、种子、延迟和服务器规则下 100% 成功。

## 当前实现状态

| 区域 | 当前状态 | 说明 |
|---|---|---|
| 项目重命名 | 已进入源码 | `FakeAiPlayer`、`fakeaiplayer`、`io.github.greytaiwolf.fakeaiplayer` 已用于核心入口、元数据、资源和网络命名空间。 |
| 多加载器布局 | 已进入源码 | 根工程包含 `common`、`fabric`、`neoforge` 三个子项目。 |
| 共享核心 | 已迁入 `common` | AI 实体、脑、目标、任务、动作、寻路、配置、持久化、权限、网络 payload 和客户端 UI 均放在共享模块。 |
| Fabric 接入 | 49/49 提交绑定 CI 通过 | 包含初始化、生命周期、命令、聊天、断线、完整 payload、原版式 Bot Menu 和客户端事件接入。代码提交 `2450e13` 的 CI #129 已运行历史 24 项、共享 P0/P1 11 项及共享 P2 14 项；完整客户端交互仍待人工验证。 |
| NeoForge 接入 | 25/25 提交绑定 CI 通过 | 包含 `@Mod` 入口、事件总线、完整 payload、Menu 注册、服务端/客户端传输和按键。代码提交 `2450e13` 的 CI #129 已运行共享 P0/P1 11 项与共享 P2 14 项、正式 `gameTestServer`、生产构建及持久化检查；完整客户端交互仍待人工验证。 |
| 自然语言大脑 | 已保留并重命名 | 默认连接 DeepSeek；请求格式为 OpenAI 兼容的非流式 `/v1/chat/completions` 工具调用。 |
| 每 Bot 客户端 Key | 双加载器已接线，待实机验证 | owner/OP 可通过 `/fakeaiplayer ai setup <name>` 打开遮罩输入窗。Key 验证成功后才明文保存在当前玩家客户端，服务端仅在会话内存中持有；失败的新 Key 不会替换旧 Key。 |
| 确定性玩法 | 已保留 | 源码包含数十个工具定义、类型化 Goal，以及采集、挖矿、合成、冶炼、建造、战斗、农牧等任务状态机。 |
| 建筑目录、多层生成与投影 | 双加载器接线，未验证 | 四位起建筑码可复现 2–8 层、最大 48×48 的确定性模块建筑；内含五类原版材料风格、分层楼板、梁柱/外墙、门窗、室内隔墙、永久折返楼梯与两类屋顶。服务端会在已加载且允许观察的地形上做有界两阶段选址并生成平地/桩柱地基；Fabric 与 NeoForge 共用真实 BlockState 模型投影、冲突线框与人工确认。双加载器客户端实机和大型生存施工证据尚缺。 |
| 语义注视感知 | 已通过世界回归 | Bot 通过服务端射线识别准星下的方块、方块实体、生物、普通实体和掉落物；紧凑摘要自动进入模型上下文，详细信息由 `inspect_focus` 按需读取；方块、遮挡、生物、掉落物、目标漂移和容器详查均有 GameTest。 |
| 客户端面板 | 原版式背包菜单已接线 | 聊天/状态、快捷动作、目标链和设置页面均存在；背包按钮与空主手右键 Bot 会打开同一个服务端权威 Menu。Fabric/NeoForge 均已编译，完整双端客户端拖拽/双击烟雾仍需人工验收。 |
| 单元测试 | 当前源码 79 类、312 个 `@Test`；CI #129 通过 | P2 导航契约、预算、分段和动态失效回归随当前完整 Java 21 测试集在代码提交 `2450e13` 的 CI #129 通过。 |
| Fabric GameTest | 当前源码 49/49；CI #129 通过 | 历史 24 项、共享 P0/P1 11 项及 14 个 loader-neutral P2 场景均通过；测试代码与共享结构资源使用隔离 source set，不进入发布 JAR。 |
| NeoForge GameTest | 当前源码 25/25；CI #129 通过 | 共享 P0/P1 11 项及同一组 14 个 P2 场景均通过；NeoForge wrapper 与 Fabric 使用相同测试体、batch 和阈值。 |
| 正式发行 | 尚未完成 | 当前没有在本文承诺可用于生产服的稳定二进制发布。 |

判断一个能力是否“完成”时，请区分四个层次：源码存在、能够编译、能够在游戏启动、在真实地形上有重复验证。路线图或源文件出现某个能力，不等于该能力已经达到最后一个层次。

导航、交互姿态、整树采伐、任务仲裁、世界模型以及 AI/确定性核心的具体 P0–P10 实施与验收边界，见 [Bot 智能机制重构开放目标清单](docs/BOT_INTELLIGENCE_REWORK.md)。

## 它是如何工作的

```mermaid
flowchart TB
    P[玩家：聊天 / 命令 / 客户端面板] --> AUTH[目标解析与 owner/OP 授权]
    AUTH --> BRAIN[BrainCoordinator]
    BRAIN --> API[OpenAI 兼容模型\n意图选择与工具调用]
    BRAIN --> GOAL[类型化 Goal 与 GoalPlanner]
    API --> TOOLS[ToolRegistry / ActionDispatcher]
    GOAL --> EXEC[GoalExecutor 与完成条件]
    TOOLS --> TASK[确定性 Task 状态机]
    EXEC --> TASK
    TASK --> ACTION[动作、背包、合成、战斗、建造]
    ACTION --> PATH[A* 寻路、站立性与危险检查]
    PATH --> WORLD[Minecraft 服务端世界]
    WORLD --> PERCEPTION[有限感知快照]
    PERCEPTION --> BRAIN
    SAFETY[生存保护 / TPS Guard / Capability Gate] -.约束.-> BRAIN
    SAFETY -.约束.-> TASK
    PERSIST[原子 runtime.json 快照] -.恢复.-> EXEC
    PERSIST -.恢复.-> TASK
    LOADER[Fabric / NeoForge 适配层] --> AUTH
    LOADER --> WORLD
```

### 为什么不让 LLM 直接控制每一个 Tick

逐 Tick 远程模型控制会带来高延迟、高费用、不稳定动作、线程阻塞和不可验证的世界修改。FakeAiPlayer 把模型调用放在异步决策层；移动、挖掘、交互和状态机仍由服务器线程中的确定性逻辑执行。旧响应还会通过 decision epoch/lease 检查，降低“玩家已经改令，但较慢的旧 API 响应回来后继续执行”的风险。

### Goal、Task 与 Action 的区别

| 层次 | 回答的问题 | 示例 |
|---|---|---|
| Goal | 最终要证明什么成立？ | “背包中有 3 颗钻石”“完成一套护甲”“建好指定蓝图”。 |
| GoalStep | 为了 Goal 需要哪些依赖步骤？ | 做木镐、升级石镐、找到矿层、采矿、回收掉落。 |
| Task | 一个可以跨多个 Tick 推进、暂停、失败或完成的状态机是什么？ | `GatherQuotaTask`、`StripMineTask`、`BuildTask`。 |
| Action | 具体的一次游戏动作如何执行？ | 看向坐标、选择工具、破坏方块、放置方块、移动物品。 |

类型化 Goal 当前覆盖：物品数量、镐等级、矿石采集、农作物收获、护甲、工作站、囤货、食物和蓝图建造。任务结束后，Goal 后置条件会再次读取世界/背包状态并形成完成、部分完成、失败或取消结果。

## 主要功能

### 真实服务端 AI 玩家

- Bot 以服务端玩家实体加入玩家列表和世界，拥有生命、饥饿、背包、装备、游戏模式、位置和维度。
- 新 Bot 强制使用生存模式；不会因为召唤者处于创造模式就继承创造模式。
- 每位普通玩家最多拥有一个个人 Bot；OP/控制台可以执行更广泛的管理操作。
- owner 或 OP 在同维度 8 格内、主手为空时右键 Bot，可打开原版式背包与装备 Menu；主手有物品时不会抢占原有物品交互。
- 背包会话会独立暂停普通工作并冻结自动装备；关闭只释放 `INVENTORY` 自己的暂停。即时危险会强制关菜单并接管，结束后再按原所有者恢复被暂停的同一任务。
- Bot 名称、角色、owner UUID、生命、饥饿、背包、记忆和任务运行时可进入持久化快照。

### 自然语言与模型工具调用

- 世界聊天支持 `@Bot名 指令内容`。
- 命令支持 `/fakeaiplayer brain say <Bot名> <内容>`。
- 客户端面板支持聊天输入和回复记录。
- 模型只看到当前开放的工具组；低层工具、记忆工具和协作工具可以按配置或 Bot 运行时选项开关。
- 当前 `ToolRegistry` 注册数十个工具，覆盖回复、语义注视、观察/移动、合成、采集、挖矿、目标、建筑投影、容器、战斗、生存、记忆、协作和控制；确切数量以当前源码/自动检查为准。
- 每次请求限制历史消息、单轮工具调用数和总轮次，并对超时、429 与 5xx 执行有限指数退避。

### 确定性玩法任务

现有任务与动作代码覆盖的主要领域包括：

- 移动、跟随、守点、保持位置、闲置观察/有界漫游、策略化站立性检查和 A* 寻路；普通陆地策略拒绝水，执行前复验未来三节点、玩家碰撞箱、支撑、流体和动态障碍；
- 普通方块挖掘、矿脉 BFS、分支挖矿、向目标 Y 层下降、工具选择和火把；
- 定额采集、掉落物回收、库存不足时补给；
- 生存配方合成、工作台/熔炉放置、冶炼链；
- 箱子查找、存入、取出、囤货；
- 耕地、播种、收获、持续照料、灌溉、繁殖；
- 进食、睡觉、区域照明、紧急避险和庇护；
- 战斗、低血量撤退、守卫、打猎；
- 钓鱼、挤奶、村民简单交易；
- JSON 蓝图、默认小屋、参数化房屋和建造验收。

这些能力并不意味着每个复杂任务在任何地形都会成功。任务仍会受到距离、区块加载、可见性、工具、材料、游戏规则和当前实现边界影响。

### 模块住宅与人工确认投影（Fabric / NeoForge）

当前工作树提供受约束的“建筑码/意图 → 确定性编译 → 选址 → 投影 → 人工确认”路径。AI 不输出任意方块坐标，也没有确认权。旧 `modular-house-3` 小屋仍兼容；新的 `multi-storey-building-1` 以公开建筑码解析原型、占地、2–8 层、材料风格、屋顶和独立 entropy，再编译地基、逐层楼板、梁柱、外墙、门窗、室内隔墙、永久支撑折返楼梯和屋顶。内置风格为 `oak_cottage`、`spruce_lodge`、`birch_townhouse`、`dark_oak_manor` 与 `stone_keep`。

```text
/fakeaiplayer building draft <bot> <oak_cottage|spruce_lodge|birch_townhouse|dark_oak_manor|stone_keep>
/fakeaiplayer building draft <bot> <style> <width> <depth> <wall_height> [seed]
/fakeaiplayer building catalog <building_code>
/fakeaiplayer building code <bot> <building_code> [search_radius]
/fakeaiplayer building random <bot> [search_radius]
/fakeaiplayer building move <x> <y> <z>
/fakeaiplayer building rotate <north|east|south|west>
/fakeaiplayer building status
/fakeaiplayer building confirm
/fakeaiplayer building cancel
```

AI 能够成功推进的建筑工具只有 `draft_building`、`building_preview_status` 和 `cancel_building_preview`；没有 AI 确认工具。旧 `build_house` 以及 `assign_task`/`post_job` 的 `build` 变体仍保留兼容入口，但现在都会失败关闭并要求使用 `draft_building`；重启后，旧 AI build Job 和缺少确认绑定的旧 build Mission 都不能从恢复路径绕过确认。玩家必须在对应加载器客户端检查投影后执行确认命令，或在“控制”设置中自行绑定默认未绑定的确认键。

确认时服务端重新验证 owner/Bot、维度、距离、方案摘要、变换 revision、边界、区块、BlockState、替换策略、方块实体与多格原子组；最多 65,536 格的世界检查使用每玩家/全局预算跨 tick 执行，同一会话有进行中闸和重试冷却，重复确认包不能反复触发整图同步扫描。任何没有正下方计划依赖的地基格都必须有可检查、干燥且上表面稳固的外部支撑；该契约会写入 canonical 蓝图并在实际落块前再次检查，关闭备料期间地面变化的竞态。确认后的精确蓝图以 canonical SHA-256、锚点和维度绑定到 Goal/持久化任务；恢复与执行会失败关闭地拒绝摘要或维度不匹配。V2 构件依赖也会转换为稳定的蓝图前置序号，执行每个依赖格前必须重新精确匹配其 BlockState，不能在支撑失败后继续搭建上层。

建筑码采用规范十进制文本：初始空间为 `0000..9999`，之后按 `10000`、`10001` 继续扩展；现有码不会因新增内容重新编号。`building-catalog-1` 有显式历史 resolver，初始 10,000 码的 archetype/风格/尺寸/层数/屋顶/entropy 还由单一黄金 manifest SHA-256 锁定。相同码和目录/生成器版本得到相同 `design_hash`，而具体坐标和场地适配另有计划 hash。`building code` 会先以角点/边中点/中心粗筛，再只完整调查每个朝向的最佳候选；双朝向共享 40,000 次地面探测硬上限，并按实际旋转方案检查已加载区块、完整建筑体积、流体、方块实体和替换冲突。调查遵守 `HIDDEN_BLOCK_SCAN` 能力，签名绑定维度、坐标、高度网格与派生统计。干燥缓坡会编译为锁定坐标的平地或永久桩柱地基；没有安全候选时只给出 `MANUAL_UNSURVEYED` 人工投影，不会暗中整地或确认。

大型确认任务以最多 1,792 个待建材料单位为一个背包批次；规划循环达到上限立即停止扫描，原子组仍只计一个最终物料单位。缺料时根据世界中已完成的精确状态重新规划下一批。恢复扫描每 tick 有上限，生产性重补给允许更多轮次。Bot 仍通过真实材料、寻路、射线和 `useItemOn` 施工，不使用模板直接写世界。此机制尚缺任务级工地保护、完整仓储/工具耐久预算和大型真实生存验收，所以仍是 `UNVERIFIED`。

每位玩家同时只有一个投影，会话默认五分钟过期。普通方案的 `move` 是绝对世界锚点；场地锁定方案不能移动/旋转，必须重新调查。确认距离上限为 128 格。最多 65,536 个投影格按每包 256 格、每 tick 最多 8 包发送，Commit 前客户端只保留 staging，不显示半份方案。缺失方块使用带纹理的半透明 BlockState baked model；错误状态、错误方块、清除和保留冲突用彩色线框。两端共用 palette 缓存、16³ section 近距排序、跨帧 section 公平轮转、维度/192 格/区块/视锥裁剪和每帧预算；每帧最多重绘 256 个真实模型，其余缺失格退化为线框。当前仍没有持久 VBO、邻接伪世界、方块实体、实体或动态流体投影。详见 [AI 建筑系统设计基线](docs/AI_BUILDING_SYSTEM.md)。

### 感知、记忆和多人协作

- `PerceptionCollector` 以配置半径收集有限数量的方块、实体和物品摘要。
- `FocusTracker` 是独立于主 Task 的注视状态机；Bot 在挖矿、走路、战斗、建造、暂停或待机时都能继续观察准星目标。
- 每次模型上下文只加入紧凑 `focus` 摘要；玩家问题确实依赖“眼前这个目标”的详细状态时，模型再调用核心工具 `inspect_focus`。
- 严格生存模式会通过可观察世界查询限制隐藏方块扫描。
- 每个 Bot 有键值事实、命名地点、基地、死亡地点和长期目标等记忆。
- `TaskBoard` 支持带 kind、role、参数和 scope 的共享 Job；空闲且角色匹配的 Bot 可以认领。
- 同一 owner 的 Bot 可以通过正常授权链互相发送命令消息；跨 owner 操作会被拒绝。

#### 语义注视：不使用视觉模型的“眼睛”

这项能力不截图、不上传像素，也不调用多模态视觉模型。服务端从 Bot 的眼睛坐标和 yaw/pitch 发出一条有限距离射线，同时检查方块和实体命中，并选择更近的目标。墙后的实体不能越过前方方块成为目标；地面掉落物虽然不是原版可选中实体，也会在一个很小的碰撞箱扩展范围内被识别。

```text
Bot 眼睛位置 + 朝向
        ↓
方块射线 + 实体射线（最近命中获胜）
        ↓
安全、限长的 FocusSnapshot
        ├── 紧凑 focus：自动进入 Current state
        ├── inspect_focus：模型按需读取详细状态
        └── ACQUIRED / CHANGED / UPDATED / LOST：后台短期历史与结构化日志
```

并行状态机为 `NO_TARGET → ACQUIRING → TRACKING → LOST_GRACE`。连续样本确认和短暂丢失宽限会过滤走路时的准星抖动。它不继承 `Task`、不访问 `TaskManager`，所以观察不会打断挖矿、战斗或建造。

紧凑摘要包含目标种类、ID、名称、位置、距离、命中面、交互距离和保守的行为标签。详细读取可以包含方块状态、硬度、工具要求、光照、流体、受限方块实体摘要，以及实体生命、护甲、速度、姿势、状态效果、攻击目标和证据化行为判断。行为判断带置信度和证据；`CHASING` 等标签是服务器状态推导，不被冒充为实体的真实“心理意图”。

观察层从不序列化原始 Entity、BlockEntity 或 NBT。名称与自定义文本会限长并被视为不可信数据。容器内容只在交互距离内形成有界物品计数；锁与当前手持物不匹配时拒绝读取；尚未展开战利品表的箱子不会因为“看一眼”而生成或改变战利品；第三方模组容器在没有专用只读 provider 前不会调用其任意 `Container` 方法。`MISS` 是成功的观察结果，只表示准星没有命中，不代表附近没有任何对象。

模型收到的紧凑状态大致如下；它会与生命、饥饿、背包、当前任务、附近摘要和时间等原有信息一起发送，而不是单独使用：

```json
{
  "focus": {
    "state": "TRACKING",
    "kind": "BLOCK",
    "source": "BOT_GAZE",
    "targetToken": "block:minecraft:overworld:33912381452:minecraft:iron_ore",
    "id": "minecraft:iron_ore",
    "displayName": "Iron Ore",
    "position": { "x": 12, "y": -31, "z": 48 },
    "distance": 3.42,
    "withinInteractionReach": true,
    "stale": false,
    "hitFace": "north",
    "behavior": "UNKNOWN"
  }
}
```

典型对话链路是：玩家问“你现在看着什么，它能不能挖” → 模型先读 `Current state.focus` → 因为问题依赖硬度和工具状态而调用一次 `inspect_focus {"detail":"full","expected_target_token":"..."}` → 把返回的 `requiresCorrectTool`、`heldToolCorrect`、距离和自身背包结合起来 → 决定回答、切换工具或调用确定性挖掘工具。若玩家只问“你看着什么”，紧凑摘要已经足够，模型无需额外调用工具。

`targetToken` 把一次请求中的“这个/那个”与具体目标关联起来。`inspect_focus` 会重新读取调用瞬间的准星，并返回 `targetChanged`；若模型思考或网络等待期间 Bot 已转头，它会被明确告知目标发生变化，不能把新目标误当成玩家刚才所指的对象。方块令牌由维度、位置和方块 ID 组成，实体令牌由维度、UUID 和实体/物品 ID 组成，不依赖可能被服务端复用的短期实体编号；所以同坐标的铁矿被替换成 TNT 也会被检测。`MISS` 和 `DISABLED` 同样使用非空哨兵令牌，因此从“没有目标”变成“出现目标”不会漏报。

| 层级 | 主要字段 | 使用目的 |
|---|---|---|
| 通用 | `state/kind/targetToken/id/displayName/position/distance/stale` | 判断是否稳定命中、目标是什么、是否在模型等待期间改变以及位于哪里。 |
| 方块 | 方块状态属性、硬度、是否不可破坏、正确工具要求、手持工具是否合适、流体、光照 | 回答“这是什么状态”“能否挖”“该换什么工具”。 |
| 方块实体 | 类型、槽位数、受限物品计数、是否可安全读取及拒绝原因 | 在不读取原始 NBT、不触发战利品生成的前提下理解容器。 |
| 生物/实体 | 生命、最大生命、护甲、速度、姿势、环境状态、主手、攻击目标、状态效果 | 为战斗、救援、跟随或解释当前行为提供证据。 |
| 掉落物 | 物品 ID、数量、耐久、自定义名、发光状态 | 判断眼前掉落物是否值得拾取。 |
| 行为 | `label/confidence/evidence` | 提供可审计的保守推断；证据不足时保持 `UNKNOWN`。 |

`inspect_focus` 是核心只读工具，即使任务被玩家暂停也能使用；它不会替换当前 Task、移动 Bot、打开 GUI 或改变世界。若模型先执行 `look_at`，同一 Tick 随后的检查也会重新射线，而不会返回转头前的缓存。TPS 降级时只会放慢后台采样，玩家问题触发的按需检查仍是即时读取。

### 运行时控制和可观察性

- 当前任务与目标支持替换、暂停、恢复、停止和全部取消。
- `TpsGuard` 根据服务器状态延缓高成本继续决策与扫描。
- `BotProfiler`、`ReplayRecorder`、结构化日志和诊断命令用于观察性能和最近决策。
- 日志包含单 Bot 文件、全局文件、按日期/大小轮转、队列溢出计数和 SLF4J 镜像。
- 安全拒绝会进入安全审计日志，即使异步结构化日志尚未启动也尽量保持可观察。

### 客户端控制面板

安装同加载器客户端模组后，可使用：

- `Alt + 0`：打开/关闭聊天与状态面板；
- `Alt + 9`：打开快捷动作面板；
- Minecraft“控制”设置中也会注册两个未默认绑定的 `KeyMapping`，玩家可以自行绑定；
- 状态页：生命、饥饿、坐标、任务状态、进度、模型 token；
- 目标页：目标标题、确定性步骤链和验收结果；
- 背包按钮：打开原版 Menu 语义的 AI 背包/装备栏；也可空主手右键自己的 Bot 打开。上半部分是 Bot 护甲、副手、27 格主背包和 9 格快捷栏，下半部分是操作者自己的 27+9 背包；
- 快捷动作：过来、暂停/继续、停止、进食、睡觉、挖掘、合成、冶炼；
- 设置页：低层工具、记忆工具、进度播报，以及 operator 模式允许时的“传送至 AI / 召回 AI”。

面板只是一层客户端入口。所有目标选择、物品移动、设置更新和传送都必须在服务端重新做 owner/OP 与能力校验；客户端按钮是否显示或启用不被视为安全边界。

## Fabric 与 NeoForge 的区别

共享核心不直接依赖 Fabric API 或 NeoForge 事件。两个加载器只负责把平台事件和网络传输转交给 `FakeAiPlayer`。

| 事项 | Fabric | NeoForge |
|---|---|---|
| 子项目 | `fabric` | `neoforge` |
| 入口 | `FakeAiPlayerFabric` / `FakeAiPlayerFabricClient` | `FakeAiPlayerNeoForge` / NeoForge 客户端事件类 |
| 元数据 | `fabric.mod.json` | `META-INF/neoforge.mods.toml` |
| 平台依赖 | Fabric Loader + Fabric API | NeoForge |
| 命令/生命周期 | Fabric API callbacks | NeoForge event bus |
| 聊天捕获 | `ServerMessageEvents` | `ServerChatEvent` |
| Payload | Fabric networking API | NeoForge payload registrar/handler |
| 客户端按键 | Fabric key binding 与 client tick callbacks | NeoForge `RegisterKeyMappingsEvent` 与 client tick event |
| 模块建筑投影 | Begin/Chunk/Commit/Ready/Confirm/Cancel、真实 BlockState 幽灵模型与冲突线框已进入源码，仍未完成真实客户端验收 | 与 Fabric 共用协议、分类、缓存、section 索引和预算；NeoForge 仅保留事件/网络适配 |
| 核心逻辑 | 与 NeoForge 共用 `common` | 与 Fabric 共用 `common` |
| 兼容状态 | 原上游路径的主要参考实现；迁移后仍需重新回归 | 新增移植目标；需要更严格的启动、假玩家连接、Mixin 和网络验证 |

不要同时把 Fabric 版和 NeoForge 版 JAR 放入同一个实例。客户端若要使用面板，客户端与服务端必须选择同一种加载器，并使用匹配的 FakeAiPlayer 版本。

## 安装

### 通用要求

- Minecraft Java Edition `1.21.3`；
- Java `21`；
- 一个支持出站 HTTPS 的服务端环境（仅自然语言功能需要）；
- 在 Fabric 或 NeoForge 中二选一；
- 在测试服和备份世界中先验证 Alpha 版本。

### Fabric

| 组件 | 版本 |
|---|---|
| Minecraft | `1.21.3` |
| Fabric Loader | `0.18.4` 或满足元数据要求的更新兼容版本 |
| Fabric API | `0.114.1+1.21.3` 或满足元数据要求的更新兼容版本 |
| Java | `21` |

将以下内容放入服务端 `mods/`：

1. Fabric 版 FakeAiPlayer JAR；
2. 对应 Minecraft 1.21.3 的 Fabric API。

希望使用图形面板的玩家，也要在自己的 Fabric 客户端安装同版本 FakeAiPlayer 和 Fabric API。只使用 `/fakeaiplayer` 命令或 `@Bot名` 聊天时，客户端面板不是必需条件。

### NeoForge

| 组件 | 版本 |
|---|---|
| Minecraft | `1.21.3` |
| NeoForge | `21.3.96` 为当前开发目标 |
| Java | `21` |

将 NeoForge 版 FakeAiPlayer JAR 放入服务端 `mods/`。希望使用图形面板的玩家，也要在自己的 NeoForge 客户端安装匹配版本。

> [!WARNING]
> NeoForge 是本仓库新增的移植目标。第一次运行应使用专门的测试世界，并重点检查 Bot 生成/退出、服务器停止保存、payload 协商、客户端面板、村民交易 Mixin 和长时间 Tick 稳定性。

### 从源码构建 JAR

```bash
git clone https://github.com/GreyTaiWolf/FakeAiPlayer.git
cd FakeAiPlayer

./gradlew :fabric:build :neoforge:build
```

预期输出目录：

```text
fabric/build/libs/
neoforge/build/libs/
```

归档基础名按 `fakeaiplayer-<loader>-1.21.3` 生成；最终文件名还包含项目版本。不要把 `*-sources.jar` 当作游戏模组安装。

## 快速开始

### 1. 启动一次生成配置

首次启动会在加载器配置目录创建：

```text
config/fakeaiplayer.json
```

### 2. 选择模型密钥方式

服务器管理员可以在启动进程环境中提供全局默认 Key：

```bash
export DEEPSEEK_API_KEY="sk-your-key"
```

Windows PowerShell 当前会话示例：

```powershell
$env:DEEPSEEK_API_KEY = "sk-your-key"
```

然后从同一终端启动服务端。环境变量会覆盖配置对象中的 `deepseek.apiKey`，但不会由加载逻辑主动回写到 `fakeaiplayer.json`。它是未绑定每 Bot Key 时的服务器 fallback。

### 3. 生成个人助手

```mcfunction
/fakeaiplayer spawn Bob
```

带角色生成：

```mcfunction
/fakeaiplayer spawn Bob miner
```

如果不想使用服务器默认 Key，生成 Bot 后由 owner/OP 在安装同版模组的客户端执行：

```mcfunction
/fakeaiplayer ai setup Bob
```

该命令打开遮罩输入窗，不要在聊天框或命令参数中粘贴 Key。服务端先使用无工具调用的请求验证它；只有连接成功才会保存到玩家客户端的 `config/fakeaiplayer-client.json`。该文件是明文，详见下文的[API Key 安全建议](#api-key-安全建议)。

### 4. 与它交流

世界聊天：

```text
@Bob 帮我收集 16 个圆石，然后放进基地附近的箱子
```

或命令：

```mcfunction
/fakeaiplayer brain say Bob 帮我做一把铁镐
```

也可以完全绕过 LLM，直接分配确定性任务：

```mcfunction
/fakeaiplayer task assign Bob mine minecraft:stone 16
/fakeaiplayer task status Bob
```

### 5. 停止或移除

```mcfunction
/fakeaiplayer task pause Bob
/fakeaiplayer task resume Bob
/fakeaiplayer task abort Bob
/fakeaiplayer despawn Bob
```

## 模型与 API Key 配置

### 当前 API 模型

提供商端点和模型名仍是服务器级单一配置；鉴权 Key 则可按 Bot 绑定。请求优先使用该 Bot 已验证的客户端 Key，没有时才使用服务器默认 Key：

- 默认 `baseUrl`：`https://api.deepseek.com`；
- 默认 `model`：`deepseek-chat`；
- 请求地址：规范化后的 `baseUrl + /v1/chat/completions`；
- 鉴权：`Authorization: Bearer <apiKey>`；
- 请求模式：非流式；
- 工具协议：OpenAI 风格 `tools`、`tool_choice=auto` 和 `tool_calls`；
- 429 与 5xx 可按配置重试；
- 模型必须正确支持 function/tool calling，否则自然语言执行链无法可靠工作。

如果 `baseUrl` 以 `/v1` 结尾，客户端会先去掉这个结尾，再拼接 `/v1/chat/completions`。因此常见配置既可写服务根地址，也可写以 `/v1` 结尾的兼容地址。带有其他自定义路径的网关需要自行确认最终 URL。

### 每 Bot Key 连接流程

```mcfunction
/fakeaiplayer ai setup <name>
/fakeaiplayer ai status <name>
/fakeaiplayer ai test <name>
/fakeaiplayer ai disconnect <name>
```

- `setup` 只能由游戏内玩家执行。服务端先完成 owner/OP 授权，再发放一次性、短时有效的 nonce，客户端才会打开遮罩输入窗；
- Key 最长 512 字符。提交后先进入 pending 槽，以“无工具、不可执行任务”的请求测试鉴权与基本聊天连通性；
- 成功后才原子启用新 Key。无效 Key、限流、超时、网络/提供商失败都会丢弃 pending 值，原来已激活的 Bot Key 或服务器 fallback 保持不变；
- 连接成功后，模型可选择向 owner 发一句简短问候。这一轮没有任何工具，不能新建、改变或执行任务；
- 验证成功后才将 Key 写入客户端配置。下次连入同一账号与服务器范围时，客户端自动还原并再次验证；
- `disconnect` 清除该 Bot 在服务端会话中的 Key，并通知当前客户端删除对应本地项。如果服务器设置了默认 Key，Bot 会回退到它；
- 提交/恢复请求按“玩家 + Bot”限制为每 1,200 server tick 最多 4 次，并使用独立有界并发队列。常规模型决策同样使用有界队列：每 Bot 同时一个请求，排队时只保留最新待处理项。

### API Key 安全建议

1. 管理员的全局 Key 优先使用环境变量 `DEEPSEEK_API_KEY`；玩家 Key 只用 `ai setup` 的遮罩输入窗。不要把真实密钥粘贴到聊天、命令参数，或提交到 Git、截图、Issue、日志包和公开整合包。
2. 给此项目使用单独密钥，设置余额/速率上限，并定期轮换。
3. 玩家 Key 会以明文 JSON 保存在该玩家客户端 `config/fakeaiplayer-client.json`，按 Minecraft 账号、服务器/单人世界范围和 Bot 分开。POSIX 文件系统会尽量设为 `0600`，但这不是加密；本机管理员、恶意模组、备份/同步软件或不支持 POSIX 权限的系统仍可能读取。
4. 模型调用发生在服务端。玩家 Key 在每次连接/恢复时会通过模组 payload 发到服务端，并在当前会话的服务端内存中保留；服务器管理员与运行时环境仍是信任边界。它不会写入世界快照或服务端的玩家 Key 配置。
5. 服务端 `fakeaiplayer.json` 支持 `deepseek.apiKey` 字段，但它同样是明文，只适合权限受控的私有测试环境。
6. 提交故障日志前搜索并清理密钥、请求内容、玩家聊天和世界坐标等敏感信息。代码已对凭据 payload 的 `toString()` 和常见错误路径做脱敏，但不应把这当成日志包可公开的保证。
7. 任何 OpenAI 兼容反向代理都能看到 Key、聊天历史、感知摘要和工具定义；只连接你信任的 HTTPS 端点。
8. 自然语言请求可能计费。普通确定性命令和 Task 不需要调用模型，可用于无密钥测试。

## 完整配置参考

配置文件位置由加载器的 config 目录决定，文件名固定为 `fakeaiplayer.json`。以下示例与当前 `AIBotConfig.defaults()` 对齐：

```json
{
  "profile": "strict_survival",
  "operatorCapabilities": {
    "hiddenBlockScan": true,
    "emergencyTeleport": true,
    "forcedPickup": true,
    "manualTeleport": true
  },
  "deepseek": {
    "apiKey": "",
    "baseUrl": "https://api.deepseek.com",
    "model": "deepseek-chat",
    "maxTokens": 2048,
    "temperature": 0.3,
    "timeoutSeconds": 60,
    "retryCount": 3,
    "retryBackoffMs": 500
  },
  "perception": {
    "radius": 16,
    "maxBlocks": 20,
    "maxEntities": 10,
    "maxItems": 10,
    "includeRawLists": false,
    "focus": {
      "enabled": true,
      "range": 8,
      "sampleIntervalTicks": 2,
      "stableSamples": 2,
      "lostGraceSamples": 4,
      "historySize": 128,
      "maxDetailChars": 4096
    }
  },
  "brain": {
    "maxHistoryMessages": 36,
    "maxToolCallsPerTurn": 6,
    "maxTurnsPerRequest": 12,
    "exposeLowLevelTools": false,
    "enableMemoryTools": true,
    "enableCoordinationTools": false,
    "maxTaskRetries": 3,
    "verboseReports": true
  },
  "watchdog": {
    "stuckWindowTicks": 200
  },
  "logging": {
    "enabled": true,
    "directory": "logs/fakeaiplayer",
    "perBotFile": true,
    "rotation": "daily",
    "maxFileSizeMb": 50,
    "maxBackups": 30,
    "mirrorToSlf4j": true,
    "categories": {
      "LIFECYCLE": "INFO",
      "COMM": "INFO",
      "API": "INFO",
      "ACTION": "INFO",
      "PERCEPTION": "DEBUG",
      "PATH": "DEBUG",
      "TASK": "INFO",
      "DANGER": "INFO",
      "ERROR": "ERROR",
      "CONFIG": "INFO"
    }
  },
  "survival": {
    "hungerEatThreshold": 14,
    "hungerCriticalThreshold": 6
  },
  "combat": {
    "retreatHp": 10,
    "maxEnemiesToFight": 2
  },
  "night": {
    "autoSleep": true,
    "torchLightThreshold": 8
  },
  "mining": {
    "returnWhenFreeSlots": 2,
    "toolDurabilityFloor": 0.1,
    "placeTorches": true
  },
  "goal": {
    "maxPlanDepth": 24,
    "replanOnFailure": true,
    "autoToolFill": true
  },
  "nav": {
    "jumpReach": 1.0,
    "sidleAfter": 12,
    "sidleLimit": 60,
    "hardLimit": 30,
    "lookahead": 4,
    "nodeRetry": 2,
    "sprintMinDist": 3.0,
    "maxSafeFall": 3
  },
  "pickup": {
    "forceRadiusH": 2.75,
    "forceRadiusV": 2.5,
    "sweepRadius": 8.0
  }
}
```

### 顶层运行模式

| 字段 | 默认 | 含义 |
|---|---:|---|
| `profile` | `strict_survival` | `strict_survival` 禁止四项特权能力；`operator` 再按 `operatorCapabilities` 的单项开关决定。 |
| `operatorCapabilities.hiddenBlockScan` | `true` | operator 模式允许读取不可见隐藏方块。strict 下即使为 true 也会被拒绝。 |
| `operatorCapabilities.emergencyTeleport` | `true` | operator 模式允许紧急脱困传送。 |
| `operatorCapabilities.forcedPickup` | `true` | operator 模式允许强制吸取掉落物。 |
| `operatorCapabilities.manualTeleport` | `true` | operator 模式允许面板“传送至 AI / 召回 AI”。 |

`AIBOT_PROFILE=strict_survival` 或 `AIBOT_PROFILE=operator` 会覆盖文件值。未知环境变量或无效文件值会 fail closed 到 `strict_survival`。已有配置文件如果完全没有 `profile` 字段，会为上游兼容暂时解析成 `operator` 并写警告；因此迁移旧配置后务必手动加入明确的 `profile`。

### `deepseek`

| 字段 | 默认 | 含义 |
|---|---:|---|
| `apiKey` | 空 | 服务端模型密钥；环境变量优先。 |
| `baseUrl` | `https://api.deepseek.com` | OpenAI 兼容服务根地址。 |
| `model` | `deepseek-chat` | 发送到 API 的模型名称。 |
| `maxTokens` | `2048` | 单次完成的最大 token。 |
| `temperature` | `0.3` | 模型随机度，是否接受及范围由提供商决定。 |
| `timeoutSeconds` | `60` | 单次 HTTP 请求超时。 |
| `retryCount` | `3` | 首次请求之后的最大重试次数。 |
| `retryBackoffMs` | `500` | 初始退避毫秒数，重试时倍增。 |

### `perception` 与 `brain`

| 字段 | 默认 | 含义 |
|---|---:|---|
| `perception.radius` | `16` | 感知扫描半径。增大它会增加扫描和提示体积。 |
| `perception.maxBlocks` | `20` | 感知摘要中的方块条目上限。 |
| `perception.maxEntities` | `10` | 实体条目上限。 |
| `perception.maxItems` | `10` | 掉落物条目上限。 |
| `perception.includeRawLists` | `false` | 是否把更原始的列表加入感知数据；可能显著增加 token 和隐私暴露。 |
| `perception.focus.enabled` | `true` | 是否启用 Bot 准星的语义注视感知。旧配置缺少此对象时会合并为启用，而不是静默关闭。 |
| `perception.focus.range` | `8` | 注视射线距离；限制为 1–64，运行时还不会超过 `perception.radius`。 |
| `perception.focus.sampleIntervalTicks` | `2` | 后台采样间隔，限制为 1–200 Tick；TPS 降级时会自动放慢。模型按需读取仍执行即时射线。 |
| `perception.focus.stableSamples` | `2` | 同一目标连续出现多少个样本后进入 `TRACKING`，限制为 1–20。 |
| `perception.focus.lostGraceSamples` | `4` | 允许连续缺失的样本数；第 N+1 个缺失样本确认 `LOST`，限制为 1–100。 |
| `perception.focus.historySize` | `128` | 每个 Bot 在内存中保留的重要注视变化条数；限制为 1–1024，不会每 Tick 追加。 |
| `perception.focus.maxDetailChars` | `4096` | `inspect_focus` 最终工具消息（含 JSON envelope）的字符上限，限制为 1024–16384；超限时退化为紧凑摘要或最小变更提示。 |
| `brain.maxHistoryMessages` | `36` | 每个 Bot 保留的模型对话历史上限。 |
| `brain.maxToolCallsPerTurn` | `6` | 单个模型响应允许执行的工具调用数。 |
| `brain.maxTurnsPerRequest` | `12` | 一次玩家请求的工具往返轮次上限。 |
| `brain.exposeLowLevelTools` | `false` | 默认是否给模型开放手动移动/破坏/放置等低层工具。 |
| `brain.enableMemoryTools` | `true` | 默认是否开放记忆工具。 |
| `brain.enableCoordinationTools` | `false` | 默认是否开放多 Bot Job/通信工具。 |
| `brain.maxTaskRetries` | `3` | 任务失败后的有限重试预算。 |
| `brain.verboseReports` | `true` | 是否发送更详细的进度报告。 |

### 生存、目标与移动

| 字段 | 默认 | 含义 |
|---|---:|---|
| `watchdog.stuckWindowTicks` | `200` | 判断长时间没有有效进展的观察窗口。 |
| `survival.hungerEatThreshold` | `14` | 低于该饥饿值时考虑进食。 |
| `survival.hungerCriticalThreshold` | `6` | 危急饥饿阈值。 |
| `combat.retreatHp` | `10` | 生命较低时撤退的阈值。 |
| `combat.maxEnemiesToFight` | `2` | 同时愿意处理的敌对目标上限。 |
| `night.autoSleep` | `true` | 是否尝试正常寻找/放置床并睡觉。 |
| `night.torchLightThreshold` | `8` | 区域补光使用的方块光照阈值。 |
| `mining.returnWhenFreeSlots` | `2` | 背包剩余槽位较少时返回/存储。 |
| `mining.toolDurabilityFloor` | `0.10` | 工具耐久比例下限。 |
| `mining.placeTorches` | `true` | 挖矿任务是否尝试放火把。 |
| `goal.maxPlanDepth` | `24` | 依赖规划最大深度。 |
| `goal.replanOnFailure` | `true` | 步骤失败后是否允许重新规划。 |
| `goal.autoToolFill` | `true` | 是否自动补全工具依赖。 |
| `nav.jumpReach` | `1.0` | 跳跃/移动可达性参数。 |
| `nav.sidleAfter` | `12` | 停滞后开始侧移修正的阈值。 |
| `nav.sidleLimit` | `60` | 侧移恢复限制。 |
| `nav.hardLimit` | `30` | 节点执行硬限制。 |
| `nav.lookahead` | `4` | 路径执行前视节点数。 |
| `nav.nodeRetry` | `2` | 节点重试次数。 |
| `nav.sprintMinDist` | `3.0` | 允许冲刺的最小距离。 |
| `nav.maxSafeFall` | `3` | 允许的最大安全落差。 |
| `pickup.forceRadiusH` | `2.75` | operator 强制拾取的水平范围。 |
| `pickup.forceRadiusV` | `2.5` | operator 强制拾取的垂直范围。 |
| `pickup.sweepRadius` | `8.0` | 搜索可拾取物的范围。 |

### `logging`

| 字段 | 默认 | 含义 |
|---|---:|---|
| `enabled` | `true` | 是否启动结构化文件日志。 |
| `directory` | `logs/fakeaiplayer` | 相对于游戏目录的日志目录。 |
| `perBotFile` | `true` | 是否写 `by-bot/<安全化名称>.log`。 |
| `rotation` | `daily` | 当前配置的轮转策略标识。 |
| `maxFileSizeMb` | `50` | 文件大小轮转阈值。 |
| `maxBackups` | `30` | 最大备份数量。 |
| `mirrorToSlf4j` | `true` | INFO 及以上是否镜像到普通服务端日志。 |
| `categories` | 见完整 JSON | 各日志类别的最低级别。 |

## 指令、聊天与客户端面板

所有生产命令根节点已经从上游 `/aibot` 改为：

```mcfunction
/fakeaiplayer
```

命令中的 `<name>` 是 Bot 名；在支持 owner 自动选择的入口里，空名称通常表示当前玩家自己的 Bot。具体 Brigadier 自动补全以当前游戏内命令树为准。

### Bot 生命周期

```mcfunction
/fakeaiplayer spawn <name> [role]
/fakeaiplayer role <name> <role>
/fakeaiplayer despawn <name>
/fakeaiplayer list
```

普通玩家可以生成自己的个人 Bot，但同一玩家只能拥有一个。无玩家来源的控制台生成属于全局管理操作。

### 每 Bot AI 连接

```mcfunction
/fakeaiplayer ai setup <name>
/fakeaiplayer ai status <name>
/fakeaiplayer ai test <name>
/fakeaiplayer ai disconnect <name>
```

`setup` 需要当前玩家的 Fabric/NeoForge 客户端安装匹配版本；服务端控制台不能代替玩家客户端保存 Key。`test` 只做无工具的连通性检查。`disconnect` 撤销会话 Key 并删除当前客户端的对应本地项；四个入口都经过 Bot owner/OP 授权。

### 大脑

```mcfunction
/fakeaiplayer brain status <name>
/fakeaiplayer brain reset <name>
/fakeaiplayer brain manual <name> on|off
/fakeaiplayer brain say <name> <text...>
```

诊断入口（需要相应管理授权，可能调用 API 或制造错误场景）：

```mcfunction
/fakeaiplayer brain validate <name> api-failure
/fakeaiplayer brain validate <name> bad-tool-args
/fakeaiplayer brain validate <name> bad-response
/fakeaiplayer brain validate <name> tps <3..60> <text...>
```

### 确定性任务

常用任务：

```mcfunction
/fakeaiplayer task assign <name> move <x> <y> <z>
/fakeaiplayer task assign <name> forage <count>
/fakeaiplayer task assign <name> attack <entity_type> <count>
/fakeaiplayer task assign <name> mine <block_id> <count>
/fakeaiplayer task assign <name> gather <item_id> <count>
/fakeaiplayer task assign <name> craft <item_id> <count>
/fakeaiplayer task assign <name> eat
/fakeaiplayer task assign <name> sleep
/fakeaiplayer task assign <name> light_area <radius> <max_torches>
```

挖矿和矿脉：

```mcfunction
/fakeaiplayer task assign <name> strip_mine <north|south|east|west> [length] [spacing]
/fakeaiplayer task assign <name> strip_mine <north|south|east|west> <length> <spacing> depot <x> <y> <z>
/fakeaiplayer task assign <name> mine_vein [ore_id]
```

农牧：

```mcfunction
/fakeaiplayer task assign <name> farm <x> <y> <z> <radius> <crop_id> [keep_tending]
/fakeaiplayer task assign <name> harvest <x> <y> <z> <radius> <crop_id>
/fakeaiplayer task assign <name> breed <entity_type> <pairs>
```

容器和囤货：

```mcfunction
/fakeaiplayer task assign <name> deposit all_except_tools
/fakeaiplayer task assign <name> deposit item <item_id> <count>
/fakeaiplayer task assign <name> deposit at <x> <y> <z> all_except_tools
/fakeaiplayer task assign <name> withdraw <item_id> <count>
/fakeaiplayer task assign <name> withdraw at <x> <y> <z> <item_id> <count>
/fakeaiplayer task assign <name> stockpile [include_tools]
```

冶炼和建造：

```mcfunction
/fakeaiplayer task assign <name> smelt <input_item> <output_item> <count>
/fakeaiplayer task assign <name> build <blueprint> <x> <y> <z> [flatten]
/fakeaiplayer task assign <name> build <blueprint> auto_site [flatten]
```

控制和状态：

```mcfunction
/fakeaiplayer task status <name>
/fakeaiplayer task pause <name>
/fakeaiplayer task resume <name>
/fakeaiplayer task abort <name>
```

### 记忆

```mcfunction
/fakeaiplayer memory <name> remember <key> <value...>
/fakeaiplayer memory <name> recall <key>
/fakeaiplayer memory <name> forget <key>
/fakeaiplayer memory <name> mark_place <place>
/fakeaiplayer memory <name> set_base
/fakeaiplayer memory <name> goto_place <place>
/fakeaiplayer memory <name> set_goal <title> <step1|step2|...>
/fakeaiplayer memory <name> advance_goal <result...>
/fakeaiplayer memory <name> goal_status
/fakeaiplayer memory <name> inject
```

### 多 Bot Job

```mcfunction
/fakeaiplayer job post <kind> <role> [params...]
/fakeaiplayer job list
/fakeaiplayer job tell <from_bot> <target_bot> <message...>
/fakeaiplayer job clear
```

Job 有 owner/global scope 和运行时 lease 语义。服务重启时旧进程留下的认领不会被无条件信任；恢复逻辑会重新打开或标记不安全的旧 lease。

### 持久化、观测和管理员诊断

```mcfunction
/fakeaiplayer persist save
/fakeaiplayer persist reload
/fakeaiplayer persist trust-empty-log-baseline confirm
/fakeaiplayer profile <name>
/fakeaiplayer replay <name> [1..50]
/fakeaiplayer tps
/fakeaiplayer log status
/fakeaiplayer log rotate
/fakeaiplayer log overflow [1..50000]
/fakeaiplayer snapshot [1..24]
/fakeaiplayer deplint <name> <spec...>
```

注意：

- `persist reload` 只会在没有 Bot、活动任务和 Job 时执行，防止把运行中的状态直接替换掉；
- `persist trust-empty-log-baseline confirm` 只允许全局管理员在审计/备份世界后使用；它明确接受
  基线前未知原木结构风险，并拒绝覆盖现有、损坏或未干净关闭的来源证据；
- `snapshot` 会扫描隐藏地形并写复现场景，只允许全局管理员，且要求 operator + `hiddenBlockScan`；
- `log overflow` 是压力/诊断命令，会主动向队列注入大量事件，不应在生产高峰随意运行；
- `deplint` 用于检查 Goal 依赖链，支持 `mine_ore`、`item`、`pickaxe`、`armor`、`workstation`、`stockpile` 等 spec。

### 聊天语法

```text
@<Bot名> <消息>
```

示例：

```text
@Bob 跟着我
@Bob 暂停
@Bob 继续刚才的任务
@Bob 帮我做 8 个熟牛肉
```

聊天监听会先做目标 Bot 解析与 `COMMAND` 授权，再把消息交给控制短语路由或模型大脑。没有 `@Bot名` 前缀的普通聊天不会触发 AI。

Bot 回复与必要的系统状态默认以 `[AI] <Bot名> ...` 发送到 owner 的主聊天，不向附近玩家或全服广播。Bot 面板仍保留更长的详细记录；玩家自己的输入回显只进面板。主聊天文本会去除控制/格式字符、压成单行、截断并按 Bot/消息类型限流。

当前生产接线允许在每 Bot Key 首次连接成功后生成一句可选问候。这种主动社交轮次明确不开放工具，不能自行启动任务；新任务仍需 owner 或已授权 OP 的明确聊天、命令或面板操作。既有生存安全层仍可以在危险时暂停普通工作并避险，这不等于模型自主创建新任务。

## 权限与安全模型

### Owner、OP 与控制台

| 发起者 | 默认允许范围 |
|---|---|
| Bot owner | 查看、聊天/命令、背包操作、自己的 Bot 管理操作。 |
| OP（权限等级 2+） | 可操作其他玩家 Bot，并执行全局管理入口。 |
| 可信控制台（权限等级 4） | 全局管理。 |
| 同 owner 的另一个 Bot | 仅允许经过策略的 `COMMAND` 协作，不允许背包/传送/管理。 |
| 跨 owner Bot 或未知发起者 | 拒绝。 |

查找失败和权限失败对普通调用者返回相同的模糊提示，避免通过名字探测不属于自己的 Bot；详细原因写入安全审计日志。

### 运行模式

`strict_survival` 是新安装默认模式。它无条件拒绝：

- `HIDDEN_BLOCK_SCAN`；
- `EMERGENCY_TELEPORT`；
- `FORCED_PICKUP`；
- `MANUAL_TELEPORT`。

`operator` 只是允许管理员进一步选择，并不绕过单项开关。四个能力仍分别由 `operatorCapabilities` 决定。

### 服务端是最终边界

- 面板 C2S payload 不会直接修改世界；服务端先解析目标、复核 owner/OP，再分发动作。
- API Key 设置 payload 使用服务端发放的一次性 nonce，并再次复核目标 Bot 的 owner/OP 权限；自动恢复同样重做授权和提供商验证。
- 背包使用服务端权威 `AbstractContainerMenu` 槽位事务，服务端复核 owner/OP、距离、维度、装备限制和实际可移动数量；拖拽、右键分堆、双击与 Shift 移动不再由自定义物品搬运包模拟。
- 传送除了权限外还要求 `MANUAL_TELEPORT` 有效，并寻找附近可站立位置。
- S2C 状态订阅每次推送前都会重新检查查看权限；玩家断线或失权后订阅会清理。
- 模型工具也必须通过工具组、授权和能力边界；不要把 prompt 当作安全机制。

此项目仍是 Alpha，网络输入长度/数量边界、请求速率与模型费用滥用需要持续审计。不要直接把未经验证的开发构建部署到不受信任的公开服务器。

## 持久化与目录

以下路径以游戏/服务器工作目录为基准：

| 数据 | 路径 | 说明 |
|---|---|---|
| 主配置 | `config/fakeaiplayer.json` | 模型、模式、感知、脑、日志、生存和寻路参数。 |
| 客户端 AI 凭据 | `config/fakeaiplayer-client.json` | 仅存在于玩家客户端；按账号 + 服务器/单人世界 + Bot 存储验证通过的 Key。明文 JSON；POSIX 上尽量使用 `0600`。 |
| 结构化日志 | `logs/fakeaiplayer/all.log` | 默认全局日志。 |
| 单 Bot 日志 | `logs/fakeaiplayer/by-bot/*.log` | Bot 名会安全化后作为文件名。 |
| 日志归档 | `logs/fakeaiplayer/archive/` | 日期/大小轮转输出。 |
| 世界运行快照 | `<world>/fakeaiplayer/runtime.json` | 版本化的 Bot、任务/目标、暂停状态和 Job 快照。 |
| 旧格式输入 | `<world>/fakeaiplayer/bots.json`、`jobs.json` | 仅当 `runtime.json` 不存在时尝试读取并转换。 |
| 蓝图 | `<gameDir>/blueprints/*.json` | 自定义/默认蓝图，以及确认模块投影后写入的不可变 `generated_*` 精确蓝图。 |
| 地形诊断 | 通常为游戏目录相邻的 `reports/snapshot_x_y_z.txt` | `/fakeaiplayer snapshot` 输出。 |

### 保存行为

- 服务端启动时加载并重生 Bot，再恢复目标/任务运行时和 Job；
- 状态变更会触发合并/防抖的异步写；
- 每 6000 server tick 触发一次周期异步保存；
- 服务端停止时执行生命周期保存；
- `runtime.json` 通过临时文件和原子移动尽量避免半写文件；
- 不支持的 schema 或损坏文件会使持久化进入只读保护，避免用空状态覆盖原文件；
- 管理员可用 `/fakeaiplayer persist save` 显式保存。

不要在服务器运行时手工编辑 `runtime.json`。修改前停止服务并保留整个世界目录备份。

### 蓝图

- `small_hut` 和 `hut_5x5` 会在首次使用时生成默认 JSON；
- 自定义蓝图从 `<gameDir>/blueprints/<name>.json` 读取；
- 参数化房屋使用内部名字 `custom:宽x深x高:材质`；
- 展开后的蓝图最多 65,536 个方块，编码文件最多 32 MiB，超过限制会拒绝；
- 建造命令支持指定锚点、自动选址和可选整平。
- 确认模块投影后，服务端把已旋转的精确顺序写为 `generated_*` 蓝图，写后回读并核对 canonical SHA-256，再把名称、绝对锚点、维度和 `blueprint_digest` 写入 `Goal.Build`/`MissionSpec`；恢复、备料和建造期间的错误维度会失败关闭，旧 generated 任务缺少摘要时不会执行，同名不同内容也拒绝覆盖。

## 网络协议

共享模块定义 Mojang `CustomPacketPayload`，加载器适配层分别完成注册与发送。当前命名空间全部为 `fakeaiplayer`：

| ID | 方向 | 用途 |
|---|---|---|
| `fakeaiplayer:subscribe_bot` | C2S | 订阅/取消一个有权查看的 Bot。 |
| `fakeaiplayer:bot_command` | C2S | 面板聊天、移动、挖掘、合成、冶炼、进食、睡觉、暂停、恢复、停止、重置。 |
| `fakeaiplayer:set_option` | C2S | 修改单 Bot 的 manual/memory/reports 运行选项。 |
| `fakeaiplayer:open_bot_inventory` | C2S | 面板请求打开与空手右键相同的服务端权威 Bot 背包 Menu。 |
| `fakeaiplayer:teleport` | C2S | operator 能力允许时传送至 AI 或召回 AI。 |
| `fakeaiplayer:set_bot_ai_credential` | C2S | 在一次性 setup nonce 下提交最多 512 字符的候选 Key；服务端授权、分阶段验证后才启用。 |
| `fakeaiplayer:restore_bot_ai_credential` | C2S | 客户端加入已保存的账号/服务器范围时恢复 Key；不绕过授权或重新验证。 |
| `fakeaiplayer:bot_snapshot` | S2C | 生命、饥饿、坐标、任务、Goal、token、能力、背包和装备快照。 |
| `fakeaiplayer:bot_chat` | S2C | AI、用户回显和系统面板消息。 |
| `fakeaiplayer:open_bot_ai_setup` | S2C | 服务端授权后发送 Bot 名与短时 nonce，打开遮罩凭据输入窗。 |
| `fakeaiplayer:bot_ai_credential_status` | S2C | 仅回传有界状态码、是否已连接及是否删除本地项；不回传 Key 或原始提供商错误。 |
| `fakeaiplayer:building_preview_begin/chunk/commit/clear` | S2C（Fabric + NeoForge） | 有界 palette、每 tick 有预算的分块传输、Commit 原子发布和清理服务端建筑投影。 |
| `fakeaiplayer:building_preview_ready/confirm/cancel` | C2S（Fabric + NeoForge） | 客户端完整接收回执，以及玩家显式确认/取消；确认不上传方块表。 |

面板打开时订阅目标，关闭时取消订阅。服务端每 10 tick 最多向有效订阅者推一次展示快照；快照中的物品数据不再具有写入权限。客户端没有相应 channel 时，聊天/快捷动作的一部分可以回退到普通聊天或 `/fakeaiplayer` 命令；背包按钮使用打开 Menu 的请求，后续物品事务走原版容器同步，设置和传送仍使用各自的自定义 payload。凭据 UI 与自动恢复没有纯服务端回退，必须在客户端安装同协议版模组。NeoForge 网络协议版本已提升为 `4`。

当前协议在解码和执行两层做边界检查：Bot 名称、动作和设置键最长 32 字符，命令参数最长 1024，聊天文本最长 8192，API Key 最长 512，凭据状态码最长 64；Goal 步骤和能力列表各最多 64 项，背包最多 64 项，装备最多 6 项。列表长度在分配内存前检查，物品 ID 必须是合法 `ResourceLocation`，方向/动作/设置必须在白名单内，槽位、数量和传送模式也会在服务端二次验证。动态 S2C 文本会按同一协议边界截断，避免异常模型输出把客户端踢下线。

这些限制解决的是单包解析与越界问题，不等同于请求频率控制。按玩家的 LLM 请求速率、并发和费用配额仍是公开服务器部署前需要补齐的保护。

任何二次开发新增 payload 时都应同时完成：长度和数量上限、枚举/槽位验证、主线程执行、owner/OP 授权、能力检查、速率限制、加载器两端注册，以及断线清理。

## 源码架构与每个区域

```text
FakeAiPlayer/
├── common/                 # 与加载器无关的共享玩法、网络数据和客户端 UI
│   └── src/
│       ├── main/java/io/github/greytaiwolf/fakeaiplayer/
│       ├── main/resources/
│       ├── gametest/       # 双加载器共用的世界场景与 fixture
│       └── test/           # JUnit
├── fabric/                 # Fabric 入口、事件、网络和运行配置
│   └── src/gametest/       # Fabric GameTest 注册与历史 Fabric-only 场景
├── neoforge/               # NeoForge 入口、事件、网络和运行配置
│   └── src/gametest/       # NeoForge 共享场景注册层
├── buildSrc/               # 多加载器 Gradle 约定插件
├── docs/                   # 运行模式、测试证据、能力矩阵等工程文档
├── scripts/                # 验证、证据、可靠性和持久化脚本
├── reports/                # 基线索引与报告元数据
├── promo/                  # 已重命名的 HTML 模板与无字背景；旧品牌渲染 PNG 已移除，发布前需重新生成
├── build.gradle
├── settings.gradle
└── gradle.properties       # 项目身份与依赖版本的单一入口
```

### `common` 包级职责

| 包 | 职责 | 修改时重点 |
|---|---|---|
| 根包 | `FakeAiPlayer` 共享启动、Tick、命令桥；`AIBotConfig` 配置。 | 不引入具体加载器 API；保持启动幂等。 |
| `action` | 低层移动、挖掘、放置、交互、背包、装备、耕种等动作。 | 所有世界修改必须在服务端线程并有可达/权限前置条件。 |
| `auth` | owner/OP/控制台/同 owner Bot 的纯策略与 Minecraft 适配门。 | 新入口必须选择 VIEW/COMMAND/INVENTORY/TELEPORT/ADMIN 之一。 |
| `brain` | 对话历史、异步 API、工具 schema、工具分发、决策 lease。 | 不在 server tick 同步等待 HTTP；旧响应必须可失效。 |
| `client` | 客户端状态、按键、命令桥、网络处理和面板。 | 客户端状态不可信，权限必须在服务端复核。 |
| `command` | `/fakeaiplayer` 生产命令树。 | 文档、权限 channel 和 Brigadier 参数同步更新。 |
| `coordination` | Job、TaskBoard、空闲 Bot 协调。 | 处理 owner/global scope 与重启后的 stale lease。 |
| `craft` | 配方索引、采集提示、合成/冶炼依赖。 | 兼容动态配方时不能假设只有原版静态表。 |
| `entity` | `AIPlayerEntity` 及 Bot 自身 Tick。 | 假连接、死亡、维度和玩家列表行为是双加载器高风险点。 |
| `goal` | 类型化目标、规划器、执行器、后置条件和结构验收。 | “Task 完成”不能直接等同于“Goal 完成”。 |
| `log` | 结构化日志、异步 writer、分类与诊断。 | 不记录 API Key；安全拒绝不能静默丢失。 |
| `manager` | Bot 生成、索引、角色、owner、恢复和移除。 | 名称/UUID 唯一性、一 owner 一 Bot、服务停止清理。 |
| `memory` | 键值事实、地点、长期目标、知识与 episode。 | 记忆会进入持久化及模型上下文，注意隐私和大小。 |
| `mining` | 矿石识别、矿层/矿脉扫描和工具等级。 | strict 模式不得偷偷读取不可见矿石。 |
| `mixin` | 假玩家连接、玩家列表和交易所需的窄 Mixin。 | 目标签名受 MC/loader 映射影响，必须分别启动验证。 |
| `mode` | strict/operator 配置、能力决策和可观察世界边界。 | 未知/缺失值按兼容规则或 fail closed 处理。 |
| `network` | 共享 payload、服务端订阅与传输接口。 | 长度/数量边界、channel 协商、授权、主线程和速率。 |
| `observe` | TPS、性能统计和最近决策回放。 | 诊断不能反过来拖慢主线程或泄露敏感内容。 |
| `pathfinding` | A*、邻居枚举、成本、危险、站立性与路径执行。 | 落差、液体、门、方块碰撞和缓存失效。 |
| `perception` | 构建给模型的有限世界快照；`perception.focus` 提供并行准星状态机、射线解析、短期历史和语义 DTO。 | 限制扫描量、token、隐私与 strict 可见性；禁止原始 NBT 和跨线程世界对象。 |
| `persist` | schema、原子文件、Bot/任务/Job 恢复。 | 先备份；旧 schema、损坏文件、降级都不能覆盖原数据。 |
| `platform` | `PlatformEnvironment` / `PlatformServices` 抽象。 | 只暴露共享核心真正需要的平台能力。 |
| `runtime` | 意图替换、暂停/恢复、执行栈与生命周期事务。 | 保证取消、替换和恢复的 exactly-once 语义。 |
| `task` | 跨 Tick 的确定性任务状态机、生存保护和卡住恢复。 | 每个阶段要有终止条件、取消处理、进度与清理。 |
| `util` | 离线 GameProfile 等小型工具。 | 避免把加载器特定实现重新带回 common。 |

### 加载器适配层

Fabric 与 NeoForge 都需要提供四类接线：

1. `PlatformEnvironment`：游戏目录、配置目录、模组版本和加载器名；
2. `ServerNetworkTransport` / `ClientNetworkTransport`：payload channel 探测和发送；
3. 服务端事件：启动、停止、Tick、命令、聊天、断线；
4. 客户端事件：payload 接收、按键注册、client tick 和系统聊天捕获。

新增第三个加载器时，应先实现这些接口，而不是复制 `common` 业务代码。

### Mixin

两个加载器共享同一组 Mixin Java 类，但各自携带独立的 `fakeaiplayer.mixins.json`。Fabric 配置显式引用 Loom 生成的 `fakeaiplayer.refmap.json`，以便正式 intermediary 产物把 Mojang 映射下的字段/方法名转换正确；NeoForge 配置不引用 Fabric refmap，继续使用其 Mojang/Parchment 运行时重映射链。它们只覆盖无法通过公开 API 完成的窄边界，例如为假玩家连接安装访问器、拦截玩家列表连接建立，以及调用村民交易奖励方法。Mixin 是最容易随 Minecraft 小版本、映射和加载器内部实现变化的区域之一，升级版本时必须逐个验证目标描述符和运行行为。

## 构建、开发与测试

### 开发环境

- JDK 21；
- 使用仓库自带 Gradle Wrapper；
- 首次构建需要访问 Mojang、Fabric、NeoForge、Parchment、Sponge 和 Maven Central 依赖仓库；
- 不要用 IDE 自带的错误 Java 版本运行 Gradle。

检查 Java：

```bash
java -version
./gradlew --version
```

### 常用 Gradle 任务

```bash
# 编译共享核心
./gradlew :common:compileJava

# 分别编译加载器
./gradlew :fabric:compileJava
./gradlew :neoforge:compileJava

# 构建两个发行 JAR
./gradlew :fabric:build :neoforge:build

# 运行共享单元测试
./gradlew :common:test

# 启动隔离的 Fabric 无头 GameTest（当前源码注册 49 项：历史 24 + P0/P1 11 + P2 14）
./gradlew :fabric:runGameTest

# 启动隔离的 NeoForge GameTest server（当前共享 25 项：P0/P1 11 + P2 14）
./gradlew :neoforge:runGameTestServer

# 开发启动
./gradlew :fabric:runClient
./gradlew :fabric:runServer
./gradlew :neoforge:runClient
./gradlew :neoforge:runServer
```

### 本次移植验证记录

以下结果来自不同阶段，不能把历史基线自动合并为当前证明。项目当前目标仍是 Minecraft 1.21.3、Java 21、Fabric Loader 0.18.4 / Fabric API 0.114.1+1.21.3 和 NeoForge 21.3.96。P0/P1 的结论绑定提交 `9155c9c` 与 CI #106；P2 的结论绑定代码提交 `2450e13` 与 CI #129。两者均未显式 pin 为发布 baseline：

| 验证 | 结果 |
|---|---|
| 原上游 Fabric 基线 | 完整 `build` 通过；68 个 JUnit + 3 个 GameTest 全部通过。 |
| 此前多加载器 clean-build 基线 | `clean :common:test :fabric:build :neoforge:build` 成功；不是当前建筑提交的结果。 |
| 当前建筑工作树本地 Java 21 诊断 | Temurin 21.0.11 下完成 Java 21 源码语法解析、纯生成器编译与 12,800 组公开尺寸/风格/seed 方案烟雾（最大 1,459 格），并通过静态检查；这些不是 Gradle/JUnit/GameTest 通过证明。 |
| P0/P1 Java 21 CI | 提交 [`9155c9c`](https://github.com/GreyTaiWolf/FakeAiPlayer/commit/9155c9ca18cb1c8021bf50d453bbafe84f2c1489) 的 [CI #106](https://github.com/GreyTaiWolf/FakeAiPlayer/actions/runs/29862817627) 已通过 Java 21、全部 JUnit、Fabric 35/35、NeoForge 11/11、Fabric/NeoForge 生产构建与产物检查、两进程持久化和 strict-survival runtime/evidence；该 run 尚未写入显式 pinned baseline。 |
| 当前共享单元测试 | 源码清单为 79 个测试类、312 个 `@Test`；P2 新增正式导航契约、单 frontier 多目标 A*、预算、分段与动态失效回归，已在代码提交 `2450e13` 的 Java 21 CI #129 通过。 |
| 当前 P2 编译与生产构建 | CI #129 已通过 Java 21 类型编译、完整 JUnit、Fabric/NeoForge 生产构建与产物隔离检查。 |
| 历史 Fabric GameTest 基线 | 无头服务器运行 24/24 通过并正常停止；覆盖闲置控制、寻路运行时复验、安全接管和原版式背包事务。 |
| 共享 P0/P1 GameTest | 共享测试体精确为 11 个场景；CI #106 已在 Fabric 35/35 总集和 NeoForge 11/11 共享集中通过。 |
| 当前共享 P2 GameTest | 增量精确为 14 个场景；CI #129 已通过 Fabric 49/49 与 NeoForge 25/25。两端使用相同共享测试体、batch 和阈值。 |
| 此前 Fabric GameTest 基线 | 无头服务器正常启动，9/9 通过并正常停止。 |
| 此前 NeoForge 专用服基线 | 正常识别 `FakeAiPlayer 0.1.0-alpha.1`，Mixin/配置/配方索引成功。 |
| 此前 NeoForge Bot 生命周期基线 | 控制台实际完成 `spawn PortBot`、`list`、`despawn PortBot`；假连接登录/离开、持久化与 `stop` 清理正常。 |
| 此前发布 JAR 检查 | 两个二进制 JAR 均含正确 loader 元数据、加载器专属 Mixin 配置、语言资源和许可证；Fabric 另含并引用 refmap；GameTest 类未进入发布包。 |

尚未在本记录中宣称通过：两个加载器的真实客户端面板、客户端到服务端全部 payload 交互、真实 API 提供商调用、村民交易完整流程、重启中途任务恢复、复杂地形长时间稳定性。

### 发布前最低验证矩阵

| 检查 | Fabric | NeoForge |
|---|---:|---:|
| clean compile / build | 必须 | 必须 |
| 专用服务端无客户端类崩溃 | 必须 | 必须 |
| 客户端进入服务器 | 必须 | 必须 |
| `/fakeaiplayer spawn/list/despawn` | 必须 | 必须 |
| `@Bot` 与 `brain say` | 必须 | 必须 |
| 面板打开、订阅、关闭 | 必须 | 必须 |
| 快捷任务、暂停/恢复/停止 | 必须 | 必须 |
| 背包双向移动 | 必须 | 必须 |
| strict 能力拒绝 | 必须 | 必须 |
| operator 单项能力开关 | 必须 | 必须 |
| 保存、完整停止、重启恢复 | 必须 | 必须 |
| 假连接发包/断线不泄漏 | 必须 | 必须 |
| 村民交易与 Mixin | 必须 | 必须 |
| 30 分钟以上 Tick/任务稳定性 | 建议 | 必须 |

### 测试与证据说明

`common/src/test` 保存纯 Java/JUnit 边界测试，包括授权、配置/模式、Goal 结果、任务取消、决策 session、执行栈、原子持久化和导航契约回归。当前源码包含 79 个测试类、312 个 `@Test`。历史 Fabric-only 世界回归有 24 项；`common/src/gametest` 保存 11 个共享 P0/P1 场景和 14 个共享 P2 场景，因此当前 Fabric 源码注册总数是 49，NeoForge 是 25。

当前分支已经接好 Fabric 的 `:fabric:runGameTest` 与 NeoForge 的 `:neoforge:runGameTestServer`；CI 和静态门禁要求执行两端任务，并在失败时保留两端服务端日志与 crash report。P0/P1 的 11 个共享场景已由 CI #106 在两端通过；P2 的 14 个共享场景已由代码提交 `2450e13` 的 CI #129 在两端通过。NeoForge 1.21.3 的结果以 Gradle 退出码和完整服务端日志为准，不假定它生成 Fabric 风格的 JUnit XML。剩余 24 个 Fabric-only 世界场景和交互式 evidence harness 尚无完整 NeoForge 等价实现。

严谨的能力结论应带有：精确提交、干净工作树、Java/loader/MC 版本、世界种子、配置、完整日志和可重复步骤。旧 TSV、截图或在脏工作树上通过的运行不能自动证明当前 NeoForge 移植版本。

## 从上游 aibot 数据迁移

上游项目使用 `aibot` 模组 ID、`io.github.zoyluo.aibot` 包、`/aibot` 命令和旧数据目录；FakeAiPlayer 使用新的命名空间。当前代码不会跨目录自动搜索 `<world>/aibot` 或 `config/aibot.json`，因此迁移必须由管理员在离线状态下完成。

### 推荐步骤

1. 完整停止服务器，不要热迁移。
2. 备份服务端、世界目录、`config/`、旧 `logs/aibot/` 和 `blueprints/`。
3. 从 `mods/` 移除上游 `mc_aiplayer` JAR，避免两个模组同时注册假玩家、命令和数据。
4. 安装正确加载器的 FakeAiPlayer JAR。
5. 将旧 `config/aibot.json` 复制为 `config/fakeaiplayer.json`，保留原文件作为备份。
6. 在新配置顶层显式加入 `"profile": "strict_survival"` 或 `"profile": "operator"`。缺失 `profile` 的已有文件会为兼容解析成 operator，可能不是你想要的安全级别。
7. 将 `<world>/aibot/` 复制为 `<world>/fakeaiplayer/`。不要只移动而不保留备份。
8. 如果新目录中只有 `bots.json` 和 `jobs.json`，首次启动会在该新目录内尝试旧格式迁移并写 `runtime.json`；成功后会保留 `.migrated.bak` 备份。
9. P1 的整树采伐使用 `<world>/fakeaiplayer/player_placed_logs.json` 保存玩家放置原木的正证据。缺少该账本时一律关闭自动整树，并拒绝本项目能够观察到的玩家/Bot 原木放置，避免世界已改变但来源证据丢失；普通非原木放置不受影响。P1 的证据只增不减：拆除已记录原木不会立刻删除坐标，代价是未来同坐标的新天然树可能被保守跳过，但不会因区块与账本保存顺序不同而误砍结构。管理员审计并备份世界后，可执行 `/fakeaiplayer persist trust-empty-log-baseline confirm`，或离线安装 schema-valid 基线再重启。选择空基线等于明确接受此前未知结构的风险。损坏账本或 `player_placed_logs.active` 未干净会话标记必须先备份并离线核对，命令不会覆盖；该标记只会在加载器的终止事件中完成最终账本写盘后删除，持续写盘失败或未抵达该边界的崩溃会让下次启动保持 fail-closed。加载器终止事件本身不被当作区块保存成功证明；单调正证据保证保存顺序异常最多产生保守的旧坐标。
10. `blueprints/` 路径仍在游戏工作目录根部，通常不需要改名，但应检查自定义 JSON。
11. 旧 `logs/aibot/` 不需要导入运行时；可作为历史归档。新日志默认写入 `logs/fakeaiplayer/`。
12. 把自动化、权限插件、命令方块和文档中的 `/aibot` 改为 `/fakeaiplayer`。
13. 在副本世界启动，检查 `logs/latest.log`、`logs/fakeaiplayer/all.log` 和 `<world>/fakeaiplayer/runtime.json`。
14. 依次验证 `list`、Bot owner、背包、位置、角色、记忆、当前 Goal 和 Job，再开放玩家使用。
15. 确认正常后执行 `/fakeaiplayer persist save`，但仍保留迁移前备份至少一个发布周期。

### 不能直接兼容的标识

- 模组 ID：`aibot` → `fakeaiplayer`；
- 命令：`/aibot` → `/fakeaiplayer`；
- payload：`aibot:*` → `fakeaiplayer:*`；
- 资源：`assets/aibot` → `assets/fakeaiplayer`；
- 配置：`aibot.json` → `fakeaiplayer.json`；
- 世界数据目录：`<world>/aibot` → `<world>/fakeaiplayer`；
- 日志默认目录：`logs/aibot` → `logs/fakeaiplayer`；
- Java 包：`io.github.zoyluo.aibot` → `io.github.greytaiwolf.fakeaiplayer`。

环境变量名目前为了脚本兼容仍是 `DEEPSEEK_API_KEY` 和 `AIBOT_PROFILE`，尚未改成 `FAKEAIPLAYER_*`。

## 已知限制

- 当前是 `0.1.0-alpha.1` 移植开发版，尚未声明生产可用。
- Minecraft 目标精确为 1.21.3；不要假设能直接运行于 1.21.1、1.21.4 或更新版本。
- NeoForge 接入是新代码；假玩家连接、payload、Mixin、客户端事件和服务停止路径需要比 Fabric 更充分的验证。
- 每 Bot Key 已有客户端遮罩 UI 与会话绑定，但它是客户端明文 JSON，不是加密钥匙链；也没有玩家配额、计费归属、provider 隔离或服务端密文保管。端点与模型名仍由服务器全局配置。
- 凭据协议、遮罩输入、原子客户端存储和两加载器注册有单元/静态门禁，但仍需 Fabric 与 NeoForge 真实客户端完成 setup、断线重连、无效 Key 替换和 disconnect 的端到端验收。
- 兼容端点必须支持 OpenAI 风格的非流式工具调用；只支持文本聊天的模型不能完整驱动工具链。
- 模型可能误解目标、产生无效参数或消耗预算；确定性边界能降低风险，但不能让规划永远正确。
- 长距离导航、复杂洞穴、液体、门、梯子、跨维度、未加载区块和模组方块仍可能导致失败或卡住。
- 配方和物品能力以当前实现认识的原版/运行时配方为主，复杂模组 GUI 与自定义机器没有通用适配。
- 严格生存模式会把一些原本可用传送恢复的场景变成明确失败，这是预期的公平性取舍。
- 大范围感知、原始列表、过多历史和开放低层工具会增加 token、延迟和模型费用。
- 语义注视识别的是服务器可确定的方块/实体状态，不理解像素、材质包、粒子画面、地图图案或实体真实意图；复杂模组机器需要后续专用解析器。
- 默认 JSON 蓝图和参数化小屋不是通用建筑语言；建筑目录目前只编译内置矩形原型，展开上限为 65,536 格。
- 多层生成器已有 2–8 层、永久折返楼梯、隔墙、五套 vanilla 风格、平顶/阶梯人字顶和有界场地调查，但还没有阳台、栏杆、L 形体量、复杂屋顶、通用 NBT 模块池或通用模组 blockstate 材料包。
- 建筑投影的 payload、客户端 renderer、确认/取消按键和断线清理已同时接通 Fabric 与 NeoForge，但两端仍缺真实客户端交互和完整生存施工验收。
- 客户端面板需要安装匹配模组；纯服务端安装仍可使用命令和 `@Bot` 聊天，但没有自定义 UI、背包点击和设置按钮。
- 上游 `aibot` 目录不会自动跨命名空间发现，必须按迁移章节离线复制。
- 提交 `9155c9c` 的 CI #106 已运行 Fabric 35/35 与 NeoForge 11/11，其中包含 11 个双加载器共享 P0/P1 场景；代码提交 `2450e13` 的 CI #129 已进一步运行 Fabric 49/49 与 NeoForge 25/25，其中 P2 增量是两端同源的 14 个场景。剩余 24 个 Fabric-only 世界场景和交互式 harness 尚无完整 NeoForge 等价实现。受控夹具仍不能替代真实自然地形、完整 Bot 生存施工、长时间任务回归或两个加载器的人工客户端交互验收。
- 项目使用 Minecraft 内部行为和少量 Mixin；任何游戏小版本升级都可能破坏目标签名或网络行为。

## 路线图

以下是开发方向，不是发布日期承诺。

### P0：让双加载器移植可验证

- [x] 完成 Fabric 与 NeoForge clean build；
- [x] 完成 Fabric GameTest 服务端和 NeoForge 专用服冒烟；
- [ ] 完成两个加载器的真实客户端、面板和网络交互冒烟；
- [x] 验证假玩家 `Connection`/packet listener 在 NeoForge payload channel 检查下安全工作；
- [x] 验证服务启动、Mixin 应用、Bot 生成/断线与停止清理；
- [ ] 完成村民交易 Mixin 的真实交互回归；
- [x] 检查所有 C2S 字符串、列表、槽位、数量和方向边界；
- [x] 为凭据提交增加服务端“玩家 + Bot”速率限制，并为模型请求增加有界并发/每 Bot 最新待处理槽；
- [ ] 增加可配额、费用归属与管理员可观测的费用保护。

### P1：测试与持续集成

- [x] 重新接通 Fabric GameTest 源集和独立运行任务；
- [x] NeoForge `gameTestServer` 已运行 11 个共享 P0/P1 场景，并在提交 `9155c9c` 的 CI #106 以 11/11 通过；
- [x] 14 个共享 P2 场景已在代码提交 `2450e13` 的 CI #129 由 Fabric/NeoForge 同源运行通过；
- [ ] 为剩余 24 个 Fabric-only 世界场景与交互式 evidence harness 建立完整 NeoForge 等价实现；
- [x] 在 CI 中构建两个 loader，并检查 JAR 完整性、元数据和测试代码泄漏；
- [x] 将旧脚本从单项目路径更新为多加载器输出；
- [ ] 为网络编码/解码、授权、迁移和损坏快照增加回归测试。

### P2：配置和玩家体验

- 增加安全的服务端 provider/profile 管理，而不是在聊天中粘贴密钥；
- [x] 提供不经聊天的每 Bot 客户端 Key 输入、验证、自动恢复、状态测试与撤销流程；
- [ ] 用操作系统钥匙链/加密存储替代客户端明文 JSON，并完成配额、费用归属与审计设计；
- 给配置增加 schema/version、范围校验和热重载边界；
- 完成宣传图与演示素材重制；运行时面板、命令和日志文案已切换为 `FakeAiPlayer`，但历史类名保留以控制移植风险；
- 增加正式版本迁移向导和备份检查。

### P3：玩法可靠性

- 改进长距离寻路、区块边界、液体、门/梯子和跨维度任务；
- 扩展结构验收、材料回收和失败恢复；
- 通过多种子、干净提交的证据包重新认证采矿、食物、建造、战斗和囤货能力；
- 建立兼容其他模组配方、方块标签和交互的扩展接口。
- [x] 加入第一阶段语义注视状态机、紧凑模型上下文和 `inspect_focus` 按需详查；
- [ ] 为常见原版机器及第三方模组方块增加可注册的 Focus inspection provider；
- [ ] 在客户端面板显示当前注视目标，并增加玩家准星转交给个人 Bot 的 `PLAYER_GAZE` 模式。

## 故障排查

### 模组无法加载：Java 版本错误

症状通常包含 class version、toolchain 或 Java 17/Java 8 提示。

```bash
java -version
./gradlew --version
```

两者都应指向 Java 21。服务端启动脚本也必须使用同一个 JDK。

### Fabric 提示缺少依赖

确认 Minecraft 1.21.3、Fabric Loader 和 Fabric API 版本与安装表一致，并确认没有误装 NeoForge JAR 或 `sources.jar`。

### NeoForge 提示不兼容或在初始化崩溃

确认使用目标 NeoForge 21.3.x 和 Minecraft 1.21.3。保留完整 crash report，重点查看 `FakeAiPlayerNeoForge`、payload 注册、Mixin apply 和假玩家连接栈。Alpha 期间不要靠删除 Mixin 配置“绕过”后继续使用，因为那可能让 Bot 以更隐蔽的方式损坏。

### `/fakeaiplayer` 不存在

- 查看模组列表是否有 `fakeaiplayer`；
- 检查你安装的是发行 JAR 而不是 common/sources JAR；
- 检查服务端日志中的模组初始化错误；
- 不要继续输入旧 `/aibot`。

### Bot 生成失败

- 名称是否已被真实玩家或另一个 Bot 使用；
- 当前玩家是否已经拥有一个个人 Bot；
- 生成位置是否安全；
- 查看 `bot_spawned`、`bot_spawn_position_unsafe` 或连接/Mixin 异常。

### `@Bob` 没反应

- 消息必须包含 `@Bob` 加空格和正文；Bot 名由正则 `\w+` 匹配，复杂字符名可能不适用；
- 确认发送者是 owner 或 OP；
- 确认 `DEEPSEEK_API_KEY` 已进入服务端进程，而不是只设置在另一个终端；
- 检查 `logs/fakeaiplayer/all.log` 中 `deepseek_key_missing`、`auth_error`、`rate_limited`、`api_timeout`、`bad_response`；
- 无密钥时先用 `/fakeaiplayer task assign ...` 验证 Bot 核心。

### API 返回 404

核对 `baseUrl`。代码最终调用 `/v1/chat/completions`；如果代理要求其他路径，需要调整代理或代码。不要把完整 `/chat/completions` 再填入 `baseUrl`。

### API 返回 401/403

检查密钥、服务商账户和模型权限。不要把密钥粘贴到 Issue。轮换可能泄露的密钥后再继续。

### API 返回 429 或费用过高

当前会有限重试 429。降低使用频率、`maxTokens`、历史长度、感知原始列表和开放工具数量，并在服务商侧设置额度。公开服务器在没有速率限制补强前不应开放无限自然语言调用。

### 面板打不开或一直等待数据

- 客户端和服务端是否都安装同 loader、同版本 FakeAiPlayer；
- Fabric 客户端是否有 Fabric API；
- 尝试 `Alt+0` / `Alt+9`，也检查 Minecraft 控制设置中的按键；
- 确认玩家拥有目标 Bot 或是 OP；
- 用命令检查 `/fakeaiplayer list` 和 `/fakeaiplayer task status <name>`；
- 查看网络 channel/payload 注册错误。

### 传送按钮不可用

这是默认行为。需要同时满足：

1. `profile` 为 `operator`；
2. `operatorCapabilities.manualTeleport` 为 `true`；
3. 当前玩家对该 Bot 有 TELEPORT 权限。

严格生存模式下按钮必须保持禁用，服务端也会拒绝伪造 payload。

### 重启后 Bot 没恢复

- 检查 `<world>/fakeaiplayer/runtime.json` 是否存在；
- 检查 schema unsupported、malformed、dimension missing 和 inventory restore 日志；
- 若文件损坏，先复制备份再修复/移走，切勿让空状态覆盖唯一副本；
- 若从上游迁移，确认数据已经复制到新的 `fakeaiplayer` 目录，而不是仍在 `<world>/aibot`。

### 持久化 reload 被拒绝

这是保护机制。先停止并移除 Bot 的活动工作、despawn 所有 Bot，并清空 Job，再执行 reload。生产服更推荐完整停止后恢复备份。

### Bot 卡住或任务失败

- 查看 `/fakeaiplayer task status <name>`、`profile <name>`、`replay <name>`；
- 检查材料、工具耐久、背包空间、目标区块是否加载、路径是否被门/液体/落差阻挡；
- 先 `pause`，必要时 `abort` 或 `brain reset`；
- strict 模式下确认失败是否来自被正确拒绝的特权恢复，而不是程序异常；
- 提交复现时包含版本、loader、种子、坐标、配置（去除密钥）、任务命令和脱敏日志。

## 许可证与上游归属

本仓库基于 zoyluo 的开源项目 [`zoyluoblue/mc_aiplayer`](https://github.com/zoyluoblue/mc_aiplayer) 进行移植和修改，导入基线提交为 `8f0621ade1059c62fb866a01a39a418aa98895a2`。上游以 MIT License 发布，原始版权声明必须保留。

- 根许可证：[LICENSE](LICENSE)
- 第三方与上游声明：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 当前项目仓库：<https://github.com/GreyTaiWolf/FakeAiPlayer>
- 上游项目仓库：<https://github.com/zoyluoblue/mc_aiplayer>

FakeAiPlayer 与 Mojang Studios、Microsoft、Fabric、NeoForge、DeepSeek 或其他模型提供商没有官方隶属或背书关系。Minecraft 名称、资源和商标归其各自权利人所有。

贡献代码前，请确保你有权以仓库许可证提交，并保留上游 MIT 版权和许可文本。分发修改版时，应一并分发 `LICENSE` 与 `THIRD_PARTY_NOTICES.md`。
