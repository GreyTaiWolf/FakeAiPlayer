package io.github.greytaiwolf.fakeaiplayer.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.greytaiwolf.fakeaiplayer.client.screen.BotPanelScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class AIBotKeyBindings {
    private static final KeyMapping OPEN_PANEL = new KeyMapping(
            "key.fakeaiplayer.open_panel",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.fakeaiplayer");
    private static final KeyMapping OPEN_ACTIONS = new KeyMapping(
            "key.fakeaiplayer.open_actions",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.fakeaiplayer");
    private static final KeyMapping CONFIRM_BUILDING_PREVIEW = new KeyMapping(
            "key.fakeaiplayer.confirm_building_preview",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.fakeaiplayer");
    private static final KeyMapping CANCEL_BUILDING_PREVIEW = new KeyMapping(
            "key.fakeaiplayer.cancel_building_preview",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.fakeaiplayer");
    private static boolean altZeroDown;
    private static boolean altNineDown;

    private AIBotKeyBindings() {
    }

    public static KeyMapping openPanel() {
        return OPEN_PANEL;
    }

    public static KeyMapping openActions() {
        return OPEN_ACTIONS;
    }

    public static KeyMapping confirmBuildingPreview() {
        return CONFIRM_BUILDING_PREVIEW;
    }

    public static KeyMapping cancelBuildingPreview() {
        return CANCEL_BUILDING_PREVIEW;
    }

    public static PreviewAction pollPreviewAction() {
        while (CONFIRM_BUILDING_PREVIEW.consumeClick()) {
            return PreviewAction.CONFIRM;
        }
        while (CANCEL_BUILDING_PREVIEW.consumeClick()) {
            return PreviewAction.CANCEL;
        }
        return null;
    }

    public static BotPanelScreen.Mode pollToggle(Minecraft client) {
        boolean chatPressed = false;
        while (OPEN_PANEL.consumeClick()) {
            chatPressed = true;
        }
        boolean actionsPressed = false;
        while (OPEN_ACTIONS.consumeClick()) {
            actionsPressed = true;
        }
        long handle = client.getWindow().getWindow();
        boolean altPressed = InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean zeroPressed = InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_0);
        boolean altZeroPressed = altPressed && zeroPressed;
        boolean chatComboOpened = altZeroPressed && !altZeroDown;
        altZeroDown = altZeroPressed;
        boolean ninePressed = InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_9);
        boolean altNinePressed = altPressed && ninePressed;
        boolean actionsComboOpened = altNinePressed && !altNineDown;
        altNineDown = altNinePressed;
        if (!(client.screen == null || client.screen instanceof BotPanelScreen)) {
            return null;
        }
        if (actionsPressed || actionsComboOpened) {
            return BotPanelScreen.Mode.ACTIONS;
        }
        if (chatPressed || chatComboOpened) {
            return BotPanelScreen.Mode.CHAT_STATUS;
        }
        return null;
    }

    public enum PreviewAction {
        CONFIRM,
        CANCEL
    }
}
