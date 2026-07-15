package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Wire-level limits shared by both loaders.
 *
 * <p>These limits are deliberately lower than the generic Minecraft UTF/string and payload
 * limits. They describe this mod's protocol, prevent a peer from requesting large allocations,
 * and make client/server validation consistent.</p>
 */
public final class PayloadLimits {
    public static final int BOT_NAME_LENGTH = 32;
    public static final int ACTION_LENGTH = 32;
    public static final int OPTION_KEY_LENGTH = 32;
    public static final int COMMAND_ARGUMENT_LENGTH = 1_024;

    public static final int ROLE_LENGTH = 32;
    public static final int CHAT_TEXT_LENGTH = 8_192;

    public static final int TASK_NAME_LENGTH = 128;
    public static final int TASK_STATE_LENGTH = 64;
    public static final int GOAL_TEXT_LENGTH = 1_024;
    public static final int GOAL_RESULT_STATUS_LENGTH = 64;
    public static final int GOAL_RESULT_SUMMARY_LENGTH = 4_096;
    public static final int PROFILE_LENGTH = 64;
    public static final int CAPABILITY_LENGTH = 64;
    public static final int ITEM_ID_LENGTH = 256;
    public static final int PREVIEW_PLAN_ID_LENGTH = 160;
    public static final int PREVIEW_HASH_LENGTH = 64;
    public static final int PREVIEW_DIMENSION_LENGTH = 256;
    public static final int PREVIEW_REASON_LENGTH = 512;
    public static final int BLOCK_PROPERTY_LENGTH = 64;

    public static final int MAX_GOAL_STEPS = 64;
    public static final int MAX_CAPABILITIES = 64;
    public static final int MAX_INVENTORY_ENTRIES = 64;
    public static final int MAX_EQUIPMENT_ENTRIES = 6;
    public static final int MAX_PREVIEW_PLACEMENTS = 4_096;
    public static final int MAX_PREVIEW_PALETTE = 256;
    public static final int MAX_PREVIEW_PROPERTIES = 16;
    public static final int MAX_PREVIEW_CHUNK_CELLS = 256;
    public static final int MAX_PREVIEW_CHUNKS = 16;
    public static final int MAX_PREVIEW_BEGIN_BYTES = 256 * 1_024;

    public static final int MAX_COMMAND_COUNT = 4_096;
    public static final int MAX_INVENTORY_SLOT = 63;
    public static final int MAX_EQUIPMENT_SLOT = 5;
    /** Vanilla 1.21 item stack component range is 1..99; zero means "the whole stack" here. */
    public static final int MAX_ITEM_MOVE_AMOUNT = 99;

    private PayloadLimits() {
    }

    public static int readSize(RegistryFriendlyByteBuf buf, int maximum, String field) {
        int size = buf.readInt();
        if (size < 0 || size > maximum) {
            throw new DecoderException(field + " size " + size + " is outside 0.." + maximum);
        }
        return size;
    }

    public static void requireSize(int size, int maximum, String field) {
        if (size < 0 || size > maximum) {
            throw new IllegalArgumentException(field + " size " + size + " is outside 0.." + maximum);
        }
    }

    public static boolean validBotName(String value) {
        return value != null && !value.isBlank() && value.length() <= BOT_NAME_LENGTH;
    }

    public static void requireUtf(String value, int maximumLength, String field) {
        if (value == null || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " length is outside 0.." + maximumLength);
        }
    }

    /** Conservative encoded size: UTF-8 bytes plus the maximum five-byte VarInt prefix. */
    public static int estimatedUtfBytes(String value) {
        return 5 + (value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length);
    }

    public static boolean validSha256Hex(String value) {
        if (value == null || value.length() != PREVIEW_HASH_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** Bounds dynamic server-generated text without splitting a UTF-16 surrogate pair. */
    public static String truncate(String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maximumLength) {
            return value;
        }
        int end = maximumLength;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
