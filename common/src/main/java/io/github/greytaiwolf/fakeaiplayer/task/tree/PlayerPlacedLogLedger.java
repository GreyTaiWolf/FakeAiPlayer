package io.github.greytaiwolf.fakeaiplayer.task.tree;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogCategory;
import io.github.greytaiwolf.fakeaiplayer.persist.AtomicSnapshotFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Persistent positive evidence that a log position was placed through a player BlockItem action.
 *
 * <p>This is deliberately not a claim that every unrecorded block is natural: old saves, commands,
 * world editors, pistons and other mods can produce unknown provenance. The tree detector combines
 * this hard negative signal with conservative geometry and explicit documented limits. P1 keeps
 * evidence monotonic: destroying a tracked block does not erase its coordinate, because no loader
 * lifecycle event proves every relevant chunk save committed atomically with this file. A stale
 * coordinate can conservatively skip future work; losing evidence could destroy a player build.</p>
 */
public final class PlayerPlacedLogLedger {
    public static final PlayerPlacedLogLedger INSTANCE = new PlayerPlacedLogLedger();
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_LOADED_POSITIONS = 1_000_000;
    private static final long MAX_LEDGER_BYTES = 64L * 1024L * 1024L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, Set<Long>> positionsByDimension = new HashMap<>();
    private Path file;
    private Path sessionMarker;
    private boolean dirty;
    private boolean healthy;
    private boolean loadIntegrityTrusted;
    private boolean sessionMarkerActive;

    public enum BaselineInstallResult {
        INSTALLED,
        ALREADY_TRUSTED,
        EXISTING_STATE_REQUIRES_OFFLINE_REVIEW,
        WRITE_FAILED
    }

    private PlayerPlacedLogLedger() {
    }

