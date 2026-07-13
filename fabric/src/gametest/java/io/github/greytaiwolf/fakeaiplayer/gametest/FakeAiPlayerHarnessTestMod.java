package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.command.AIBotTestSubcommand;
import io.github.greytaiwolf.fakeaiplayer.command.AIBotVerifySubcommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;

/** Test-only command harness. This class and both subcommands are excluded from the production jar. */
public final class FakeAiPlayerHarnessTestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("fakeaiplayer")
                        .then(AIBotTestSubcommand.build(registryAccess))
                        .then(AIBotVerifySubcommand.build())
                        .then(AIBotRestartHarnessCommand.build())));
        ServerTickEvents.END_SERVER_TICK.register(AIBotVerifySubcommand::tick);
    }
}
