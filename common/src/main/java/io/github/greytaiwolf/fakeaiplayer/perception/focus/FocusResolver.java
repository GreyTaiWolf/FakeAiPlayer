package io.github.greytaiwolf.fakeaiplayer.perception.focus;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Main-thread-only deterministic raycast and semantic projection. */
public final class FocusResolver {
    private static final double ITEM_PICK_INFLATION = 0.25D;
    private static final double ENTITY_PICK_INFLATION = 0.10D;
    private static final int MAX_BLOCK_PROPERTIES = 32;
    private static final int MAX_CONTAINER_SLOTS_READ = 256;
    private static final int MAX_CONTAINER_ITEM_TYPES = 16;
    private static final int MAX_EFFECTS = 8;
    private static final int MAX_TEXT_LENGTH = 96;

    private FocusResolver() {
    }

    /** Lightweight observation used by the background tracker and compact model context. */
    public static FocusSnapshot observeNow(AIPlayerEntity bot) {
        return resolve(bot, false);
    }

    /** Detailed bounded observation used only when the model explicitly calls inspect_focus. */
    public static FocusSnapshot inspectNow(AIPlayerEntity bot) {
        return resolve(bot, true);
    }

    private static FocusSnapshot resolve(AIPlayerEntity bot, boolean detailed) {
        AIBotConfig.Focus config = AIBotConfig.get().perception().focus();
        long tick = bot.getServer().getTickCount();
        String dimension = bot.serverLevel().dimension().location().toString();
        if (!config.enabledValue()) {
            return FocusSnapshot.disabled(tick, dimension);
        }

        double range = Math.max(1.0D, Math.min(config.range(), AIBotConfig.get().perception().radius()));
        Vec3 eye = bot.getEyePosition();
        Vec3 look = bot.getLookAngle().normalize();
        Vec3 end = eye.add(look.scale(range));

        HitResult blockHit = bot.pick(range, 1.0F, false);
        double blockDistanceSquared = blockHit.getType() == HitResult.Type.BLOCK
                ? eye.distanceToSqr(blockHit.getLocation())
                : range * range;

        EntityHit entityHit = nearestEntityHit(bot, eye, end, look, range, blockDistanceSquared);
        if (entityHit != null && entityHit.distanceSquared() + 1.0E-6D < blockDistanceSquared) {
            return entitySnapshot(bot, entityHit.entity(), entityHit.location(), tick, dimension, detailed);
        }
        if (blockHit instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return blockSnapshot(bot, hit, tick, dimension, detailed);
        }
        return FocusSnapshot.miss(tick, dimension);
    }

