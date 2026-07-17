package io.github.greytaiwolf.fakeaiplayer.building.site;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateResolver;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanTransforms;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanPlacement;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanTransform;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded two-orientation site selection used before a projection is published. */
public final class BuildingSiteSelector {
    public static final int DEFAULT_SEARCH_RADIUS = 48;
    public static final int MAX_TERRAIN_SPAN = 5;
    // Plan-volume clearance is also synchronous; only inspect the best coarse candidate for each
    // orientation so a hostile/obstructed site cannot trigger six near-full 65k-cell scans.
    public static final int MAX_FINE_SURVEYS_PER_ORIENTATION = 1;
    private static final int SEARCH_RINGS = 3;

    private BuildingSiteSelector() {
    }

    public static Optional<BuildingSiteCandidate> select(AIPlayerEntity bot,
                                                          BuildingPlan plan,
                                                          Rotation preferredRotation,
                                                          int searchRadius) {
        if (bot == null || plan == null || searchRadius < 0) {
            return Optional.empty();
        }
        Rotation preferred = preferredRotation == null ? Rotation.NONE : preferredRotation;
        Rotation quarterTurn = rotateQuarter(preferred);
        BuildingSiteSurveyor.ProbeBudget probeBudget = new BuildingSiteSurveyor.ProbeBudget(
                BuildingSiteSurveyor.MAX_SELECTION_STANDABILITY_PROBES);
        boolean hiddenScanAllowed = CapabilityRuntime.decide(
                bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "building_site_selection").allowed();
        List<BuildingSiteCandidate> candidates = new ArrayList<>(2);
        collect(bot, plan, preferred, searchRadius, 0, candidates,
                probeBudget, hiddenScanAllowed);
        if (plan.width() != plan.depth() && !probeBudget.exhausted()) {
            collect(bot, plan, quarterTurn, searchRadius, 1, candidates,
                    probeBudget, hiddenScanAllowed);
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(BuildingSiteCandidate::score)
                        .thenComparingInt(candidate -> candidate.transform().rotation().ordinal())
                        .thenComparing(candidate -> candidate.survey().signature()));
    }

    private static void collect(AIPlayerEntity bot,
                                BuildingPlan plan,
                                Rotation rotation,
                                int searchRadius,
                                int orientationPenalty,
                                List<BuildingSiteCandidate> output,
                                BuildingSiteSurveyor.ProbeBudget probeBudget,
                                boolean hiddenScanAllowed) {
        PlanTransform transform = new PlanTransform(Mirror.NONE, rotation);
        BuildingPlan orientedPlan;
        try {
            orientedPlan = BuildingPlanTransforms.bake(plan, transform);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return;
        }
        int width = orientedPlan.width();
        int depth = orientedPlan.depth();
        List<CoarseCandidate> coarse = new ArrayList<>();
        for (BlockPos origin : candidateOrigins(bot.blockPosition(), width, depth, searchRadius)) {
            if (probeBudget.exhausted()) {
                break;
            }
            BuildingSiteSurveyor.coarseSurvey(
                            bot, origin, width, depth, origin.getY(),
                            probeBudget, hiddenScanAllowed)
                    .filter(BuildingSiteSurveyor.CoarseSurvey::complete)
                    .filter(survey -> survey.waterColumns() == 0)
                    .filter(survey -> survey.terrainSpan() <= MAX_TERRAIN_SPAN)
                    .ifPresent(survey -> coarse.add(new CoarseCandidate(
                            origin,
                            survey.terrainSpan() * 32.0D
                                    + survey.variance() * 8.0D
                                    + origin.distSqr(bot.blockPosition()) / 256.0D)));
        }
        List<CoarseCandidate> fineCandidates = coarse.stream()
                .sorted(Comparator.comparingDouble(CoarseCandidate::score)
                        .thenComparingInt(candidate -> candidate.origin().getX())
                        .thenComparingInt(candidate -> candidate.origin().getZ()))
                .limit(MAX_FINE_SURVEYS_PER_ORIENTATION)
                .toList();
        for (CoarseCandidate candidate : fineCandidates) {
            if (probeBudget.exhausted()) {
                return;
            }
            Optional<BuildingSiteSurvey> surveyed = BuildingSiteSurveyor.survey(
                    bot, candidate.origin(), width, depth, candidate.origin().getY(),
                    probeBudget, hiddenScanAllowed);
            if (surveyed.isEmpty()) {
                continue;
            }
            BuildingSiteSurvey survey = surveyed.get();
            if (!survey.complete() || survey.waterColumns() > 0
                    || survey.terrainSpan() > MAX_TERRAIN_SPAN
                    || !hasOrientedPlanClearance(
                            bot, orientedPlan, survey, hiddenScanAllowed)) {
                continue;
            }
            BuildingTerrainAdapter.AdaptedPlan adapted;
            try {
                adapted = BuildingTerrainAdapter.adapt(plan, transform, survey);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                continue;
            }
            if (!hasAddedPierClearance(bot, adapted, hiddenScanAllowed)) {
                continue;
            }
            double distance = survey.planAnchor().distSqr(bot.blockPosition()) / 256.0D;
            double score = survey.terrainSpan() * 32.0D
                    + survey.variance() * 8.0D
                    + distance
                    + orientationPenalty * 0.25D;
            output.add(new BuildingSiteCandidate(survey, transform, score,
                    survey.terrainSpan() == 0 ? "LEVEL" : "STILTS"));
            // Coarse candidates are already ordered best-first. One fully clear candidate per
            // orientation bounds plan-sized world reads while still comparing both orientations.
            return;
        }
    }

