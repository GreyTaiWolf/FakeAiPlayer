package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.action.HarvestCore;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.action.MaterialPalette;
import io.github.greytaiwolf.fakeaiplayer.craft.CraftingHelper;
import io.github.greytaiwolf.fakeaiplayer.craft.SmeltChain;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.fakeaiplayer.mining.ToolTier;
import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillOutcome;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillRuntimeGate;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillSpec;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintLoader;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import io.github.greytaiwolf.fakeaiplayer.task.BuildTask;
import io.github.greytaiwolf.fakeaiplayer.task.CraftTask;
import io.github.greytaiwolf.fakeaiplayer.task.CreateObsidianTask;
import io.github.greytaiwolf.fakeaiplayer.task.DescendToYTask;
import io.github.greytaiwolf.fakeaiplayer.task.DigDownTask;
import io.github.greytaiwolf.fakeaiplayer.task.EquipLoadoutTask;
import io.github.greytaiwolf.fakeaiplayer.task.FarmTask;
import io.github.greytaiwolf.fakeaiplayer.task.GatherQuotaTask;
import io.github.greytaiwolf.fakeaiplayer.task.HuntTask;
import io.github.greytaiwolf.fakeaiplayer.task.MilkCowTask;
import io.github.greytaiwolf.fakeaiplayer.task.MoveTask;
import io.github.greytaiwolf.fakeaiplayer.task.OreDigTask;
import io.github.greytaiwolf.fakeaiplayer.task.PlaceStationsTask;
import io.github.greytaiwolf.fakeaiplayer.task.SmeltTask;
import io.github.greytaiwolf.fakeaiplayer.task.StockpileTask;
import io.github.greytaiwolf.fakeaiplayer.task.Task;
import io.github.greytaiwolf.fakeaiplayer.task.TaskState;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Authoritative runtime boundary for {@link LegacyMissionCompiler} Skills.
 *
 * <p>A legacy {@link Task} is only an execution mechanism. Its own {@code COMPLETED} state is not
 * accepted as Mission evidence until this adapter proves that the declarative contract is the
 * compiler-registered contract and re-reads the relevant inventory/world postcondition. Resource
 * discovery remains inside the Task; admission deliberately does not reject a gather, hunt, farm,
 * or mining Skill merely because its first local scan has no target.</p>
 */
public final class LegacySkillVerifier {
    private static final UUID CONTRACT_MISSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int MAX_INVOCATION_INDEX = 4096;
    private static final double MOVE_REACHED_SQUARED = 4.0D;
    private static final Set<Item> HELMETS = Set.of(
            Items.IRON_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET);
    private static final Set<Item> CHESTPLATES = Set.of(
            Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE);
    private static final Set<Item> LEGGINGS = Set.of(
            Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS);
    private static final Set<Item> BOOTS = Set.of(
            Items.IRON_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS);
    private static final Set<Item> SWORDS = Set.of(
            Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);
    private static final List<Item> STATIONS = List.of(
            Items.CRAFTING_TABLE, Items.FURNACE, Items.CHEST);

    private LegacySkillVerifier() {
    }

    /**
     * Fail-closed admission and baseline capture. The returned Session belongs to exactly one bot,
     * dimension, Goal, and compiler invocation.
     */
    public static Preparation prepare(AIPlayerEntity bot,
                                      GoalSpec goalSpec,
                                      Goal goal,
                                      LegacyMissionCompiler.ExecutableSkill executable,
                                      GoalSnapshotCollector.Context context) {
        if (bot == null) {
            return Preparation.rejected(fatal("legacy_skill_bot_missing", Map.of()));
        }
        String dimension = bot.serverLevel().dimension().location().toString();
        ContractCheck contract = validateContract(
                goalSpec, goal, executable, dimension, context);
        if (!contract.valid()) {
            return Preparation.rejected(fatal(contract.reason(), contract.evidence()));
        }

        Session session = new Session(
                bot.getUUID(), goalSpec, goal, executable, context, dimension,
                relevantCount(bot, executable.step()), bot.blockPosition(), bot,
                () -> context, contract);
        SkillRuntimeGate.GateResult gate = SkillRuntimeGate.beforeStart(
                goalSpec, executable.spec(), session);
        if (!gate.allowed()) {
            return Preparation.rejected(gate.rejection());
        }
        return Preparation.accepted(session);
    }

    /** Alias named for the Mission runtime call site. */
    public static Preparation beforeStart(AIPlayerEntity bot,
                                          GoalSpec goalSpec,
                                          Goal goal,
                                          LegacyMissionCompiler.ExecutableSkill executable,
                                          GoalSnapshotCollector.Context context) {
        return prepare(bot, goalSpec, goal, executable, context);
    }