    private static EntityHit nearestEntityHit(AIPlayerEntity bot,
                                              Vec3 eye,
                                              Vec3 end,
                                              Vec3 look,
                                              double range,
                                              double blockDistanceSquared) {
        AABB scanBox = bot.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0D);
        Entity nearest = null;
        Vec3 nearestLocation = null;
        double nearestDistanceSquared = blockDistanceSquared;
        for (Entity entity : bot.serverLevel().getEntities(bot, scanBox, FocusResolver::canTarget)) {
            double inflation = entity instanceof ItemEntity ? ITEM_PICK_INFLATION : ENTITY_PICK_INFLATION;
            AABB bounds = entity.getBoundingBox().inflate(inflation);
            Optional<Vec3> intersection = bounds.contains(eye) ? Optional.of(eye) : bounds.clip(eye, end);
            if (intersection.isEmpty()) {
                continue;
            }
            double distanceSquared = eye.distanceToSqr(intersection.get());
            if (distanceSquared >= nearestDistanceSquared
                    || !ObservableWorldQuery.canObserveEntity(bot, entity)) {
                continue;
            }
            nearest = entity;
            nearestLocation = intersection.get();
            nearestDistanceSquared = distanceSquared;
        }
        return nearest == null ? null : new EntityHit(nearest, nearestLocation, nearestDistanceSquared);
    }

    private static boolean canTarget(Entity entity) {
        return !entity.isSpectator() && (entity.isPickable() || entity instanceof ItemEntity);
    }

    private static FocusSnapshot blockSnapshot(AIPlayerEntity bot,
                                               BlockHitResult hit,
                                               long tick,
                                               String dimension,
                                               boolean detailed) {
        ServerLevel world = bot.serverLevel();
        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        BlockEntity blockEntity = world.getBlockEntity(pos);
        String blockId = idOfBlock(state);
        String heldTool = idOfItem(bot.getMainHandItem());
        String fluid = state.getFluidState().isEmpty()
                ? ""
                : BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString();
        float destroySpeed = state.getDestroySpeed(world, pos);
        boolean withinInteractionReach = bot.canInteractWithBlock(pos, 0.0D);
        FocusSnapshot.BlockDetails details = new FocusSnapshot.BlockDetails(
                blockId,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                stateProperties(state),
                destroySpeed,
                destroySpeed < 0.0F,
                state.requiresCorrectToolForDrops(),
                bot.getMainHandItem().isCorrectToolForDrops(state),
                heldTool,
                fluid,
                world.getMaxLocalRawBrightness(pos),
                blockEntityDetails(bot, blockEntity, withinInteractionReach, detailed));
        FocusKind kind = blockEntity == null ? FocusKind.BLOCK : FocusKind.BLOCK_ENTITY;
        double distance = round(bot.getEyePosition().distanceTo(hit.getLocation()));
        return new FocusSnapshot(
                FocusState.ACQUIRING,
                kind,
                FocusSource.BOT_GAZE,
                "block:" + dimension + ":" + pos.asLong(),
                tick,
                dimension,
                new FocusSnapshot.Position(pos.getX(), pos.getY(), pos.getZ()),
                distance,
                true,
                withinInteractionReach,
                false,
                blockId,
                sanitize(state.getBlock().getName()),
                hit.getDirection().getName(),
                details,
                null,
                null,
                new FocusSnapshot.BehaviorDetails(ObservedBehavior.UNKNOWN, 1.0D, List.of("stationary_block")));
    }

    private static FocusSnapshot entitySnapshot(AIPlayerEntity bot,
                                                Entity entity,
                                                Vec3 hitLocation,
                                                long tick,
                                                String dimension,
                                                boolean detailed) {
        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        FocusSnapshot.Position position = position(entity.position());
        Vec3 movement = entity.getDeltaMovement();
        FocusSnapshot.Velocity velocity = new FocusSnapshot.Velocity(
                round(movement.x),
                round(movement.y),
                round(movement.z),
                round(movement.length()));
        Mob mob = entity instanceof Mob value ? value : null;
        Entity rawAttackTarget = mob == null ? null : mob.getTarget();
        Entity attackTarget = canObserveRelatedEntity(bot, rawAttackTarget) ? rawAttackTarget : null;
        FocusSnapshot.BehaviorDetails behavior = EntityBehaviorClassifier.classify(entity, attackTarget);

        if (entity instanceof ItemEntity itemEntity) {
            ItemStack stack = itemEntity.getItem();
            String itemId = idOfItem(stack);
            FocusSnapshot.ItemDetails item = new FocusSnapshot.ItemDetails(
                    itemId,
                    stack.getCount(),
                    stack.getDamageValue(),
                    stack.getMaxDamage(),
                    stack.has(DataComponents.CUSTOM_NAME) ? sanitize(stack.getHoverName()) : "",
                    itemEntity.isCurrentlyGlowing());
            return new FocusSnapshot(
                    FocusState.ACQUIRING,
                    FocusKind.ITEM_ENTITY,
                    FocusSource.BOT_GAZE,
                    entityTargetKey(dimension, entity),
                    tick,
                    dimension,
                    position,
                    round(bot.getEyePosition().distanceTo(hitLocation)),
                    true,
                    bot.canInteractWithEntity(entity, 0.0D),
                    false,
                    itemId,
                    sanitize(stack.getHoverName()),
                    "",
                    null,
                    null,
                    item,
                    behavior);
        }

        LivingEntity living = entity instanceof LivingEntity value ? value : null;
        List<String> effects = living == null || !detailed ? List.of() : effects(living);
        FocusSnapshot.EntityDetails details = new FocusSnapshot.EntityDetails(
                entity.getId(),
                entityType,
                position,
                velocity,
                living == null ? null : roundFloat(living.getHealth()),
                living == null ? null : roundFloat(living.getMaxHealth()),
                living == null ? null : living.getArmorValue(),
                entity.isAlive(),
                entity instanceof Enemy,
                entity instanceof AgeableMob ageable && ageable.isBaby(),
                entity.onGround(),
                entity.isInWater(),
                entity.isOnFire(),
                entity.isSprinting(),
                entity.isCrouching(),
                living != null && living.isUsingItem(),
                entity.getPose().name(),
                living == null ? "" : idOfItem(living.getMainHandItem()),
                attackTarget == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(attackTarget.getType()).toString(),
                attackTarget == null ? null : round(entity.distanceTo(attackTarget)),
                effects);
        return new FocusSnapshot(
                FocusState.ACQUIRING,
                living == null ? FocusKind.ENTITY : FocusKind.LIVING_ENTITY,
                FocusSource.BOT_GAZE,
                entityTargetKey(dimension, entity),
                tick,
                dimension,
                position,
                round(bot.getEyePosition().distanceTo(hitLocation)),
                true,
                bot.canInteractWithEntity(entity, 0.0D),
                false,
                entityType,
                sanitize(entity.getDisplayName()),
                "",
                null,
                details,
                null,
                behavior);
    }

    private static Map<String, String> stateProperties(BlockState state) {
        Map<String, String> properties = new TreeMap<>();
        int count = 0;
        for (Property<?> property : state.getProperties()) {
            if (count++ >= MAX_BLOCK_PROPERTIES) {
                break;
            }
            properties.put(property.getName(), propertyValue(state, property));
        }
        return properties;
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static FocusSnapshot.BlockEntityDetails blockEntityDetails(AIPlayerEntity bot,
                                                                        BlockEntity blockEntity,
                                                                        boolean withinInteractionReach,
                                                                        boolean detailed) {
        if (blockEntity == null) {
            return null;
        }
        ResourceLocation typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        if (typeId == null) {
            return new FocusSnapshot.BlockEntityDetails(
                    "unregistered", 0, 0, 0, Map.of(), false, false, "unregistered_block_entity_type");
        }
        String type = typeId.toString();
        if (!(blockEntity instanceof Container container)) {
            return new FocusSnapshot.BlockEntityDetails(type, 0, 0, 0, Map.of(), false, true, "");
        }
        if (!typeId.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) {
            // Reject before calling any Container method. Arbitrary modded getContainerSize/getItem
            // implementations are not assumed to be side-effect free.
            return new FocusSnapshot.BlockEntityDetails(
                    type, 0, 0, 0, Map.of(), false, false, "modded_container_provider_required");
        }
        if (blockEntity instanceof ContainerLockAccessor lockAccessor
                && !bot.isSpectator()
                && !lockAccessor.fakeaiplayer$getLockKey().unlocksWith(bot.getMainHandItem())) {
            // BaseContainerBlockEntity.canOpen would send chat/sound on failure. The accessor lets
            // observation respect the same lock without producing side effects.
            return new FocusSnapshot.BlockEntityDetails(
                    type, 0, 0, 0, Map.of(), false, false, "locked_for_current_held_item");
        }
        int slots = container.getContainerSize();
        if (!detailed) {
            return new FocusSnapshot.BlockEntityDetails(
                    type, slots, 0, 0, Map.of(), false, false, "detail_not_requested");
        }
        if (!withinInteractionReach) {
            return new FocusSnapshot.BlockEntityDetails(
                    type, slots, 0, 0, Map.of(), false, false, "out_of_interaction_reach");
        }
        if (blockEntity instanceof RandomizableContainerBlockEntity randomizable
                && randomizable.getLootTable() != null) {
            // Looking must remain read-only. Calling getItem here would unpack and mutate loot.
            return new FocusSnapshot.BlockEntityDetails(
                    type, slots, 0, 0, Map.of(), false, false, "unopened_loot_table");
        }
        int readLimit = Math.min(slots, MAX_CONTAINER_SLOTS_READ);
        int occupied = 0;
        int totalItems = 0;
        boolean truncated = slots > readLimit;
        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        for (int slot = 0; slot < readLimit; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            occupied++;
            totalItems += stack.getCount();
            String itemId = idOfItem(stack);
            if (itemCounts.containsKey(itemId) || itemCounts.size() < MAX_CONTAINER_ITEM_TYPES) {
                itemCounts.merge(itemId, stack.getCount(), Integer::sum);
            } else {
                truncated = true;
            }
        }
        return new FocusSnapshot.BlockEntityDetails(
                type, slots, occupied, totalItems, itemCounts, truncated, true, "");
    }

    private static List<String> effects(LivingEntity living) {
        List<String> result = new ArrayList<>();
        int count = 0;
        for (MobEffectInstance effect : living.getActiveEffects()) {
            if (count++ >= MAX_EFFECTS) {
                break;
            }
            String id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString();
            result.add(id + ":amplifier=" + effect.getAmplifier() + ":ticks=" + effect.getDuration());
        }
        return List.copyOf(result);
    }

    private static String idOfBlock(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String idOfItem(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String entityTargetKey(String dimension, Entity entity) {
        return "entity:" + dimension + ":" + entity.getUUID();
    }

    private static boolean canObserveRelatedEntity(AIPlayerEntity bot, Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        double range = Math.max(1.0D, Math.min(
                AIBotConfig.get().perception().focus().range(),
                AIBotConfig.get().perception().radius()));
        return bot.distanceToSqr(entity) <= range * range && bot.hasLineOfSight(entity);
    }

    private static FocusSnapshot.Position position(Vec3 value) {
        return new FocusSnapshot.Position(round(value.x), round(value.y), round(value.z));
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_TEXT_LENGTH));
        for (int index = 0; index < value.length() && result.length() < MAX_TEXT_LENGTH; index++) {
            char character = value.charAt(index);
            result.append(Character.isISOControl(character) ? ' ' : character);
        }
        return result.toString().trim();
    }

    private static String sanitize(Component value) {
        return value == null ? "" : sanitize(value.getString(MAX_TEXT_LENGTH));
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static float roundFloat(float value) {
        return Math.round(value * 100.0F) / 100.0F;
    }

    private record EntityHit(Entity entity, Vec3 location, double distanceSquared) {
    }
}
