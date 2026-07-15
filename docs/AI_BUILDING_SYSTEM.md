# AI 建筑系统设计基线

本文定义 FakeAiPlayer 的建筑长期架构和分阶段验收边界。它不是“让模型输出一串方块坐标”，而是把自然语言设计、确定性规划、客户端投影和生存模式施工分开。

## 当前实现状态

当前源码已经进入 Fabric/NeoForge 双加载器的 P1/P2 实现切片，但仍是 `UNVERIFIED` 开发状态：

- `IN SOURCE`：V2 不可变 plan、结构/registry 两阶段校验、确定性指纹、vanilla 坐标与 BlockState 变换，以及保留 operation、replace policy、atomic group 和稳定 sequence 的旧执行器适配层。
- `IN SOURCE`：`modular-house-3` 确定性模块住宅生成器，现有 `oak_cottage`、`spruce_lodge` 两套 vanilla 材料风格；方案包含地基、地板、梁柱、墙体、窗户、双格门、门廊、自然地面两级入口台阶、前后永久露台、永久支撑直梯、可步行阁楼、无方向檐口、人字屋顶和屋脊。楼梯、阁楼和露台在高柱前完成；门和西侧门框延后到屋顶完成后，门轴由门框依赖顺序强制；Bot 通过露台、楼梯、阁楼、圈梁和檐口从两侧逐排攀建坡面。施工接近寻路禁止计划外挖墙或垫柱，并用方块轮廓 raycast 预先证明真实 clicked face、方向方块朝向和柱/梁轴线。
- `IN SOURCE / FABRIC + NEOFORGE`：服务端 `PreviewSession`、Begin/Chunk/Commit/Clear 分块协议、客户端 staging 后原子发布、Ready 回执、彩色世界线框、移动/90°旋转、显式确认/取消和断线/超时清理。
- `IN SOURCE`：命令入口和 AI 工具 `draft_building`、`building_preview_status`、`cancel_building_preview`。AI 只能起草、查询和取消；确认施工仍必须由已授权的在线玩家完成。
- `IN SOURCE`：旧 AI `build_house`、`assign_task build` 和 `post_job build` 均失败关闭并返回“使用 `draft_building`”；空闲协调器也不再执行持久化的旧 build Job。人工直接输入的确定性建造命令仍属于另一条显式授权入口，不等于给模型确认权。
- `IN SOURCE`：V2 placement 依赖会在 adapter 中转换为稳定的前置 sequence。Loader 拒绝缺失、非先行或无 sequence 的依赖；`BuildTask` 在依赖格改变世界前重新精确比较每个前置格的 BlockState，reviewed V2 格一旦失败便失败关闭，而不是跳过支撑继续施工。
- `PARTIAL`：确认会重新检查 owner、Bot、维度、距离、hash、transform revision、边界、区块、BlockState、替换策略、流体、方块实体和原子组；位于局部 `dy=0` 的 `FOUNDATION/PLACE` 格还要求下方可检查、无流体且上表面稳固，并在执行落块前再次检查，然后才把确定方案交给既有备料/建造链。材料规划用保留账本防止门、楼梯、工作台、熔炉和工具前置吞掉最终地板/梁材，沙子按缺口增量采集，literal 圆石与宽松 stone-like 工具链也已拆开。此路径已在 Temurin Java 21.0.11 下完成源码语法解析、纯生成器编译/12,800 方案烟雾与静态门禁；本地完整 Gradle 曾因当前环境 JVM 无法访问外部 Maven、在 `fabric-loom` 解析时于项目编译前中止，但随后同步提交 `dfb0ab8` 的 GitHub Actions 全矩阵通过 `:common:test`、10 项 Fabric GameTest、Fabric/NeoForge 生产构建、产物检查、两 JVM 持久化与 strict-survival runtime/evidence。确认后的自动备料目前仍没有 mission-scoped 场地保护和分阶段工具/背包物流：采集可能选中投影支撑或已完成构件，大批原木/圆石还缺少可靠的工具耐久预算。因此 Fabric/NeoForge 真实客户端、真实生存材料链和复杂地形 GameTest 均尚未完成，不能称为“建筑已可靠完成”，该能力仍须保持 `UNVERIFIED`，不能据此宣称已达到发布质量。
- `PARTIAL`：普通原版 `BlockItem` 会用 `BlockPlaceContext` 做保守预测；草、花、雪层走真实的“点击可替换目标格”交互，门下半格使用双格放置语义。带流体目标会在确认期拒绝，reviewed plan 不使用 `setBlock` 修正或兜底。当前公开住宅以永久阁楼通路避免临时脚手架；通用高层/复杂屋顶脚手架、错误状态事务回滚和模组方块适配仍未完成。
- `TODO`：双加载器客户端实机验证、VBO/分区缓存、场地调查、A/B/C 方案、类型化局部修改、楼层/阶段过滤、完整 BOM UI、完整二层房间/阳台/栏杆，以及真实 GameTest 和双加载器发布证据。

