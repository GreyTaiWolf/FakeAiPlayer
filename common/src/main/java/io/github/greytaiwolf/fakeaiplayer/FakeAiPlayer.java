package io.github.greytaiwolf.fakeaiplayer;

import com.mojang.brigadier.CommandDispatcher;
import io.github.greytaiwolf.fakeaiplayer.brain.BrainCoordinator;
import io.github.greytaiwolf.fakeaiplayer.command.AIBotCommand;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.BotLogWriter;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityPolicy;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import io.github.greytaiwolf.fakeaiplayer.network.AIBotServerNetworking;
import io.github.greytaiwolf.fakeaiplayer.network.ServerNetworkTransport;
import io.github.greytaiwolf.fakeaiplayer.observe.TpsGuard;
import io.github.greytaiwolf.fakeaiplayer.persist.BotPersistence;
import io.github.greytaiwolf.fakeaiplayer.perception.focus.FocusTracker;
import io.github.greytaiwolf.fakeaiplayer.platform.PlatformEnvironment;
import io.github.greytaiwolf.fakeaiplayer.platform.PlatformServices;
import io.github.greytaiwolf.fakeaiplayer.runtime.RuntimeLifecycleCoordinator;
import io.github.greytaiwolf.fakeaiplayer.task.BotTickCoordinator;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/** Loader-neutral bootstrap shared by Fabric and NeoForge. */
public final class FakeAiPlayer {
    public static final String MOD_ID = "fakeaiplayer";
    public static final String MOD_NAME = "FakeAiPlayer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static boolean initialized;
    private static AIBotConfig config = AIBotConfig.defaults();

    private FakeAiPlayer() {
    }

    public static synchronized void initialize(PlatformEnvironment environment,
                                               ServerNetworkTransport networkTransport) {
        if (initialized) {
            return;
        }
        PlatformServices.initialize(environment);
        AIBotServerNetworking.INSTANCE.configure(networkTransport);

        config = AIBotConfig.load();
        BotLogWriter.INSTANCE.start(config);
        BotLog.lifecycle("mod_loaded", "version", PlatformServices.modVersion());
        BotLog.config("config_loaded",
                "profile", config.profile().configValue(),
                "configured_operator_capabilities", config.operatorCapabilities(),
                "effective_capabilities", Arrays.stream(PrivilegedCapability.values())
                        .filter(capability -> CapabilityPolicy.decide(
                                config.profile(), config.operatorCapabilities(), capability).allowed())
                        .map(Enum::name)
                        .toList(),
                "deepseek_model", config.deepseek().model(),
                "perception_radius", config.perception().radius(),
                "focus_enabled", config.perception().focus().enabledValue(),
                "focus_range", config.perception().focus().range(),
                "focus_sample_interval", config.perception().focus().sampleIntervalTicks(),
                "nav_lookahead", config.nav().lookahead(),
                "pickup_force_radius", config.pickup().forceRadiusH(),
                "logging_enabled", config.logging().enabled());

        LOGGER.info("================================");
        LOGGER.info("  {} v{} loaded", MOD_NAME, PlatformServices.modVersion());
        LOGGER.info("  Shared runtime: Fabric + NeoForge");
        LOGGER.info("================================");

        BrainCoordinator.INSTANCE.configure(config);
        initialized = true;
    }

    public static void onServerStarted(MinecraftServer server) {
        requireInitialized();
        BotLog.lifecycle("server_started", "motd", server.getMotd());
        RuntimeLifecycleCoordinator.INSTANCE.onServerStarted(server, config);
    }

    public static void onServerStopping(MinecraftServer server) {
        if (initialized) {
            RuntimeLifecycleCoordinator.INSTANCE.onServerStopping(server);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        requireInitialized();
        TpsGuard.INSTANCE.tick(server);
        TaskManager.INSTANCE.tickAll(server);
        BotTickCoordinator.INSTANCE.tick(server);
        FocusTracker.INSTANCE.tick(server);
        AIBotServerNetworking.INSTANCE.tick(server);
        io.github.greytaiwolf.fakeaiplayer.log.DiagnosticLogger.INSTANCE.tick(server);
        if (server.getTickCount() > 0 && server.getTickCount() % 6000 == 0) {
            BotPersistence.INSTANCE.saveAllAsync(server);
        }
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                        CommandBuildContext registryAccess) {
        AIBotCommand.register(dispatcher, registryAccess);
    }

    public static AIBotConfig config() {
        return config;
    }

    private static void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("FakeAiPlayer has not been initialized by a loader entrypoint");
        }
    }
}
