package io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards;

import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class StatusCard extends PanelCard {
    @Override
    protected String titleKey() {
        return "card.fakeaiplayer.status";
    }

    @Override
    protected int bodyHeight() {
        return 100;
    }

    @Override
    protected void renderBody(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawString(renderer, Theme.tr("status.fakeaiplayer.waiting"), bx, by, Theme.TEXT_DIM);
            return;
        }
        drawStat(context, renderer, bx, by, bw, Theme.tr("status.fakeaiplayer.hp"), snapshot.health(), snapshot.maxHealth(), Theme.HP);
        drawStat(context, renderer, bx, by + 20, bw, Theme.tr("status.fakeaiplayer.food"), snapshot.food(), 20.0F, Theme.FOOD);
        drawStat(context, renderer, bx, by + 40, bw, Theme.tr("status.fakeaiplayer.progress"), snapshot.progress(), 1.0F, Theme.OK);

        String task = Theme.tr("task.fakeaiplayer." + snapshot.taskName());
        if (task.equals("task.fakeaiplayer." + snapshot.taskName())) {
            task = snapshot.taskName();
        }
        String brain = snapshot.brainBusy() ? Theme.tr("status.fakeaiplayer.brain.busy") : Theme.tr("status.fakeaiplayer.brain.idle");
        int brainColor = snapshot.brainBusy() ? Theme.ACCENT : Theme.TEXT_DIM;
        context.drawString(renderer, Theme.tr("status.fakeaiplayer.task", task, snapshot.taskState()), bx, by + 61, Theme.TEXT);
        context.drawString(renderer, brain, bx, by + 73, brainColor);
        String tokens = Theme.tr("status.fakeaiplayer.tokens", snapshot.promptTokens(), snapshot.completionTokens());
        context.drawString(renderer, tokens, bx + Math.max(0, bw - renderer.width(tokens)), by + 73, Theme.TEXT_DIM);
        // 实时坐标:快照由服务端周期下发,bot 移动即刷新(挖矿/下潜时可直接看到 bot 在哪)。
        String pos = Theme.tr("status.fakeaiplayer.pos", snapshot.x(), snapshot.y(), snapshot.z());
        context.drawString(renderer, pos, bx, by + 87, Theme.TEXT);
    }

    private static void drawStat(GuiGraphics context, Font renderer, int x, int y, int w, String label, float value, float max, int color) {
        String text = max == 1.0F ? label + " " + (int) (value * 100) + "%" : label + " " + (int) value + "/" + (int) max;
        context.drawString(renderer, text, x, y, Theme.TEXT);
        Theme.bar(context, x, y + 10, w, 7, max <= 0.0F ? 0.0F : value / max, color);
    }
}