因此，下文同时包含“当前实际协议”和长期设计。只有标为 `IN SOURCE` 的部分已经接线；它也只表示源码存在，不表示已在游戏中验收。

## 目标与非目标

目标：

- 玩家用自然语言描述用途、尺寸、风格、房间、朝向、预算和必须/可选/禁止特征。
- AI 将描述转成受约束的 `BuildingBrief`，不直接生成数千格最终坐标。
- 确定性规划器根据场地、风格包和种子生成可复现的 `BuildingPlan`。
- 玩家在任何世界改动前看到完整投影、材料清单、挖填方和冲突。
- Bot 在 `strict_survival` 中使用真实物品、真实点击、真实寻路和真实材料消耗施工。
- 最终验收比较计划要求的 BlockState，并能报告、修补和恢复。

第一阶段的非目标：

- 任意形状、任意模组方块都能自动施工。
- LLM 直接逐格控制施工。
- 未经确认覆盖容器、机器、玩家建筑或领地。
- 复制不明来源的优秀建筑资源、NBT 或模组实现。

## 权威数据流

```text
玩家描述 / AI API
        ↓
BuildingBrief（类型化意图；required/preferred/forbidden）
        ↓
SiteSurvey（高度、水、障碍、入口、保护格、观察证据）
        ↓
SemanticPlan（房间、柱网、开间、楼梯、屋顶、附件）
        ↓
PlanCompiler（材质角色、BlockState、原子组、依赖、替换策略）
        ↓
BuildingPlan + planHash
        ↓
独立分块网络协议 → 客户端投影 → 修改/确认
        ↓
材料计划 → ConstructionTask → 精确验收/修补 → 清理临时脚手架
```

相同的规范化 Brief、SiteSurvey、Style Pack 版本、生成器版本和 seed 必须得到相同 plan hash。确认后禁止施工过程自行随机改变设计；修改产生新 revision 和差异清单。

## AI 边界

AI 可以决定：

- `buildingUse`、风格、尺寸范围、楼层数和预算；
- 哪些需求必须实现、尽量实现或禁止出现；
- 从规划器返回的 A/B/C 合法方案中选择并解释取舍；
- 将“窗户多一点”“阳台朝河”“屋顶更陡”转换成类型化 patch。

AI 不可以决定：

- 未经校验的最终方块坐标或任意 BlockState 文本；
- 绕过场地、保护格、材料、可达性或权限检查；
- 施工中临时修改已确认 plan；
- 在服务端主线程之外读取或修改世界。

长期工具边界：

```text
survey_build_site     只读场地调查
draft_building        生成候选和材料/冲突摘要，不改世界
revise_building       对指定 revision 应用类型化 patch
preview_building      向授权玩家发布/刷新投影
confirm_building      锁定 plan hash，进入备料与施工
get_build_status      查询阶段、缺料、冲突、进度
cancel_build          停工并只清理 Bot 自己记录的临时结构
```

当前切片只开放其中的安全子集：

- `draft_building(style, width, depth, wall_height, seed)`：生成确定方案并发布给在线 owner，不修改世界；
- `building_preview_status()`：只读查询当前投影；
- `cancel_building_preview()`：取消当前投影；
- 不向模型注册确认工具。模型调用 `draft_building` 后必须停止并等待玩家检查。

AI 等待状态绑定到具体 `sessionId`，而不是只记录一个 Bot 布尔值。命令投影不会唤醒旧 AI 对话；替换、取消、拒绝、断线或超时只解除与该精确会话相同的等待令牌。玩家的新消息会显式结束旧会话关联，但不会暗中确认或删除仍可见的投影。

