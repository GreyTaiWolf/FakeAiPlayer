package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class CostModelHeuristicTest {
    @Test
    void jumpDoesNotDoubleCountHorizontalAndVerticalProgress() {
        double estimate = CostModel.heuristic(BlockPos.ZERO, new BlockPos(1, 1, 0));
        assertTrue(estimate <= CostModel.stepCost(MoveType.JUMP_UP, 0));
    }

    @Test
    void dropDoesNotDoubleCountHorizontalAndVerticalProgress() {
        double estimate = CostModel.heuristic(new BlockPos(0, 1, 0), new BlockPos(1, 0, 0));
        assertTrue(estimate <= CostModel.stepCost(MoveType.DROP_DOWN, 1));
    }

    @Test
    void diagonalLowerBoundDoesNotExceedDiagonalEdge() {
        double estimate = CostModel.heuristic(BlockPos.ZERO, new BlockPos(1, 0, 1));
        assertTrue(estimate <= CostModel.stepCost(MoveType.DIAGONAL, 0));
    }
}
