package io.github.greytaiwolf.fakeaiplayer.client;

import io.github.greytaiwolf.fakeaiplayer.network.payload.BotCommandC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.OpenBotInventoryC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotTeleportC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SetOptionC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SubscribeBotC2S;
import net.minecraft.client.Minecraft;

public final class BotCommandBridge {
    private BotCommandBridge() {
    }

    public static boolean hasPermission() {
        Minecraft client = Minecraft.getInstance();
        // Owner authorization is server-side and depends on the selected Bot; the client cannot
        // infer it from OP level. This probe only means that a player connection exists.
        return client.player != null;
    }

    public static void subscribe(String botName, boolean subscribe) {
        if (ClientNetworkServices.canSend(SubscribeBotC2S.ID)) {
            ClientNetworkServices.send(new SubscribeBotC2S(botName, subscribe));
        }
    }

    public static void chat(String botName, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        BotClientState.INSTANCE.addTranscript("user", text.trim());
        if (ClientNetworkServices.canSend(BotCommandC2S.ID)) {
            ClientNetworkServices.send(new BotCommandC2S(botName, "chat", text.trim(), "", 1));
        } else {
            sendChatMessage("@" + botName + " " + text.trim());
        }
    }

    public static void command(String botName, String action, String arg1, String arg2, int count) {
        if (ClientNetworkServices.canSend(BotCommandC2S.ID)) {
            ClientNetworkServices.send(new BotCommandC2S(clean(botName), action, clean(arg1), clean(arg2), count));
            return;
        }
        sendCommand(fallbackCommand(clean(botName), action, clean(arg1), clean(arg2), Math.max(1, count)));
    }

    private static String fallbackCommand(String botName, String action, String arg1, String arg2, int count) {
        return switch (action) {
            case "move" -> "fakeaiplayer task assign " + botName + " move " + arg1;
            case "mine" -> "fakeaiplayer task assign " + botName + " mine " + arg1 + " " + count;
            case "craft" -> "fakeaiplayer task assign " + botName + " craft " + arg1 + " " + count;
            case "smelt" -> "fakeaiplayer task assign " + botName + " smelt " + arg1 + " " + arg2 + " " + count;
            case "eat" -> "fakeaiplayer task assign " + botName + " eat";
            case "sleep" -> "fakeaiplayer task assign " + botName + " sleep";
            case "abort" -> "fakeaiplayer task abort " + botName;
            case "pause" -> "fakeaiplayer task pause " + botName;
            case "resume" -> "fakeaiplayer task resume " + botName;
            case "reset" -> "fakeaiplayer brain reset " + botName;
            default -> "fakeaiplayer status";
        };
    }

    private static void sendCommand(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) {
            client.getConnection().sendCommand(command);
        }
    }

    public static void openInventory(String botName) {
        if (ClientNetworkServices.canSend(OpenBotInventoryC2S.ID)) {
            ClientNetworkServices.send(new OpenBotInventoryC2S(clean(botName)));
        }
    }

    /** 传送。direction:BotTeleportC2S.TO_AI(玩家→AI 附近)/ RECALL_AI(AI→玩家附近)。 */
    public static void teleport(String botName, int direction) {
        if (ClientNetworkServices.canSend(BotTeleportC2S.ID)) {
            ClientNetworkServices.send(new BotTeleportC2S(clean(botName), direction));
        }
    }

    public static void setOption(String botName, String key, boolean value) {
        if (ClientNetworkServices.canSend(SetOptionC2S.ID)) {
            ClientNetworkServices.send(new SetOptionC2S(botName == null ? "" : botName, key, value));
        }
    }

    private static void sendChatMessage(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) {
            client.getConnection().sendChat(message);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
