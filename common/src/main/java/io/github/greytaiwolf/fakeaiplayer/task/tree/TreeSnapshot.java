package io.github.greytaiwolf.fakeaiplayer.task.tree;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Immutable, bounded observation of one connected natural tree. */
public record TreeSnapshot(
        TreeId id,
        BlockPos seed,
        Set<BlockPos> logs,
        Set<BlockPos> leafEvidence,
        AABB bounds,
        boolean natural,
        boolean truncated
) {
    public TreeSnapshot {
        seed = seed.immutable();
        logs = immutablePositions(logs);
        leafEvidence = immutablePositions(leafEvidence);
    }

    private static Set<BlockPos> immutablePositions(Set<BlockPos> positions) {
        return positions.stream()
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record TreeId(ResourceKey<Level> dimension, BlockPos root, int fingerprint) {
        public TreeId {
            root = root.immutable();
        }
    }
}