    /**
     * Compatibility factory for GoalExecutor's live-context integration. Contract failures remain
     * fail-closed: the returned evaluator reports the capability as unsupported to the gate.
     */
    static Session open(AIPlayerEntity bot,
                        Goal goal,
                        GoalSpec goalSpec,
                        GoalStep step,
                        SkillSpec spec,
                        Supplier<GoalSnapshotCollector.Context> contextSupplier) {
        if (bot == null || goal == null || goalSpec == null || step == null || spec == null) {
            throw new IllegalArgumentException("legacy_skill_open_incomplete");
        }
        GoalSnapshotCollector.Context initial = null;
        boolean supplied = contextSupplier != null;
        if (supplied) {
            try {
                initial = contextSupplier.get();
            } catch (RuntimeException ignored) {
                supplied = false;
            }
        }
        if (initial == null) {
            supplied = false;
            initial = GoalSnapshotCollector.Context.at(bot.blockPosition());
        }
        LegacyMissionCompiler.ExecutableSkill executable =
                new LegacyMissionCompiler.ExecutableSkill(spec, step);
        String dimension = bot.serverLevel().dimension().location().toString();
        ContractCheck contract = supplied
                ? validateContract(goalSpec, goal, executable, dimension, initial)
                : ContractCheck.invalid("legacy_skill_context_missing", Map.of());
        GoalSnapshotCollector.Context captured = initial;
        Supplier<GoalSnapshotCollector.Context> liveContext = contextSupplier == null
                ? () -> captured : contextSupplier;
        return new Session(
                bot.getUUID(), goalSpec, goal, executable, captured, dimension,
                relevantCount(bot, step), bot.blockPosition(), bot, liveContext, contract);
    }

    /** Uses the admission-time context when no Task-produced binding evidence must be added. */
    public static SkillOutcome verifySuccess(AIPlayerEntity bot,
                                             Task completedTask,
                                             Session session) {
        return verifySuccess(bot, completedTask, session,
                session == null ? null : session.startContext());
    }

    /**
     * Verifies a completed Task against live server state. A caller may supply an updated context
     * after capturing BuildTask/StockpileTask evidence; the verifier also reads those Task helpers
     * directly so a stale context cannot hide their authoritative targets.
     */
    public static SkillOutcome verifySuccess(AIPlayerEntity bot,
                                             Task completedTask,
                                             Session session,
                                             GoalSnapshotCollector.Context context) {
        if (bot == null || completedTask == null || session == null || context == null) {
            return fatal("legacy_skill_verification_incomplete", Map.of());
        }
        if (!session.botId().equals(bot.getUUID())) {
            return fatal("legacy_skill_bot_identity_mismatch", Map.of(
                    "expected_bot", session.botId().toString(),
                    "actual_bot", bot.getUUID().toString()));
        }
        String dimension = bot.serverLevel().dimension().location().toString();
        if (!session.dimension().equals(dimension)) {
            return failed(
                    SkillOutcome.Status.BLOCKED,
                    SkillOutcome.FailureKind.WORLD_CHANGED,
                    "legacy_skill_dimension_changed",
                    0,
                    Map.of("expected_dimension", session.dimension(),
                            "actual_dimension", dimension));
        }
        ContractCheck contract = validateContract(
                session.goalSpec(), session.goal(), session.executable(), dimension, context);
        if (!contract.valid()) {
            return fatal("legacy_skill_contract_invalidated:" + contract.reason(),
                    contract.evidence());
        }
        TaskState taskState;
        String taskName;
        int progress;
        try {
            taskState = completedTask.state();
            taskName = completedTask.name();
            progress = (int) Math.round(Math.max(0.0D, completedTask.progress()) * 1000.0D);
        } catch (RuntimeException exception) {
            return fatal("legacy_skill_task_diagnostic_failed:" + exception.getClass().getSimpleName(),
                    Map.of("skill", session.executable().spec().invocationId()));
        }
        if (taskState != TaskState.COMPLETED) {
            return fatal("legacy_skill_task_not_completed", Map.of(
                    "task", taskName,
                    "state", taskState.name()));
        }
        if (!expectedTaskClass(session.executable().step()).isInstance(completedTask)) {
            return fatal("legacy_skill_task_adapter_mismatch", Map.of(
                    "skill", session.executable().spec().invocationId(),
                    "task", completedTask.getClass().getName(),
                    "expected_task", expectedTaskClass(session.executable().step()).getName()));
        }

        Map<String, String> adapterEvidence = new LinkedHashMap<>(contract.evidence());
        adapterEvidence.put("task", taskName);
        adapterEvidence.put("task_state", taskState.name());
        return SkillRuntimeGate.verifySuccess(
                session.executable().spec(),
                new CompletionEvaluator(bot, completedTask, session, context, contract),
                progress,
                adapterEvidence);
    }

    /**
     * Rebinds a running adapter session after the same Goal receives stronger admission metadata.
     * Baselines and Task identity remain untouched; only source/priority may change.
     */
    static Session rebindAdmission(Session session, GoalSpec upgradedGoalSpec) {
        if (session == null || upgradedGoalSpec == null) {
            throw new IllegalArgumentException("legacy_skill_admission_rebind_incomplete");
        }
        GoalSpec previous = session.goalSpec();
        if (!previous.type().equals(upgradedGoalSpec.type())
                || !previous.successPredicate().equals(upgradedGoalSpec.successPredicate())
                || !previous.dimension().equals(upgradedGoalSpec.dimension())
                || !previous.policy().equals(upgradedGoalSpec.policy())
                || !previous.attributes().equals(upgradedGoalSpec.attributes())) {
            throw new IllegalArgumentException("legacy_skill_admission_rebind_invalid");
        }
        ContractCheck reboundContract = validateContract(
                upgradedGoalSpec,
                session.goal(),
                session.executable(),
                session.dimension(),
                session.startContext());
        if (!reboundContract.valid()) {
            throw new IllegalArgumentException(
                    "legacy_skill_admission_rebind_invalid:" + reboundContract.reason());
        }
        return new Session(
                session.botId(),
                upgradedGoalSpec,
                session.goal(),
                session.executable(),
                session.startContext(),
                session.dimension(),
                session.relevantBaseline(),
                session.startPosition(),
                session.runtimeBot(),
                session.contextSupplier(),
                reboundContract);
    }

