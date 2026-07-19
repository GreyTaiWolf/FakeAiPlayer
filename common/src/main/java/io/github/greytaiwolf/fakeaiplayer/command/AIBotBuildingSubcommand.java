package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingCatalog;
import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingCatalogEntry;
import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingSeedCode;
import io.github.greytaiwolf.fakeaiplayer.building.generator.HouseDimensions;
import io.github.greytaiwolf.fakeaiplayer.building.generator.ModularHouseGenerator;
import io.github.greytaiwolf.fakeaiplayer.building.generator.ModularHouseRequest;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanTransform;
import io.github.greytaiwolf.fakeaiplayer.building.preview.BuildingPreviewService;
import io.github.greytaiwolf.fakeaiplayer.building.style.HouseMaterialStyle;
import io.github.greytaiwolf.fakeaiplayer.building.style.VanillaHouseStyles;
import io.github.greytaiwolf.fakeaiplayer.building.workflow.CatalogBuildingDraftService;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Player-facing draft → projection → explicit confirmation workflow. */
public final class AIBotBuildingSubcommand {
    private static final int DEFAULT_WIDTH = 9;
    private static final int DEFAULT_DEPTH = 9;
    private static final int DEFAULT_WALL_HEIGHT = 5;
    private AIBotBuildingSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("building")
                .then(literal("draft")
                        .then(argument("bot", StringArgumentType.word())
                                .then(argument("style", StringArgumentType.word())
                                        .executes(context -> draft(
                                                context, DEFAULT_WIDTH, DEFAULT_DEPTH,
                                                DEFAULT_WALL_HEIGHT, 0L))
                                        .then(argument("width", IntegerArgumentType.integer(
                                                        HouseDimensions.MIN_FOOTPRINT,
                                                        HouseDimensions.MAX_EXECUTABLE_FOOTPRINT))
                                                .then(argument("depth", IntegerArgumentType.integer(
                                                                HouseDimensions.MIN_FOOTPRINT,
                                                                HouseDimensions.MAX_EXECUTABLE_FOOTPRINT))
                                                        .then(argument("wall_height", IntegerArgumentType.integer(
                                                                        HouseDimensions.MIN_WALL_HEIGHT,
                                                                        HouseDimensions.MAX_EXECUTABLE_WALL_HEIGHT))
                                                                .executes(context -> draft(
                                                                        context,
                                                                        IntegerArgumentType.getInteger(context, "width"),
                                                                        IntegerArgumentType.getInteger(context, "depth"),
                                                                        IntegerArgumentType.getInteger(context, "wall_height"),
                                                                        0L))
                                                                .then(argument("seed", LongArgumentType.longArg())
                                                                        .executes(context -> draft(
                                                                                context,
                                                                                IntegerArgumentType.getInteger(context, "width"),
                                                                                IntegerArgumentType.getInteger(context, "depth"),
                                                                                IntegerArgumentType.getInteger(context, "wall_height"),
                                                                                LongArgumentType.getLong(context, "seed"))))))))))
                .then(literal("catalog")
                        .then(argument("building_code", StringArgumentType.word())
                                .executes(AIBotBuildingSubcommand::catalog)))
                .then(literal("code")
                        .then(argument("bot", StringArgumentType.word())
                                .then(argument("building_code", StringArgumentType.word())
                                        .executes(context -> draftCatalog(
                                                context,
                                                CatalogBuildingDraftService.DEFAULT_SEARCH_RADIUS,
                                                false))
                                        .then(argument("search_radius", IntegerArgumentType.integer(
                                                        0, CatalogBuildingDraftService.MAX_SEARCH_RADIUS))
                                                .executes(context -> draftCatalog(
                                                        context,
                                                        IntegerArgumentType.getInteger(
                                                                context, "search_radius"),
                                                        false))))))
                .then(literal("random")
                        .then(argument("bot", StringArgumentType.word())
                                .executes(context -> draftCatalog(
                                        context,
                                        CatalogBuildingDraftService.DEFAULT_SEARCH_RADIUS,
                                        true))
                                .then(argument("search_radius", IntegerArgumentType.integer(
                                                0, CatalogBuildingDraftService.MAX_SEARCH_RADIUS))
                                        .executes(context -> draftCatalog(
                                                context,
                                                IntegerArgumentType.getInteger(
                                                        context, "search_radius"),
                                                true)))))
                .then(literal("confirm")
                        .executes(AIBotBuildingSubcommand::confirm))
                .then(literal("cancel")
                        .executes(AIBotBuildingSubcommand::cancel))
                .then(literal("move")
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(AIBotBuildingSubcommand::move)))))
                .then(literal("rotate")
                        .then(literal("north").executes(context -> rotate(context, Rotation.NONE)))
                        .then(literal("east").executes(context -> rotate(context, Rotation.CLOCKWISE_90)))
                        .then(literal("south").executes(context -> rotate(context, Rotation.CLOCKWISE_180)))
                        .then(literal("west").executes(context -> rotate(context, Rotation.COUNTERCLOCKWISE_90))))
                .then(literal("status")
                        .executes(AIBotBuildingSubcommand::status));
    }

    private static int draft(CommandContext<CommandSourceStack> context,
                             int width,
                             int depth,
                             int wallHeight,
                             long seed) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 建筑投影需要游戏内玩家执行。"));
            return 0;
        }
        String botName = StringArgumentType.getString(context, "bot");
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, botName, BotAuthorizationPolicy.Operation.COMMAND, "command:building_draft");
        if (bot.isEmpty()) {
            return 0;
        }
        HouseMaterialStyle style;
        try {
            style = style(StringArgumentType.getString(context, "style"));
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(
                    "[FakeAiPlayer] 未知风格；可用 oak_cottage、spruce_lodge、"
                            + "birch_townhouse、dark_oak_manor 或 stone_keep。"));
            return 0;
        }

        String planId = "fakeaiplayer:modular_house/" + player.getUUID() + "/" + seed;
        BuildingPlan plan;
        try {
            plan = new ModularHouseGenerator().generate(new ModularHouseRequest(
                    planId,
                    style.id() + " " + width + "x" + depth,
                    new HouseDimensions(width, depth, wallHeight),
                    seed,
                    style));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 建筑方案生成失败: " + exception.getMessage()));
            return 0;
        }

        PlanTransform transform = new PlanTransform(Mirror.NONE, rotationForFront(player.getDirection().getOpposite()));
        BlockPos anchor = anchorInFrontOf(player, plan, transform);
        BuildingPreviewService.OpenResult result = BuildingPreviewService.INSTANCE.open(
                player, bot.get(), plan, anchor, transform);
        if (!result.success()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] " + result.message()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "[FakeAiPlayer] 已生成 " + style.id()
                        + " 投影：" + width + "x" + depth + "，墙高 " + wallHeight
                        + "，" + result.placementCount() + " 个方案单元。"
                        + " 检查彩色投影后执行 /fakeaiplayer building confirm；"
                        + "取消用 /fakeaiplayer building cancel。"), false);
        return result.placementCount();
    }

    private static int catalog(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BuildingCatalogEntry entry;
        try {
            entry = BuildingCatalog.resolve(
                    StringArgumentType.getString(context, "building_code"));
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(
                    "[FakeAiPlayer] 建筑码必须是 4 到 32 位十进制数字。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "[FakeAiPlayer] 建筑码 " + entry.seedCode().value()
                        + "：" + entry.archetype().id()
                        + "，风格=" + entry.styleId()
                        + "，占地=" + entry.width() + "x" + entry.depth()
                        + "，楼层=" + entry.floors()
                        + "，屋顶=" + entry.roofType().id()
                        + "，catalog=" + entry.catalogVersion()
                        + "，generator=" + entry.generatorVersion()
                        + "，entropy=" + Long.toUnsignedString(entry.entropy())), false);
        return 1;
    }

    private static int draftCatalog(CommandContext<CommandSourceStack> context,
                                    int searchRadius,
                                    boolean randomCode) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 建筑投影需要游戏内玩家执行。"));
            return 0;
        }
        String botName = StringArgumentType.getString(context, "bot");
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, botName, BotAuthorizationPolicy.Operation.COMMAND, "command:building_code_draft");
        if (bot.isEmpty()) {
            return 0;
        }

        BuildingSeedCode code;
        try {
            code = randomCode
                    ? BuildingSeedCode.fourDigit(
                            player.getRandom().nextInt(BuildingSeedCode.INITIAL_CAPACITY))
                    : BuildingSeedCode.parse(
                            StringArgumentType.getString(context, "building_code"));
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(
                    "[FakeAiPlayer] 建筑码必须是 4 到 32 位十进制数字。"));
            return 0;
        }

        CatalogBuildingDraftService.PreparedDraft draft;
        try {
            draft = CatalogBuildingDraftService.prepare(player, bot.get(), code, searchRadius);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            source.sendFailure(Component.literal(
                    "[FakeAiPlayer] 多层建筑方案生成失败: " + exception.getMessage()));
            return 0;
        }
        BuildingPreviewService.OpenResult result = BuildingPreviewService.INSTANCE.open(
                player, bot.get(), draft.plan(), draft.anchor(), draft.transform());
        if (!result.success()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] " + result.message()));
            return 0;
        }

        BuildingCatalogEntry entry = draft.entry();
        String site = draft.automaticallySelectedSite()
                ? "已自动选择可观察地块并使用 " + draft.siteStrategy() + " 地基"
                : "未找到已加载且可观察的安全地块，当前为未勘测人工投影（"
                        + draft.fallbackReason() + "）";
        String review = draft.automaticallySelectedSite()
                ? "该地形适配投影已锁定位置，请直接检查"
                : "请检查投影，必要时先用 building move 调整位置";
        source.sendSuccess(() -> Component.literal(
                "[FakeAiPlayer] 已生成建筑码 " + entry.seedCode().value()
                        + " 的 " + entry.archetype().id()
                        + "：" + entry.width() + "x" + entry.depth()
                        + "，" + entry.floors() + " 层，风格=" + entry.styleId()
                        + "，屋顶=" + entry.roofType().id()
                        + "，" + result.placementCount() + " 个方案单元，"
                        + site + "，anchor=" + draft.anchor().toShortString()
                        + "，design=" + draft.designHash().substring(0, 12)
                        + "，hash=" + result.planHash().substring(0, 12)
                        + "。AI 不会确认；" + review
                        + "，然后由玩家执行 /fakeaiplayer building confirm。"), false);
        return result.placementCount();
    }

    private static int confirm(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 只有游戏内玩家能确认投影。"));
            return 0;
        }
        BuildingPreviewService.ConfirmResult result = BuildingPreviewService.INSTANCE.confirmLatest(player);
        if (!result.success()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] " + result.message()));
            return 0;
        }
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 只有游戏内玩家能取消投影。"));
            return 0;
        }
        if (!BuildingPreviewService.INSTANCE.cancelLatest(player, "cancelled_by_player")) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 当前没有建筑投影。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] 已取消建筑投影。"), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 只有游戏内玩家能查看投影状态。"));
            return 0;
        }
        Optional<BuildingPreviewService.SessionView> session = BuildingPreviewService.INSTANCE.session(player);
        if (session.isEmpty()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 当前没有建筑投影。"));
            return 0;
        }
        BuildingPreviewService.SessionView value = session.get();
        source.sendSuccess(() -> Component.literal(
                "[FakeAiPlayer] " + value.planName()
                        + " id=" + value.planId()
                        + " @ " + value.anchor().toShortString()
                        + " size=" + value.width() + "x" + value.height() + "x" + value.depth()
                        + " cells=" + value.placementCount()
                        + " rotation=" + value.rotation()
                        + " revision=" + value.transformRevision()
                        + " hash=" + value.planHash().substring(0, 12)), false);
        return 1;
    }

    private static int move(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 只有游戏内玩家能移动投影。"));
            return 0;
        }
        BlockPos anchor = new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
        BuildingPreviewService.UpdateResult result =
                BuildingPreviewService.INSTANCE.moveLatest(player, anchor);
        if (!result.success()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] " + result.message()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "[FakeAiPlayer] 投影锚点已移动到 " + result.session().anchor().toShortString()
                        + "；确认时将校验新的变换版本。"), false);
        return 1;
    }

    private static int rotate(CommandContext<CommandSourceStack> context, Rotation rotation) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 只有游戏内玩家能旋转投影。"));
            return 0;
        }
        BuildingPreviewService.UpdateResult result =
                BuildingPreviewService.INSTANCE.rotateLatest(player, rotation);
        if (!result.success()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] " + result.message()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "[FakeAiPlayer] 投影已旋转为 " + rotation
                        + "，revision=" + result.session().transformRevision()), false);
        return 1;
    }

    public static HouseMaterialStyle style(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "oak", "cottage", "oak_cottage" -> VanillaHouseStyles.OAK_COTTAGE;
            case "spruce", "lodge", "spruce_lodge" -> VanillaHouseStyles.SPRUCE_LODGE;
            case "birch", "townhouse", "birch_townhouse" -> VanillaHouseStyles.BIRCH_TOWNHOUSE;
            case "dark_oak", "manor", "dark_oak_manor" -> VanillaHouseStyles.DARK_OAK_MANOR;
            case "stone", "keep", "stone_keep" -> VanillaHouseStyles.STONE_KEEP;
            default -> throw new IllegalArgumentException("unknown_house_style: " + value);
        };
    }

    private static BlockPos anchorInFrontOf(ServerPlayer player,
                                            BuildingPlan plan,
                                            PlanTransform transform) {
        int width = transform.transformedWidth(plan.width(), plan.depth());
        int depth = transform.transformedDepth(plan.width(), plan.depth());
        int distance = Math.max(width, depth) / 2 + 5;
        BlockPos center = player.blockPosition().relative(player.getDirection(), distance);
        return new BlockPos(
                center.getX() - width / 2,
                player.blockPosition().getY(),
                center.getZ() - depth / 2);
    }

    private static Rotation rotationForFront(Direction front) {
        return switch (front) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }
}
