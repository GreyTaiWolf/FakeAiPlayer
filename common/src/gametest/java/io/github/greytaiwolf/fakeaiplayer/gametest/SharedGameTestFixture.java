package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.task.EpisodeMemory;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.tree.PlayerPlacedLogLedger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Loader-neutral fixture used by the Fabric and NeoForge GameTest registration wrappers. */
final class SharedGameTestFixture implements AutoCloseable {
    private final GameTestHelper helper;
    private final Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
    private final List<String> botNames = new ArrayList<>();
    private boolean closed;
    private boolean finished;

    SharedGameTestFixture(GameTestHelper helper) {
        this.helper = helper;
    }

    BlockPos absolute(BlockPos relative) {
        return helper.absolutePos(relative);
    }

    void prepareFlat(int feetY, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos feet = absolute(new BlockPos(x, feetY, z));
                setAbsolute(feet.below(), Blocks.STONE.defaultBlockState());
                setAbsolute(feet, Blocks.AIR.defaultBlockState());
                setAbsolute(feet.above(), Blocks.AIR.defaultBlockState());
            }
        }
        Standability.clearCache();
    }

    void setRelative(BlockPos relative, BlockState state) {
        setAbsolute(absolute(relative), state);
    }

    void setPlayerPlacedRelative(BlockPos relative, BlockState state) {
        BlockPos position = absolute(relative);
        setAbsolute(position, state);
        PlayerPlacedLogLedger.INSTANCE.record(helper.getLevel(), position, state);
    }

    void setAbsolute(BlockPos position, BlockState state) {
        BlockPos immutable = position.immutable();
        originalBlocks.putIfAbsent(immutable, helper.getLevel().getBlockState(immutable));
        helper.getLevel().setBlockAndUpdate(immutable, state);
    }

    AIPlayerEntity spawnBot(String name, BlockPos relativeFeet) {
        if (name == null || name.isBlank() || name.length() > 16) {
            throw new IllegalArgumentException(
                    "GameTest bot name must contain 1-16 characters: " + name);
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        helper.getLevel().getServer(),
                        name,
                        helper.getLevel(),
                        Vec3.atBottomCenterOf(absolute(relativeFeet)),
                        0.0F,
                        0.0F,
                        GameType.SURVIVAL,
                        null)
                .orElseThrow(() -> new IllegalStateException(
                        "Could not spawn shared GameTest bot " + name));
        botNames.add(name);
        EpisodeMemory.INSTANCE.reset(bot.getUUID());
        return bot;
    }

    void checked(Runnable action) {
        if (finished) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException | AssertionError failure) {
            fail(failure);
        }
    }

    void succeed() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            close();
            helper.succeed();
        } catch (RuntimeException cleanupFailure) {
            helper.fail(message(cleanupFailure));
        }
    }

    void fail(Throwable failure) {
        if (finished) {
            return;
        }
        finished = true;
        try {
            close();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        helper.fail(message(failure));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException cleanupFailure = null;

        for (String botName : botNames) {
            try {
                AIPlayerManager.INSTANCE.getByName(botName).ifPresent(bot -> {
                    GoalExecutor.INSTANCE.clear(bot);
                    TaskManager.INSTANCE.resetToIdle(bot);
                    bot.getActionPack().stopAll();
                    EpisodeMemory.INSTANCE.reset(bot.getUUID());
                });
                AIPlayerManager.INSTANCE.despawn(helper.getLevel().getServer(), botName);
            } catch (RuntimeException exception) {
                if (cleanupFailure == null) {
                    cleanupFailure = exception;
                } else {
                    cleanupFailure.addSuppressed(exception);
                }
            }
        }
        botNames.clear();

        if (!originalBlocks.isEmpty()) {
            int minX = originalBlocks.keySet().stream().mapToInt(BlockPos::getX).min().orElse(0);
            int minY = originalBlocks.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
            int minZ = originalBlocks.keySet().stream().mapToInt(BlockPos::getZ).min().orElse(0);
            int maxX = originalBlocks.keySet().stream().mapToInt(BlockPos::getX).max().orElse(0);
            int maxY = originalBlocks.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0);
            int maxZ = originalBlocks.keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0);
            AABB cleanupBounds = new AABB(
                    minX - 2.0D, minY - 2.0D, minZ - 2.0D,
                    maxX + 3.0D, maxY + 3.0D, maxZ + 3.0D);
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, cleanupBounds)
                    .forEach(ItemEntity::discard);
        }

        List<Map.Entry<BlockPos, BlockState>> restore =
                new ArrayList<>(originalBlocks.entrySet());
        Collections.reverse(restore);
        for (Map.Entry<BlockPos, BlockState> entry : restore) {
            try {
                helper.getLevel().setBlockAndUpdate(entry.getKey(), entry.getValue());
            } catch (RuntimeException exception) {
                if (cleanupFailure == null) {
                    cleanupFailure = exception;
                } else {
                    cleanupFailure.addSuppressed(exception);
                }
            }
        }
        originalBlocks.clear();
        Standability.clearCache();

        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