    /** Task-bound evaluator used by the shared postcondition gate on the production completion path. */
    private record CompletionEvaluator(
            AIPlayerEntity bot,
            Task completedTask,
            Session session,
            GoalSnapshotCollector.Context context,
            ContractCheck contract
    ) implements SkillRuntimeGate.PredicateEvaluator {
        @Override
        public boolean supports(SkillSpec skill) {
            return contract.valid() && skill != null && skill.equals(session.executable().spec());
        }

        @Override
        public SkillRuntimeGate.PredicateResult evaluate(SkillSpec skill,
                                                         String predicate,
                                                         SkillRuntimeGate.Phase phase) {
            if (!supports(skill)) {
                return SkillRuntimeGate.PredicateResult.unknown("legacy_skill_contract_mismatch");
            }
            if (phase != SkillRuntimeGate.Phase.SUCCESS
                    || !LegacyMissionCompiler.successPredicate(session.executable().step())
                    .equals(predicate)) {
                return SkillRuntimeGate.PredicateResult.unknown(
                        "legacy_success_predicate_mismatch");
            }
            Observation observation = observe(bot, completedTask, session, context);
            Map<String, String> evidence = new LinkedHashMap<>(contract.evidence());
            evidence.putAll(observation.evidence());
            return observation.satisfied()
                    ? SkillRuntimeGate.PredicateResult.satisfied(evidence)
                    : SkillRuntimeGate.PredicateResult.unsatisfied(
                    SkillOutcome.FailureKind.WORLD_CHANGED,
                    "authoritative_skill_postcondition_unsatisfied:"
                            + session.executable().step().kind().name()
                            .toLowerCase(java.util.Locale.ROOT),
                    evidence);
        }
    }

    /** Pure contract validation used by admission and lightweight unit tests. */
    static ContractCheck validateContract(GoalSpec goalSpec,
                                          Goal goal,
                                          LegacyMissionCompiler.ExecutableSkill executable,
                                          String actualDimension,
                                          GoalSnapshotCollector.Context context) {
        if (goalSpec == null || goal == null || executable == null || context == null) {
            return ContractCheck.invalid("legacy_skill_contract_incomplete", Map.of());
        }
        if (actualDimension == null || actualDimension.isBlank()) {
            return ContractCheck.invalid("legacy_skill_actual_dimension_missing", Map.of());
        }
        if (!actualDimension.equals(goalSpec.dimension())) {
            return ContractCheck.invalid("legacy_skill_bound_dimension_mismatch", Map.of(
                    "bound_dimension", goalSpec.dimension(),
                    "actual_dimension", actualDimension));
        }

        int invocationIndex = invocationIndex(executable);
        if (invocationIndex < 0) {
            return ContractCheck.invalid("legacy_skill_invocation_not_canonical", Map.of(
                    "invocation", executable.spec().invocationId()));
        }

        LegacyMissionCompiler.CompiledMission canonical;
        try {
            List<GoalStep> repeated = Collections.nCopies(
                    invocationIndex + 1, executable.step());
            canonical = LegacyMissionCompiler.compile(
                    CONTRACT_MISSION_ID,
                    0,
                    goal,
                    goalSpec.source(),
                    goalSpec.priority(),
                    goalSpec.dimension(),
                    goalSpec.policy(),
                    repeated);
        } catch (RuntimeException exception) {
            return ContractCheck.invalid(
                    "legacy_skill_contract_compile_failed:" + safeReason(exception), Map.of());
        }

        if (!canonical.plan().goal().equals(goalSpec)) {
            return ContractCheck.invalid("legacy_goal_spec_not_canonical", Map.of(
                    "goal_type", goalSpec.type(),
                    "goal_source", goalSpec.source().name()));
        }
        LegacyMissionCompiler.ExecutableSkill expected = canonical.skills().get(invocationIndex);
        if (!expected.equals(executable)) {
            return ContractCheck.invalid("legacy_skill_spec_not_canonical", Map.of(
                    "invocation", executable.spec().invocationId(),
                    "capability", executable.spec().id()));
        }
        if (!SkillRuntimeGate.scopeAllowed(
                goalSpec.policy().mutationScope(), executable.spec().mutationScope())) {
            return ContractCheck.invalid("legacy_skill_mutation_scope_exceeds_goal", Map.of(
                    "goal_scope", goalSpec.policy().mutationScope().name(),
                    "skill_scope", executable.spec().mutationScope().name()));
        }
        if (!SkillRuntimeGate.riskAllowed(
                goalSpec.policy().riskLevel(), executable.spec().requiredRisk())) {
            return ContractCheck.invalid("legacy_skill_risk_exceeds_goal", Map.of(
                    "goal_risk", goalSpec.policy().riskLevel().name(),
                    "skill_risk", executable.spec().requiredRisk().name()));
        }

        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("contract", LegacyMissionCompiler.PLANNER_VERSION);
        evidence.put("invocation", executable.spec().invocationId());
        evidence.put("dimension", actualDimension);
        evidence.put("mutation_scope", executable.spec().mutationScope().name());
        evidence.put("required_risk", executable.spec().requiredRisk().name());
        if (goal instanceof Goal.Build build) {
            ContractCheck buildBinding = validateBuildBinding(build, context, actualDimension);
            if (!buildBinding.valid()) {
                return buildBinding;
            }
            evidence.putAll(buildBinding.evidence());
        } else if (executable.step().kind() == GoalStep.Kind.BUILD) {
            return ContractCheck.invalid("legacy_build_skill_requires_build_goal", evidence);
        }
        return ContractCheck.valid(evidence);
    }

