package io.github.greytaiwolf.fakeaiplayer.persist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.greytaiwolf.fakeaiplayer.brain.BrainCoordinator;
import io.github.greytaiwolf.fakeaiplayer.coordination.Job;
import io.github.greytaiwolf.fakeaiplayer.coordination.TaskBoard;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.platform.PlatformServices;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Versioned, single-writer runtime snapshot for Bot, Mission queue/checkpoint, pause state, and Job leases. */
public final class BotPersistence {
    public static final BotPersistence INSTANCE = new BotPersistence();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String INVENTORY_KEY = "Inventory";
    private static final String RUNTIME_FILE = "runtime.json";
    private static final String LEGACY_BOTS_FILE = "bots.json";
    private static final String LEGACY_JOBS_FILE = "jobs.json";

    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "FakeAiPlayerPersistenceWriter");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<PendingWrite> pendingAsync = new AtomicReference<>();
    private final AtomicBoolean asyncDrainScheduled = new AtomicBoolean();
    private volatile boolean readOnlyDueToLoadFailure;
    private volatile boolean acceptingAsyncWrites = true;
    private volatile boolean restoring;
    private volatile boolean lastSaveSucceeded;
    private volatile boolean lastWriterOperationSucceeded = true;
    private volatile String readOnlyReason = "";

    private BotPersistence() {
    }

    public int saveAll(MinecraftServer server) {
        if (readOnlyDueToLoadFailure) {
            lastSaveSucceeded = false;
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, null,
                    "runtime_persist_skipped_read_only", "path", runtimeFile(server));
            return 0;
        }
        RuntimeSnapshot snapshot;
        try {
            snapshot = captureSnapshot();
        } catch (RuntimeException exception) {
            lastSaveSucceeded = false;
            BotLog.error("runtime_persist_capture_failed", exception, "path", runtimeFile(server));
            return 0;
        }
        try {
            Future<Boolean> future = writer.submit(() -> {
                drainPendingWrites();
                return writeSnapshot(runtimeFile(server), snapshot);
            });
            lastSaveSucceeded = future.get();
            return lastSaveSucceeded ? snapshot.bots().size() : 0;
        } catch (InterruptedException exception) {
            lastSaveSucceeded = false;
            Thread.currentThread().interrupt();
            BotLog.error("runtime_persist_sync_failed", exception, "path", runtimeFile(server));
            return 0;
        } catch (ExecutionException | RejectedExecutionException exception) {
            lastSaveSucceeded = false;
            BotLog.error("runtime_persist_sync_failed", exception, "path", runtimeFile(server));
            return 0;
        }
    }

    public void saveAllAsync(MinecraftServer server) {
        if (readOnlyDueToLoadFailure || !acceptingAsyncWrites || restoring) {
            return;
        }
        RuntimeSnapshot snapshot;
        try {
            snapshot = captureSnapshot();
        } catch (RuntimeException exception) {
            BotLog.error("runtime_persist_capture_failed", exception, "path", runtimeFile(server));
            return;
        }
        pendingAsync.set(new PendingWrite(runtimeFile(server), snapshot));
        scheduleAsyncDrain();
    }

    /** Mutation-driven, debounced background flush. Capture occurs on the server thread. */
    public void markDirty(MinecraftServer server) {
        saveAllAsync(server);
    }

    public void freezeWrites() {
        acceptingAsyncWrites = false;
    }

    public void resumeWrites() {
        acceptingAsyncWrites = true;
    }

    public boolean lastSaveSucceeded() {
        return lastSaveSucceeded;
    }

    public boolean readOnlyRecoveryActive() {
        return readOnlyDueToLoadFailure;
    }

    public String readOnlyRecoveryReason() {
        return readOnlyReason;
    }

    public List<BotRecord> load(MinecraftServer server) {
        LoadOutcome outcome = loadSnapshot(server);
        return outcome.snapshot().bots().stream().map(PersistedBot::bot).toList();
    }

    public int loadAndRespawn(MinecraftServer server) {
        restoring = true;
        try {
            if (!quiescePendingWritesForRestore()) {
                enterRestoreReadOnly(
                        server,
                        "runtime_restore_writer_quiesce_failed_read_only",
                        0,
                        0);
                return 0;
            }
            try {
                return loadAndRespawnInternal(server);
            } catch (RuntimeException restoreFailure) {
                // A malformed record may fail after earlier Bots or Jobs have already been
                // reconstructed. Keep every currently known Bot and every future Bot behind the
                // session-wide gate instead of returning a half-live, non-persistable runtime.
                enterRestoreReadOnly(
                        server,
                        "runtime_restore_unexpected_failure_read_only",
                        AIPlayerManager.INSTANCE.all().size(),
                        -1);
                BotLog.error("runtime_restore_unexpected_failure", restoreFailure,
                        "path", runtimeFile(server),
                        "recovery", "source_snapshot_preserved; runtime_read_only");
                return 0;
            }
        } finally {
            restoring = false;
        }
    }

    /** Admin command boundary: never replace Job leases or Bot runtime underneath live work. */
    public int reloadIfIdle(MinecraftServer server) {
        if (!AIPlayerManager.INSTANCE.all().isEmpty()
                || TaskManager.INSTANCE.activeCount() > 0
                || !TaskBoard.INSTANCE.snapshot().isEmpty()) {
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, null,
                    "runtime_live_reload_rejected", "reason", "runtime_not_empty");
            return -1;
        }
        if (pendingAsync.get() != null || asyncDrainScheduled.get()) {
            // An admin may be about to replace runtime.json with a repaired authoritative copy.
            // Never flush a debounced pre-repair snapshot over that source from the reload path.
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, null,
                    "runtime_live_reload_rejected", "reason", "pending_writer_activity",
                    "recovery", "wait_for_persistence_flush_then_repair_and_retry");
            return -2;
        }
        return loadAndRespawn(server);
    }

    private int loadAndRespawnInternal(MinecraftServer server) {
        readOnlyDueToLoadFailure = false;
        readOnlyReason = "";
        TaskManager.INSTANCE.beginRuntimeSession();
        TaskBoard.INSTANCE.beginRuntimeSession();
        LoadOutcome outcome = loadSnapshot(server);
        if (readOnlyDueToLoadFailure) {
            // Unsupported, malformed and failed legacy reads all return an empty projection. They
            // still require the same whole-session execution gate as a partially restored graph;
            // otherwise a newly spawned Bot could mutate the world while persistence is disabled.
            enterRestoreReadOnly(
                    server,
                    "runtime_load_failure_read_only",
                    0,
                    0);
            return 0;
        }
        RuntimeSnapshot snapshot = outcome.snapshot();
        List<RestoredMission> missions = new ArrayList<>();
        int restored = 0;
        boolean fullyAccounted = true;
        for (PersistedBot persisted : snapshot.bots()) {
            if (persisted == null || persisted.bot() == null) {
                fullyAccounted = false;
                continue;
            }
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.respawnFromRecord(server, persisted.bot());
            if (bot.isPresent()) {
                restored++;
                missions.add(new RestoredMission(bot.get(), persisted.missions()));
            } else {
                fullyAccounted = false;
            }
        }
        List<Job> migratedJobs = migrateJobs(snapshot.jobs());
        if (migratedJobs.size() != snapshot.jobs().size()) {
            fullyAccounted = false;
        }
        TaskBoard.INSTANCE.replaceAll(migratedJobs);
        List<Job> restoredJobs = TaskBoard.INSTANCE.snapshot();
        if (!restoredJobIdentityMatches(migratedJobs, restoredJobs)) {
            fullyAccounted = false;
        }
        for (RestoredMission restoredMission : missions) {
            boolean missionAccounted = GoalExecutor.INSTANCE.restoreRuntime(
                    restoredMission.bot(), restoredMission.missions());
            fullyAccounted &= missionAccounted;
        }
        boolean canonicalWriteAllowed = canonicalRestoreWriteAllowed(
                readOnlyDueToLoadFailure,
                snapshot.bots().size(),
                restored,
                fullyAccounted,
                snapshot.jobs().size(),
                restoredJobs.size());
        if (!readOnlyDueToLoadFailure && !canonicalWriteAllowed) {
            // A canonical snapshot may contain only fully accounted entries. Otherwise capture()
            // would silently omit a Bot/Mission/Job that failed to restore and overwrite the last
            // recoverable runtime.json. Keep the whole session read-only until an operator repairs
            // or removes the isolated record.
            enterRestoreReadOnly(
                    server,
                    "runtime_partial_restore_read_only",
                    restored,
                    snapshot.bots().size());
        }
        if (!readOnlyDueToLoadFailure) {
            // restoreRuntime suppresses mutation-driven writes while the in-memory graph is
            // incomplete. Once every Bot, Mission and stale Job lease has been reconstructed,
            // synchronously persist the canonical revision/cursor before releasing the restoring
            // guard and publishing runtime-ready; an async debounce leaves a second-crash window.
            saveAll(server);
            if (!lastSaveSucceeded) {
                enterRestoreReadOnly(
                        server,
                        "runtime_canonical_restore_save_failed_read_only",
                        restored,
                        snapshot.bots().size());
            }
            if (outcome.legacyMigrated()) {
                if (lastSaveSucceeded) {
                    backupLegacyFiles(server);
                    BotLog.lifecycle("runtime_legacy_migrated", "bots", restored,
                            "schema", RuntimeSnapshot.CURRENT_SCHEMA);
                } else {
                    BotLog.error("runtime_legacy_migration_write_failed", null,
                            "recovery", "legacy_files_were_left_in_place");
                }
            }
        }
        return restored;
    }

    private boolean quiescePendingWritesForRestore() {
        try {
            Future<Boolean> barrier = writer.submit(() -> {
                // The reload caller has already set restoring=true, so no new captures can enter.
                // Admin live reload rejects pending writer activity before reaching this barrier.
                // At startup, drain rather than discard any residual coalesced snapshot: it may be
                // the only durable record that a Bot was deleted before a same-JVM world restart.
                drainPendingWrites();
                return lastWriterOperationSucceeded;
            });
            return barrier.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | RejectedExecutionException exception) {
            return false;
        }
    }

    private void enterRestoreReadOnly(MinecraftServer server,
                                      String event,
                                      int restoredBots,
                                      int persistedBots) {
        readOnlyDueToLoadFailure = true;
        readOnlyReason = event;
        lastSaveSucceeded = false;
        TaskManager.INSTANCE.enterRuntimeRecoveryMode(event);
        TaskBoard.INSTANCE.suspendClaims(event);
        for (AIPlayerEntity bot : List.copyOf(AIPlayerManager.INSTANCE.all())) {
            try {
                BrainCoordinator.INSTANCE.invalidateDecision(bot, event);
                BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
                BotInventorySessionManager.INSTANCE.closeForSafety(bot, event);
                bot.getActionPack().stopAll();
                bot.setInvulnerable(true);
                bot.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
                TaskManager.INSTANCE.acquireRuntimeRecoveryLock(bot);
                TaskManager.INSTANCE.pauseUserIntent(bot, event);
            } catch (RuntimeException isolationFailure) {
                // The global gate was installed before per-Bot isolation. One malformed Bot may
                // therefore lose a diagnostic USER lock, but cannot resume ordinary execution.
                BotLog.error(bot, "runtime_restore_bot_isolation_failed", isolationFailure,
                        "event", event);
            }
        }
        BotLog.error(event, null,
                "path", runtimeFile(server),
                "restored_bots", restoredBots,
                "persisted_bots", persistedBots,
                "recovery", "source_snapshot_preserved; repair_or_remove_isolated_records");
    }

    static boolean canonicalRestoreWriteAllowed(boolean loadReadOnly,
                                                int persistedBots,
                                                int restoredBots,
                                                boolean entriesAccounted,
                                                int persistedJobs,
                                                int restoredJobs) {
        if (persistedBots < 0 || restoredBots < 0
                || persistedJobs < 0 || restoredJobs < 0) {
            throw new IllegalArgumentException("restore_accounting_negative");
        }
        return !loadReadOnly
                && entriesAccounted
                && persistedBots == restoredBots
                && persistedJobs == restoredJobs;
    }

    static boolean restoredJobIdentityMatches(List<Job> expected, List<Job> actual) {
        if (expected == null || actual == null) {
            return false;
        }
        java.util.Set<UUID> expectedIds = new java.util.HashSet<>();
        for (Job job : expected) {
            if (job == null || job.id() == null || !expectedIds.add(job.id())) {
                return false;
            }
        }
        java.util.Set<UUID> actualIds = new java.util.HashSet<>();
        for (Job job : actual) {
            if (job == null || job.id() == null || !actualIds.add(job.id())) {
                return false;
            }
        }
        return expected.size() == actual.size()
                && expectedIds.equals(actualIds);
    }

    public static BotRecord capture(AIPlayerEntity bot) {
        return new BotRecord(
                bot.getGameProfile().getName(),
                bot.level().dimension().location().toString(),
                bot.getX(), bot.getY(), bot.getZ(), bot.getYRot(), bot.getXRot(),
                bot.gameMode.getGameModeForPlayer().getName(),
                bot.getHealth(), bot.getFoodData().getFoodLevel(),
                encodeInventory(bot), AIPlayerManager.INSTANCE.role(bot),
                BotMemoryStore.INSTANCE.saveString(bot.getUUID()),
                AIPlayerManager.INSTANCE.ownerOf(bot).map(UUID::toString).orElse(""));
    }

    public static String encodeInventory(ServerPlayer player) {
        CompoundTag root = new CompoundTag();
        root.put(INVENTORY_KEY, player.getInventory().save(new ListTag()));
        return root.toString();
    }

    /**
     * Validates every persisted Bot sub-payload before AIPlayerManager creates a live shell.
     * Blank owner/inventory/memory values are the supported legacy-empty representation; malformed
     * nonblank values are unaccounted restore data and must block the canonical rewrite.
     */
    public static boolean restorableBotSubstate(BotRecord record) {
        if (record == null
                || !optionalOwnerUuidValid(record.ownerUuid())
                || !inventoryPayloadValid(record.inventoryNbt())) {
            return false;
        }
        return BotMemoryStore.INSTANCE.persistedPayloadValid(record.memoryNbt());
    }

    static boolean optionalOwnerUuidValid(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean inventoryPayloadValid(String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return true;
        }
        try {
            CompoundTag root = TagParser.parseTag(snbt);
            if (!root.getAllKeys().equals(Set.of(INVENTORY_KEY))
                    || !root.contains(INVENTORY_KEY, Tag.TAG_LIST)) {
                return false;
            }
            if (!(root.get(INVENTORY_KEY) instanceof ListTag inventory)) {
                return false;
            }
            return inventory.isEmpty() || inventory.getElementType() == Tag.TAG_COMPOUND;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Applies a preflighted inventory and verifies that Minecraft can encode the exact same list.
     * Returning false keeps the source runtime snapshot read-only instead of canonicalizing a
     * partially dropped inventory.
     */
    public static boolean applyInventory(ServerPlayer player, String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return true;
        }
        try {
            if (!inventoryPayloadValid(snbt)) {
                BotLog.error(player instanceof AIPlayerEntity bot ? bot : null,
                        "bot_inventory_restore_failed", null,
                        "reason", "invalid_persisted_shape");
                return false;
            }
            CompoundTag root = TagParser.parseTag(snbt);
            ListTag inventory = root.getList(INVENTORY_KEY, Tag.TAG_COMPOUND);
            Inventory playerInventory = player.getInventory();
            playerInventory.load(inventory);
            playerInventory.setChanged();
            ListTag restored = playerInventory.save(new ListTag());
            if (!inventory.equals(restored)) {
                BotLog.error(player instanceof AIPlayerEntity bot ? bot : null,
                        "bot_inventory_restore_failed", null,
                        "reason", "non_exact_round_trip");
                return false;
            }
            return true;
        } catch (Exception exception) {
            BotLog.error(player instanceof AIPlayerEntity bot ? bot : null, "bot_inventory_restore_failed", exception);
            return false;
        }
    }

    private RuntimeSnapshot captureSnapshot() {
        List<PersistedBot> bots = new ArrayList<>();
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            bots.add(new PersistedBot(capture(bot), GoalExecutor.INSTANCE.captureRuntime(bot)));
        }
        return new RuntimeSnapshot(
                RuntimeSnapshot.CURRENT_SCHEMA,
                Instant.now().toString(),
                buildVersion(),
                TaskBoard.INSTANCE.runtimeSessionId().toString(),
                bots,
                TaskBoard.INSTANCE.snapshot());
    }

    private LoadOutcome loadSnapshot(MinecraftServer server) {
        Path runtime = runtimeFile(server);
        if (Files.exists(runtime)) {
            try (Reader reader = Files.newBufferedReader(runtime)) {
                RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(reader);
                if (decoded.status() == RuntimeSnapshotCodec.Status.UNSUPPORTED_SCHEMA) {
                    readOnlyDueToLoadFailure = true;
                    BotLog.error("runtime_schema_unsupported", null,
                            "path", runtime, "found", decoded.foundSchema(),
                            "supported", RuntimeSnapshot.CURRENT_SCHEMA,
                            "recovery", "downgrade_is_blocked; restore_a_compatible_backup_or_upgrade_the_mod");
                    return new LoadOutcome(emptySnapshot(), false);
                }
                if (decoded.status() == RuntimeSnapshotCodec.Status.MALFORMED) {
                    readOnlyDueToLoadFailure = true;
                    BotLog.error("runtime_persist_load_failed", null,
                            "path", runtime, "reason", decoded.reason(),
                            "recovery", "repair_or_move_runtime_json_then_restart");
                    return new LoadOutcome(emptySnapshot(), false);
                }
                return new LoadOutcome(decoded.snapshot(), false);
            } catch (IOException | RuntimeException exception) {
                readOnlyDueToLoadFailure = true;
                BotLog.error("runtime_persist_load_failed", exception,
                        "path", runtime, "recovery", "repair_or_move_runtime_json_then_restart");
                return new LoadOutcome(emptySnapshot(), false);
            }
        }
        return loadLegacy(server);
    }

    private LoadOutcome loadLegacy(MinecraftServer server) {
        Path dir = aibotDir(server);
        List<PersistedBot> bots = new ArrayList<>();
        List<Job> jobs = List.of();
        boolean found = false;
        Path botFile = dir.resolve(LEGACY_BOTS_FILE);
        if (Files.exists(botFile)) {
            found = true;
            try (Reader reader = Files.newBufferedReader(botFile)) {
                BotRecord[] records = GSON.fromJson(reader, BotRecord[].class);
                if (records != null) {
                    for (BotRecord record : records) {
                        bots.add(new PersistedBot(record, MissionRuntimeRecord.empty()));
                    }
                }
            } catch (IOException | RuntimeException exception) {
                readOnlyDueToLoadFailure = true;
                BotLog.error("legacy_bot_persist_load_failed", exception, "path", botFile);
                return new LoadOutcome(emptySnapshot(), false);
            }
        }
        Path jobFile = dir.resolve(LEGACY_JOBS_FILE);
        if (Files.exists(jobFile)) {
            found = true;
            try (Reader reader = Files.newBufferedReader(jobFile)) {
                Job[] loaded = GSON.fromJson(reader, Job[].class);
                jobs = loaded == null ? List.of() : List.of(loaded);
            } catch (IOException | RuntimeException exception) {
                readOnlyDueToLoadFailure = true;
                BotLog.error("legacy_jobs_persist_load_failed", exception, "path", jobFile);
                return new LoadOutcome(emptySnapshot(), false);
            }
        }
        return new LoadOutcome(new RuntimeSnapshot(RuntimeSnapshot.CURRENT_SCHEMA, Instant.now().toString(),
                buildVersion(), TaskBoard.INSTANCE.runtimeSessionId().toString(), bots, jobs), found);
    }

    private List<Job> migrateJobs(List<Job> loaded) {
        List<Job> migrated = new ArrayList<>();
        for (Job job : loaded == null ? List.<Job>of() : loaded) {
            if (job == null || job.id() == null || job.params() == null) {
                continue;
            }
            if (job.scope() != null) {
                migrated.add(job);
                continue;
            }
            UUID owner = job.claimant() == null ? null : AIPlayerManager.INSTANCE.getByUuid(job.claimant())
                    .flatMap(AIPlayerManager.INSTANCE::ownerOf).orElse(null);
            if (owner != null) {
                migrated.add(new Job(job.id(), job.kind(), job.params(), job.role(), Job.Scope.OWNER, owner,
                        Job.Status.OPEN, null, null, null, "legacy_claim_reopened"));
            } else {
                migrated.add(new Job(job.id(), job.kind(), job.params(), job.role(), Job.Scope.GLOBAL_ADMIN, null,
                        Job.Status.FAILED, null, null, null, "legacy_scope_required"));
            }
        }
        return List.copyOf(migrated);
    }

    private void scheduleAsyncDrain() {
        if (asyncDrainScheduled.compareAndSet(false, true)) {
            writer.schedule(this::drainPendingWrites, 750, TimeUnit.MILLISECONDS);
        }
    }

    private void drainPendingWrites() {
        try {
            PendingWrite pending;
            while ((pending = pendingAsync.getAndSet(null)) != null) {
                writeSnapshot(pending.path(), pending.snapshot());
            }
        } finally {
            asyncDrainScheduled.set(false);
            if (pendingAsync.get() != null) {
                scheduleAsyncDrain();
            }
        }
    }

    private boolean writeSnapshot(Path file, RuntimeSnapshot snapshot) {
        try {
            boolean atomic = AtomicSnapshotFile.write(file, RuntimeSnapshotCodec.encode(snapshot));
            lastWriterOperationSucceeded = true;
            BotLog.lifecycle("runtime_persist_saved", "bots", snapshot.bots().size(),
                    "jobs", snapshot.jobs().size(), "path", file, "schema", snapshot.schemaVersion(),
                    "atomic_move", atomic);
            if (!atomic) {
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, null,
                        "atomic_move_not_supported", "path", file);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            lastWriterOperationSucceeded = false;
            BotLog.error("runtime_persist_save_failed", exception, "path", file,
                    "bots", snapshot.bots().size(), "jobs", snapshot.jobs().size());
            return false;
        }
    }

    private void backupLegacyFiles(MinecraftServer server) {
        for (String name : List.of(LEGACY_BOTS_FILE, LEGACY_JOBS_FILE)) {
            Path source = aibotDir(server).resolve(name);
            Path backup = source.resolveSibling(name + ".migrated.bak");
            try {
                if (Files.exists(source) && !Files.exists(backup)) {
                    Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (IOException exception) {
                BotLog.error("legacy_backup_failed", exception, "path", source);
            }
        }
    }

    private RuntimeSnapshot emptySnapshot() {
        return new RuntimeSnapshot(RuntimeSnapshot.CURRENT_SCHEMA, Instant.now().toString(), buildVersion(),
                TaskBoard.INSTANCE.runtimeSessionId().toString(), List.of(), List.of());
    }

    private Path runtimeFile(MinecraftServer server) {
        return aibotDir(server).resolve(RUNTIME_FILE);
    }

    private Path aibotDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("fakeaiplayer");
    }

    private static String buildVersion() {
        return PlatformServices.modVersion();
    }

    private record PendingWrite(Path path, RuntimeSnapshot snapshot) {
    }

    private record LoadOutcome(RuntimeSnapshot snapshot, boolean legacyMigrated) {
    }

    private record RestoredMission(AIPlayerEntity bot, MissionRuntimeRecord missions) {
    }
}