    private static boolean hasOrientedPlanClearance(AIPlayerEntity bot,
                                                     BuildingPlan orientedPlan,
                                                     BuildingSiteSurvey survey,
                                                     boolean hiddenScanAllowed) {
        ServerLevel world = bot.serverLevel();
        if (!survey.dimension().equals(world.dimension().location().toString())) {
            return false;
        }
        BlockPos anchor = survey.planAnchor();
        int baseOffset = survey.terrainSpan();
        for (PlanPlacement placement : orientedPlan.placements()) {
            BlockPos pos = anchor.offset(
                    placement.dx(), placement.dy() + baseOffset, placement.dz());
            if (!compatibleWorldCell(world, bot, pos, placement, hiddenScanAllowed)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAddedPierClearance(AIPlayerEntity bot,
                                                  BuildingTerrainAdapter.AdaptedPlan adapted,
                                                  boolean hiddenScanAllowed) {
        ServerLevel world = bot.serverLevel();
        List<PlanPlacement> placements = adapted.plan().placements();
        for (int index = 0; index < adapted.pierCount(); index++) {
            PlanPlacement placement = placements.get(index);
            BlockPos pos = adapted.anchor().offset(
                    placement.dx(), placement.dy(), placement.dz());
            if (!compatibleWorldCell(world, bot, pos, placement, hiddenScanAllowed)) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatibleWorldCell(ServerLevel world,
                                                AIPlayerEntity bot,
                                                BlockPos pos,
                                                PlanPlacement placement,
                                                boolean hiddenScanAllowed) {
        if (pos.getY() < world.getMinY()
                || pos.getY() >= world.getMinY() + world.getHeight()
                || !world.getWorldBorder().isWithinBounds(pos)
                || !world.hasChunkAt(pos)
                || (!hiddenScanAllowed && !ObservableWorldQuery.canObserveCell(bot, pos))) {
            return false;
        }
        BlockState actual = world.getBlockState(pos);
        return isCompatibleCell(actual, world.getBlockEntity(pos) != null, placement);
    }

    static boolean isCompatibleCell(BlockState actual,
                                    boolean hasBlockEntity,
                                    PlanPlacement placement) {
        if (actual == null || placement == null || hasBlockEntity) {
            return false;
        }
        boolean expectedMatches = BlockStateResolver.matches(actual, placement.state());
        if (placement.operation() == CellOperation.PLACE && expectedMatches) {
            return true;
        }
        return switch (placement.operation()) {
            case PLACE -> switch (placement.replacePolicy()) {
                case REQUIRE_EMPTY -> actual.isAir();
                case REPLACE_REPLACEABLE, REPLACE_NATURAL -> actual.isAir()
                        || actual.canBeReplaced() && actual.getFluidState().isEmpty();
                case CLEAR_AUTHORIZED, PRESERVE_EXISTING, FORCE_AUTHORIZED -> false;
            };
            case CLEAR -> actual.isAir();
            case PRESERVE -> expectedMatches;
            case TEMPORARY -> false;
        };
    }

    static List<BlockPos> candidateOrigins(BlockPos centre,
                                           int width,
                                           int depth,
                                           int searchRadius) {
        Set<BlockPos> origins = new LinkedHashSet<>();
        addOrigin(origins, centre, width, depth, 0, 0);
        if (searchRadius == 0) {
            return List.copyOf(origins);
        }
        for (int ring = 1; ring <= SEARCH_RINGS; ring++) {
            int radius = Math.max(1, (int) Math.round(searchRadius * (ring / (double) SEARCH_RINGS)));
            addOrigin(origins, centre, width, depth, radius, 0);
            addOrigin(origins, centre, width, depth, radius, radius);
            addOrigin(origins, centre, width, depth, 0, radius);
            addOrigin(origins, centre, width, depth, -radius, radius);
            addOrigin(origins, centre, width, depth, -radius, 0);
            addOrigin(origins, centre, width, depth, -radius, -radius);
            addOrigin(origins, centre, width, depth, 0, -radius);
            addOrigin(origins, centre, width, depth, radius, -radius);
        }
        return List.copyOf(origins);
    }

    private static void addOrigin(Set<BlockPos> origins,
                                  BlockPos centre,
                                  int width,
                                  int depth,
                                  int offsetX,
                                  int offsetZ) {
        origins.add(new BlockPos(
                centre.getX() + offsetX - width / 2,
                centre.getY(),
                centre.getZ() + offsetZ - depth / 2));
    }

    private static Rotation rotateQuarter(Rotation rotation) {
        return switch (rotation) {
            case NONE -> Rotation.CLOCKWISE_90;
            case CLOCKWISE_90 -> Rotation.CLOCKWISE_180;
            case CLOCKWISE_180 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.NONE;
        };
    }

    private record CoarseCandidate(BlockPos origin, double score) {
    }
}