    public synchronized void onServerStarted(MinecraftServer server) {
        positionsByDimension.clear();
        file = server.getWorldPath(LevelResource.ROOT)
                .resolve("fakeaiplayer")
                .resolve("player_placed_logs.json");
        sessionMarker = file.resolveSibling("player_placed_logs.active");
        dirty = false;
        healthy = false;
        loadIntegrityTrusted = false;
        sessionMarkerActive = false;
        if (!Files.exists(file)) {
            // Time=0 is not proof of a new world: an imported/reset save may already contain
            // player structures. Missing provenance is therefore always an explicit trust
            // decision, never an automatic first-run migration.
            BotLog.warn(LogCategory.SECURITY, null,
                    "player_placed_log_ledger_baseline_required",
                    "file", file.toString(),
                    "effect", "automatic_whole_tree_felling_disabled");
            return;
        }
        if (!Files.isRegularFile(file)) {
            healthy = false;
            loadIntegrityTrusted = false;
            BotLog.warn(LogCategory.SECURITY, null,
                    "player_placed_log_ledger_not_regular_file",
                    "file", file.toString(),
                    "effect", "automatic_whole_tree_felling_disabled");
            return;
        }
        try {
            if (Files.size(file) > MAX_LEDGER_BYTES) {
                throw new IOException("ledger_file_too_large");
            }
            Snapshot snapshot = GSON.fromJson(Files.readString(file), Snapshot.class);
            if (snapshot == null
                    || snapshot.schemaVersion != SCHEMA_VERSION
                    || snapshot.dimensions == null) {
                throw new IOException("unsupported_or_missing_schema");
            }
            int loaded = 0;
            for (Map.Entry<String, List<Long>> entry : snapshot.dimensions.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IOException("invalid_dimension_entry");
                }
                Set<Long> positions = new HashSet<>();
                for (Long packed : entry.getValue()) {
                    if (packed == null || ++loaded > MAX_LOADED_POSITIONS) {
                        throw new IOException("invalid_or_excessive_position_count");
                    }
                    positions.add(packed);
                }
                if (!positions.isEmpty()) {
                    positionsByDimension.put(entry.getKey(), positions);
                }
            }
            loadIntegrityTrusted = true;
            if (beginSessionMarker()) {
                healthy = true;
            }
        } catch (IOException | RuntimeException exception) {
            positionsByDimension.clear();
            healthy = false;
            loadIntegrityTrusted = false;
            BotLog.error("player_placed_log_ledger_load_failed", exception,
                    "file", file.toString());
        }
    }

    public synchronized void onServerStopping(MinecraftServer server) {
        // Persist an early checkpoint, but deliberately retain the active marker until the
        // loader's terminal stopped event and a successful final ledger flush.
        flush();
    }

    /** Called from the loader's terminal stopped event after orderly shutdown callbacks. */
    public synchronized void onServerStopped(MinecraftServer server) {
        // Capture any tracked additions performed by orderly shutdown hooks after STOPPING, then
        // make this ledger session clean only after its own final flush succeeds.
        flush();
        closeSessionMarkerAfterCleanFlush();
        positionsByDimension.clear();
        file = null;
        sessionMarker = null;
        dirty = false;
        healthy = false;
        loadIntegrityTrusted = false;
        sessionMarkerActive = false;
    }

    /** Flushes at most once per server tick even if a building action places many blocks. */
    public synchronized void tick(MinecraftServer server) {
        if (dirty) {
            flush();
        }
    }

    public synchronized void record(
            ServerLevel world, BlockPos position, BlockState placedState) {
        if (!loadIntegrityTrusted
                || !sessionMarkerActive
                || !placedState.is(BlockTags.LOGS)) {
            return;
        }
        String dimension = dimension(world);
        long packedPosition = position.asLong();
        if (positionsByDimension
                .computeIfAbsent(dimension, ignored -> new HashSet<>())
                .add(packedPosition)) {
            dirty = true;
        }
    }

    public synchronized boolean isKnownPlayerPlaced(
            ServerLevel world, BlockPos position) {
        Set<Long> positions = positionsByDimension.get(dimension(world));
        return positions != null && positions.contains(position.asLong());
    }

    /** Corrupt or unwritable provenance must disable automatic whole-tree destruction. */
    public synchronized boolean allowsAutomaticFelling() {
        return healthy;
    }

    /**
     * Log placement through a guarded player/Bot path is refused while positive evidence cannot
     * be durably tracked. Non-log placement is unrelated to this ledger and remains available.
     */
    public synchronized boolean allowsTrackedPlacement(BlockState state) {
        return !state.is(BlockTags.LOGS) || healthy;
    }

    /**
     * Explicit administrator acknowledgement for a world whose existing structures were audited.
     * Existing/corrupt files and unclean-session markers are never overwritten by this operation.
     */
    public synchronized BaselineInstallResult installAuditedEmptyBaseline(
            MinecraftServer server) {
        Path expectedFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("fakeaiplayer")
                .resolve("player_placed_logs.json");
        if (file == null || !file.equals(expectedFile)) {
            return BaselineInstallResult.WRITE_FAILED;
        }
        if (healthy) {
            return BaselineInstallResult.ALREADY_TRUSTED;
        }
        if (Files.exists(file)
                || sessionMarker == null
                || Files.exists(sessionMarker)) {
            return BaselineInstallResult.EXISTING_STATE_REQUIRES_OFFLINE_REVIEW;
        }

        positionsByDimension.clear();
        dirty = false;
        loadIntegrityTrusted = true;
        if (!beginSessionMarker()) {
            loadIntegrityTrusted = false;
            return BaselineInstallResult.WRITE_FAILED;
        }
        dirty = true;
        flush();
        return healthy
                ? BaselineInstallResult.INSTALLED
                : BaselineInstallResult.WRITE_FAILED;
    }

    private void flush() {
        if (!loadIntegrityTrusted
                || !sessionMarkerActive
                || !dirty
                || file == null) {
            return;
        }
        Map<String, List<Long>> serialized = new TreeMap<>();
        for (Map.Entry<String, Set<Long>> entry : positionsByDimension.entrySet()) {
            List<Long> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Long::compareTo);
            serialized.put(entry.getKey(), List.copyOf(sorted));
        }
        try {
            AtomicSnapshotFile.write(file, GSON.toJson(new Snapshot(
                    SCHEMA_VERSION, serialized)));
            dirty = false;
            // A transient write error may recover. A corrupt/incompatible load may not: in that
            // case loadIntegrityTrusted stays false for the complete server lifetime and this
            // method must not replace the forensic file with an incomplete snapshot.
            healthy = loadIntegrityTrusted && sessionMarkerActive;
        } catch (IOException exception) {
            healthy = false;
            BotLog.error("player_placed_log_ledger_save_failed", exception,
                    "file", file.toString());
        }
    }

    /**
     * The marker is created before the server can accept player actions and removed only after a
     * clean final ledger flush. A crash or persistent write failure therefore leaves durable
     * evidence that the prior snapshot may lag chunk data; the next boot refuses whole-tree work.
     */
    private boolean beginSessionMarker() {
        if (sessionMarker == null) {
            return false;
        }
        if (Files.exists(sessionMarker)) {
            loadIntegrityTrusted = false;
            BotLog.warn(LogCategory.SECURITY, null,
                    "player_placed_log_ledger_unclean_session",
                    "marker", sessionMarker.toString(),
                    "effect", "automatic_whole_tree_felling_disabled");
            return false;
        }
        try {
            AtomicSnapshotFile.write(sessionMarker, "active\n");
            sessionMarkerActive = true;
            return true;
        } catch (IOException exception) {
            healthy = false;
            BotLog.error("player_placed_log_session_marker_create_failed", exception,
                    "marker", sessionMarker.toString());
            return false;
        }
    }

    private void closeSessionMarkerAfterCleanFlush() {
        if (!healthy
                || dirty
                || !loadIntegrityTrusted
                || !sessionMarkerActive
                || sessionMarker == null) {
            return;
        }
        try {
            Files.deleteIfExists(sessionMarker);
            sessionMarkerActive = false;
        } catch (IOException exception) {
            healthy = false;
            BotLog.error("player_placed_log_session_marker_delete_failed", exception,
                    "marker", sessionMarker.toString());
        }
    }

    private static String dimension(ServerLevel world) {
        return world.dimension().location().toString();
    }

    private record Snapshot(int schemaVersion, Map<String, List<Long>> dimensions) {
    }
}
