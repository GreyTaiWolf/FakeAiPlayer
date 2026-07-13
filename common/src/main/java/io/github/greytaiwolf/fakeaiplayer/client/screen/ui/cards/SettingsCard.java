package io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards;

import io.github.greytaiwolf.fakeaiplayer.client.BotClientState;
import io.github.greytaiwolf.fakeaiplayer.client.BotCommandBridge;
import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.Theme;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public final class SettingsCard extends PanelCard {
    private static final int BUTTON_H = 18;

    private final String target;
    private Button manualButton;
    private Button memoryButton;
    private Button reportsButton;
    private Button teleportToButton;  // 传送至 AI(玩家→AI 附近)
    private Button recallButton;      // 召回 AI(AI→玩家附近)

    public SettingsCard(String target) {
        this.target = target == null ? "" : target;
    }

    @Override
    protected String titleKey() {
        return "card.fakeaiplayer.settings";
    }

    @Override
    protected int bodyHeight() {
        return 118;
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        super.setBounds(x, y, w, h);
        layoutWidgets();
    }

    @Override
    public void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat) {
        super.refresh(snapshot, chat);
        updateLabels();
    }

    @Override
    public void addWidgets(Consumer<AbstractWidget> sink) {
        manualButton = button("settings.fakeaiplayer.manual", () -> toggle("manual", snapshot == null || !snapshot.manualMode()));
        memoryButton = button("settings.fakeaiplayer.memory", () -> toggle("memory", snapshot == null || !snapshot.memoryToolsEnabled()));
        reportsButton = button("settings.fakeaiplayer.reports", () -> toggle("reports", snapshot == null || !snapshot.verboseReportsEnabled()));
        teleportToButton = button("settings.fakeaiplayer.tp_to_ai",
                () -> BotCommandBridge.teleport(target, io.github.greytaiwolf.fakeaiplayer.network.payload.BotTeleportC2S.TO_AI));
        recallButton = button("settings.fakeaiplayer.recall_ai",
                () -> BotCommandBridge.teleport(target, io.github.greytaiwolf.fakeaiplayer.network.payload.BotTeleportC2S.RECALL_AI));
        layoutWidgets();
        updateLabels();
        sink.accept(teleportToButton);
        sink.accept(recallButton);
        sink.accept(manualButton);
        sink.accept(memoryButton);
        sink.accept(reportsButton);
    }

    @Override
    protected void renderBody(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer, int bx, int by, int bw, int bh) {
        String bot = snapshot == null ? (target.isBlank() ? Theme.tr("screen.fakeaiplayer.owner_bot") : target) : snapshot.botName();
        context.drawString(renderer, Theme.tr("settings.fakeaiplayer.target", bot), bx, by, Theme.TEXT_DIM);
        if (snapshot == null) {
            context.drawString(renderer, Theme.tr("status.fakeaiplayer.waiting"), bx, by + 14, Theme.TEXT_DIM);
        } else {
            String key = "strict_survival".equals(snapshot.operatingProfile())
                    ? "settings.fakeaiplayer.profile.strict" : "settings.fakeaiplayer.profile.operator";
            context.drawString(renderer, Theme.tr("settings.fakeaiplayer.profile", Theme.tr(key)),
                    bx, by + 14, "strict_survival".equals(snapshot.operatingProfile()) ? 0xFF65C18C : 0xFFE4A853);
        }
    }

    private Button button(String key, Runnable action) {
        return Button.builder(Component.translatable(key), button -> action.run()).bounds(0, 0, 120, BUTTON_H).build();
    }

    private void toggle(String key, boolean value) {
        BotCommandBridge.setOption(target, key, value);
    }

    private void layoutWidgets() {
        if (teleportToButton == null) {
            return;
        }
        int bx = x + Theme.PAD;
        int bw = w - Theme.PAD * 2;
        // 传送行:两个按钮横排(各占一半)。
        int half = (bw - 4) / 2;
        int tpY = y + 32;
        teleportToButton.setPosition(bx, tpY);
        teleportToButton.setSize(half, BUTTON_H);
        recallButton.setPosition(bx + half + 4, tpY);
        recallButton.setSize(half, BUTTON_H);
        // 三个开关竖排。
        int by = tpY + 24;
        manualButton.setPosition(bx, by);
        manualButton.setSize(bw, BUTTON_H);
        memoryButton.setPosition(bx, by + 22);
        memoryButton.setSize(bw, BUTTON_H);
        reportsButton.setPosition(bx, by + 44);
        reportsButton.setSize(bw, BUTTON_H);
    }

    private void updateLabels() {
        if (manualButton == null) {
            return;
        }
        manualButton.setMessage(label("settings.fakeaiplayer.manual", snapshot != null && snapshot.manualMode()));
        memoryButton.setMessage(label("settings.fakeaiplayer.memory", snapshot == null || snapshot.memoryToolsEnabled()));
        reportsButton.setMessage(label("settings.fakeaiplayer.reports", snapshot == null || snapshot.verboseReportsEnabled()));
        boolean teleportEnabled = snapshot != null && snapshot.effectiveCapabilities().contains("MANUAL_TELEPORT");
        teleportToButton.active = teleportEnabled;
        recallButton.active = teleportEnabled;
    }

    private static Component label(String key, boolean enabled) {
        return Component.literal(Theme.tr(key) + ": " + Theme.tr(enabled ? "settings.fakeaiplayer.on" : "settings.fakeaiplayer.off"));
    }
}
