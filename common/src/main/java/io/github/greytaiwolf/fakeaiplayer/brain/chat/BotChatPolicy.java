package io.github.greytaiwolf.fakeaiplayer.brain.chat;

import java.util.Locale;

/**
 * Pure formatting and audience policy for messages emitted by a bot.
 *
 * <p>Panel text is deliberately longer than the owner's normal chat line. The panel remains the
 * detailed diagnostic surface, while the Minecraft chat stream gets concise, single-line output.
 */
public final class BotChatPolicy {
    public static final int PANEL_TEXT_LIMIT = 2_048;
    public static final int OWNER_BOT_TEXT_LIMIT = 384;
    public static final int OWNER_SYSTEM_TEXT_LIMIT = 160;
    public static final int BOT_NAME_LIMIT = 32;

    public static final int BOT_CHAT_COOLDOWN_TICKS = 20;
    public static final int SYSTEM_CHAT_COOLDOWN_TICKS = 60;

    private BotChatPolicy() {
    }

    public static PreparedMessage prepare(String role, String text) {
        MessageKind kind = MessageKind.fromRole(role);
        String panelText = sanitize(text, PANEL_TEXT_LIMIT);
        if (panelText.isEmpty()) {
            return new PreparedMessage(kind.panelRole(), "", "", false, 0);
        }
        return switch (kind) {
            case BOT -> new PreparedMessage(
                    kind.panelRole(),
                    panelText,
                    truncate(panelText, OWNER_BOT_TEXT_LIMIT),
                    true,
                    BOT_CHAT_COOLDOWN_TICKS);
            case SYSTEM -> new PreparedMessage(
                    kind.panelRole(),
                    panelText,
                    truncate(panelText, OWNER_SYSTEM_TEXT_LIMIT),
                    true,
                    SYSTEM_CHAT_COOLDOWN_TICKS);
            case USER, OTHER -> new PreparedMessage(
                    kind.panelRole(),
                    panelText,
                    "",
                    false,
                    0);
        };
    }

    public static String sanitizeBotName(String name) {
        String sanitized = sanitize(name, BOT_NAME_LIMIT);
        if (sanitized.isEmpty()) {
            return "Bot";
        }
        StringBuilder safe = new StringBuilder(sanitized.length());
        for (int offset = 0; offset < sanitized.length();) {
            int codePoint = sanitized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            safe.appendCodePoint(Character.isLetterOrDigit(codePoint)
                    || codePoint == '_'
                    || codePoint == '-'
                    ? codePoint
                    : '_');
        }
        return safe.toString();
    }

    /** Removes control/format characters, folds whitespace, and applies a code-point-safe limit. */
    public static String sanitize(String value, int maxCodePoints) {
        if (value == null || value.isBlank() || maxCodePoints <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxCodePoints));
        boolean pendingSpace = false;
        int written = 0;
        for (int offset = 0; offset < value.length() && written < maxCodePoints;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            boolean unsafe = Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || codePoint == '\u00a7';
            if (unsafe || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = !builder.isEmpty();
                continue;
            }
            if (pendingSpace && written < maxCodePoints) {
                builder.append(' ');
                written++;
            }
            pendingSpace = false;
            if (written >= maxCodePoints) {
                break;
            }
            builder.appendCodePoint(codePoint);
            written++;
        }
        return builder.toString().trim();
    }

    public static String truncate(String value, int maxCodePoints) {
        if (value == null || value.isEmpty() || maxCodePoints <= 0) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        if (maxCodePoints == 1) {
            return "\u2026";
        }
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end).stripTrailing() + "\u2026";
    }

    public enum MessageKind {
        BOT("bot"),
        SYSTEM("system"),
        USER("user"),
        OTHER("system");

        private final String panelRole;

        MessageKind(String panelRole) {
            this.panelRole = panelRole;
        }

        public String panelRole() {
            return panelRole;
        }

        public static MessageKind fromRole(String role) {
            if (role == null) {
                return OTHER;
            }
            return switch (role.trim().toLowerCase(Locale.ROOT)) {
                case "bot", "assistant" -> BOT;
                case "system" -> SYSTEM;
                case "user", "player" -> USER;
                default -> OTHER;
            };
        }
    }

    public record PreparedMessage(
            String panelRole,
            String panelText,
            String ownerText,
            boolean ownerVisible,
            int ownerCooldownTicks
    ) {
        public boolean empty() {
            return panelText.isEmpty();
        }
    }
}
