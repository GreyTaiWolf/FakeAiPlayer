package io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards;

import io.github.greytaiwolf.fakeaiplayer.client.BotCommandBridge;
import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.Theme;
import io.github.greytaiwolf.fakeaiplayer.client.BotClientState;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.util.List;

public final class QuickActionCard extends PanelCard {
    private static final int INPUT_H = 18;

    private final String target;
    private EditBox idField;
    private EditBox countField;
    private Button comeButton;
    private Button pauseButton;
    private Button stopButton;
    private Button eatButton;
    private Button sleepButton;
    private Button mineButton;
    private Button craftButton;
    private Button smeltButton;

    public QuickActionCard(String target) {
        this.target = target == null ? "" : target;
    }

    @Override
    protected String titleKey() {
        return "card.fakeaiplayer.quick";
    }

    @Override
    protected int bodyHeight() {
        return 82;
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        super.setBounds(x, y, w, h);
        layoutWidgets();
    }

    @Override
    public void addWidgets(Consumer<AbstractWidget> sink) {
        Font renderer = Minecraft.getInstance().font;
        idField = new EditBox(renderer, 0, 0, 84, INPUT_H, Component.translatable("quick.fakeaiplayer.id"));
        idField.setValue("minecraft:stone");
        idField.setMaxLength(128);
        idField.setSuggestion(Theme.tr("quick.fakeaiplayer.id"));
        countField = new EditBox(renderer, 0, 0, 36, INPUT_H, Component.translatable("quick.fakeaiplayer.count"));
        countField.setValue("1");
        countField.setMaxLength(3);
        countField.setSuggestion(Theme.tr("quick.fakeaiplayer.count"));

        comeButton = button("btn.fakeaiplayer.come", () -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                BotCommandBridge.command(target, "move", client.player.blockPosition().toShortString().replace(",", ""), "", 1);
            }
        });
        pauseButton = button("btn.fakeaiplayer.pause", () -> BotCommandBridge.command(
                target, snapshot != null && snapshot.missionPaused() ? "resume" : "pause", "", "", 1));
        stopButton = button("btn.fakeaiplayer.stop", () -> BotCommandBridge.command(target, "abort", "", "", 1));
        eatButton = button("btn.fakeaiplayer.eat", () -> BotCommandBridge.command(target, "eat", "", "", 1));
        sleepButton = button("btn.fakeaiplayer.sleep", () -> BotCommandBridge.command(target, "sleep", "", "", 1));
        mineButton = button("btn.fakeaiplayer.mine", () -> BotCommandBridge.command(target, "mine", idField.getValue(), "", count()));
        craftButton = button("btn.fakeaiplayer.craft", () -> BotCommandBridge.command(target, "craft", idField.getValue(), "", count()));
        smeltButton = button("btn.fakeaiplayer.smelt", () -> BotCommandBridge.command(target, "smelt", idField.getValue(), "minecraft:iron_ingot", count()));

        layoutWidgets();
        sink.accept(idField);
        sink.accept(countField);
        sink.accept(comeButton);
        sink.accept(pauseButton);
        sink.accept(stopButton);
        sink.accept(eatButton);
        sink.accept(sleepButton);
        sink.accept(mineButton);
        sink.accept(craftButton);
        sink.accept(smeltButton);
    }

    @Override
    public void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat) {
        super.refresh(snapshot, chat);
        if (pauseButton != null) {
            pauseButton.setMessage(Component.translatable(snapshot != null && snapshot.missionPaused()
                    ? "btn.fakeaiplayer.resume" : "btn.fakeaiplayer.pause"));
        }
    }

    @Override
    protected void renderBody(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer, int bx, int by, int bw, int bh) {
        context.drawString(renderer, Theme.tr("quick.fakeaiplayer.input_hint"), bx, by, Theme.TEXT_DIM);
    }

    private Button button(String key, Runnable action) {
        return Button.builder(Component.translatable(key), button -> action.run()).bounds(0, 0, 38, INPUT_H).build();
    }

    private void layoutWidgets() {
        if (idField == null) {
            return;
        }
        int bx = x + Theme.PAD;
        int by = y + 40;
        int bw = w - Theme.PAD * 2;
        int fifth = Math.max(24, (bw - 12) / 5);
        comeButton.setPosition(bx, by);
        comeButton.setSize(fifth, INPUT_H);
        pauseButton.setPosition(bx + fifth + 3, by);
        pauseButton.setSize(fifth, INPUT_H);
        stopButton.setPosition(bx + fifth * 2 + 6, by);
        stopButton.setSize(fifth, INPUT_H);
        eatButton.setPosition(bx + fifth * 3 + 9, by);
        eatButton.setSize(fifth, INPUT_H);
        sleepButton.setPosition(bx + fifth * 4 + 12, by);
        sleepButton.setSize(Math.max(24, bw - fifth * 4 - 12), INPUT_H);
        idField.setPosition(bx, by + 22);
        idField.setSize(Math.max(76, bw - 42), INPUT_H);
        countField.setPosition(bx + bw - 36, by + 22);
        countField.setSize(36, INPUT_H);
        int third = Math.max(34, (bw - 8) / 3);
        mineButton.setPosition(bx, by + 44);
        mineButton.setSize(third, INPUT_H);
        craftButton.setPosition(bx + third + 4, by + 44);
        craftButton.setSize(third, INPUT_H);
        smeltButton.setPosition(bx + third * 2 + 8, by + 44);
        smeltButton.setSize(bw - third * 2 - 8, INPUT_H);
    }

    private int count() {
        try {
            return Math.max(1, Integer.parseInt(countField.getValue().trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }
}