    private static ContractCheck validateBuildBinding(Goal.Build build,
                                                      GoalSnapshotCollector.Context context,
                                                      String actualDimension) {
        if (!build.hasCompleteConfirmedBinding()) {
            return ContractCheck.invalid("legacy_build_binding_incomplete", Map.of());
        }
        if (!build.dimension().equals(actualDimension)) {
            return ContractCheck.invalid("legacy_build_dimension_mismatch", Map.of(
                    "build_dimension", build.dimension(),
                    "actual_dimension", actualDimension));
        }
        if (context.blueprint() == null || context.buildAnchor() == null) {
            return ContractCheck.invalid("legacy_build_context_missing", Map.of());
        }
        if (!build.anchor().equals(context.buildAnchor())) {
            return ContractCheck.invalid("legacy_build_anchor_mismatch", Map.of(
                    "goal_anchor", compact(build.anchor()),
                    "context_anchor", compact(context.buildAnchor())));
        }
        if (!build.blueprint().equals(context.blueprint().name())) {
            return ContractCheck.invalid("legacy_build_blueprint_name_mismatch", Map.of(
                    "goal_blueprint", build.blueprint(),
                    "context_blueprint", String.valueOf(context.blueprint().name())));
        }
        String actualDigest;
        try {
            actualDigest = BlueprintLoader.canonicalDigest(context.blueprint());
        } catch (IOException | RuntimeException exception) {
            return ContractCheck.invalid(
                    "legacy_build_blueprint_invalid:" + safeReason(exception), Map.of());
        }
        if (!build.blueprintDigest().equals(actualDigest)) {
            return ContractCheck.invalid("legacy_build_blueprint_digest_mismatch", Map.of(
                    "expected_digest", build.blueprintDigest(),
                    "actual_digest", actualDigest));
        }
        return ContractCheck.valid(Map.of(
                "blueprint", build.blueprint(),
                "blueprint_digest", actualDigest,
                "build_anchor", compact(build.anchor())));
    }

    private static Observation observe(AIPlayerEntity bot,
                                       Task task,
                                       Session session,
                                       GoalSnapshotCollector.Context suppliedContext) {
        GoalStep step = session.executable().step();
        return switch (step.kind()) {
            case GATHER, GATHER_EXACT, MINE, MINE_EXACT, MINE_ORE, SMELT,
                    FARM, HUNT, COOK_FOOD, MILK_COW, MAKE_OBSIDIAN ->
                    observeDelta(bot, session);
            case CRAFT -> observeCraft(bot, step);
            case MOVE, MOVE_NON_MUTATING -> observeMove(bot, step);
            case EQUIP_LOADOUT -> Observation.booleanFact(
                    EquipLoadoutTask.ready(bot), "loadout_ready");
            case PLACE_STATIONS -> observeGoalPredicate(
                    bot,
                    new Goal.Workstation(),
                    GoalSnapshotCollector.Context.at(suppliedContext.origin()));
            case STOCKPILE -> observeStockpile(bot, task, session.goal(), suppliedContext);
            case DESCEND_TO_Y -> Observation.count(
                    bot.blockPosition().getY() <= step.pos().getY(),
                    bot.blockPosition().getY(),
                    step.pos().getY(),
                    "actual_y",
                    "maximum_y");
            case BUILD -> observeBuild(bot, task, session.goal(), suppliedContext);
        };
    }

    private static Observation observeDelta(AIPlayerEntity bot, Session session) {
        int after = relevantCount(bot, session.executable().step());
        int delta = Math.max(0, after - session.relevantBaseline());
        int required = session.executable().step().count();
        return Observation.count(
                delta >= required,
                delta,
                required,
                "inventory_delta",
                "required_delta",
                Map.of("baseline", String.valueOf(session.relevantBaseline()),
                        "after", String.valueOf(after),
                        "accepted_items", itemIds(relevantItemsFor(session.executable().step()))));
    }

    private static Observation observeCraft(AIPlayerEntity bot, GoalStep step) {
        int observed = InventoryAction.countItem(bot, step.item());
        boolean available = CraftTask.utilityAlreadyAvailable(
                bot, step.item(), step.count());
        int effective = available ? Math.max(observed, step.count()) : observed;
        return Observation.count(
                available, effective, step.count(),
                "available_units", "required_units",
                Map.of("item", BuiltInRegistries.ITEM.getKey(step.item()).toString(),
                        "inventory_units", String.valueOf(observed),
                        "world_utility", String.valueOf(available && observed < step.count())));
    }

    private static Observation observeMove(AIPlayerEntity bot, GoalStep step) {
        double distanceSquared = bot.blockPosition().distSqr(step.pos());
        return new Observation(
                distanceSquared <= MOVE_REACHED_SQUARED,
                Map.of("target", compact(step.pos()),
                        "actual", compact(bot.blockPosition()),
                        "distance_squared", String.valueOf(distanceSquared),
                        "maximum_distance_squared", String.valueOf(MOVE_REACHED_SQUARED)));
    }

    private static Observation observeStockpile(AIPlayerEntity bot,
                                                Task task,
                                                Goal goal,
                                                GoalSnapshotCollector.Context context) {
        if (!(goal instanceof Goal.Stockpile)) {
            return Observation.booleanFact(false, "stockpile_goal_binding_missing");
        }
        Set<BlockPos> bound = new LinkedHashSet<>(context.boundContainers());
        if (task instanceof StockpileTask stockpile) {
            bound.addAll(stockpile.depositedContainers());
        }
        GoalSnapshotCollector.Context effective = new GoalSnapshotCollector.Context(
                context.origin(), bound, context.blueprint(), context.buildAnchor(),
                context.buildPlaced(), context.buildSkipped());
        return observeGoalPredicate(bot, goal, effective);
    }

