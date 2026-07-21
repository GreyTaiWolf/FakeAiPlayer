package io.github.greytaiwolf.fakeaiplayer.task.tree;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Bounded connected-component detector used by the deterministic tree-felling skill. */
public final class TreeDetector {
    public static final int MAX_LOGS = 128;
    private static final int MIN_REVIEWED_TRUNK_HEIGHT = 4;
    private static final int MAX_HORIZONTAL_FROM_SEED = 6;
    private static final int MAX_BELOW_SEED = 4;
    private static final int MAX_ABOVE_SEED = 32;
    private static final int LEAF_SCAN_RADIUS = 2;

    private TreeDetector() {
    }

    /**
     * Whether a log-like block uses the P1 overworld-tree topology contract. Nether stems, bamboo
     * blocks and stripped/player-finished wood retain legacy single-block gathering until they
     * have a dedicated topology model.
     */
    public static boolean supportsWholeTreeDetection(BlockState state) {
        if (!state.is(BlockTags.LOGS)) {
            return false;
        }
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = key.getPath();
        // Modded log families can have different roots, leaves and growth geometry. Until a
        // family registers an explicit topology contract, keep it on the non-component legacy
        // path instead of guessing from an English registry suffix.
        return key.getNamespace().equals("minecraft")
                && path.endsWith("_log")
                && !path.startsWith("stripped_");
    }

