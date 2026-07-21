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

    @Test
    void passiveProgressNeverSkipsVerticalWorldEdits() {
        BlockPos current = new BlockPos(4, 70, 4);

        assertFalse(PathExecutor.canPrecommitMovement(
                MoveType.PILLAR_UP, current, current.above(), true));
        assertFalse(PathExecutor.canPrecommitMovement(
                MoveType.DIG_THROUGH, current, current.below(), true));
        assertFalse(PathExecutor.canPrecommitMovement(
                MoveType.JUMP_UP, current, current.above(), true));
        assertTrue(PathExecutor.canPrecommitMovement(
                MoveType.JUMP_UP, current.above(), current.above(), true));
        assertTrue(PathExecutor.canPrecommitMovement(
                MoveType.WALK, current, current, true));
    }
}