    private static Observation observeBuild(AIPlayerEntity bot,
                                            Task task,
                                            Goal goal,
                                            GoalSnapshotCollector.Context context) {
        if (!(goal instanceof Goal.Build)) {
            return Observation.booleanFact(false, "build_goal_binding_missing");
        }
        GoalSnapshotCollector.Context effective = task instanceof BuildTask buildTask
                ? new GoalSnapshotCollector.Context(
                        context.origin(),
                        context.boundContainers(),
                        buildTask.blueprint(),
                        buildTask.anchor(),
                        buildTask.placedBlocks(),
                        buildTask.skippedBlocks())
                : context;
        return observeGoalPredicate(bot, goal, effective);
    }

    private static Observation observeGoalPredicate(AIPlayerEntity bot,
                                                    Goal goal,
                                                    GoalSnapshotCollector.Context context) {
        GoalSnapshot snapshot = GoalSnapshotCollector.collect(bot, goal, context);
        GoalEvaluation evaluation = GoalPredicates.forGoal(goal).evaluate(snapshot);
        Map<String, String> evidence = new LinkedHashMap<>(evaluation.evidence());
        evidence.put("matched", String.valueOf(evaluation.matched()));
        evidence.put("required", String.valueOf(evaluation.required()));
        if (!evaluation.unmet().isEmpty()) {
            evidence.put("unmet", String.join(",", evaluation.unmet()));
        }
        return new Observation(
                evaluation.state() == GoalEvaluation.State.SATISFIED,
                evidence);
    }

    static Set<Item> relevantItemsFor(GoalStep step) {
        if (step == null) {
            return Set.of();
        }
        return switch (step.kind()) {
            case GATHER, GATHER_EXACT -> GatherQuotaTask.acceptItemsFor(
                    step.item(), step.kind() == GoalStep.Kind.GATHER_EXACT);
            case MINE, MINE_EXACT -> DigDownTask.targetDropsFor(
                    step.block(), step.kind() == GoalStep.Kind.MINE_EXACT);
            case MINE_ORE -> HarvestCore.expectedDropsFor(step.ores());
            case SMELT -> Set.of(step.output());
            case FARM -> Set.of(step.item());
            case HUNT -> HuntTask.rawMeatDrops();
            case COOK_FOOD -> SmeltChain.COOKED_FOODS;
            case MILK_COW -> Set.of(Items.MILK_BUCKET);
            case MAKE_OBSIDIAN -> Set.of(Items.OBSIDIAN);
            default -> Set.of();
        };
    }

    private static int relevantCount(AIPlayerEntity bot, GoalStep step) {
        Set<Item> items = relevantItemsFor(step);
        return items.isEmpty() ? 0 : HarvestCore.countInventoryItems(bot, items);
    }

