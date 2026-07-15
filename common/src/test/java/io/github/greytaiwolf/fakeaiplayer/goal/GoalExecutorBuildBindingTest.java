package io.github.greytaiwolf.fakeaiplayer.goal;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorBuildBindingTest {
    private static final String DIGEST = "ab".repeat(32);

    @Test
    void onlyProjectionGeneratedBlueprintsWithCompleteBindingsAreTrusted() {
        assertFalse(GoalExecutor.hasTrustedBuildBinding(new Goal.Build("small_hut")));
        assertFalse(GoalExecutor.hasTrustedBuildBinding(new Goal.Build(
                "small_hut", new BlockPos(1, 64, 2), "minecraft:overworld", DIGEST)));
        assertFalse(GoalExecutor.hasTrustedBuildBinding(new Goal.Build(
                "generated_old", new BlockPos(1, 64, 2), "minecraft:overworld")));
        assertTrue(GoalExecutor.hasTrustedBuildBinding(new Goal.Build(
                "generated_confirmed_r0",
                new BlockPos(1, 64, 2),
                "minecraft:overworld",
                DIGEST)));
    }
}