旧 `build_house` 虽保留工具名以兼容旧 prompt/调用，但已经不再启动任务，只返回必须使用 `draft_building` 的失败结果。`assign_task` 与 `post_job` 同样在任何 Bot/Job 副作用前拒绝 `build`；重启读到的旧 build Job 也不会被空闲 Bot 认领执行。

### 当前建筑命令

```text
/fakeaiplayer building draft <bot> <oak_cottage|spruce_lodge>
/fakeaiplayer building draft <bot> <style> <width> <depth> <wall_height> [seed]
/fakeaiplayer building move <x> <y> <z>
/fakeaiplayer building rotate <north|east|south|west>
/fakeaiplayer building status
/fakeaiplayer building confirm
/fakeaiplayer building cancel
```

命令与 AI 工具当前把宽/深限制在 `7..16`，墙高限制在 `4..5`；公开尺寸均带永久直梯和阁楼屋顶通路。更大的 generator API 范围只是设计能力：仅当 `wall_height < depth`、能给直梯保留两端 landing 时加入该通路，否则 metadata 标记 `roof_access=design_only`，不会由当前命令承诺施工。`move` 接受的是投影锚点的绝对世界坐标。每位玩家同时只保留一个投影，新 draft 会替换旧 draft；会话默认五分钟过期，确认者必须与投影同维度且距锚点不超过 128 格。确认/取消按键会注册到控制设置但默认不绑定，玩家需要自行绑定。当前完整网络注册和世界渲染已在 Fabric 与 NeoForge 两端接线；两端客户端仍需分别完成真实联机与渲染验收。

## BuildingPlan V2

第一批基础类型位于 `building.plan`：

- `BlockStateSpec`：方块 ID 与规范化属性映射；
- `PlanPlacement`：局部坐标、操作、材料角色、阶段、依赖、构件和原子组；
- `BuildingPlan`：版本化、不可变、可生成稳定 SHA-256；
- `PlanTransform`：使用 vanilla `StructureTemplate.transform` 统一坐标变换；
- `BlockStateResolver`：使用方块 `StateDefinition` 解析、序列化、镜像和旋转；
- `BuildingPlanValidator`：在预览、备料和世界修改前拒绝越界、冲突和依赖环。

材料使用语义角色，而不是每格独立随机：

```text
foundation / frame / wall / floor / roof / roof_trim
window / door / stairs / railing / lighting / accent
```

选定云杉家族后，木板、原木、楼梯、台阶、门、活板门和栅栏应由一次 palette resolution 共同决定。规划器保存选择结果，恢复时不得重新抽取。

每个计划格还必须区分：

- `PLACE`：最终结构；
- `CLEAR`：确认方案明确允许清除；
- `PRESERVE`：验证证据，不得修改；
- `TEMPORARY`：Bot 自己的脚手架，必须可追踪和清理。

替换策略从 `REQUIRE_EMPTY` 到 operator-only `FORCE_AUTHORIZED` 分级。容器、未知方块实体、领地与其他保护系统在默认情况下必须失败关闭。

## BlockState 与真实放置

蓝图不能只保存 block ID。至少需要正确表达：

- 楼梯：`facing/half/shape/waterlogged`；
- 台阶：`type/waterlogged`；
- 原木与柱：`axis`；
- 门：`facing/half/hinge/open`；
- 活板门：`facing/half/open`；
- 栅栏、墙、玻璃板等邻接状态；
- 床、门、高花等多格原子结构。

变换顺序遵循 vanilla StructureTemplate：坐标使用
`StructureTemplate.transform(relativePos, mirror, rotation, pivot)`，状态使用
`state.mirror(mirror).rotate(rotation)`。不得手写仅处理 `facing` 的 switch；楼梯左右角、门铰链和原木轴向也会随变换改变。

`strict_survival` 必须保留 `gameMode.useItemOn`：

1. 规划点击支撑面、命中位置、站位和玩家朝向；
2. 使用 `BlockPlaceContext` 预测候选状态；
3. 保留真实射线、距离、可观察性和碰撞检查；
4. 真实交互后等待邻接更新并验证显式属性；
5. 状态错误时进入拆除/重放或报告，不能只因 block ID 相同而成功。

