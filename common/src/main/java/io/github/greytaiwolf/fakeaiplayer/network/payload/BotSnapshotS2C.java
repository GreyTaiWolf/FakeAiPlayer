package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BotSnapshotS2C(
        String botName,
        float health,
        float maxHealth,
        int food,
        int x,
        int y,
        int z,
        String taskName,
        String taskState,
        float progress,
        boolean brainBusy,
        int promptTokens,
        int completionTokens,
        String goalTitle,
        String goalCurrentStep,
        int goalCurrentStepIndex,
        int goalTotalSteps,
        List<String> goalSteps,
        long goalResultSequence,
        String goalResultStatus,
        String goalResultSummary,
        int goalResultMatched,
        int goalResultRequired,
        boolean missionPaused,
        int executionStackDepth,
        String operatingProfile,
        List<String> effectiveCapabilities,
        boolean manualMode,
        boolean memoryToolsEnabled,
        boolean verboseReportsEnabled,
        List<ItemEntry> inventory,
        List<ItemEntry> equipment
) implements CustomPacketPayload {
    public static final Type<BotSnapshotS2C> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "bot_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotSnapshotS2C> CODEC = StreamCodec.ofMember(BotSnapshotS2C::write, BotSnapshotS2C::new);

    private BotSnapshotS2C(RegistryFriendlyByteBuf buf) {
        this(
                buf.readUtf(PayloadLimits.BOT_NAME_LENGTH),
                buf.readFloat(),
                buf.readFloat(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readUtf(PayloadLimits.TASK_NAME_LENGTH),
                buf.readUtf(PayloadLimits.TASK_STATE_LENGTH),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readInt(),
                buf.readUtf(PayloadLimits.GOAL_TEXT_LENGTH),
                buf.readUtf(PayloadLimits.GOAL_TEXT_LENGTH),
                buf.readInt(),
                buf.readInt(),
                readStrings(
                        buf,
                        PayloadLimits.MAX_GOAL_STEPS,
                        PayloadLimits.GOAL_TEXT_LENGTH,
                        "goalSteps"),
                buf.readLong(),
                buf.readUtf(PayloadLimits.GOAL_RESULT_STATUS_LENGTH),
                buf.readUtf(PayloadLimits.GOAL_RESULT_SUMMARY_LENGTH),
                buf.readInt(),
                buf.readInt(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readUtf(PayloadLimits.PROFILE_LENGTH),
                readStrings(
                        buf,
                        PayloadLimits.MAX_CAPABILITIES,
                        PayloadLimits.CAPABILITY_LENGTH,
                        "effectiveCapabilities"),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                readInventory(
                        buf,
                        PayloadLimits.MAX_INVENTORY_ENTRIES,
                        PayloadLimits.MAX_INVENTORY_SLOT,
                        "inventory"),
                readInventory(
                        buf,
                        PayloadLimits.MAX_EQUIPMENT_ENTRIES,
                        PayloadLimits.MAX_EQUIPMENT_SLOT,
                        "equipment"));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buf.writeFloat(health);
        buf.writeFloat(maxHealth);
        buf.writeInt(food);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeUtf(taskName, PayloadLimits.TASK_NAME_LENGTH);
        buf.writeUtf(taskState, PayloadLimits.TASK_STATE_LENGTH);
        buf.writeFloat(progress);
        buf.writeBoolean(brainBusy);
        buf.writeInt(promptTokens);
        buf.writeInt(completionTokens);
        buf.writeUtf(goalTitle, PayloadLimits.GOAL_TEXT_LENGTH);
        buf.writeUtf(goalCurrentStep, PayloadLimits.GOAL_TEXT_LENGTH);
        buf.writeInt(goalCurrentStepIndex);
        buf.writeInt(goalTotalSteps);
        writeStrings(
                buf,
                goalSteps,
                PayloadLimits.MAX_GOAL_STEPS,
                PayloadLimits.GOAL_TEXT_LENGTH,
                "goalSteps");
        buf.writeLong(goalResultSequence);
        buf.writeUtf(goalResultStatus, PayloadLimits.GOAL_RESULT_STATUS_LENGTH);
        buf.writeUtf(goalResultSummary, PayloadLimits.GOAL_RESULT_SUMMARY_LENGTH);
        buf.writeInt(goalResultMatched);
        buf.writeInt(goalResultRequired);
        buf.writeBoolean(missionPaused);
        buf.writeInt(executionStackDepth);
        buf.writeUtf(operatingProfile, PayloadLimits.PROFILE_LENGTH);
        writeStrings(
                buf,
                effectiveCapabilities,
                PayloadLimits.MAX_CAPABILITIES,
                PayloadLimits.CAPABILITY_LENGTH,
                "effectiveCapabilities");
        buf.writeBoolean(manualMode);
        buf.writeBoolean(memoryToolsEnabled);
        buf.writeBoolean(verboseReportsEnabled);
        writeInventory(
                buf,
                inventory,
                PayloadLimits.MAX_INVENTORY_ENTRIES,
                PayloadLimits.MAX_INVENTORY_SLOT,
                "inventory");
        writeInventory(
                buf,
                equipment,
                PayloadLimits.MAX_EQUIPMENT_ENTRIES,
                PayloadLimits.MAX_EQUIPMENT_SLOT,
                "equipment");
    }

    private static List<String> readStrings(
            RegistryFriendlyByteBuf buf, int maximumSize, int maximumStringLength, String field) {
        int size = PayloadLimits.readSize(buf, maximumSize, field);
        List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(buf.readUtf(maximumStringLength));
        }
        return values;
    }

    private static void writeStrings(
            RegistryFriendlyByteBuf buf,
            List<String> values,
            int maximumSize,
            int maximumStringLength,
            String field) {
        PayloadLimits.requireSize(values.size(), maximumSize, field);
        buf.writeInt(values.size());
        for (String value : values) {
            buf.writeUtf(value, maximumStringLength);
        }
    }

    private static List<ItemEntry> readInventory(
            RegistryFriendlyByteBuf buf, int maximumSize, int maximumSlot, String field) {
        int size = PayloadLimits.readSize(buf, maximumSize, field);
        List<ItemEntry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            String itemId = buf.readUtf(PayloadLimits.ITEM_ID_LENGTH);
            int count = buf.readInt();
            int slot = buf.readInt();
            validateItemEntry(itemId, count, slot, maximumSlot, field);
            entries.add(new ItemEntry(itemId, count, slot));
        }
        return entries;
    }

    private static void writeInventory(
            RegistryFriendlyByteBuf buf,
            List<ItemEntry> entries,
            int maximumSize,
            int maximumSlot,
            String field) {
        PayloadLimits.requireSize(entries.size(), maximumSize, field);
        buf.writeInt(entries.size());
        for (ItemEntry entry : entries) {
            validateItemEntry(entry.itemId(), entry.count(), entry.slot(), maximumSlot, field);
            buf.writeUtf(entry.itemId(), PayloadLimits.ITEM_ID_LENGTH);
            buf.writeInt(entry.count());
            buf.writeInt(entry.slot());
        }
    }

    private static void validateItemEntry(String itemId, int count, int slot, int maximumSlot, String field) {
        if (itemId == null || ResourceLocation.tryParse(itemId) == null) {
            throw new IllegalArgumentException(field + " contains an invalid item id");
        }
        if (count < 1 || count > PayloadLimits.MAX_ITEM_MOVE_AMOUNT) {
            throw new IllegalArgumentException(
                    field + " item count " + count + " is outside 1.." + PayloadLimits.MAX_ITEM_MOVE_AMOUNT);
        }
        if (slot < 0 || slot > maximumSlot) {
            throw new IllegalArgumentException(field + " slot " + slot + " is outside 0.." + maximumSlot);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record ItemEntry(String itemId, int count, int slot) {
    }
}
