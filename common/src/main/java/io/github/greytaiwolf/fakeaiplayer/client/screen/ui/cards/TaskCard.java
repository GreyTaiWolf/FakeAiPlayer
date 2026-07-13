package io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards;

import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class TaskCard extends PanelCard {
    @Override
    protected String titleKey() {
        return "card.fakeaiplayer.task";
    }

    @Override
    protected int bodyHeight() {
        return 58;
    }

    @Override
    protected void renderBody(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawString(renderer, Theme.tr("status.fakeaiplayer.waiting"), bx, by, Theme.TEXT_DIM);
            return;
        }
        String task = Theme.tr("task.fakeaiplayer." + snapshot.taskName());
        if (task.equals("task.fakeaiplayer." + snapshot.taskName())) {
            task = snapshot.taskName();
        }
        context.drawString(renderer, task + " / " + snapshot.taskState(), bx, by, Theme.TEXT_STRONG);
        Theme.bar(context, bx, by + 14, bw, 8, snapshot.progress(), Theme.OK);
        String progress = (int) (snapshot.progress() * 100.0F) + "%";
        context.drawString(renderer, progress, bx + Math.max(0, bw - renderer.width(progress)), by, Theme.TEXT_DIM);
        String phase = Theme.tr("task.fakeaiplayer.phase", snapshot.taskName(), snapshot.taskState());
        context.drawString(renderer, trim(renderer, phase, bw), bx, by + 28, Theme.TEXT_DIM);
    }

    private static String trim(Font renderer, String value, int maxWidth) {
        if (renderer.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            String candidate = builder.toString() + value.charAt(index) + suffix;
            if (renderer.width(candidate) > maxWidth) {
                break;
            }
            builder.append(value.charAt(index));
        }
        return builder + suffix;
    }
}
