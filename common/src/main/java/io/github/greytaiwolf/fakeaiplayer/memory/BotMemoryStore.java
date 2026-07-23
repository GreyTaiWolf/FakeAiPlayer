package io.github.greytaiwolf.fakeaiplayer.memory;

import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

public final class BotMemoryStore {
    public static final BotMemoryStore INSTANCE = new BotMemoryStore();

    private final Map<UUID, BotMemory> memories = new ConcurrentHashMap<>();

    private BotMemoryStore() {
    }

    public BotMemory of(UUID botId) {
        return memories.computeIfAbsent(botId, ignored -> new BotMemory());
    }

    public String saveString(UUID botId) {
        return of(botId).toNbt().toString();
    }

    /**
     * Restores one encoded memory payload without silently accepting a lossy projection.
     *
     * @return true when the payload is blank (legacy empty memory) or was restored exactly.
     */
    public boolean loadString(UUID botId, String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return true;
        }
        try {
            CompoundTag root = TagParser.parseTag(snbt);
            if (!persistedRootValid(root)) {
                BotLog.error("bot_memory_load_failed", null,
                        "bot_uuid", botId, "reason", "invalid_persisted_shape");
                return false;
            }
            BotMemory memory = of(botId);
            memory.load(root);
            if (!root.equals(memory.toNbt())) {
                BotLog.error("bot_memory_load_failed", null,
                        "bot_uuid", botId, "reason", "non_exact_round_trip");
                return false;
            }
            BotLog.comm(null, "bot_memory_loaded", "bot_uuid", botId);
            return true;
        } catch (Exception exception) {
            BotLog.error("bot_memory_load_failed", exception, "bot_uuid", botId);
            return false;
        }
    }

    /** Side-effect-free admission check used before a persisted Bot shell is spawned. */
    public boolean persistedPayloadValid(String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return true;
        }
        try {
            return persistedRootValid(TagParser.parseTag(snbt));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean persistedRootValid(CompoundTag root) {
        if (root == null || !root.getAllKeys().equals(Set.of(
                "facts", "places", "goalTitle", "goalCursor", "goalSteps"))
                || !root.contains("facts", Tag.TAG_COMPOUND)
                || !root.contains("places", Tag.TAG_COMPOUND)
                || !root.contains("goalTitle", Tag.TAG_STRING)
                || !root.contains("goalCursor", Tag.TAG_INT)
                || !root.contains("goalSteps", Tag.TAG_LIST)) {
            return false;
        }
        CompoundTag facts = root.getCompound("facts");
        for (String key : facts.getAllKeys()) {
            if (!facts.contains(key, Tag.TAG_STRING)) {
                return false;
            }
        }
        CompoundTag places = root.getCompound("places");
        for (String key : places.getAllKeys()) {
            if (!places.contains(key, Tag.TAG_COMPOUND)) {
                return false;
            }
            CompoundTag place = places.getCompound(key);
            if (!place.getAllKeys().equals(Set.of("dimension", "x", "y", "z"))
                    || !place.contains("dimension", Tag.TAG_STRING)
                    || !place.contains("x", Tag.TAG_INT)
                    || !place.contains("y", Tag.TAG_INT)
                    || !place.contains("z", Tag.TAG_INT)) {
                return false;
            }
        }
        if (!(root.get("goalSteps") instanceof ListTag steps)) {
            return false;
        }
        return (steps.isEmpty() || steps.getElementType() == Tag.TAG_STRING)
                && root.getInt("goalCursor") >= 0
                && root.getInt("goalCursor") <= steps.size();
    }

    public void remove(UUID botId) {
        memories.remove(botId);
    }

    public void clear() {
        memories.clear();
    }
}
