package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BlockMiner;
import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.ContainerAction;
import io.github.greytaiwolf.fakeaiplayer.action.DigNav;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.craft.CraftingHelper;
import io.github.greytaiwolf.fakeaiplayer.craft.SmeltChain;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

public final class SmeltTask extends AbstractTask {
    private enum Phase {
        FINDING_FURNACE,
        WALKING_TO_FURNACE,
        CRAFTING_FURNACE,
        PLACING_FURNACE,
        LOADING,
        SMELTING,
        COLLECTING
    }

    private static final Map<Item, Integer> FUEL_TICKS = new LinkedHashMap<>();
    private static final int BASE_FUEL_RADIUS = 8;
    private static final double REACH_SQUARED = 20.25D;

    static {
        FUEL_TICKS.put(Items.COAL, 1600);
        FUEL_TICKS.put(Items.CHARCOAL, 1600);
        FUEL_TICKS.put(Items.OAK_LOG, 300);
        FUEL_TICKS.put(Items.SPRUCE_LOG, 300);
        FUEL_TICKS.put(Items.BIRCH_LOG, 300);
        FUEL_TICKS.put(Items.JUNGLE_LOG, 300);
        FUEL_TICKS.put(Items.ACACIA_LOG, 300);
        FUEL_TICKS.put(Items.DARK_OAK_LOG, 300);
        FUEL_TICKS.put(Items.MANGROVE_LOG, 300);
        FUEL_TICKS.put(Items.CHERRY_LOG, 300);
        FUEL_TICKS.put(Items.OAK_PLANKS, 300);
        FUEL_TICKS.put(Items.SPRUCE_PLANKS, 300);
        FUEL_TICKS.put(Items.BIRCH_PLANKS, 300);
        FUEL_TICKS.put(Items.JUNGLE_PLANKS, 300);
        FUEL_TICKS.put(Items.ACACIA_PLANKS, 300);
        FUEL_TICKS.put(Items.DARK_OAK_PLANKS, 300);
        FUEL_TICKS.put(Items.MANGROVE_PLANKS, 300);
        FUEL_TICKS.put(Items.CHERRY_PLANKS, 300);
        FUEL_TICKS.put(Items.STICK, 100);
    }

    private Item input;             // cookAll 模式下运行期可重选(逐种烤背包里的生食)
    private Item output;
    private final boolean cookAll;  // true=烤背包所有可烤生食,凑够 targetCount 个熟食
    private final int targetCount;
    private Phase phase = Phase.FINDING_FURNACE;
    private BlockPos furnacePos;
    private double walkBestDist2 = Double.MAX_VALUE; // WALKING 接近监控:历史最近距²(治 stuck:smelt active-but-stuck)
    private int walkStallSince;                       // 上次靠近炉子的 elapsed;久不靠近=路径卡死→升级
    private int collected;
    private final BlockMiner clearMiner = new BlockMiner(); // 被围放不下熔炉时,挖一格相邻方块腾位
    private boolean walkDigging; // 纯寻路到不了现有熔炉时,降级挖掘式朝熔炉挖过去(复用 clearMiner)
    private CraftTask furnaceCraftSub; // 走炉卡死且无备炉时,就地合成一座新炉(复用 CraftTask,不重复扣料逻辑)

    public SmeltTask(Item input, Item output, int targetCount) {
        this.input = input;
        this.output = output;
        this.cookAll = false;
        this.targetCount = Math.max(1, targetCount);
    }

    /** cookAll 模式:烤背包里所有可烤生食,凑够 targetCount 个熟食(供 COOK_FOOD 步——猎后烤肉)。 */
    public SmeltTask(int targetCount) {
        this.input = null;
        this.output = null;
        this.cookAll = true;
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "smelt";
    }

