package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.pathfinding.MoveType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EscapeRouteExecutorPolicyTest {
    @Test
    void emergencyEscapeNeverExecutesWorldEditingSteps() {
        assertTrue(EscapeRouteExecutor.supports(MoveType.WALK));
        assertTrue(EscapeRouteExecutor.supports(MoveType.DIAGONAL));
        assertTrue(EscapeRouteExecutor.supports(MoveType.JUMP_UP));
        assertTrue(EscapeRouteExecutor.supports(MoveType.DROP_DOWN));
        assertFalse(EscapeRouteExecutor.supports(MoveType.DIG_THROUGH));
        assertFalse(EscapeRouteExecutor.supports(MoveType.PILLAR_UP));
    }
}