门、床等不是“两次独立放方块”，而是一次物品操作产生多个期望世界变化。它们需要原子预检、物品成本和失败恢复。楼梯 shape、围栏连接等由邻居推导的状态应在相邻构件完成后再收敛验收。

最终设计格与施工动作必须分层：`DesignCell` 描述投影和终态验收；`BuildStep` 描述一次真实点击/交互、物品成本、完整 affected footprint、前置条件和回滚/修复策略。双台阶、蜡烛、海泡菜、雪层等同一格多次操作，以及临时支撑后再清理，都不能靠“一个坐标只对应一个放置动作”表达。当前 `PlanPlacement` 只承担第一层基础语义，并已通过兼容 adapter 接入旧 `BuildTask`；完整专用 ConstructionTask 仍需编译出真正的 `BuildStep` DAG。

operator direct placement 也不能继续无条件使用 `defaultBlockState()`。普通单格状态可使用已解析的目标 state；多格方块必须有专门适配器，并在任何写入前完成整个 footprint 的安全预检。

## 语义构件与审美规则

首批构件库：

```text
FoundationStrip / Pier / RetainingWall
Column / Beam / RingBeam
FloorPanel / StairOpening / StairRun / Landing
SolidBay / WindowBay / DoorBay / Corner
Porch / Balcony / Railing / SupportPost
RoofSlope / GableEnd / Eave / Ridge / Chimney
LightingPoint / Accent
```

构件声明尺寸、占用体积、净空、连接 socket、支撑、允许变换、风格标签、权重、最小/最大数量和依赖。模块随机只发生在经过验证的候选之间。

硬约束示例：

- 外部入口与每个房间/楼层连通；
- 楼梯有两格净空和上下 landing；
- 门窗不能穿柱、梁、楼板或楼梯；
- 阳台必须有室内门、危险边栏杆和足够支撑；
- 屋顶覆盖顶层 footprint，坡面没有非法高度跳变；
- dependency graph 无环，Bot 有施工站位或可生成脚手架；
- 计划不越界、不触碰保护格、不超过材料和方块预算。

软评分示例：

- 体块比例、轮廓和屋顶比例；
- 3–5 格结构开间形成的门窗节奏；
- 梁、窗台、楼板和屋檐对齐；
- 材质家族一致性和有限对比；
- 朝向道路、河流、视野和入口；
- 挖填方、脚手架和材料成本；
- 与近期建筑指纹的差异，避免连续重样。

WFC 仅适合骨架已锁定后的屋瓦、地板和局部装饰图案，不负责房间、楼梯和屋顶全局连通。

## 场地适配

`SiteSurvey` 至少保存高度网格、水体、可替换自然物、阻挡物、保护格、可接近入口、建议朝向、观察证据和调查版本。

首批策略：

- `LEVEL`：少量挖填；
- `STEPPED`：坡地分层基础；
- `STILTS`：水边或陡坡桩柱；
- `TERRACED`：切坡与挡土墙。

不能默认把整个长方体清空。严格生存模式仍服从 `ObservableWorldQuery`：不可见地下信息不得通过规划器绕过运行模式读取。

## 全息投影协议

投影使用独立协议，不加入每 10 tick 重发的 `BotSnapshotS2C`。当前服务端通过命令或 AI draft 在内部打开会话；客户端不能上传方块表，也不能用自选 plan 冒充服务端方案。

服务端持有权威 `PreviewSession`：

```text
sessionId / botId / ownerId
planId / revision / canonical planHash / transmitted preview digest
dimension / anchor / rotation / mirror / transformRevision
validated BuildingPlan / bounded palette + chunks
client acknowledged transform revision / AI-conversation linkage / expiresAt
```

当前 NeoForge payload：

```text
BuildingPreviewBeginS2C
  session/bot/plan identity, preview digest, transform revision,
  dimension, anchor, bounds, placement/chunk counts, BlockState palette

BuildingPreviewChunkS2C
  session + digest + transform revision + chunk index,
  最多 256 个 palette-indexed cells（坐标、operation、policy、phase）

BuildingPreviewCommitS2C
  session + digest + transform revision

BuildingPreviewReadyC2S
  客户端完整组装并校验后的同一 tuple

BuildingPreviewConfirmC2S
  玩家确认时提交同一 tuple；不包含任何方块数据

BuildingPreviewCancelC2S / BuildingPreviewClearS2C
  取消请求 / 服务端清理原因
```

