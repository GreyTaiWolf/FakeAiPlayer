package io.github.greytaiwolf.fakeaiplayer.manager;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogFields;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore;
import io.github.greytaiwolf.fakeaiplayer.network.FakeClientConnection;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.persist.BotPersistence;
import io.github.greytaiwolf.fakeaiplayer.persist.BotRecord;
import io.github.greytaiwolf.fakeaiplayer.runtime.RuntimeLifecycleCoordinator;
import io.github.greytaiwolf.fakeaiplayer.util.OfflineProfileFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AIPlayerManager {
    public static final AIPlayerManager INSTANCE = new AIPlayerManager();

    private final Map<UUID, AIPlayerEntity> players = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final Map<UUID, String> roles = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> ownerIndex = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> botOwners = new ConcurrentHashMap<>();

    private AIPlayerManager() {
    }

    /**
     * SAFE-DEAD:bot 死亡(hp<=0)后停在原地无限收到 evade,不会自动重生(假玩家无客户端发重生包,
     * ServerPlayerEntity 死后也不会被移除)。这里满血复活并传送到地表安全点,清空残留死亡状态。
     * 返回 true=已复活。
     */
    public boolean respawnDeadBot(AIPlayerEntity bot) {
        ServerLevel world = bot.serverLevel();
        // 情景记忆:死亡入流(用死亡位置=当前位置,在传送地表之前记)。蒸馏规则:同区两死 → 危险区。
        io.github.greytaiwolf.fakeaiplayer.memory.EpisodeLog.INSTANCE.record(bot,
                io.github.greytaiwolf.fakeaiplayer.memory.EpisodeLog.Type.DEATH, bot.blockPosition(),
                bot.getLastDamageSource() == null ? "unknown" : bot.getLastDamageSource().getMsgId());
        RuntimeLifecycleCoordinator.INSTANCE.onBotDeath(bot);
        boolean enhancedRespawn = CapabilityRuntime.decide(
                bot, PrivilegedCapability.EMERGENCY_TELEPORT, "death_surface_respawn").allowed();
        ServerLevel respawnWorld;
        Vec3 respawnPos;
        String respawnStrategy;
        if (enhancedRespawn) {
            BlockPos surface = world.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bot.blockPosition());
            respawnWorld = world;
            respawnPos = Vec3.atBottomCenterOf(surface);
            respawnStrategy = "operator_death_column_surface";
        } else {
            // Fake players cannot send the vanilla respawn packet. In strict mode this adapter uses
            // the world's normal spawn area instead of teleporting to the death column's surface.
            respawnWorld = bot.getServer().overworld();
            respawnPos = safeSpawnPosition(
                    respawnWorld, Vec3.atBottomCenterOf(respawnWorld.getSharedSpawnPos()),
                    bot.getGameProfile().getName());
            respawnStrategy = "strict_world_spawn";
        }
        bot.setHealth(20.0F);
        bot.deathTime = 0;
        bot.getFoodData().setFoodLevel(20);
        bot.teleportTo(respawnWorld, respawnPos.x, respawnPos.y, respawnPos.z,
                Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        bot.clearFire();
        BotLog.danger(bot, "bot_respawned_after_death",
                "pos", LogFields.pos(bot.blockPosition()),
                "strategy", respawnStrategy);
        return true;
    }

    public Optional<AIPlayerEntity> spawn(MinecraftServer server,
                                          String name,
                                          ServerLevel world,
                                          Vec3 pos,
                                          float yaw,
                                          float pitch,
                                          GameType gameMode) {
        return spawn(server, name, world, pos, yaw, pitch, gameMode, null);
    }

    public Optional<AIPlayerEntity> spawn(MinecraftServer server,
                                          String name,
                                          ServerLevel world,
                                          Vec3 pos,
                                          float yaw,
                                          float pitch,
                                          GameType gameMode,
                                          UUID ownerUuid) {
        String normalizedName = normalizeName(name);
        if (nameIndex.containsKey(normalizedName) || server.getPlayerList().getPlayerByName(name) != null) {
            return Optional.empty();
        }
        if (ownerUuid != null && botOf(ownerUuid).isPresent()) {
            return Optional.empty();
        }

        GameProfile profile = OfflineProfileFactory.create(name);
        ClientInformation options = ClientInformation.createDefault();
        AIPlayerEntity player = new AIPlayerEntity(server, world, profile, options);
        FakeClientConnection connection = new FakeClientConnection(PacketFlow.SERVERBOUND);
        CommonListenerCookie clientData = new CommonListenerCookie(profile, 0, options, false);
        Vec3 safePos = safeSpawnPosition(world, pos, name);

        server.getPlayerList().placeNewPlayer(connection, player, clientData);
        player.teleportTo(world, safePos.x, safePos.y, safePos.z, Collections.emptySet(), yaw, pitch, true);
        player.setHealth(20.0F);
        player.reviveForAIBotSpawn();
        AttributeInstance stepHeight = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(0.6D);
        }
        // AI 助手固定生存模式:创造模式破方块不掉落、冒险模式禁止破坏/放置,都会让采集/建造失效。
        // 故忽略传入的 gameMode(可能是召唤者的创造,或旧存档恢复的 creative),一律 SURVIVAL。
        GameType effectiveMode = GameType.SURVIVAL;
        player.gameMode.changeGameModeForPlayer(effectiveMode);

        players.put(player.getUUID(), player);
        nameIndex.put(normalizedName, player.getUUID());
        roles.put(player.getUUID(), "worker");
        if (ownerUuid != null) {
            ownerIndex.put(ownerUuid, player.getUUID());
            botOwners.put(player.getUUID(), ownerUuid);
        }
        BotLog.lifecycle(player, "bot_spawned", "pos", LogFields.pos(player.blockPosition()), "mode", effectiveMode.getName());
        BotPersistence.INSTANCE.markDirty(server);
        return Optional.of(player);
    }

    public Optional<AIPlayerEntity> respawnFromRecord(MinecraftServer server, BotRecord record) {
        RestoreTarget target = restoreTarget(server, record);
        GameType gameMode = GameType.SURVIVAL;  // AI 助手一律生存,忽略旧存档可能存的 creative
        Optional<AIPlayerEntity> spawned = spawn(
                server,
                record.name(),
                target.world(),
                target.pos(),
                record.yaw(),
                record.pitch(),
                gameMode,
                parseUuid(record.ownerUuid()));
        spawned.ifPresent(bot -> {
            BotPersistence.applyInventory(bot, record.inventoryNbt());
            setRole(bot, record.role());
            BotMemoryStore.INSTANCE.loadString(bot.getUUID(), record.memoryNbt());
            bot.setHealth(Math.max(1.0F, Math.min(record.health(), bot.getMaxHealth())));
            bot.getFoodData().setFoodLevel(Math.max(0, Math.min(20, record.hunger())));
            BotLog.lifecycle(bot, "bot_restored",
                    "pos", LogFields.pos(bot.blockPosition()),
                    "mode", gameMode.getName(),
                    "dimension", bot.level().dimension().location(),
                    "fallback", target.fallback());
        });
        return spawned;
    }

    public boolean despawn(MinecraftServer server, String name) {
        Optional<AIPlayerEntity> player = getByName(name);
        if (player.isEmpty()) {
            return false;
        }

        AIPlayerEntity entity = player.get();
        RuntimeLifecycleCoordinator.INSTANCE.deleteBot(entity);
        players.remove(entity.getUUID());
        nameIndex.remove(normalizeName(name));
        roles.remove(entity.getUUID());
        clearOwner(entity.getUUID());
        disconnect(server, entity, "FakeAiPlayer despawn");
        BotLog.lifecycle(entity, "bot_despawned", "reason", "command_or_shutdown");
        BotPersistence.INSTANCE.markDirty(server);
        return true;
    }

    public Optional<AIPlayerEntity> getByName(String name) {
        UUID uuid = nameIndex.get(normalizeName(name));
        return uuid == null ? Optional.empty() : Optional.ofNullable(players.get(uuid));
    }

    public Optional<AIPlayerEntity> getByUuid(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    public Optional<AIPlayerEntity> botOf(UUID ownerUuid) {
        UUID botUuid = ownerIndex.get(ownerUuid);
        if (botUuid == null) {
            return Optional.empty();
        }
        AIPlayerEntity bot = players.get(botUuid);
        if (bot == null) {
            ownerIndex.remove(ownerUuid);
            return Optional.empty();
        }
        return Optional.of(bot);
    }

    public Optional<UUID> ownerOf(AIPlayerEntity bot) {
        return Optional.ofNullable(botOwners.get(bot.getUUID()));
    }

    public Collection<AIPlayerEntity> all() {
        return Collections.unmodifiableCollection(players.values());
    }

    public void setRole(AIPlayerEntity bot, String role) {
        roles.put(bot.getUUID(), normalizeRole(role));
        BotLog.lifecycle(bot, "bot_role_set", "role", role(bot));
    }

    public String role(AIPlayerEntity bot) {
        return roles.getOrDefault(bot.getUUID(), "worker");
    }

    public java.util.Set<String> roles(AIPlayerEntity bot) {
        String role = role(bot);
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        result.add("worker");
        result.add(role);
        return java.util.Set.copyOf(result);
    }

    public void onServerStopping(MinecraftServer server) {
        int count = players.size();
        for (AIPlayerEntity player : players.values().toArray(AIPlayerEntity[]::new)) {
            RuntimeLifecycleCoordinator.INSTANCE.unloadBot(player);
            disconnect(server, player, "FakeAiPlayer server unload");
        }
        players.clear();
        nameIndex.clear();
        roles.clear();
        ownerIndex.clear();
        botOwners.clear();
        BotLog.lifecycle("all_bots_cleared", "count", count);
    }

    private void clearOwner(UUID botUuid) {
        UUID ownerUuid = botOwners.remove(botUuid);
        if (ownerUuid != null) {
            ownerIndex.remove(ownerUuid, botUuid);
        }
    }

    private static void disconnect(MinecraftServer server, AIPlayerEntity entity, String reason) {
        if (entity.connection != null) {
            entity.connection.disconnect(new DisconnectionDetails(Component.literal(reason)));
        } else {
            server.getPlayerList().remove(entity);
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static RestoreTarget restoreTarget(MinecraftServer server, BotRecord record) {
        ResourceKey<Level> worldKey;
        try {
            worldKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(record.dimension()));
        } catch (RuntimeException exception) {
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, null, "bot_restore_dimension_invalid",
                    "name", record.name(), "dimension", record.dimension());
            return overworldSpawn(server);
        }
        ServerLevel world = server.getLevel(worldKey);
        if (world == null) {
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, null, "bot_restore_world_missing",
                    "name", record.name(), "dimension", record.dimension());
            return overworldSpawn(server);
        }
        return new RestoreTarget(world, new Vec3(record.x(), record.y(), record.z()), false);
    }

    private static RestoreTarget overworldSpawn(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return new RestoreTarget(overworld, Vec3.atBottomCenterOf(overworld.getSharedSpawnPos()), true);
    }

    private static Vec3 safeSpawnPosition(ServerLevel world, Vec3 requested, String name) {
        BlockPos requestedBlock = BlockPos.containing(requested);
        Standability.clearCache();
        if (Standability.isStandable(world, requestedBlock)) {
            return requested;
        }
        Optional<BlockPos> safe = Standability.findNearestStandable(world, requestedBlock, 8, 128, 32);
        if (safe.isEmpty()) {
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, null, "bot_spawn_position_unsafe",
                    "name", name, "requested", LogFields.pos(requestedBlock));
            return requested;
        }
        BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, null, "bot_spawn_position_snapped",
                "name", name,
                "from", LogFields.pos(requestedBlock),
                "to", LogFields.pos(safe.get()));
        return Vec3.atBottomCenterOf(safe.get());
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "worker";
        }
        return role.trim().toLowerCase(Locale.ROOT);
    }

    private record RestoreTarget(ServerLevel world, Vec3 pos, boolean fallback) {
    }
}
