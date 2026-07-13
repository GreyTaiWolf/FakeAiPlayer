package io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards;

import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.Theme;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

public final class GoalCard extends PanelCard {
    @Override
    protected String titleKey() {
        return "card.fakeaiplayer.goal";
    }

    @Override
    protected int bodyHeight() {
        return 74;
    }

    @Override
    protected void renderBody(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawString(renderer, Theme.tr("status.fakeaiplayer.waiting"), bx, by, Theme.TEXT_DIM);
            return;
        }
        if (snapshot.goalTotalSteps() <= 0) {
            renderResultOrEmpty(context, renderer, bx, by, bw);
            return;
        }
        String title = snapshot.goalTitle().isBlank() ? Theme.tr("goal.fakeaiplayer.untitled") : localize(snapshot.goalTitle());
        context.drawString(renderer, trim(renderer, title, bw), bx, by, Theme.TEXT_STRONG);
        int currentNumber = Math.min(snapshot.goalCurrentStepIndex() + 1, snapshot.goalTotalSteps());
        String progress = Theme.tr("goal.fakeaiplayer.progress", currentNumber, snapshot.goalTotalSteps());
        context.drawString(renderer, progress, bx, by + 12, Theme.TEXT_DIM);
        int maxRows = Math.min(3, snapshot.goalSteps().size());
        int start = Math.max(0, Math.min(snapshot.goalCurrentStepIndex(), Math.max(0, snapshot.goalSteps().size() - maxRows)));
        for (int index = 0; index < maxRows; index++) {
            int stepIndex = start + index;
            if (stepIndex >= snapshot.goalSteps().size()) {
                break;
            }
            boolean current = stepIndex == snapshot.goalCurrentStepIndex();
            int color = current ? Theme.ACCENT : stepIndex < snapshot.goalCurrentStepIndex() ? Theme.OK : Theme.TEXT_DIM;
            String marker = current ? ">" : stepIndex < snapshot.goalCurrentStepIndex() ? "x" : "-";
            context.drawString(renderer, trim(renderer, marker + " " + localize(snapshot.goalSteps().get(stepIndex)), bw), bx, by + 27 + index * Theme.LINE_H, color);
        }
    }

    private void renderResultOrEmpty(GuiGraphics context, Font renderer, int bx, int by, int bw) {
        if (snapshot.goalResultStatus().isBlank()) {
            context.drawString(renderer, Theme.tr("goal.fakeaiplayer.empty"), bx, by, Theme.TEXT_DIM);
            return;
        }
        int color = resultColor(snapshot.goalResultStatus());
        context.drawString(renderer, Theme.tr("goal.fakeaiplayer.result." + snapshot.goalResultStatus().toLowerCase(java.util.Locale.ROOT)),
                bx, by, color);
        context.drawString(renderer, trim(renderer, snapshot.goalResultSummary(), bw), bx, by + 14, Theme.TEXT);
        context.drawString(renderer,
                Theme.tr("goal.fakeaiplayer.evidence", snapshot.goalResultMatched(), snapshot.goalResultRequired()),
                bx, by + 28, Theme.TEXT_DIM);
    }

    private static int resultColor(String status) {
        return switch (status) {
            case "COMPLETED" -> Theme.OK;
            case "PARTIAL" -> Theme.SYS;
            case "FAILED" -> Theme.HP;
            default -> Theme.TEXT_DIM;
        };
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

    // 把字符串里的 minecraft:xxx 本地化成中文物品/方块名(客户端有语言文件,服务端没有)。
    private static final Pattern ID_PATTERN = Pattern.compile("minecraft:[a-z0-9_./]+");

    private static String localize(String text) {
        if (text == null || text.indexOf(':') < 0) {
            return text;
        }
        Matcher m = ID_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(idToName(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String idToName(String id) {
        try {
            ResourceLocation ident = ResourceLocation.parse(id);
            if (BuiltInRegistries.ITEM.containsKey(ident)) {
                return BuiltInRegistries.ITEM.getValue(ident).getName().getString();
            }
            if (BuiltInRegistries.BLOCK.containsKey(ident)) {
                return BuiltInRegistries.BLOCK.getValue(ident).getName().getString();
            }
        } catch (RuntimeException ignored) {
            // 解析失败保留原 id
        }
        return id;
    }
}