    @Override
    public String describe() {
        return "Smelting " + BuiltInRegistries.ITEM.getKey(input) + " -> " + BuiltInRegistries.ITEM.getKey(output)
                + " " + collected + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) collected / targetCount);
    }

    @Override
    public boolean isWaiting() {
        // 挖掘式走向熔炉时 bot 站着挖、位置基本不变 → 视为 waiting,避免 StuckWatcher 误判(本任务总超时兜底)。
        return phase == Phase.SMELTING || (phase == Phase.WALKING_TO_FURNACE && walkDigging);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FINDING_FURNACE;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        clearMiner.cancel(bot);
        if (furnaceCraftSub != null) { // 就地合成子任务对称清理:防 abort 后字段残留 RUNNING 实例被复用
            furnaceCraftSub.abort(bot);
            furnaceCraftSub = null;
        }
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 400 + targetCount * 260) {
            fail("smelt_timeout");
            return;
        }
        if (cookAll && !ensureCurrentRawFood(bot)) {
            return; // 无可烤生食(已 complete/fail)
        }
        switch (phase) {
            case FINDING_FURNACE -> findFurnace(bot);
            case WALKING_TO_FURNACE -> walkToFurnace(bot);
            case CRAFTING_FURNACE -> craftFurnace(bot);
            case PLACING_FURNACE -> placeFurnace(bot);
            case LOADING -> loadFurnace(bot);
            case SMELTING -> waitForOutput(bot);
            case COLLECTING -> collectOutput(bot);
        }
    }

    // cookAll:确保"当前烤种"是背包里有的生食;当前种烤空且炉里也清空 → 换下一种;全烤完 → 结束。
    // 返回 false 表示本 tick 应终止(已 complete/fail)。
    private boolean ensureCurrentRawFood(AIPlayerEntity bot) {
        if (input != null && InventoryAction.countItem(bot, input) > 0) {
            return true; // 当前种背包还有,继续烤
        }
        if (input != null && hasPendingInFurnace(bot)) {
            return true; // 当前种背包空,但炉里还有它的料/产物 → 先收完再换种(避免 input 槽占用冲突)
        }
        Item next = null;
        for (Item raw : SmeltChain.RAW_FOODS) {
            if (InventoryAction.countItem(bot, raw) > 0) {
                next = raw;
                break;
            }
        }
        if (next == null) {
            if (collected > 0) {
                complete();
            } else {
                fail("no_raw_food");
            }
            return false;
        }
        input = next;
        output = SmeltChain.smeltOf(next);
        phase = Phase.FINDING_FURNACE; // 熔炉位通常已知,会快速回到 LOADING
        return true;
    }

    private boolean hasPendingInFurnace(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity f = furnace(bot);
        return f != null && (!f.getItem(0).isEmpty() || !f.getItem(2).isEmpty());
    }

    private void findFurnace(AIPlayerEntity bot) {
        if (!InventoryAction.hasItems(bot, input, 1)) {
            fail("missing " + BuiltInRegistries.ITEM.getKey(input) + " x1");
            return;
        }
        furnacePos = nearestFurnace(bot).orElse(null);
        if (furnacePos == null) {
            // 局部扫不到 → 问记忆:我自己放过的炉在哪(同维度+方块仍是熔炉才认,被拆即作废)
            var remembered = io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore.INSTANCE
                    .of(bot.getUUID()).placeIn(bot.serverLevel(), "furnace");
            if (remembered.isPresent()
                    && remembered.get().closerThan(bot.blockPosition(), 96.0D)) {
                furnacePos = remembered.get();
            }
        }
        if (furnacePos == null) {
            if (InventoryAction.findItem(bot, Items.FURNACE).isEmpty()) {
                fail("missing minecraft:furnace");
                return;
            }
            phase = Phase.PLACING_FURNACE;
            return;
        }
        if (bot.getEyePosition().distanceToSqr(furnacePos.getCenter()) <= REACH_SQUARED
                && io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, furnacePos)) {
            phase = Phase.LOADING;
            return;
        }
        BlockPos stand = adjacentStand(bot, furnacePos);
        if (stand == null) {
            // 现有炉够不到落脚点(悬崖/狭地形,real_armor 20260610 实测 no_stand 整局失败):别失败——
            // 有炉就地放一座新炉,熔炼永远能就近进行(旧炉弃用,几块鹅卵石认亏)。无炉才真失败交 replan 补炉。
            if (InventoryAction.findItem(bot, Items.FURNACE).isPresent()) {
                BotLog.action(bot, "smelt_furnace_unreachable_replace", "old", furnacePos.toShortString());
                furnacePos = null;
                phase = Phase.PLACING_FURNACE;
                return;
            }
            fail("no_stand_position_for_furnace");
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        // 纯寻路到不了现有熔炉(地下被围/自挖隧道复杂,实测 GOAL_UNREACHABLE 会让整条目标 replan 回地表砍木,
        // bot 在深处回不去而卡死)→ 不失败,降级挖掘式朝熔炉挖过去。
        walkDigging = result.isFailed();
        walkBestDist2 = Double.MAX_VALUE; // 进入 WALKING:重置接近监控
        walkStallSince = elapsed;
        phase = Phase.WALKING_TO_FURNACE;
    }

    private void walkToFurnace(AIPlayerEntity bot) {
        if (furnacePos == null) {
            phase = Phase.FINDING_FURNACE;
            return;
        }
        boolean observable = io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, furnacePos);
        if (observable && !bot.serverLevel().getBlockState(furnacePos).is(Blocks.FURNACE)) {
            phase = Phase.FINDING_FURNACE;
            return;
        }
        double dist2 = bot.getEyePosition().distanceToSqr(furnacePos.getCenter());
        if (observable && dist2 <= REACH_SQUARED) {
            clearMiner.cancel(bot);
            bot.getActionPack().stopAll();
            phase = Phase.LOADING;
            return;
        }
        // 接近监控(治 stuck:smelt WALKING_TO_FURNACE:实测 9/18 跑冻在悬崖、on_ground=false、纯寻路
        // active-but-stuck,isPathExecutorIdle 永 false 漏判,直到外部 200t 看门狗失败触发 replan 烧预算)。
        // 久不靠近 → 纯寻路升级挖掘式;挖掘式也推不近 → 弃该炉回 FINDING 重选/补炉。位移阈很低,正常移动轻松喂活。
        if (dist2 < walkBestDist2 - 0.5D) {
            walkBestDist2 = dist2;
            walkStallSince = elapsed;
        } else if (elapsed - walkStallSince > 40) {
            walkStallSince = elapsed;
            if (!walkDigging) {
                bot.getActionPack().stopAll();
                walkDigging = true;
                BotLog.action(bot, "smelt_walk_stall_dig", "furnace", furnacePos.toShortString());
            } else {
                clearMiner.cancel(bot);
                furnacePos = null;
                walkBestDist2 = Double.MAX_VALUE;
                // 挖掘式也推不近这座炉(真够不到:悬崖/深水/自挖隧道复杂地形)——有备炉就地摆一座就近熔炼,
                // 而非回 FINDING 重选同一座远炉 churn 到 smelt_timeout(real_armor 实测走炉卡死触发 166 次→熔炼步超时,
                // 其时 bot 手握 furnace×1+cobblestone×71 却在死走远炉)。同 smelt_furnace_unreachable_replace 思路:旧炉弃用认亏几块石头。
                if (InventoryAction.findItem(bot, Items.FURNACE).isPresent()) {
                    phase = Phase.PLACING_FURNACE;
                    BotLog.action(bot, "smelt_walk_stall_replace", "dist2", String.format("%.0f", dist2));
                } else if (CraftingHelper.plan(bot, Items.FURNACE, 1).success()) {
                    // 无备炉但有料(实测 24/25 churn 是 cobblestone 满手却只造过 1 座炉,摆出去就没备)——就地合成新炉
                    // 就近熔炼,而非回 FINDING 死走远炉 churn 到 smelt_timeout。合成纯背包变换(扣 8 石→给炉),
                    // 工作台"持有即可"由 CraftTask 内部处理;合成失败/短路优雅回退 FINDING(见 craftFurnace)。
                    bot.getActionPack().stopAll(); // 清残留 walkTo(挖掘式刚 startWalkTo),免合成期杂散位移
                    phase = Phase.CRAFTING_FURNACE;
                    BotLog.action(bot, "smelt_walk_stall_craft", "dist2", String.format("%.0f", dist2));
                } else {
                    phase = Phase.FINDING_FURNACE;
                    BotLog.action(bot, "smelt_walk_stall_refind", "dist2", String.format("%.0f", dist2));
                }
            }
            return;
        }
        if (walkDigging) {
            // 挖掘式朝熔炉挖过去(地下被围也能到);朝向格挨岩浆受阻 → 回 FINDING 另选(LOADING 判定 4.5 格内即停,不会挖到熔炉本身)
            if (!DigNav.digStep(bot, clearMiner, furnacePos)) {
                walkDigging = false;
                phase = Phase.FINDING_FURNACE;
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            // 纯寻路走不到 → 降级挖掘式,而非反复重找最终 smelt_timeout 失败触发 replan
            walkDigging = true;
        }
    }

    private void craftFurnace(AIPlayerEntity bot) {
        if (furnaceCraftSub == null) {
            furnaceCraftSub = new CraftTask(Items.FURNACE, 1);
            furnaceCraftSub.start(bot);
        }
        furnaceCraftSub.tick(bot);
        TaskState st = furnaceCraftSub.state();
        if (st == TaskState.COMPLETED) {
            furnaceCraftSub = null;
            // 合成完可能因 CraftTask.utilityAlreadyAvailable 短路(8 格内已有炉)而没真造出物品——
            // 有物品才摆,否则回 FINDING 让 nearestFurnace 就近接管,绝不带空手进 PLACING 硬失败。
            phase = InventoryAction.findItem(bot, Items.FURNACE).isPresent()
                    ? Phase.PLACING_FURNACE : Phase.FINDING_FURNACE;
        } else if (st == TaskState.FAILED) {
            furnaceCraftSub = null;
            phase = Phase.FINDING_FURNACE; // 合成失败(无料/无台且无木)→回退原远炉逻辑,不比基线更差
        }
        // else RUNNING:continue ticking(furnace 合成仅 1~2 步,数 tick 内完成,远低于 CraftTask 400t 超时)
    }

    private void placeFurnace(AIPlayerEntity bot) {
        OptionalInt furnaceSlot = InventoryAction.findItem(bot, Items.FURNACE);
        if (furnaceSlot.isEmpty()) {
            fail("missing minecraft:furnace");
            return;
        }
        BlockPos pos = adjacentAir(bot);
        if (pos == null) {
            // 被围(四周方块)放不下 → 挖掉一个相邻可破坏方块腾位(bot 有镐就该自己清场,而非直接失败)。
            if (!clearSpaceForFurnace(bot)) {
                fail("no_place_for_furnace");
            }
            return; // 挖位中,下 tick adjacentAir 即可找到
        }
        InventoryAction.equipFromSlot(bot, furnaceSlot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, pos);
        if (result.isFailed()) {
            fail("place_furnace_failed: " + result.reason());
            return;
        }
        furnacePos = pos;
        // R2 修:炉位入记忆——挖矿走远后 nearestFurnace(局部扫描)找不回自己放的炉,
        // missing furnace 整链报废(real_diamond 实测:第一炉用完,挖第二批铁回来炉'丢了')。
        io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore.INSTANCE.of(bot.getUUID())
                .markPlace("furnace", bot.serverLevel(), pos);
        phase = Phase.LOADING;
    }

    private void loadFurnace(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            phase = Phase.FINDING_FURNACE;
            return;
        }
        ItemStack inputSlot = furnace.getItem(0);
        if (!inputSlot.isEmpty() && !inputSlot.is(input)) {
            fail("furnace_input_occupied: " + BuiltInRegistries.ITEM.getKey(inputSlot.getItem()));
            return;
        }
        ItemStack outputSlot = furnace.getItem(2);
        if (!outputSlot.isEmpty() && !outputSlot.is(output)) {
            fail("unexpected_output: " + BuiltInRegistries.ITEM.getKey(outputSlot.getItem()));
            return;
        }
        int outputQueued = outputSlot.is(output) ? outputSlot.getCount() : 0;
        int inputQueued = inputSlot.is(input) ? inputSlot.getCount() : 0;
        int remainingToQueue = targetCount - collected - outputQueued - inputQueued;
        int inputRoom = inputSlot.isEmpty() ? 64 : 64 - inputSlot.getCount();
        int inventoryInput = InventoryAction.countItem(bot, input);
        int inputToLoad = Math.min(Math.min(remainingToQueue, inputRoom), inventoryInput);
        if (remainingToQueue > 0 && inputToLoad <= 0 && inputQueued == 0) {
            fail("missing " + BuiltInRegistries.ITEM.getKey(input) + " x" + remainingToQueue);
            return;
        }
        ItemStack fuelSlot = furnace.getItem(1);
        FuelChoice fuel = null;
        if (fuelSlot.isEmpty()) {
            int smeltsNeedingFuel = Math.max(1, inputQueued + Math.max(inputToLoad, 0));
            fuel = chooseFuel(bot, smeltsNeedingFuel);
            if (fuel == null) {
                fetchFuelFromBase(bot, smeltsNeedingFuel);
                fuel = chooseFuel(bot, smeltsNeedingFuel);
            }
            if (fuel == null) {
                fail("out_of_fuel");
                return;
            }
        }
        if (inputToLoad > 0) {
            if (!InventoryAction.removeItems(bot, input, inputToLoad)) {
                fail("missing " + BuiltInRegistries.ITEM.getKey(input) + " x" + inputToLoad);
                return;
            }
            furnace.setItem(0, new ItemStack(input, inputSlot.getCount() + inputToLoad));
        }

        if (fuel != null) {
            if (!InventoryAction.removeItems(bot, fuel.item(), fuel.count())) {
                fail("out_of_fuel: " + BuiltInRegistries.ITEM.getKey(fuel.item()));
                return;
            }
            furnace.setItem(1, new ItemStack(fuel.item(), fuel.count()));
        }
        furnace.setChanged();
        phase = Phase.SMELTING;
    }

    private void waitForOutput(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            fail("furnace_missing");
            return;
        }
        ItemStack outputSlot = furnace.getItem(2);
        if (!outputSlot.isEmpty() && !outputSlot.is(output)) {
            fail("unexpected_output: " + BuiltInRegistries.ITEM.getKey(outputSlot.getItem()));
            return;
        }
        if (!outputSlot.isEmpty()) {
            phase = Phase.COLLECTING;
            return;
        }
        ItemStack inputSlot = furnace.getItem(0);
        ItemStack fuelSlot = furnace.getItem(1);
        if (collected < targetCount && (inputSlot.isEmpty() || fuelSlot.isEmpty())) {
            phase = Phase.LOADING;
        }
    }

    private void collectOutput(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            fail("furnace_missing");
            return;
        }
        ItemStack outputSlot = furnace.getItem(2);
        if (outputSlot.isEmpty()) {
            phase = Phase.SMELTING;
            return;
        }
        if (!outputSlot.is(output)) {
            fail("unexpected_output: " + BuiltInRegistries.ITEM.getKey(outputSlot.getItem()));
            return;
        }
        int take = Math.min(targetCount - collected, outputSlot.getCount());
        ActionResult result = InventoryAction.giveItem(bot, new ItemStack(output, take));
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        outputSlot.shrink(take);
        furnace.setChanged();
        collected += take;
        if (collected >= targetCount) {
            complete();
        } else {
            phase = Phase.LOADING;
        }
    }

    private AbstractFurnaceBlockEntity furnace(AIPlayerEntity bot) {
        if (furnacePos == null
                || bot.getEyePosition().distanceToSqr(furnacePos.getCenter()) > REACH_SQUARED
                || !io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, furnacePos)) {
            return null;
        }
        return bot.serverLevel().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }

    private static Optional<BlockPos> nearestFurnace(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        return BlockPos.betweenClosedStream(origin.offset(-10, -3, -10), origin.offset(10, 4, 10))
                .filter(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.serverLevel().getBlockState(pos).is(Blocks.FURNACE))
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = pos.relative(direction);
            if (Standability.isStandable(bot.serverLevel(), candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private static BlockPos adjacentAir(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(direction);
            if (bot.serverLevel().getBlockState(candidate).isAir()) {
                return candidate.immutable();
            }
        }
        return null;
    }

    // 被围时:挖掉一个水平相邻的可破坏方块,腾出放熔炉的空位。返回 false=四周无可破坏方块(如基岩/流体)。
    private boolean clearSpaceForFurnace(AIPlayerEntity bot) {
        var world = bot.serverLevel();
        BlockPos origin = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(direction);
            var s = world.getBlockState(candidate);
            if (s.isAir() || !s.getFluidState().isEmpty() || s.getDestroySpeed(world, candidate) < 0.0F
                    || world.getBlockEntity(candidate) != null) {
                continue;
            }
            BlockMiner.Status st = clearMiner.target() != null && clearMiner.target().equals(candidate)
                    ? clearMiner.tick(bot)
                    : beginClear(bot, candidate);
            return st != BlockMiner.Status.FAILED;
        }
        return false;
    }

    private BlockMiner.Status beginClear(AIPlayerEntity bot, BlockPos pos) {
        clearMiner.begin(bot, pos);
        return clearMiner.tick(bot);
    }

    private static FuelChoice chooseFuel(AIPlayerEntity bot, int smeltCount) {
        return chooseFuel(smeltCount, item -> InventoryAction.countItem(bot, item));
    }

    static FuelChoice chooseFuel(Map<Item, Integer> inventory, int smeltCount) {
        return chooseFuel(smeltCount, item -> inventory.getOrDefault(item, 0));
    }

    private static FuelChoice chooseFuel(int smeltCount, ToIntFunction<Item> availableCount) {
        int ticksNeeded = smeltCount * 200;
        // FUEL_TICKS is deliberately ordered from efficient fuel to fallback fuel. Always exhaust
        // the first available class before moving on: returning a low-efficiency full batch while
        // coal is partially available can burn logs reserved for a confirmed building. SmeltTask
        // re-enters LOADING after a partial stack burns out, so mixed fuels remain supported.
        for (Map.Entry<Item, Integer> entry : FUEL_TICKS.entrySet()) {
            int available = availableCount.applyAsInt(entry.getKey());
            if (available <= 0) {
                continue;
            }
            int needed = divideRoundUp(ticksNeeded, entry.getValue());
            return new FuelChoice(entry.getKey(), Math.min(available, needed));
        }
        return null;
    }

    private static void fetchFuelFromBase(AIPlayerEntity bot, int smeltCount) {
        BlockPos base = BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.serverLevel(), "base")
                .orElse(null);
        if (base == null) {
            return;
        }
        for (Map.Entry<Item, Integer> entry : FUEL_TICKS.entrySet()) {
            Item fuel = entry.getKey();
            int needed = divideRoundUp(smeltCount * 200, entry.getValue());
            if (InventoryAction.countItem(bot, fuel) >= needed) {
                return;
            }
            for (BlockPos pos : fuelContainers(bot, base, fuel)) {
                Container container = ContainerAction.resolve(bot, pos).orElse(null);
                if (container == null) {
                    continue;
                }
                int missing = needed - InventoryAction.countItem(bot, fuel);
                if (missing <= 0) {
                    return;
                }
                ContainerAction.TransferResult result = ContainerAction.withdrawOne(container, bot, fuel, missing);
                if (result.movedAny() && InventoryAction.countItem(bot, fuel) >= needed) {
                    return;
                }
            }
        }
    }

    private static java.util.List<BlockPos> fuelContainers(AIPlayerEntity bot, BlockPos base, Item fuel) {
        return BlockPos.betweenClosedStream(base.offset(-BASE_FUEL_RADIUS, -3, -BASE_FUEL_RADIUS), base.offset(BASE_FUEL_RADIUS, 4, BASE_FUEL_RADIUS))
                .map(BlockPos::immutable)
                .filter(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.getEyePosition().distanceToSqr(pos.getCenter()) <= REACH_SQUARED)
                .filter(pos -> containsItem(bot, pos, fuel))
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(bot.blockPosition())))
                .toList();
    }

    private static boolean containsItem(AIPlayerEntity bot, BlockPos pos, Item item) {
        Container inventory = ContainerAction.resolve(bot, pos).orElse(null);
        if (inventory == null) {
            return false;
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    record FuelChoice(Item item, int count) {
    }
}
