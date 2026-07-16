package io.github.greytaiwolf.fakeaiplayer.client.screen;

import io.github.greytaiwolf.fakeaiplayer.client.ClientNetworkServices;
import io.github.greytaiwolf.fakeaiplayer.client.credential.ClientCredentialManager;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotAiCredentialStatusS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.PayloadLimits;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SetBotAiCredentialC2S;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Masked client-only prompt opened by a server-issued, owner-authorized setup nonce. */
public final class BotAiSetupScreen extends Screen {
    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 206;
    private static final int PANEL_BACKGROUND = 0xF020252D;
    private static final int PANEL_BORDER = 0xFF596270;
    private static final int TEXT = 0xFFE6E9EF;
    private static final int MUTED = 0xFFAAB1BC;
    private static final int WARNING = 0xFFE6C45A;

    private final String botName;
    private final UUID nonce;
    private EditBox apiKeyInput;
    private Button connectButton;
    private Component status = Component.empty();
    private String serverScope = "";
    private String pendingKey = "";
    private boolean connecting;
    private int panelX;
    private int panelY;
    private int panelWidth;

    public BotAiSetupScreen(String botName, UUID nonce) {
        super(Component.translatable("screen.fakeaiplayer.ai_setup.title", botName));
        this.botName = botName;
        this.nonce = nonce;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_WIDTH, Math.max(220, width - 24));
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - PANEL_HEIGHT) / 2);
        serverScope = ClientCredentialManager.currentServerScope(minecraft);

        apiKeyInput = new SecretEditBox(
                font,
                panelX + 16,
                panelY + 88,
                panelWidth - 32,
                20,
                Component.translatable("screen.fakeaiplayer.ai_setup.key"));
        apiKeyInput.setMaxLength(PayloadLimits.API_KEY_LENGTH);
        apiKeyInput.setFormatter((visibleText, cursorOffset) -> FormattedCharSequence.forward(
                "*".repeat(visibleText.length()), Style.EMPTY));
        apiKeyInput.setHint(Component.translatable("screen.fakeaiplayer.ai_setup.placeholder"));
        if (!serverScope.isBlank()) {
            ClientCredentialManager.find(serverScope, botName).ifPresent(apiKeyInput::setValue);
        }
        addRenderableWidget(apiKeyInput);

        connectButton = Button.builder(
                        Component.translatable("screen.fakeaiplayer.ai_setup.connect"),
                        button -> connect())
                .bounds(panelX + 16, panelY + 166, (panelWidth - 38) / 2, 20)
                .build();
        addRenderableWidget(connectButton);
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.fakeaiplayer.ai_setup.cancel"),
                        button -> onClose())
                .bounds(panelX + 22 + (panelWidth - 38) / 2,
                        panelY + 166, (panelWidth - 38) / 2, 20)
                .build());
        setInitialFocus(apiKeyInput);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + PANEL_HEIGHT, PANEL_BORDER);
        graphics.fill(panelX + 1, panelY + 1,
                panelX + panelWidth - 1, panelY + PANEL_HEIGHT - 1, PANEL_BACKGROUND);
        graphics.drawCenteredString(font, title, width / 2, panelY + 12, TEXT);
        drawWrapped(graphics,
                Component.translatable("screen.fakeaiplayer.ai_setup.description", botName),
                panelX + 16, panelY + 34, MUTED, 1);
        drawWrapped(graphics,
                Component.translatable("screen.fakeaiplayer.ai_setup.security"),
                panelX + 16, panelY + 50, WARNING, 3);
        graphics.drawString(font,
                Component.translatable("screen.fakeaiplayer.ai_setup.key"),
                panelX + 16, panelY + 78, MUTED, false);
        if (!status.getString().isBlank()) {
            drawWrapped(graphics, status, panelX + 16, panelY + 118,
                    connecting ? WARNING : TEXT, 3);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (apiKeyInput != null && apiKeyInput.isFocused() && Screen.hasControlDown()
                && (keyCode == GLFW.GLFW_KEY_C || keyCode == GLFW.GLFW_KEY_X)) {
            // A masked field must not put its underlying token on the system clipboard.
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        pendingKey = "";
        if (apiKeyInput != null) {
            apiKeyInput.setValue("");
        }
        super.onClose();
    }

    public boolean matches(String candidateBotName, UUID candidateNonce) {
        return nonce.equals(candidateNonce)
                && botName.equalsIgnoreCase(candidateBotName == null ? "" : candidateBotName);
    }

    public void applyStatus(BotAiCredentialStatusS2C payload,
                            ClientCredentialManager.StorageResult forgetResult) {
        if (!matches(payload.botName(), payload.nonce())) {
            return;
        }
        connecting = false;
        connectButton.active = true;
        apiKeyInput.setEditable(true);

        if (payload.forgetLocal()) {
            pendingKey = "";
            apiKeyInput.setValue("");
            status = forgetResult == ClientCredentialManager.StorageResult.OK
                    ? statusMessage(payload.statusKey())
                    : Component.translatable("screen.fakeaiplayer.ai_setup.status.storage_error");
            return;
        }

        if (payload.connected() && !pendingKey.isBlank()) {
            ClientCredentialManager.StorageResult saved =
                    ClientCredentialManager.save(serverScope, botName, pendingKey);
            pendingKey = "";
            apiKeyInput.setValue("");
            status = saved == ClientCredentialManager.StorageResult.OK
                    ? statusMessage(payload.statusKey())
                    : Component.translatable("screen.fakeaiplayer.ai_setup.status.connected_not_saved");
            return;
        }

        pendingKey = "";
        status = statusMessage(payload.statusKey());
    }

    private void connect() {
        if (connecting) {
            return;
        }
        String candidate = apiKeyInput.getValue().strip();
        if (!PayloadLimits.validApiKey(candidate)) {
            status = Component.translatable("screen.fakeaiplayer.ai_setup.status.invalid_input");
            return;
        }
        if (serverScope.isBlank()) {
            status = Component.translatable("screen.fakeaiplayer.ai_setup.status.no_scope");
            return;
        }
        if (!ClientNetworkServices.canSend(SetBotAiCredentialC2S.ID)) {
            status = Component.translatable("screen.fakeaiplayer.ai_setup.status.unsupported");
            return;
        }
        pendingKey = candidate;
        connecting = true;
        connectButton.active = false;
        apiKeyInput.setEditable(false);
        status = Component.translatable("screen.fakeaiplayer.ai_setup.status.testing");
        ClientNetworkServices.send(new SetBotAiCredentialC2S(botName, nonce, pendingKey));
    }

    public static Component statusMessage(String statusCode) {
        String normalized = statusCode == null ? "" : statusCode.toLowerCase(Locale.ROOT);
        String translationKey = switch (normalized) {
            case BotAiCredentialStatusS2C.CONNECTED ->
                    "screen.fakeaiplayer.ai_setup.status.connected";
            case BotAiCredentialStatusS2C.RESTORED ->
                    "screen.fakeaiplayer.ai_setup.status.restored";
            case BotAiCredentialStatusS2C.DISCONNECTED ->
                    "screen.fakeaiplayer.ai_setup.status.disconnected";
            case BotAiCredentialStatusS2C.INVALID_KEY ->
                    "screen.fakeaiplayer.ai_setup.status.invalid_key";
            case BotAiCredentialStatusS2C.UNAUTHORIZED ->
                    "screen.fakeaiplayer.ai_setup.status.unauthorized";
            case BotAiCredentialStatusS2C.BOT_NOT_FOUND ->
                    "screen.fakeaiplayer.ai_setup.status.bot_not_found";
            case BotAiCredentialStatusS2C.REQUEST_EXPIRED ->
                    "screen.fakeaiplayer.ai_setup.status.request_expired";
            case BotAiCredentialStatusS2C.PROVIDER_ERROR ->
                    "screen.fakeaiplayer.ai_setup.status.provider_error";
            case BotAiCredentialStatusS2C.RATE_LIMITED ->
                    "screen.fakeaiplayer.ai_setup.status.rate_limited";
            case BotAiCredentialStatusS2C.BUSY ->
                    "screen.fakeaiplayer.ai_setup.status.busy";
            default -> "screen.fakeaiplayer.ai_setup.status.unknown";
        };
        return Component.translatable(translationKey);
    }

    private void drawWrapped(GuiGraphics graphics,
                             Component text,
                             int x,
                             int y,
                             int color,
                             int maximumLines) {
        int line = 0;
        for (FormattedCharSequence part : font.split(text, panelWidth - 32)) {
            if (line >= maximumLines) {
                break;
            }
            graphics.drawString(font, part, x, y + line * 10, color, false);
            line++;
        }
    }

    /** Keeps accessibility narration from reading the unmasked EditBox value aloud. */
    private static final class SecretEditBox extends EditBox {
        private SecretEditBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE,
                    Component.translatable("screen.fakeaiplayer.ai_setup.key"));
        }
    }
}