客户端先在 staging buffer 接收同一 tuple 的分块；允许乱序，但拒绝重复 chunk、缺块、数量错误、越界坐标、非法 palette index、过旧 revision 和摘要不匹配。只有 Commit 校验通过才原子替换当前快照；传输中的半份方案不会渲染。新 draft、移动或旋转会形成新 transform revision 并重新发布有界快照；当前尚未实现“只发 transform、不重传 cells”的增量优化。临时进入其他维度时保留已审核快照但停止渲染，返回原维度后恢复显示；断线、取消、拒绝或超时才清理缓存。

第一版设置独立 `MAX_PREVIEW_PLACEMENTS = 4096` 和 payload/palette/属性字符串预算；超过限制的 plan 不会发布。当前 NeoForge renderer 在 `AFTER_PARTICLES` 阶段用 `RenderType.lines`/`ShapeRenderer.renderLineBox` 即时画线框，并做 192 格距离、视锥和维度裁剪。它没有 VBO、16×16×16 分区缓存、真实方块模型、方块实体、实体或动态流体渲染。

投影颜色：

- 青色：计划方块缺失，需要放置；
- 橙色：方块 ID 正确但 BlockState 错误；
- 红色：方块类型错误或确认后需要替换；
- 洋红：计划要求为空但世界存在多余方块；
- 紫色：`PRESERVE` 证据不匹配；
- 浅蓝：临时脚手架；
- 已正确存在：隐藏。

当前生成器不会把临时脚手架交给旧执行器；浅蓝只是协议/渲染语义预留。

当前交互支持绝对锚点移动和四向旋转。`PlanTransform` 能表达镜像，但尚无玩家镜像命令；楼层/阶段过滤、A/B/C 候选、局部 revision、模型面预览和材料/冲突 UI 都是后续工作。

客户端组装状态位于 common；NeoForge 提供 payload 注册、按键与世界渲染适配。Fabric 尚未注册这些 payload 或 renderer。NeoForge renderer 留在 NeoForge client 源集，专用服务器不会加载它。

确认操作携带 `sessionId + digest + transformRevision`，且只有服务端记录了同 revision 的 Ready 回执才接受。服务端再次检查 Bot/owner 权限、玩家/Bot/投影维度、128 格距离、计划 hash、世界边界、建筑高度、区块加载、BlockState、operation/ReplacePolicy、方块实体和多格原子组；第一版拒绝 `FORCE_AUTHORIZED` 和部分已存在的门原子组。旧 tuple 失败但不能删除玩家更新后的新会话，避免延迟包破坏当前方案。

## 持久化与恢复

当前确认路径会把变换后的精确 `BlueprintSchema` 写入受限的 `generated_*` 命名空间，再提交带确定锚点的 `Goal.Build`：

- 材料闭包预检先直接使用同一份内存 blueprint；只有预检成功才写文件，因此不存在“文件尚未生成导致预检必失败”，也不会因预检拒绝留下孤儿文件；
- 写入前执行 expansion、BlockState、operation/policy、sequence 和 4 MiB 文件上限校验；
- 生成文件使用耐久临时文件替换，不跟随 symlink；同名同内容重试幂等，同名不同内容失败，并在写入后通过普通有界 loader 回读及 canonical SHA-256 核验；
- `Goal.Build`/`MissionSpec` 保存 blueprint 名、`anchor_x/y/z`、维度与 `blueprint_digest`。执行器只接受投影确认服务写出的 `generated_*` 名称及完整绑定；升级前的 preset/custom/generated 记录仍可读取以隔离旧存档，但一律不可信执行；
- Goal checkpoint 保存锚点和已观测进度。恢复、执行前重新读取持久化 blueprint 并比较摘要；`BuildTask` 每 tick 在导航或世界变更前比较不可变执行蓝图摘要和目标维度。备料期间跨维度会立即终止整条确认任务，而不会在另一个世界的同坐标继续施工。
- adapter 把每个 `PlanPlacement.dependencies` 映射为最终稳定 sequence 的 `prerequisites`；持久化摘要包含这些依赖，Loader 要求它们引用已出现的更小 sequence，执行器也要求每个前置格在依赖格施工前仍精确符合已审核状态。

这仍不是完整 V2 持久化。投影会话本身不会跨重启恢复；以下长期字段仍需进入独立的 plan/施工记录：