    private static int nearbyBlockCount(AIPlayerEntity bot, Block block) {
        BlockPos origin = bot.blockPosition();
        return (int) BlockPos.betweenClosedStream(
                        origin.offset(-8, -2, -8), origin.offset(8, 3, 8))
                .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.serverLevel().getBlockState(pos).is(block))
                .count();
    }

    private static Class<? extends Task> expectedTaskClass(GoalStep step) {
        return switch (step.kind()) {
            case GATHER, GATHER_EXACT -> GatherQuotaTask.class;
            case MINE, MINE_EXACT -> DigDownTask.class;
            case MINE_ORE -> OreDigTask.class;
            case CRAFT -> CraftTask.class;
            case SMELT, COOK_FOOD -> SmeltTask.class;
            case MOVE, MOVE_NON_MUTATING -> MoveTask.class;
            case FARM -> FarmTask.class;
            case HUNT -> HuntTask.class;
            case MILK_COW -> MilkCowTask.class;
            case EQUIP_LOADOUT -> EquipLoadoutTask.class;
            case PLACE_STATIONS -> PlaceStationsTask.class;
            case STOCKPILE -> StockpileTask.class;
            case DESCEND_TO_Y -> DescendToYTask.class;
            case MAKE_OBSIDIAN -> CreateObsidianTask.class;
            case BUILD -> BuildTask.class;
        };
    }

    private static int invocationIndex(LegacyMissionCompiler.ExecutableSkill executable) {
        SkillSpec spec = executable.spec();
        String capability = "legacy." + executable.step().kind().name()
                .toLowerCase(java.util.Locale.ROOT);
        String prefix = "step.";
        String suffix = "." + capability;
        String invocation = spec.invocationId();
        if (!spec.id().equals(capability)
                || !invocation.startsWith(prefix)
                || !invocation.endsWith(suffix)) {
            return -1;
        }
        String indexText = invocation.substring(
                prefix.length(), invocation.length() - suffix.length());
        if (indexText.isEmpty() || indexText.length() > 1 && indexText.startsWith("0")) {
            return -1;
        }
        try {
            int index = Integer.parseInt(indexText);
            return index >= 0 && index <= MAX_INVOCATION_INDEX ? index : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static SkillOutcome fatal(String reason, Map<String, String> evidence) {
        return failed(SkillOutcome.Status.FATAL_FAILURE, SkillOutcome.FailureKind.INTERNAL,
                reason, 0, evidence);
    }

    private static SkillOutcome failed(SkillOutcome.Status status,
                                       SkillOutcome.FailureKind kind,
                                       String reason,
                                       int progress,
                                       Map<String, String> evidence) {
        return new SkillOutcome(status, kind, reason, progress, evidence);
    }

    private static String safeReason(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static String compact(BlockPos pos) {
        return pos == null ? "" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String itemIds(Set<Item> items) {
        return items.stream()
                .map(BuiltInRegistries.ITEM::getKey)
                .map(Object::toString)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private record PreflightEvaluator(Session session)
            implements SkillRuntimeGate.PredicateEvaluator {
        @Override
        public boolean supports(SkillSpec skill) {
            return skill != null && skill.equals(session.executable().spec());
        }

        @Override
        public SkillRuntimeGate.PredicateResult evaluate(SkillSpec skill,
                                                         String predicate,
                                                         SkillRuntimeGate.Phase phase) {
            if (!supports(skill)) {
                return SkillRuntimeGate.PredicateResult.unknown(
                        "legacy_skill_contract_mismatch");
            }
            if (phase != SkillRuntimeGate.Phase.PRECONDITION) {
                return SkillRuntimeGate.PredicateResult.unknown(
                        "legacy_success_requires_authoritative_verifier");
            }
            return switch (predicate) {
                case "world:bound_dimension" -> fact(
                        session.dimension().equals(session.goalSpec().dimension())
                                && session.dimension().equals(session.runtimeBot().serverLevel()
                                .dimension().location().toString()),
                        predicate,
                        "legacy_skill_bound_dimension_mismatch",
                        SkillOutcome.FailureKind.WORLD_CHANGED);
                case "inventory:pickup_space" -> fact(
                        hasPickupSpace(), predicate, "inventory_full",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "inventory:usable_tool" -> fact(
                        hasUsableTool(), predicate, "missing_usable_tool",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "world:safe_work_face" -> fact(
                        session.runtimeBot().isAlive() && !session.runtimeBot().isInLava(),
                        predicate, "unsafe_work_face", SkillOutcome.FailureKind.SAFETY);
                case "inventory:recipe_inputs" -> fact(
                        hasRecipeInputs(), predicate, "missing_recipe_inputs",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "inventory:smelt_input" -> fact(
                        hasSmeltInput(), predicate, "missing_smelt_input",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "inventory:fuel" -> fact(
                        hasFuel(), predicate, "missing_fuel",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "station:furnace" -> fact(
                        hasFurnace(), predicate, "missing_furnace",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "world:mature_crop_or_plantable_seed" -> fact(
                        hasHarvestOrPlantOpportunity(), predicate,
                        "no_mature_crop_or_plantable_seed",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "safety:combat_budget" -> fact(
                        session.runtimeBot().isAlive()
                                && session.runtimeBot().getHealth() > Math.max(
                                4.0F, session.runtimeBot().getMaxHealth() * 0.3F),
                        predicate, "combat_budget_unsafe", SkillOutcome.FailureKind.SAFETY);
                case "inventory:bucket" -> fact(
                        InventoryAction.countItem(session.runtimeBot(), Items.BUCKET) > 0,
                        predicate, "missing_bucket", SkillOutcome.FailureKind.PRECONDITION);
                case "inventory:owned_armor_and_weapon" -> fact(
                        ownsLoadout(), predicate, "missing_owned_loadout",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "inventory:no_open_session" -> fact(
                        !BotInventorySessionManager.INSTANCE.isOpen(session.runtimeBot()),
                        predicate, "inventory_session_open",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "inventory:stations" -> fact(
                        STATIONS.stream().allMatch(this::stationAvailable),
                        predicate, "missing_stations", SkillOutcome.FailureKind.PRECONDITION);
                case "human:confirmed_blueprint" -> fact(
                        session.goal() instanceof Goal.Build build
                                && build.hasCompleteConfirmedBinding()
                                && session.startContext().blueprint() != null,
                        predicate,
                        "legacy_build_binding_incomplete",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "inventory:building_materials" -> fact(
                        hasBuildingMaterials(), predicate, "missing_building_materials",
                        SkillOutcome.FailureKind.PRECONDITION);
                case "world:confirmed_dimension_and_anchor" -> fact(
                        session.goal() instanceof Goal.Build build
                                && build.dimension().equals(session.dimension())
                                && build.anchor().equals(session.startContext().buildAnchor()),
                        predicate,
                        "legacy_build_dimension_or_anchor_mismatch",
                        SkillOutcome.FailureKind.WORLD_CHANGED);
                default -> SkillRuntimeGate.PredicateResult.unknown(
                        "unsupported_legacy_precondition:" + predicate);
            };
        }

        private boolean hasPickupSpace() {
            Set<Item> accepted = relevantItemsFor(session.executable().step());
            for (ItemStack stack : session.runtimeBot().getInventory().items) {
                if (stack.isEmpty()
                        || accepted.contains(stack.getItem())
                        && stack.getCount() < stack.getMaxStackSize()) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasUsableTool() {
            GoalStep step = session.executable().step();
            return switch (step.kind()) {
                case MINE, MINE_EXACT -> ToolTier.bestPickaxeTier(session.runtimeBot())
                        >= ToolTier.requiredPickaxeTier(step.block());
                case MINE_ORE -> ToolTier.hasRequiredPickaxe(session.runtimeBot(), step.ores());
                case DESCEND_TO_Y -> ToolTier.bestPickaxeTier(session.runtimeBot()) >= ToolTier.WOOD;
                case MAKE_OBSIDIAN -> ToolTier.bestPickaxeTier(session.runtimeBot()) >= ToolTier.DIAMOND;
                default -> false;
            };
        }

        private boolean hasRecipeInputs() {
            GoalStep step = session.executable().step();
            return CraftTask.utilityAlreadyAvailable(
                    session.runtimeBot(), step.item(), step.count())
                    || CraftingHelper.plan(session.runtimeBot(), step.item(), step.count()).success();
        }

        private boolean hasSmeltInput() {
            GoalStep step = session.executable().step();
            return step.kind() == GoalStep.Kind.COOK_FOOD
                    ? HarvestCore.countInventoryItems(
                    session.runtimeBot(), SmeltChain.RAW_FOODS) > 0
                    : step.input() != null
                    && InventoryAction.countItem(session.runtimeBot(), step.input()) > 0;
        }

        private boolean hasFuel() {
            return SmeltTask.hasUsableFuelSource(session.runtimeBot());
        }

        private boolean hasFurnace() {
            return SmeltTask.hasUsableFurnaceSource(session.runtimeBot());
        }

        private boolean hasHarvestOrPlantOpportunity() {
            GoalStep step = session.executable().step();
            return step.input() != null && step.block() != null
                    && FarmTask.hasHarvestOrPlantOpportunity(
                    session.runtimeBot(), session.runtimeBot().blockPosition(), 4,
                    step.input(), step.block());
        }

        private boolean stationAvailable(Item station) {
            if (InventoryAction.countItem(session.runtimeBot(), station) > 0) {
                return true;
            }
            if (station == Items.CRAFTING_TABLE || station == Items.FURNACE) {
                return CraftTask.utilityAlreadyAvailable(session.runtimeBot(), station, 1);
            }
            return station == Items.CHEST
                    && (nearbyBlockCount(session.runtimeBot(), Blocks.CHEST) > 0
                    || nearbyBlockCount(session.runtimeBot(), Blocks.TRAPPED_CHEST) > 0);
        }

        private boolean ownsLoadout() {
            return ownsAny(HELMETS, EquipmentSlot.HEAD)
                    && ownsAny(CHESTPLATES, EquipmentSlot.CHEST)
                    && ownsAny(LEGGINGS, EquipmentSlot.LEGS)
                    && ownsAny(BOOTS, EquipmentSlot.FEET)
                    && ownsAny(SWORDS, EquipmentSlot.MAINHAND);
        }

        private boolean ownsAny(Set<Item> items, EquipmentSlot slot) {
            ItemStack equipped = session.runtimeBot().getItemBySlot(slot);
            if (!equipped.isEmpty() && items.contains(equipped.getItem())) {
                return true;
            }
            return items.stream().anyMatch(
                    item -> InventoryAction.countItem(session.runtimeBot(), item) > 0);
        }

        private boolean hasBuildingMaterials() {
            if (!(session.goal() instanceof Goal.Build build)
                    || session.startContext().blueprint() == null
                    || session.startContext().buildAnchor() == null) {
                return false;
            }
            BlueprintSchema blueprint = session.startContext().blueprint();
            java.util.function.Predicate<BlueprintSchema.BlockPlacement> alreadySatisfied =
                    placement -> StructureVerifier.matches(
                            session.runtimeBot().serverLevel(), build.anchor(), placement);
            List<BlueprintSchema.BlockPlacement> placements =
                    GoalPlanner.usesExactBuildingMaterials(build.blueprint())
                            ? GoalPlanner.materialBatchPlacements(
                            blueprint, alreadySatisfied,
                            GoalPlanner.MAX_BUILD_MATERIAL_CELLS_PER_BATCH)
                            : GoalPlanner.materialPlacements(blueprint, alreadySatisfied);
            Map<String, Integer> paletteNeeds = new LinkedHashMap<>();
            Map<Item, Integer> exactNeeds = new LinkedHashMap<>();
            for (BlueprintSchema.BlockPlacement placement : placements) {
                if (placement.palette() != null
                        && MaterialPalette.isKnown(placement.palette())) {
                    paletteNeeds.merge(placement.palette(), 1, Integer::sum);
                    continue;
                }
                Item item;
                try {
                    item = BuiltInRegistries.BLOCK.getOptional(
                            net.minecraft.resources.ResourceLocation.parse(placement.blockId()))
                            .orElse(Blocks.AIR).asItem();
                } catch (RuntimeException exception) {
                    return false;
                }
                if (item == Items.AIR) {
                    return false;
                }
                exactNeeds.merge(item, 1, Integer::sum);
            }
            boolean palettesReady = paletteNeeds.entrySet().stream().allMatch(entry -> {
                List<Item> family = MaterialPalette.GROUPS.get(entry.getKey());
                return family != null && HarvestCore.countInventoryItems(
                        session.runtimeBot(), Set.copyOf(family)) >= entry.getValue();
            });
            return palettesReady && exactNeeds.entrySet().stream().allMatch(entry ->
                    InventoryAction.countItem(
                            session.runtimeBot(), entry.getKey()) >= entry.getValue());
        }

        private static SkillRuntimeGate.PredicateResult fact(boolean satisfied,
                                                             String predicate,
                                                             String failureReason,
                                                             SkillOutcome.FailureKind failureKind) {
            Map<String, String> evidence = Map.of(
                    "predicate", predicate,
                    "observed", String.valueOf(satisfied));
            return satisfied
                    ? SkillRuntimeGate.PredicateResult.satisfied(evidence)
                    : SkillRuntimeGate.PredicateResult.unsatisfied(
                            failureKind,
                            failureReason,
                            evidence);
        }
    }

    public record Session(
            UUID botId,
            GoalSpec goalSpec,
            Goal goal,
            LegacyMissionCompiler.ExecutableSkill executable,
            GoalSnapshotCollector.Context startContext,
            String dimension,
            int relevantBaseline,
            BlockPos startPosition,
            AIPlayerEntity runtimeBot,
            Supplier<GoalSnapshotCollector.Context> contextSupplier,
            ContractCheck contract
    ) implements SkillRuntimeGate.PredicateEvaluator {
        public Session {
            if (botId == null || goalSpec == null || goal == null || executable == null
                    || startContext == null || dimension == null || dimension.isBlank()
                    || startPosition == null || runtimeBot == null || contextSupplier == null
                    || contract == null) {
                throw new IllegalArgumentException("legacy_skill_session_incomplete");
            }
            if (!botId.equals(runtimeBot.getUUID())) {
                throw new IllegalArgumentException("legacy_skill_session_bot_mismatch");
            }
            relevantBaseline = Math.max(0, relevantBaseline);
            startPosition = startPosition.immutable();
        }

        @Override
        public boolean supports(SkillSpec skill) {
            return contract.valid()
                    && skill != null
                    && skill.equals(executable.spec());
        }

        @Override
        public SkillRuntimeGate.PredicateResult evaluate(SkillSpec skill,
                                                         String predicate,
                                                         SkillRuntimeGate.Phase phase) {
            if (!supports(skill)) {
                return SkillRuntimeGate.PredicateResult.unknown(
                        contract.valid() ? "legacy_skill_contract_mismatch" : contract.reason());
            }
            if (phase == SkillRuntimeGate.Phase.PRECONDITION) {
                return new PreflightEvaluator(this).evaluate(skill, predicate, phase);
            }
            if (!LegacyMissionCompiler.successPredicate(executable.step()).equals(predicate)) {
                return SkillRuntimeGate.PredicateResult.unknown(
                        "legacy_success_predicate_mismatch");
            }
            if (!botId.equals(runtimeBot.getUUID())) {
                return SkillRuntimeGate.PredicateResult.unknown(
                        "legacy_skill_bot_identity_mismatch");
            }
            String actualDimension = runtimeBot.serverLevel()
                    .dimension().location().toString();
            GoalSnapshotCollector.Context liveContext;
            try {
                liveContext = contextSupplier.get();
            } catch (RuntimeException exception) {
                return SkillRuntimeGate.PredicateResult.unknown(
                        "legacy_skill_context_read_failed:" + safeReason(exception));
            }
            ContractCheck liveContract = validateContract(
                    goalSpec, goal, executable, actualDimension, liveContext);
            if (!liveContract.valid()) {
                return SkillRuntimeGate.PredicateResult.unknown(liveContract.reason());
            }
            Observation observation = observe(
                    runtimeBot, null, this, liveContext);
            Map<String, String> evidence = new LinkedHashMap<>(liveContract.evidence());
            evidence.putAll(observation.evidence());
            return observation.satisfied()
                    ? SkillRuntimeGate.PredicateResult.satisfied(evidence)
                    : SkillRuntimeGate.PredicateResult.unsatisfied(
                            SkillOutcome.FailureKind.WORLD_CHANGED,
                            "authoritative_skill_postcondition_unsatisfied:"
                                    + executable.step().kind().name()
                                    .toLowerCase(java.util.Locale.ROOT),
                            evidence);
        }
    }

    public record Preparation(boolean allowed, Session session, SkillOutcome rejection) {
        public Preparation {
            if (allowed != (session != null) || allowed == (rejection != null)) {
                throw new IllegalArgumentException("legacy_skill_preparation_inconsistent");
            }
        }

        private static Preparation accepted(Session session) {
            return new Preparation(true, session, null);
        }

        private static Preparation rejected(SkillOutcome rejection) {
            return new Preparation(false, null, rejection);
        }
    }

    public record ContractCheck(boolean valid, String reason, Map<String, String> evidence) {
        public ContractCheck {
            reason = reason == null ? "" : reason;
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
            if (valid == !reason.isBlank()) {
                throw new IllegalArgumentException("legacy_contract_check_inconsistent");
            }
        }

        private static ContractCheck valid(Map<String, String> evidence) {
            return new ContractCheck(true, "", evidence);
        }

        private static ContractCheck invalid(String reason, Map<String, String> evidence) {
            return new ContractCheck(false, reason, evidence);
        }
    }

    private record Observation(boolean satisfied, Map<String, String> evidence) {
        private Observation {
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }

        private static Observation booleanFact(boolean satisfied, String evidenceKey) {
            return new Observation(satisfied, Map.of(evidenceKey, String.valueOf(satisfied)));
        }

        private static Observation count(boolean satisfied,
                                         int actual,
                                         int required,
                                         String actualKey,
                                         String requiredKey) {
            return count(satisfied, actual, required, actualKey, requiredKey, Map.of());
        }

        private static Observation count(boolean satisfied,
                                         int actual,
                                         int required,
                                         String actualKey,
                                         String requiredKey,
                                         Map<String, String> extraEvidence) {
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put(actualKey, String.valueOf(actual));
            evidence.put(requiredKey, String.valueOf(required));
            evidence.putAll(extraEvidence);
            return new Observation(satisfied, evidence);
        }
    }
}
