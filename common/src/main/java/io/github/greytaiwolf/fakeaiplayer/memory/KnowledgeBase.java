package io.github.greytaiwolf.fakeaiplayer.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义知识库(三层记忆模型第 3 层):去情境化的持久知识——资源点/危险区/教训。
 * 知识不是凭空记的:由 {@link EpisodeLog} 的情景流**规则蒸馏**而来(确定性、零 LLM 成本):
 *  - 死亡同区聚类 ≥2 次 → 危险区(单次死亡是偶然,不神经质;再犯才立牌);
 *  - 资源发现去重合并 → 资源点(供规划"附近有矿不下潜"、roam 直奔);
 *  - 目标失败计数 → 教训;同键目标后来成功 → 教训销账。
 * JSON 一 bot 一文件落盘(world/fakeaiplayer/knowledge_<uuid>.json),重启加载——跨会话越用越聪明。
 */
public final class KnowledgeBase {
    public static final KnowledgeBase INSTANCE = new KnowledgeBase();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int RESOURCE_CAP = 64;
    private static final int DANGER_MERGE_DIST = 16;   // 死亡点与既有危险区中心距 ≤此 → 并入(hits++)
    private static final int DANGER_BASE_RADIUS = 12;

    public record ResourcePoint(String dimension, String blockId, int x, int y, int z, long learnedTick) {
        public ResourcePoint {
            dimension = cleanDimension(dimension);
            blockId = blockId == null ? "" : blockId;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public record DangerZone(String dimension, int x, int y, int z,
                             int radius, String cause, int hits, long lastTick) {
        public DangerZone {
            dimension = cleanDimension(dimension);
            cause = cause == null ? "" : cause;
        }

        public BlockPos center() {
            return new BlockPos(x, y, z);
        }
    }

    public record Lesson(String key, String reason, int count, long lastTick) {
    }

    static final class BotKnowledge {
        List<ResourcePoint> resources = new ArrayList<>();
        List<DangerZone> dangers = new ArrayList<>();
        Map<String, Lesson> lessons = new HashMap<>();
    }

    private final Map<UUID, BotKnowledge> knowledge = new ConcurrentHashMap<>();
    private MinecraftServer server;

    private KnowledgeBase() {
    }

    private BotKnowledge of(UUID botId) {
        return knowledge.computeIfAbsent(botId, this::loadOrEmpty);
    }

    // ==================== 蒸馏(由 EpisodeLog.record 触发) ====================

    public void distill(AIPlayerEntity bot, EpisodeLog.EpisodeEvent event, List<EpisodeLog.EpisodeEvent> all) {
        this.server = bot.getServer();
        UUID botId = bot.getUUID();
        BotKnowledge k = of(botId);
        boolean dirty = false;
        switch (event.type()) {
            case DEATH -> dirty = distillDeath(k, event, all);
            case RESOURCE_FOUND -> dirty = distillResource(k, event);
            case GOAL_FAILED -> {
                Lesson old = k.lessons.get(event.detail());
                k.lessons.put(event.detail(), new Lesson(event.detail(),
                        event.detail(), old == null ? 1 : old.count() + 1, event.gameTick()));
                dirty = true;
            }
            case GOAL_DONE -> dirty = k.lessons.remove(event.detail()) != null; // 后来成功了 → 教训销账
            case THREAT -> {
            }
        }
        if (dirty) {
            save(botId, k);
        }
    }

    private boolean distillDeath(BotKnowledge k, EpisodeLog.EpisodeEvent event, List<EpisodeLog.EpisodeEvent> all) {
        BlockPos pos = event.pos();
        // 并入既有危险区:hits++ 扩半径(封顶 32)
        for (int i = 0; i < k.dangers.size(); i++) {
            DangerZone z = k.dangers.get(i);
            if (sameDimension(z.dimension(), event.dimension())
                    && z.center().closerThan(pos, DANGER_MERGE_DIST)) {
                k.dangers.set(i, new DangerZone(z.dimension(), z.x(), z.y(), z.z(),
                        Math.min(32, (z.hits() + 1) * 8 + 4), z.cause(), z.hits() + 1, event.gameTick()));
                return true;
            }
        }
        // 新区成立条件:历史死亡里还有一次落在 16 格内(同区两次死才立牌——一次是偶然)
        long priorNearby = all.stream()
                .filter(e -> e.type() == EpisodeLog.Type.DEATH && e != event)
                .filter(e -> sameDimension(e.dimension(), event.dimension()))
                .filter(e -> e.pos().closerThan(pos, DANGER_MERGE_DIST))
                .count();
        if (priorNearby >= 1) {
            k.dangers.add(new DangerZone(event.dimension(), pos.getX(), pos.getY(), pos.getZ(),
                    DANGER_BASE_RADIUS, event.detail(), 2, event.gameTick()));
            return true;
        }
        return false;
    }

    private boolean distillResource(BotKnowledge k, EpisodeLog.EpisodeEvent event) {
        for (ResourcePoint r : k.resources) {
            if (sameDimension(r.dimension(), event.dimension())
                    && r.blockId().equals(event.detail())
                    && r.pos().closerThan(event.pos(), 8)) {
                return false; // 同种资源 8 格内已记过 → 去重
            }
        }
        if (k.resources.size() >= RESOURCE_CAP) {
            k.resources.remove(0); // 满了删最旧
        }
        k.resources.add(new ResourcePoint(event.dimension(), event.detail(),
                event.pos().getX(), event.pos().getY(), event.pos().getZ(), event.gameTick()));
        return true;
    }

    // ==================== 查询(消费口) ====================

    /** 最近的已知资源点(按 blockId,跳过危险区内的);到了发现没了请调 invalidateResource。 */
    public Optional<ResourcePoint> nearestResource(UUID botId,
                                                   String dimension,
                                                   String blockId,
                                                   BlockPos from,
                                                   double maxDist) {
        return of(botId).resources.stream()
                .filter(r -> sameDimension(r.dimension(), dimension))
                .filter(r -> r.blockId().equals(blockId))
                .filter(r -> !isDanger(botId, dimension, r.pos()))
                .filter(r -> r.pos().closerThan(from, maxDist))
                .min(java.util.Comparator.comparingDouble(r -> r.pos().distSqr(from)));
    }

    /** 富矿区(P1 消费口):同 blockId 资源点 ≥minPoints 个聚在 radius 内 → 返回簇心。
     * 运行时聚类(零新 schema):prospect 兜底用——64 格内扫不到矿时,直奔"以前总在那挖到"的富区。 */
    public Optional<BlockPos> richZoneNear(UUID botId,
                                           String dimension,
                                           String blockId,
                                           BlockPos from,
                                           double maxDist,
                                           int minPoints,
                                           double radius) {
        List<ResourcePoint> mine = of(botId).resources.stream()
                .filter(r -> sameDimension(r.dimension(), dimension))
                .filter(r -> r.blockId().equals(blockId))
                .filter(r -> r.pos().closerThan(from, maxDist))
                .toList();
        Optional<BlockPos> best = Optional.empty();
        double bestDist = Double.MAX_VALUE;
        for (ResourcePoint center : mine) {
            long n = mine.stream().filter(r -> r.pos().closerThan(center.pos(), radius)).count();
            if (n >= minPoints && !isDanger(botId, dimension, center.pos())) {
                double d = center.pos().distSqr(from);
                if (d < bestDist) {
                    bestDist = d;
                    best = Optional.of(center.pos());
                }
            }
        }
        return best;
    }

    public boolean isDanger(UUID botId, String dimension, BlockPos pos) {
        for (DangerZone z : of(botId).dangers) {
            if (sameDimension(z.dimension(), dimension)
                    && z.center().closerThan(pos, z.radius())) {
                return true;
            }
        }
        return false;
    }

    /** 测试隔离:清掉该 bot 的全部知识。套件互染实锤:前 9 个挖矿场景的资源点让 richZoneNear
     * 把 geo_rich 的富区导向拐去早挖空的废区(套跑 FAIL 单跑 PASS)。真实使用不走此口,知识照常持久。 */
    public void resetFor(UUID botId) {
        BotKnowledge k = of(botId);
        k.resources.clear();
        k.dangers.clear();
        k.lessons.clear();
        save(botId, k);
    }

    public void invalidateResource(UUID botId, String dimension, BlockPos pos) {
        BotKnowledge k = of(botId);
        if (k.resources.removeIf(r -> sameDimension(r.dimension(), dimension)
                && r.pos().closerThan(pos, 4))) {
            save(botId, k);
        }
    }

    public int resourceCount(UUID botId) {
        return of(botId).resources.size();
    }

    public int dangerCount(UUID botId) {
        return of(botId).dangers.size();
    }

    // ==================== 落盘 ====================

    public void attachServer(MinecraftServer server) {
        knowledge.clear();
        this.server = server;
    }

    public void detachServer() {
        knowledge.clear();
        server = null;
    }

    public void forget(UUID botId) {
        knowledge.remove(botId);
    }

    private Path fileFor(UUID botId) {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("fakeaiplayer");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir.resolve("knowledge_" + botId + ".json");
    }

    private void save(UUID botId, BotKnowledge k) {
        if (server == null) {
            return;
        }
        try (Writer w = Files.newBufferedWriter(fileFor(botId))) {
            GSON.toJson(k, w);
        } catch (IOException e) {
            BotLog.error("knowledge_save_failed", e, "bot", botId);
        }
    }

    private BotKnowledge loadOrEmpty(UUID botId) {
        if (server == null) {
            return new BotKnowledge();
        }
        Path f = fileFor(botId);
        if (!Files.exists(f)) {
            return new BotKnowledge();
        }
        try (Reader r = Files.newBufferedReader(f)) {
            BotKnowledge k = GSON.fromJson(r, new TypeToken<BotKnowledge>() {
            }.getType());
            int resourcesBefore = k == null || k.resources == null ? 0 : k.resources.size();
            int dangersBefore = k == null || k.dangers == null ? 0 : k.dangers.size();
            BotKnowledge normalized = normalize(k);
            int droppedResources = resourcesBefore - normalized.resources.size();
            int droppedDangers = dangersBefore - normalized.dangers.size();
            if (droppedResources > 0 || droppedDangers > 0) {
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE,
                        null,
                        "knowledge_legacy_dimension_entries_dropped",
                        "bot", botId,
                        "resources", droppedResources,
                        "dangers", droppedDangers);
            }
            return normalized;
        } catch (IOException | RuntimeException e) {
            BotLog.error("knowledge_load_failed", e, "bot", botId);
            return new BotKnowledge();
        }
    }

    private static BotKnowledge normalize(BotKnowledge loaded) {
        BotKnowledge normalized = loaded == null ? new BotKnowledge() : loaded;
        if (normalized.resources == null) {
            normalized.resources = new ArrayList<>();
        } else {
            normalized.resources.removeIf(resource -> resource == null
                    || cleanDimension(resource.dimension()).isEmpty());
        }
        if (normalized.dangers == null) {
            normalized.dangers = new ArrayList<>();
        } else {
            normalized.dangers.removeIf(danger -> danger == null
                    || cleanDimension(danger.dimension()).isEmpty());
        }
        if (normalized.lessons == null) {
            normalized.lessons = new HashMap<>();
        }
        return normalized;
    }

    private static String cleanDimension(String dimension) {
        return dimension == null ? "" : dimension.trim();
    }

    private static boolean sameDimension(String first, String second) {
        String a = cleanDimension(first);
        String b = cleanDimension(second);
        return !a.isEmpty() && a.equals(b);
    }
}