```text
planId / revision / planHash / generatorVersion / stylePackVersion / seed
anchor / dimension / rotation / mirror
currentPhase / completedStepBitmap / failedAttempts
temporaryBlocks / reservedMaterials / externalConflicts
```

完整恢复必须先读取真实世界：正确状态直接完成，缺失状态进入待建队列，错误状态在明确替换许可下才可修补；不得重新随机生成设计。规划期最终材料 reservation 已进入源码；执行期库存锁定、临时脚手架追踪、局部修补 bitmap 和跨维度自动迁移仍未实现。旧 `BlueprintSchema` 通过 `LegacyBlueprintAdapter` 进入 V2 设计边界，新模块 plan 则在确认后编译回现有任务链。

## 分阶段交付门槛

### P0：BlockState 基础

- V2 plan model、依赖校验、稳定指纹；
- 旧 blueprint 增加可选 properties 并保持旧 JSON 兼容；
- 属性解析、vanilla 旋转/镜像和属性验收；
- 横向原木、楼梯、台阶、门的真实放置 GameTest。

### P1：单一高质量住宅切片

- `9×7`、`11×9` 木石住宅；
- 地基、梁柱、开间墙、门窗、人字顶和门廊；
- 材料家族锁定和完整 BOM；
- 所有最终格与显式状态 100% 匹配。

### P2：投影与确认

- 独立分块 payload、客户端 staging cache、彩色投影；
- 移动、旋转、镜像、楼层/阶段过滤；
- 材料、挖填方、冲突和 plan hash 确认。

### P3：两层与 AI Brief

- 完整二层房间、折返楼梯、阳台、栏杆和可追踪通用脚手架；
- `survey/draft/revise/preview/confirm/status/cancel`；
- A/B/C 方案与类型化局部 revision。

### P4：风格包与复杂地形

- 平原、云杉山屋、木骨架三种风格；
- 阶梯/桩柱/挡土墙地基；
- L 形、四坡顶、交叉人字顶和多 Bot 分工。

任何阶段都不能用“源码存在”作为完成标准。至少需要单元测试、属性/种子回归、真实 GameTest、Fabric/NeoForge 构建和专服 smoke；建筑能力只有在真实种子上终态完整验收后才能标为 VERIFIED。

## 参考实现与许可证边界

- MineColonies / Structurize：锚点、风格包、材料请求、阶段施工、旋转镜像和预览思想；
  <https://minecolonies.com/wiki/tutorials/schematics/>
  <https://github.com/ldtteam/Structurize>
- Create Schematicannon：材料清单、库存来源、缺料暂停、覆盖策略和施工状态；
  <https://github.com/Creators-of-Create/Create>
- Building Gadgets 2：复制区域、幽灵预览、位置微调和模板分享；
  <https://github.com/Direwolf20-MC/BuildingGadgets2>
- Litematica：投影交互和差异显示参考；
  <https://github.com/maruohon/litematica>
- Repurposed Structures：受约束的模块池、必需/最大数量和重掷；
  <https://github.com/TelepathicGrunt/RepurposedStructures>
- CityEngine CGA / CGAL straight skeleton：立面 split grammar 与复杂屋顶算法参考；
  <https://doc.arcgis.com/en/cityengine/latest/cga/cga-split.htm>
  <https://doc.cgal.org/latest/Straight_skeleton_2/index.html>
- Parchment 1.21.3 与 NeoForge GameTest：Minecraft API 名称和测试依据；
  <https://github.com/ParchmentMC/Parchment>
  <https://github.com/neoforged/NeoForge/blob/1.21.3/docs/NEOGAMETESTS.md>

这里只借鉴公开机制和交互原则。禁止复制许可证不兼容的代码、模型、蓝图或建筑资产；引入第三方内容前必须单独记录来源、版本、作者、许可证和所需模组。

许可证边界：Create 代码为 MIT、但资源另有保留条款；Building Gadgets 2 为 MIT；Structurize 与 MineColonies 为 GPL-3.0；Litematica 为 LGPL-3.0。FakeAiPlayer 当前为 MIT，因此投影与规划实现应依据 Minecraft/Parchment/loader API 独立编写，不能直接移植 GPL/LGPL 实现或复制 Create 的受限资源。此处是工程合规约束，不构成法律意见。
