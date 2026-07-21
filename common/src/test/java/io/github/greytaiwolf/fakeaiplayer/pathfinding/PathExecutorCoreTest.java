package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PathExecutorCoreTest {
    @Test
    void adjacentPathCellIsNotReached() {
        BlockPos current = new BlockPos(10, 64, 10);

        assertFalse(PathExecutor.hasReachedNode(current, current.east()));
        assertFalse(PathExecutor.hasReachedNode(current, current.north()));
    }

    @Test
    void requestedColumnIsReachedWithReviewedVerticalTolerance() {
        BlockPos target = new BlockPos(10, 64, 10);

        assertTrue(PathExecutor.hasReachedNode(target, target));
        assertTrue(PathExecutor.hasReachedNode(target.below(), target),
                "stairs and bottom slabs may report the same reviewed column one block lower");
        assertFalse(PathExecutor.hasReachedNode(target.below(2), target),
                "vertical tolerance must not turn a multi-block drop into arrival");
    }
}
