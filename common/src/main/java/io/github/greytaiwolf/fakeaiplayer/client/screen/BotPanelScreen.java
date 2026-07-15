package io.github.greytaiwolf.fakeaiplayer.client.screen;

import io.github.greytaiwolf.fakeaiplayer.client.BotClientState;
import io.github.greytaiwolf.fakeaiplayer.client.BotCommandBridge;
import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.ChatView;
import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.PanelComponent;
import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.Theme;
import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards.GoalView;
import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards.QuickActionCard;
import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards.SettingsCard;
import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards.StatusCard;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class BotPanelScreen extends Screen {
    public enum Mode {
        CHAT_STATUS,
        ACTIONS,
        SETTINGS,
        GOAL
    }

    private final List<PanelComponent> leftCards = new ArrayList<>();
    private final List<PanelComponent> laidOutCards = new ArrayList<>();
    private final List<AbstractWidget> panelWidgets = new ArrayList<>();
    private final Mode mode;
    private ChatView chat;
    private EditBox input;
    private Button sendButton;
    private Button goalButton;
    private Button inventoryButton;
    private Button settingsButton;
    private Button closeButton;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private int leftW;
    private int rightW;
    private String target = "";

    public BotPanelScreen(Mode mode) {
        super(mode == Mode.GOAL ? Component.literal("目标与执行链")
                : Component.translatable(mode == Mode.ACTIONS ? "screen.fakeaiplayer.actions_panel"
                : mode == Mode.SETTINGS ? "screen.fakeaiplayer.settings_panel"
                : "screen.fakeaiplayer.panel"));
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    @Override
    protected void init() {
        target = BotClientState.INSTANCE.targetBot();
        panelWidgets.clear();
        computeLayout();
        buildCards();
        layoutComponents();
        if (mode == Mode.CHAT_STATUS) {
            int stripY = py + ph - Theme.INPUT_H - Theme.PAD + 1;
            input = new EditBox(font, px + leftW + Theme.GUTTER + Theme.PAD, stripY,
                    Math.max(60, rightW - Theme.PAD * 2 - 54), 18, Component.translatable("chat.fakeaiplayer.input"));
            input.setMaxLength(512);
            input.setSuggestion(Theme.tr("chat.fakeaiplayer.input"));
            sendButton = Button.builder(Component.translatable("btn.fakeaiplayer.send"), button -> sendChat())
                    .bounds(px + pw - Theme.PAD - 48, stripY, 48, 18)
                    .build();
            register(input);
            register(sendButton);
        }
        goalButton = Button.builder(Component.literal(mode == Mode.GOAL ? "聊天" : "目标"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new BotPanelScreen(mode == Mode.GOAL ? Mode.CHAT_STATUS : Mode.GOAL));
                    }
                })
                .bounds(px + pw - 178, py + 4, 40, 14)
                .build();
        inventoryButton = Button.builder(Component.translatable("btn.fakeaiplayer.inventory"), button -> {
                    if (minecraft != null) {
                        BotCommandBridge.subscribe(target, false);
                        BotCommandBridge.openInventory(target);
                        minecraft.setScreen(null);
                    }
                })
                .bounds(px + pw - 134, py + 4, 40, 14)
                .build();
        settingsButton = Button.builder(Component.translatable(mode == Mode.SETTINGS ? "btn.fakeaiplayer.chat" : "btn.fakeaiplayer.settings"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new BotPanelScreen(mode == Mode.SETTINGS ? Mode.CHAT_STATUS : Mode.SETTINGS));
                    }
                })
                .bounds(px + pw - 90, py + 4, 40, 14)
                .build();
        closeButton = Button.builder(Component.translatable("btn.fakeaiplayer.close"), button -> onClose())
                .bounds(px + pw - 46, py + 4, 38, 14)
                .build();
        register(goalButton);
        register(inventoryButton);
        register(settingsButton);
        register(closeButton);
        for (PanelComponent card : laidOutCards) {
            card.addWidgets(this::register);
        }
        BotCommandBridge.subscribe(target, true);
        if (input != null) {
            setInitialFocus(input);
        }
    }

    private void register(AbstractWidget widget) {
        addRenderableWidget(widget);   // 注册以接管点击/焦点/输入路由
        panelWidgets.add(widget);   // 渲染由本类手动负责(见 render),绕开 super.render 的背景模糊 pass
    }

    @Override
    public void onClose() {
        BotCommandBridge.subscribe(target, false);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (chat != null && chat.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        for (PanelComponent card : laidOutCards) {
            if (card.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (PanelComponent card : laidOutCards) {
            if (card.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && input != null && input.isFocused()) {
            sendChat();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        Theme.panel(context, px, py, pw, ph, Theme.PANEL_BG);
        drawTitleBar(context);
        BotSnapshotS2C snapshot = BotClientState.INSTANCE.snapshot();
        List<BotClientState.ChatLine> transcript = BotClientState.INSTANCE.transcript();
        for (PanelComponent card : laidOutCards) {
            card.refresh(snapshot, transcript);
            card.render(context, mouseX, mouseY, delta, font);
        }
        if (chat != null) {
            chat.refresh(snapshot, transcript);
            chat.render(context, mouseX, mouseY, delta, font);
        }
        if (mode == Mode.CHAT_STATUS) {
            drawInputStrip(context);
        }
        // 手动渲染控件,不调用 super.render()——绕开 1.21 屏幕背景模糊/暗化 pass(否则面板内容会被模糊,而控件清晰)。
        for (AbstractWidget widget : panelWidgets) {
            widget.render(context, mouseX, mouseY, delta);
        }
    }

    private void computeLayout() {
        boolean docked = width >= 420;
        if (mode == Mode.ACTIONS) {
            pw = docked ? Math.min(260, Math.max(220, (int) (width * 0.24F))) : Math.max(220, width - 20);
            ph = Math.max(150, Math.min(height - 24, 180));
        } else if (mode == Mode.SETTINGS) {
            pw = docked ? Math.min(300, Math.max(260, (int) (width * 0.28F))) : Math.max(240, width - 20);
            ph = Math.max(150, Math.min(height - 24, 190));
        } else if (mode == Mode.GOAL) {
            // 目标与执行链:单栏,容下完整步骤链
            pw = docked ? Math.min(300, Math.max(240, (int) (width * 0.26F))) : Math.max(240, width - 20);
            ph = Math.max(180, Math.min(height - 24, 260));
        } else {
            pw = docked ? Math.min(520, Math.max(360, (int) (width * 0.48F))) : Math.max(240, width - 20);
            // 关键:ph 必须能装进屏幕(含上下边距),否则底部输入框会被挤出屏幕下沿
            ph = Math.max(160, Math.min(height - 24, 380));
        }
        px = docked ? width - pw - 12 : (width - pw) / 2;
        py = 12;
        leftW = mode == Mode.ACTIONS || mode == Mode.SETTINGS || mode == Mode.GOAL ? pw : Math.max(160, Math.round(pw * 0.42F));
        rightW = pw - leftW - Theme.GUTTER;
    }

    private void buildCards() {
        leftCards.clear();
        if (mode == Mode.ACTIONS) {
            leftCards.add(new QuickActionCard(target));
            chat = null;
            return;
        }
        if (mode == Mode.SETTINGS) {
            leftCards.add(new SettingsCard(target));
            chat = null;
            return;
        }
        if (mode == Mode.GOAL) {
            leftCards.add(new GoalView());
            chat = null;
            return;
        }
        // CHAT_STATUS:只放状态卡(血/饱食/进度/任务)。目标与执行链走顶部"目标"按钮,背包走"背包"按钮。
        leftCards.add(new StatusCard());
        chat = new ChatView();
    }

    private void layoutComponents() {
        laidOutCards.clear();
        int leftX = px + Theme.PAD;
        int cardY = py + Theme.TITLE_H + Theme.PAD;
        int cardW = leftW - Theme.PAD * 2;
        // CHAT_STATUS 左栏底线要给底部输入条留位;其它模式用到面板底
        int bottom = py + ph - Theme.PAD - (mode == Mode.CHAT_STATUS ? Theme.INPUT_H : 0);
        for (PanelComponent card : leftCards) {
            int remaining = bottom - cardY;
            if (remaining < 40) {
                break;  // 放不下就不再布局,避免未布局卡以默认 (0,0) 渲染到面板外
            }
            int cardH = Math.min(card.preferredHeight(), remaining);
            card.setBounds(leftX, cardY, cardW, cardH);
            laidOutCards.add(card);
            cardY += cardH + Theme.GUTTER;
        }
        if (chat != null) {
            int rightX = px + leftW + Theme.GUTTER;
            int rightY = py + Theme.TITLE_H + Theme.PAD;
            int chatH = ph - Theme.TITLE_H - Theme.PAD * 3 - Theme.INPUT_H;
            chat.setBounds(rightX + Theme.PAD, rightY, rightW - Theme.PAD * 2, Math.max(40, chatH));
        }
    }

    private void drawTitleBar(GuiGraphics context) {
        String name = displayTarget();
        if (mode == Mode.GOAL) {
            context.drawString(font, Component.literal("目标与执行链 · " + name), px + Theme.PAD, py + 6, Theme.TEXT_STRONG);
        } else {
            String titleKey = mode == Mode.ACTIONS ? "screen.fakeaiplayer.actions_title"
                    : mode == Mode.SETTINGS ? "screen.fakeaiplayer.settings_title"
                    : "screen.fakeaiplayer.title";
            context.drawString(font, Theme.tr(titleKey, name), px + Theme.PAD, py + 6, Theme.TEXT_STRONG);
        }
        context.hLine(px + Theme.PAD, px + pw - Theme.PAD - 1, py + Theme.TITLE_H, Theme.BORDER);
        if (mode == Mode.CHAT_STATUS) {
            context.vLine(px + leftW, py + Theme.TITLE_H + 1, py + ph - Theme.PAD, Theme.BORDER);
        }
    }

    private void drawInputStrip(GuiGraphics context) {
        int x = px + leftW + Theme.GUTTER + Theme.PAD - 2;
        int y = py + ph - Theme.INPUT_H - Theme.PAD - 1;
        int w = rightW - Theme.PAD * 2 + 4;
        int h = Theme.INPUT_H + 2;
        Theme.panel(context, x, y, w, h, Theme.CHAT_INPUT_BG);
        context.hLine(x + 1, x + w - 2, y + 1, Theme.BORDER_BRIGHT);
    }

    private String displayTarget() {
        BotSnapshotS2C snapshot = BotClientState.INSTANCE.snapshot();
        if (snapshot != null) {
            return snapshot.botName();
        }
        return target == null || target.isBlank() ? Theme.tr("screen.fakeaiplayer.owner_bot") : target;
    }

    private void sendChat() {
        if (input == null) {
            return;
        }
        String text = input.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        BotCommandBridge.chat(target, text);
        input.setValue("");
    }
}
