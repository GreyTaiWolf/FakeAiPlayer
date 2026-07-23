package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemory;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore;
import io.github.greytaiwolf.fakeaiplayer.runtime.IntentController;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.task.MoveTask;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class AIBotMemorySubcommand {
    private AIBotMemorySubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("memory")
                .then(argument("name", StringArgumentType.word())
                        .then(literal("remember")
                                .then(argument("key", StringArgumentType.word())
                                        .then(argument("value", StringArgumentType.greedyString())
                                                .executes(context -> remember(context.getSource(),
                                                        StringArgumentType.getString(context, "name"),
                                                        StringArgumentType.getString(context, "key"),
                                                        StringArgumentType.getString(context, "value"))))))
                        .then(literal("recall")
                                .then(argument("key", StringArgumentType.word())
                                        .executes(context -> recall(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "key")))))
                        .then(literal("forget")
                                .then(argument("key", StringArgumentType.word())
                                        .executes(context -> forget(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "key")))))
                        .then(literal("mark_place")
                                .then(argument("place", StringArgumentType.word())
                                        .executes(context -> markPlace(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "place")))))
                        .then(literal("set_base")
                                .executes(context -> markPlace(context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        "base")))
                        .then(literal("goto_place")
                                .then(argument("place", StringArgumentType.word())
                                        .executes(context -> gotoPlace(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "place")))))
                        .then(literal("set_goal")
                                .then(argument("title", StringArgumentType.word())
                                        .then(argument("steps", StringArgumentType.greedyString())
                                                .executes(context -> setGoal(context.getSource(),
                                                        StringArgumentType.getString(context, "name"),
                                                        StringArgumentType.getString(context, "title"),
                                                        StringArgumentType.getString(context, "steps"))))))
                        .then(literal("advance_goal")
                                .executes(context -> advanceGoal(context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        ""))
                                .then(argument("result", StringArgumentType.greedyString())
                                        .executes(context -> advanceGoal(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "result")))))
                        .then(literal("goal_status")
                                .executes(context -> goalStatus(context.getSource(), StringArgumentType.getString(context, "name"))))
                        .then(literal("inject")
                                .executes(context -> inject(context.getSource(), StringArgumentType.getString(context, "name")))));
    }

    private static int remember(CommandSourceStack source, String name, String key, String value) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "remember");
        if (bot.isEmpty()) {
            return 0;
        }
        memory(bot.get()).remember(key, value);
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] remembered " + key), false);
        return 1;
    }

    private static int recall(CommandSourceStack source, String name, String key) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.VIEW, "recall");
        if (bot.isEmpty()) {
            return 0;
        }
        String value = memory(bot.get()).recall(key).orElse("<missing>");
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] " + key + " = " + value), false);
        return 1;
    }

    private static int forget(CommandSourceStack source, String name, String key) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "forget");
        if (bot.isEmpty()) {
            return 0;
        }
        boolean removed = memory(bot.get()).forget(key);
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] forget " + key + " " + removed), false);
        return removed ? 1 : 0;
    }

    private static int markPlace(CommandSourceStack source, String name, String place) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "mark_place");
        if (bot.isEmpty()) {
            return 0;
        }
        memory(bot.get()).markPlace(place, bot.get().serverLevel(), bot.get().blockPosition());
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] marked place " + place + " at " + bot.get().blockPosition().toShortString()), false);
        return 1;
    }

    private static int gotoPlace(CommandSourceStack source, String name, String place) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "goto_place");
        if (bot.isEmpty()) {
            return 0;
        }
        Optional<BotMemory.Place> target = memory(bot.get()).place(place);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] unknown place: " + place));
            return 0;
        }
        if (!bot.get().serverLevel().dimension().location().toString().equals(target.get().dimension())) {
            source.sendFailure(Component.literal("[FakeAiPlayer] place is in another dimension: " + target.get().dimension()));
            return 0;
        }
        MoveTask task = new MoveTask(bot.get(), target.get().pos());
        IntentController.ReplaceResult result = IntentController.INSTANCE.replace(
                bot.get(),
                IntentController.ControlOrigin.PLAYER_COMMAND,
                "command_memory_goto:" + place,
                () -> io.github.greytaiwolf.fakeaiplayer.task.TaskManager.INSTANCE.assign(
                        bot.get(), task,
                        TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "command_memory_goto")).started());
        if (!result.replacementStarted()) {
            source.sendFailure(Component.literal(
                    "[FakeAiPlayer] movement deferred while safety work is active"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] moving to " + place), false);
        return 1;
    }

    private static int setGoal(CommandSourceStack source, String name, String title, String steps) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "set_goal");
        if (bot.isEmpty()) {
            return 0;
        }
        memory(bot.get()).setGoal(title, Arrays.asList(steps.split("\\|")));
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] " + memory(bot.get()).goalStatus("")), false);
        return 1;
    }

    private static int advanceGoal(CommandSourceStack source, String name, String result) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "advance_goal");
        if (bot.isEmpty()) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] " + memory(bot.get()).advanceGoal(result)), false);
        return 1;
    }

    private static int goalStatus(CommandSourceStack source, String name) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.VIEW, "goal_status");
        if (bot.isEmpty()) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] " + memory(bot.get()).goalStatus("")), false);
        return 1;
    }

    private static int inject(CommandSourceStack source, String name) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.VIEW, "inject");
        if (bot.isEmpty()) {
            return 0;
        }
        String text = memory(bot.get()).inject();
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] memory inject: " + (text.isBlank() ? "<empty>" : text)), false);
        return 1;
    }

    private static Optional<AIPlayerEntity> bot(CommandSourceStack source,
                                                String name,
                                                BotAuthorizationPolicy.Operation operation,
                                                String action) {
        Optional<AIPlayerEntity> resolved = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, name, operation, "command:memory_" + action);
        if (operation == BotAuthorizationPolicy.Operation.COMMAND
                && resolved.filter(TaskManager.INSTANCE::hasRuntimeRecoveryLock).isPresent()) {
            source.sendFailure(Component.literal(
                    "[FakeAiPlayer] memory mutation rejected: runtime recovery is read-only"));
            return Optional.empty();
        }
        return resolved;
    }

    private static BotMemory memory(AIPlayerEntity bot) {
        return BotMemoryStore.INSTANCE.of(bot.getUUID());
    }
}