    public static TreeSnapshot detect(
            ServerLevel world, BlockPos seed, Set<Block> allowedLogBlocks) {
        BlockPos immutableSeed = seed.immutable();
        Block componentBlock = world.getBlockState(immutableSeed).getBlock();
        if (!isComponentLog(
                world.getBlockState(immutableSeed), allowedLogBlocks, componentBlock)) {
            return empty(world, immutableSeed);
        }

        Set<BlockPos> discovered = new LinkedHashSet<>();
        Set<BlockPos> queued = new LinkedHashSet<>();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        queued.add(immutableSeed);
        frontier.add(immutableSeed);
        boolean truncated = false;

        while (!frontier.isEmpty()) {
            if (discovered.size() >= MAX_LOGS) {
                truncated = true;
                break;
            }
            BlockPos current = frontier.remove();
            if (!withinBounds(immutableSeed, current)
                    || !isComponentLog(
                    world.getBlockState(current), allowedLogBlocks, componentBlock)) {
                continue;
            }
            discovered.add(current.immutable());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = current.offset(dx, dy, dz);
                        if (!isComponentLog(
                                world.getBlockState(next), allowedLogBlocks, componentBlock)) {
                            continue;
                        }
                        if (!withinBounds(immutableSeed, next)) {
                            // A connected log outside the reviewed component limits means this is
                            // not a complete tree snapshot. Never silently chop the in-bounds half.
                            truncated = true;
                            continue;
                        }
                        if (!discovered.contains(next) && queued.add(next.immutable())) {
                            frontier.add(next.immutable());
                        }
                    }
                }
            }
        }

        if (discovered.isEmpty()) {
            return empty(world, immutableSeed);
        }
        if (!frontier.isEmpty()) {
            truncated = true;
        }
        BlockPos root = discovered.stream()
                .min(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .orElse(immutableSeed);
        Set<BlockPos> leaves = findLeafEvidence(world, discovered, componentBlock);
        AABB bounds = bounds(discovered);
        int fingerprint = fingerprint(world, discovered);
        boolean provenanceHealthy = PlayerPlacedLogLedger.INSTANCE.allowsAutomaticFelling();
        boolean playerPlacedComponent = discovered.stream().anyMatch(position ->
                PlayerPlacedLogLedger.INSTANCE.isKnownPlayerPlaced(world, position));
        return new TreeSnapshot(
                new TreeSnapshot.TreeId(world.dimension(), root, fingerprint),
                immutableSeed,
                discovered,
                leaves,
                bounds,
                provenanceHealthy
                        && !playerPlacedComponent
                        && looksNatural(world, root, discovered, leaves),
                truncated);
    }

    private static boolean isComponentLog(
            BlockState state, Set<Block> allowedLogBlocks, Block componentBlock) {
        return state.is(BlockTags.LOGS)
                && state.getBlock() == componentBlock
                && (allowedLogBlocks == null
                || allowedLogBlocks.isEmpty()
                || allowedLogBlocks.contains(state.getBlock()));
    }

    private static boolean withinBounds(BlockPos seed, BlockPos candidate) {
        return Math.abs(candidate.getX() - seed.getX()) <= MAX_HORIZONTAL_FROM_SEED
                && Math.abs(candidate.getZ() - seed.getZ()) <= MAX_HORIZONTAL_FROM_SEED
                && candidate.getY() >= seed.getY() - MAX_BELOW_SEED
                && candidate.getY() <= seed.getY() + MAX_ABOVE_SEED;
    }

    private static Set<BlockPos> findLeafEvidence(
            ServerLevel world, Set<BlockPos> logs, Block componentBlock) {
        Set<BlockPos> leaves = new LinkedHashSet<>();
        for (BlockPos log : logs) {
            for (int dx = -LEAF_SCAN_RADIUS; dx <= LEAF_SCAN_RADIUS; dx++) {
                for (int dy = -LEAF_SCAN_RADIUS; dy <= LEAF_SCAN_RADIUS; dy++) {
                    for (int dz = -LEAF_SCAN_RADIUS; dz <= LEAF_SCAN_RADIUS; dz++) {
                        BlockPos candidate = log.offset(dx, dy, dz);
                        BlockState state = world.getBlockState(candidate);
                        if (isNaturalLeafFor(state, componentBlock)) {
                            leaves.add(candidate.immutable());
                        }
                    }
                }
            }
        }

        return leaves;
    }

    private static boolean isNaturalLeafFor(BlockState state, Block componentBlock) {
        if (!state.is(BlockTags.LEAVES)
                || (state.hasProperty(LeavesBlock.PERSISTENT)
                && state.getValue(LeavesBlock.PERSISTENT))) {
            return false;
        }
        if (componentBlock == Blocks.OAK_LOG) {
            return state.is(Blocks.OAK_LEAVES)
                    || state.is(Blocks.AZALEA_LEAVES)
                    || state.is(Blocks.FLOWERING_AZALEA_LEAVES);
        }
        if (componentBlock == Blocks.SPRUCE_LOG) {
            return state.is(Blocks.SPRUCE_LEAVES);
        }
        if (componentBlock == Blocks.BIRCH_LOG) {
            return state.is(Blocks.BIRCH_LEAVES);
        }
        if (componentBlock == Blocks.JUNGLE_LOG) {
            return state.is(Blocks.JUNGLE_LEAVES);
        }
        if (componentBlock == Blocks.ACACIA_LOG) {
            return state.is(Blocks.ACACIA_LEAVES);
        }
        if (componentBlock == Blocks.DARK_OAK_LOG) {
            return state.is(Blocks.DARK_OAK_LEAVES);
        }
        if (componentBlock == Blocks.MANGROVE_LOG) {
            return state.is(Blocks.MANGROVE_LEAVES);
        }
        if (componentBlock == Blocks.CHERRY_LOG) {
            return state.is(Blocks.CHERRY_LEAVES);
        }
        return false;
    }

    private static boolean looksNatural(
            ServerLevel world, BlockPos root, Set<BlockPos> logs, Set<BlockPos> leaves) {
        if (logs.size() < 2 || leaves.size() < 2) {
            return false;
        }
        int rootY = root.getY();
        Set<BlockPos> rootBases = logs.stream()
                .filter(log -> log.getY() == rootY)
                .filter(log -> isNaturalSubstrate(world.getBlockState(log.below())))
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!isReviewedRootFootprint(rootBases)) {
            return false;
        }

        // Only uninterrupted columns rising from reviewed natural substrate are trunk core.
        // Merely touching that core is not enough to inherit the tree's natural-leaf evidence.
        // This conservatively rejects low attached beams and ambiguous 2/3-column root clusters.
        Set<BlockPos> trunkCore = new LinkedHashSet<>();
        for (BlockPos base : rootBases) {
            BlockPos cursor = base;
            while (logs.contains(cursor)) {
                trunkCore.add(cursor.immutable());
                cursor = cursor.above();
            }
        }
        boolean reviewedTrunkHeight = rootBases.stream().allMatch(base -> {
            for (int dy = 0; dy < MIN_REVIEWED_TRUNK_HEIGHT; dy++) {
                if (!trunkCore.contains(base.above(dy))) {
                    return false;
                }
            }
            return true;
        });
        if (!reviewedTrunkHeight) {
            return false;
        }

        boolean canopyAboveRoot = leaves.stream().anyMatch(leaf -> leaf.getY() > root.getY());
        int topCoreY = trunkCore.stream().mapToInt(BlockPos::getY).max().orElse(rootY);
        Set<BlockPos> branches = new LinkedHashSet<>(logs);
        branches.removeAll(trunkCore);
        // Off-core wood is accepted only as a top continuation. A side beam attached at trunk or
        // canopy height is ownership-ambiguous and therefore rejects the complete component.
        boolean reviewedBranches = branches.stream().allMatch(branch ->
                branch.getY() > topCoreY
                        && hasNearbyNaturalLeaf(branch, leaves));
        boolean treeLikeGeometry = trunkCore.size() * 2L >= logs.size()
                && logs.stream().allMatch(log -> rootBases.stream().anyMatch(base ->
                Math.abs(log.getX() - base.getX()) <= 5
                        && Math.abs(log.getZ() - base.getZ()) <= 5));
        return canopyAboveRoot && reviewedBranches && treeLikeGeometry;
    }

    private static boolean isNaturalSubstrate(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.MUD) || state.is(Blocks.CLAY);
    }

    private static boolean isReviewedRootFootprint(Set<BlockPos> roots) {
        if (roots.size() == 1) {
            return true;
        }
        // Vanilla large trunks use a complete 2x2 base. Two or three adjacent dirt-founded
        // columns are more likely an attached support than a natural trunk, so fail closed.
        if (roots.size() != 4) {
            return false;
        }
        int minX = roots.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int maxX = roots.stream().mapToInt(BlockPos::getX).max().orElse(0);
        int minZ = roots.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        int maxZ = roots.stream().mapToInt(BlockPos::getZ).max().orElse(0);
        if (maxX - minX != 1 || maxZ - minZ != 1) {
            return false;
        }
        return roots.contains(new BlockPos(minX, roots.iterator().next().getY(), minZ))
                && roots.contains(new BlockPos(minX, roots.iterator().next().getY(), maxZ))
                && roots.contains(new BlockPos(maxX, roots.iterator().next().getY(), minZ))
                && roots.contains(new BlockPos(maxX, roots.iterator().next().getY(), maxZ));
    }

    private static boolean hasNearbyNaturalLeaf(BlockPos log, Set<BlockPos> leaves) {
        return leaves.stream().anyMatch(leaf ->
                Math.abs(leaf.getX() - log.getX()) <= LEAF_SCAN_RADIUS
                        && Math.abs(leaf.getY() - log.getY()) <= LEAF_SCAN_RADIUS
                        && Math.abs(leaf.getZ() - log.getZ()) <= LEAF_SCAN_RADIUS);
    }

    private static AABB bounds(Set<BlockPos> logs) {
        int minX = logs.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int minY = logs.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int minZ = logs.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        int maxX = logs.stream().mapToInt(BlockPos::getX).max().orElse(0);
        int maxY = logs.stream().mapToInt(BlockPos::getY).max().orElse(0);
        int maxZ = logs.stream().mapToInt(BlockPos::getZ).max().orElse(0);
        return new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
    }

    private static int fingerprint(ServerLevel world, Set<BlockPos> logs) {
        int hash = 1;
        for (BlockPos position : logs.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList()) {
            hash = 31 * hash + Long.hashCode(position.asLong());
            hash = 31 * hash + BuiltInRegistries.BLOCK
                    .getKey(world.getBlockState(position).getBlock()).hashCode();
        }
        return hash;
    }

    private static TreeSnapshot empty(ServerLevel world, BlockPos seed) {
        AABB bounds = new AABB(seed);
        return new TreeSnapshot(
                new TreeSnapshot.TreeId(world.dimension(), seed, 0),
                seed,
                Set.of(),
                Set.of(),
                bounds,
                false,
                false);
    }
}
