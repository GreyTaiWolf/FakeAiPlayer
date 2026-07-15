package io.github.greytaiwolf.fakeaiplayer.building.generator;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildPhase;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanValidator;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.MaterialRole;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanPlacement;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.building.style.HouseMaterialStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, registry-independent compiler for a compact timber-frame house.
 *
 * <p>The generated plan is deliberately made from semantic modules rather than one monolithic
 * cuboid. The same final-state plan can therefore drive preview filters, material estimates and a
 * later survival placement compiler without asking an AI model to emit individual blocks.</p>
 */
public final class ModularHouseGenerator {
    public static final String GENERATOR_VERSION = "modular-house-3";

    private static final int EAVE_OVERHANG = 1;
    private static final int PORCH_DEPTH = 2;
    private static final int ENTRANCE_STAIR_RUN = 2;
    private static final int FOUNDATION_Y = 0;
    private static final int FLOOR_Y = 1;
    private static final int WALL_BOTTOM_Y = 2;

    public BuildingPlan generate(ModularHouseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("modular_house_request_missing");
        }

        HouseDimensions dimensions = request.dimensions();
        HouseMaterialStyle style = request.materialStyle();
        Layout layout = Layout.from(dimensions, request.seed());
        PlanBuilder builder = new PlanBuilder();

        addFoundation(builder, layout, style);
        addFrame(builder, layout, style);
        addGroundFloor(builder, layout, style);
        if (layout.hasLoftAccess()) {
            addLoftAndStairs(builder, layout, style);
        }
        addWallsAndOpenings(builder, layout, style);
        addGables(builder, layout, style);
        addRoof(builder, layout, style);
        addPorch(builder, layout, style);

        BuildingPlan plan = new BuildingPlan(
                BuildingPlan.CURRENT_SCHEMA_VERSION,
                request.planId(),
                0,
                request.name(),
                layout.planWidth,
                layout.planHeight,
                layout.planDepth,
                request.seed(),
                GENERATOR_VERSION,
                builder.placements(),
                Map.of(
                        "building_kind", "modular_house",
                        "entrance_facing", "north",
                        "enclosed_footprint", dimensions.width() + "x" + dimensions.depth(),
                        "material_style", style.id(),
                        "module_set", layout.hasLoftAccess()
                                ? "foundation,floor,frame,loft,stairs,walls,openings,porch,terraces,gable_roof"
                                : "foundation,floor,frame,walls,openings,porch,terraces,gable_roof",
                        "roof_access", layout.hasLoftAccess() ? "permanent_loft_stair" : "design_only",
                        "porch_width", Integer.toString(layout.porchWidth),
                        "wall_height", Integer.toString(dimensions.wallHeight())));

        BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validate(plan);
        if (!validation.valid()) {
            BuildingPlanValidator.Problem first = validation.problems().get(0);
            throw new IllegalStateException(
                    "generated_house_invalid: " + first.code() + ": " + first.detail());
        }
        return plan;
    }

    private static void addFoundation(PlanBuilder builder, Layout layout, HouseMaterialStyle style) {
        for (int z = layout.houseZ; z <= layout.backZ; z++) {
            for (int x = layout.houseX; x <= layout.rightX; x++) {
                builder.add(
                        "foundation:slab", x, FOUNDATION_Y, z,
                        style.foundation(), MaterialRole.FOUNDATION, BuildPhase.FOUNDATION,
                        ReplacePolicy.REPLACE_NATURAL, List.of(), "");
            }
        }

        builder.add(
                "foundation:porch_support_west", layout.porchStartX, FOUNDATION_Y, layout.porchFrontZ,
                style.porchSupport(), MaterialRole.FOUNDATION, BuildPhase.FOUNDATION,
                ReplacePolicy.REPLACE_NATURAL, List.of(), "");
        builder.add(
                "foundation:porch_support_east", layout.porchEndX, FOUNDATION_Y, layout.porchFrontZ,
                style.porchSupport(), MaterialRole.FOUNDATION, BuildPhase.FOUNDATION,
                ReplacePolicy.REPLACE_NATURAL, List.of(), "");
        // The floor surface is two blocks above the surrounding terrain when the anchor is the
        // player's feet/air layer. A full support under the upper stair makes a genuine two-step
        // entrance possible and also gives strict-survival placement a legal clicked face.
        builder.add(
                "foundation:porch_step_support", layout.doorX, FOUNDATION_Y, layout.porchStepZ,
                style.porchSupport(), MaterialRole.FOUNDATION, BuildPhase.FOUNDATION,
                ReplacePolicy.REPLACE_NATURAL, List.of(), "");

        // These permanent front/rear terraces are both architecture and the reviewed work poses
        // for the two eaves. At the public wall-height limit (5), a player standing here can reach
        // the exterior face of the ring beam with an ordinary useItemOn interaction. Once an eave
        // exists, the interior loft/ring beam route reaches its top and construction proceeds
        // uphill without disposable scaffolding.
        for (int x = layout.houseX; x <= layout.rightX; x++) {
            if (builder.idAt(x, FOUNDATION_Y, layout.frontTerraceZ) == null) {
                builder.add(
                        "foundation:front_terrace", x, FOUNDATION_Y, layout.frontTerraceZ,
                        style.foundation(), MaterialRole.FOUNDATION, BuildPhase.FOUNDATION,
                        ReplacePolicy.REPLACE_NATURAL, List.of(), "");
            }
            builder.add(
                    "foundation:rear_terrace", x, FOUNDATION_Y, layout.rearTerraceZ,
                    style.foundation(), MaterialRole.FOUNDATION, BuildPhase.FOUNDATION,
                    ReplacePolicy.REPLACE_NATURAL, List.of(), "");
        }
    }

    private static void addFrame(PlanBuilder builder, Layout layout, HouseMaterialStyle style) {
        addCornerPost(builder, layout.houseX, layout.houseZ, layout, style, "north_west");
        addCornerPost(builder, layout.rightX, layout.houseZ, layout, style, "north_east");
        addCornerPost(builder, layout.houseX, layout.backZ, layout, style, "south_west");
        addCornerPost(builder, layout.rightX, layout.backZ, layout, style, "south_east");

        String northWest = builder.idAt(layout.houseX, layout.wallTopY, layout.houseZ);
        String northEast = builder.idAt(layout.rightX, layout.wallTopY, layout.houseZ);
        String southWest = builder.idAt(layout.houseX, layout.wallTopY, layout.backZ);
        String southEast = builder.idAt(layout.rightX, layout.wallTopY, layout.backZ);

        for (int x = layout.houseX + 1; x < layout.rightX; x++) {
            builder.add(
                    "frame:beam_north", x, layout.wallTopY, layout.houseZ,
                    style.frame().alongX(), MaterialRole.FRAME, BuildPhase.FRAME,
                    ReplacePolicy.REQUIRE_EMPTY,
                    List.of(nearest(x, layout.houseX, layout.rightX, northWest, northEast)), "");
            builder.add(
                    "frame:beam_south", x, layout.wallTopY, layout.backZ,
                    style.frame().alongX(), MaterialRole.FRAME, BuildPhase.FRAME,
                    ReplacePolicy.REQUIRE_EMPTY,
                    List.of(nearest(x, layout.houseX, layout.rightX, southWest, southEast)), "");
        }
        for (int z = layout.houseZ + 1; z < layout.backZ; z++) {
            builder.add(
                    "frame:beam_west", layout.houseX, layout.wallTopY, z,
                    style.frame().alongZ(), MaterialRole.FRAME, BuildPhase.FRAME,
                    ReplacePolicy.REQUIRE_EMPTY,
                    List.of(nearest(z, layout.houseZ, layout.backZ, northWest, southWest)), "");
            builder.add(
                    "frame:beam_east", layout.rightX, layout.wallTopY, z,
                    style.frame().alongZ(), MaterialRole.FRAME, BuildPhase.FRAME,
                    ReplacePolicy.REQUIRE_EMPTY,
                    List.of(nearest(z, layout.houseZ, layout.backZ, northEast, southEast)), "");
        }
    }

    private static void addCornerPost(
            PlanBuilder builder,
            int x,
            int z,
            Layout layout,
            HouseMaterialStyle style,
            String corner
    ) {
        for (int y = FLOOR_Y; y <= layout.wallTopY; y++) {
            String dependency = y == FLOOR_Y
                    ? builder.idAt(x, FOUNDATION_Y, z)
                    : builder.idAt(x, y - 1, z);
            builder.add(
                    "frame:corner_post_" + corner, x, y, z,
                    style.frame().vertical(), MaterialRole.FRAME, BuildPhase.FRAME,
                    ReplacePolicy.REQUIRE_EMPTY, List.of(dependency), "");
        }
    }

    private static void addGroundFloor(PlanBuilder builder, Layout layout, HouseMaterialStyle style) {
        for (int z = layout.houseZ; z <= layout.backZ; z++) {
            for (int x = layout.houseX; x <= layout.rightX; x++) {
                if (builder.idAt(x, FLOOR_Y, z) != null) {
                    // Corner posts continue through the floor onto the foundation rather than
                    // floating one block above it. They already occupy these four floor cells.
                    continue;
                }
                builder.add(
                        "floor:ground", x, FLOOR_Y, z,
                        // The current strict-survival executor needs a walkable work deck before
                        // erecting tall posts and ring beams. Keep this ground floor in the
                        // foundation stage; upper floors/stairs remain a later semantic phase.
                        style.floor(), MaterialRole.FLOOR, BuildPhase.FOUNDATION,
                        ReplacePolicy.REQUIRE_EMPTY,
                        List.of(builder.idAt(x, FOUNDATION_Y, z)), "");
            }
        }
    }

    /**
     * Permanent supported stair and loft deck used both as architecture and as the roof work
     * platform. Every stair has a real column/floor support. The public-height loft stays at y=4:
     * high enough for two blocks of ground-floor headroom, but low enough that a player at its edge
     * can raycast over the deck and click the tops of unfinished frame posts. For wall height 5,
     * one final stair continues above the loft as roof access.
     */
    private static void addLoftAndStairs(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style
    ) {
        int loftY = Math.min(layout.wallTopY - 1, WALL_BOTTOM_Y + 2);
        int stairTopY = layout.wallTopY - 1;
        int stairX = layout.houseX + 1;
        int stairStartZ = layout.houseZ + 1;
        int stairCount = stairTopY - WALL_BOTTOM_Y + 1;
        if (!layout.hasLoftAccess()
                || stairStartZ + stairCount - 1 >= layout.backZ) {
            throw new IllegalStateException("generated_house_loft_stair_does_not_fit");
        }

        Set<Cell> stairOpening = new HashSet<>();
        String previousStair = "";
        Cell loftLandingStair = null;
        for (int index = 0; index < stairCount; index++) {
            int y = WALL_BOTTOM_Y + index;
            int z = stairStartZ + index;
            stairOpening.add(new Cell(stairX, loftY, z));

            String support = builder.idAt(stairX, FLOOR_Y, z);
            for (int supportY = WALL_BOTTOM_Y; supportY < y; supportY++) {
                support = builder.add(
                        "interior:stair_support", stairX, supportY, z,
                        // Permanent construction access must exist before tall frame posts.
                        style.floor(), MaterialRole.FLOOR, BuildPhase.CONSTRUCTION_ACCESS,
                        ReplacePolicy.REQUIRE_EMPTY, List.of(support), "");
            }
            List<String> dependencies = previousStair.isBlank()
                    ? List.of(support)
                    : List.of(support, previousStair);
            previousStair = builder.add(
                    "interior:loft_stair", stairX, y, z,
                    style.porchStep(), MaterialRole.STAIRS, BuildPhase.CONSTRUCTION_ACCESS,
                    ReplacePolicy.REQUIRE_EMPTY, dependencies, "");
            if (y == loftY) {
                loftLandingStair = new Cell(stairX, loftY, z);
            }
        }

        if (loftLandingStair == null) {
            throw new IllegalStateException("generated_house_loft_stair_missing");
        }
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Set<Cell> visited = new HashSet<>();
        queue.add(loftLandingStair);
        visited.add(loftLandingStair);
        int[][] horizontal = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            String dependency = builder.idAt(current.x(), loftY, current.z());
            if (dependency == null) {
                throw new IllegalStateException("generated_house_loft_seed_missing");
            }
            for (int[] offset : horizontal) {
                Cell next = new Cell(current.x() + offset[0], loftY, current.z() + offset[1]);
                if (next.x() <= layout.houseX || next.x() >= layout.rightX
                        || next.z() <= layout.houseZ || next.z() >= layout.backZ
                        || stairOpening.contains(next)
                        || !visited.add(next)) {
                    continue;
                }
                builder.add(
                        "floor:loft", next.x(), loftY, next.z(),
                        style.floor(), MaterialRole.FLOOR, BuildPhase.CONSTRUCTION_ACCESS,
                        ReplacePolicy.REQUIRE_EMPTY, List.of(dependency), "");
                queue.addLast(next);
            }
        }
    }

    private static void addWallsAndOpenings(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style
    ) {
        Set<Integer> frontWindows = windowPositionsAlongX(layout, true);
        Set<Integer> backWindows = windowPositionsAlongX(layout, false);
        Set<Integer> sideWindows = windowPositionsAlongZ(layout);

        for (int y = WALL_BOTTOM_Y; y < layout.wallTopY; y++) {
            for (int x = layout.houseX + 1; x < layout.rightX; x++) {
                addFacadeCell(builder, layout, style, x, y, layout.houseZ, true, frontWindows);
                addFacadeCell(builder, layout, style, x, y, layout.backZ, false, backWindows);
            }
            for (int z = layout.houseZ + 1; z < layout.backZ; z++) {
                addSideCell(builder, layout, style, layout.houseX, y, z, "west", sideWindows);
                addSideCell(builder, layout, style, layout.rightX, y, z, "east", sideWindows);
            }
        }
    }

    private static void addFacadeCell(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style,
            int x,
            int y,
            int z,
            boolean front,
            Set<Integer> windowPositions
    ) {
        String dependency = supportBelow(builder, x, y, z);
        if (front && x == layout.doorX && (y == WALL_BOTTOM_Y || y == WALL_BOTTOM_Y + 1)) {
            boolean lower = y == WALL_BOTTOM_Y;
            List<String> doorDependencies;
            if (lower) {
                // Facing SOUTH, vanilla DoorBlock assigns the counter-clockwise EAST side a
                // negative neighbour score, and a negative score resolves to LEFT. Build the east
                // jamb at both levels first and deliberately delay the west jamb
                // until after the click. This forces the hinge without relying on a floating-point
                // ray landing on exactly one side of the supporting face's 0.5 centre line.
                doorDependencies = List.of(
                        dependency,
                        placementId(frontFacadeComponent(layout, x + 1, WALL_BOTTOM_Y, windowPositions),
                                x + 1, WALL_BOTTOM_Y, z),
                        placementId(frontFacadeComponent(layout, x + 1, WALL_BOTTOM_Y + 1, windowPositions),
                                x + 1, WALL_BOTTOM_Y + 1, z));
            } else {
                doorDependencies = List.of(dependency);
            }
            builder.add(
                    "opening:front_door", x, y, z,
                    lower
                            ? style.frontDoor().lower(layout.rightDoorHinge)
                            : style.frontDoor().upper(layout.rightDoorHinge),
                    // Keep the doorway physically open while the bot uses the interior stair and
                    // loft as its roof work platform. The finished door is installed afterwards.
                    MaterialRole.DOOR, BuildPhase.EXTERIOR_FEATURES,
                    ReplacePolicy.REQUIRE_EMPTY, doorDependencies, "door:front");
            return;
        }
        List<String> dependencies = facadeDependencies(layout, front, x, y, z, dependency);
        BuildPhase phase = dependencies.stream().anyMatch(value -> value.startsWith("opening:front_door@"))
                ? BuildPhase.EXTERIOR_FEATURES
                : BuildPhase.WALLS_AND_OPENINGS;
        if (windowPositions.contains(x) && layout.isWindowLevel(y)) {
            builder.add(
                    front ? "opening:window_north" : "opening:window_south", x, y, z,
                    style.window(), MaterialRole.WINDOW, phase,
                    ReplacePolicy.REQUIRE_EMPTY,
                    dependencies, "");
            return;
        }
        builder.add(
                front ? "envelope:wall_north" : "envelope:wall_south", x, y, z,
                style.wall(), MaterialRole.WALL, phase,
                ReplacePolicy.REQUIRE_EMPTY,
                dependencies, "");
    }

    private static void addSideCell(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style,
            int x,
            int y,
            int z,
            String side,
            Set<Integer> windowPositions
    ) {
        boolean window = windowPositions.contains(z) && layout.isWindowLevel(y);
        builder.add(
                window ? "opening:window_" + side : "envelope:wall_" + side,
                x, y, z,
                window ? style.window() : style.wall(),
                window ? MaterialRole.WINDOW : MaterialRole.WALL,
                BuildPhase.WALLS_AND_OPENINGS,
                ReplacePolicy.REQUIRE_EMPTY,
                List.of(supportBelow(builder, x, y, z)), "");
    }

    private static void addGables(PlanBuilder builder, Layout layout, HouseMaterialStyle style) {
        for (int z = layout.houseZ; z <= layout.backZ; z++) {
            int surfaceY = layout.roofSurfaceY(z);
            for (int y = layout.wallTopY + 1; y < surfaceY; y++) {
                addGableCell(builder, layout.houseX, y, z, "west", style);
                addGableCell(builder, layout.rightX, y, z, "east", style);
            }
        }
    }

    private static void addGableCell(
            PlanBuilder builder,
            int x,
            int y,
            int z,
            String side,
            HouseMaterialStyle style
    ) {
        builder.add(
                "envelope:gable_" + side, x, y, z,
                // Interleave gable infill with roof rows by height. The first low roof stair then
                // becomes a real walkable route for the next gable support and slope row instead
                // of demanding that the bot erect the whole high triangle from the floor.
                style.wall(), MaterialRole.WALL, BuildPhase.ROOF,
                ReplacePolicy.REQUIRE_EMPTY,
                List.of(builder.idAt(x, y - 1, z)), "");
    }

    private static void addRoof(PlanBuilder builder, Layout layout, HouseMaterialStyle style) {
        addEaveRow(builder, layout, style, layout.roofFrontZ, layout.houseZ);
        addEaveRow(builder, layout, style, layout.roofBackZ, layout.backZ);

        // Compile each pitch from its low eave toward the ridge. The completed outward row is a
        // permanent work platform: standing on it makes StairBlock's horizontal placement facing
        // match the reviewed uphill state. Each new row also keeps an actual clicked-face support
        // (gable/ring beam for x=houseX, then the previous block across x).
        for (int z = layout.roofFrontZ + 1;
             layout.roofRise(z) < layout.maximumRoofRise;
             z++) {
            addPitchRow(builder, layout, style, z, z - 1, style.roof().northSlope());
        }
        for (int z = layout.roofBackZ - 1;
             layout.roofRise(z) < layout.maximumRoofRise;
             z--) {
            addPitchRow(builder, layout, style, z, z + 1, style.roof().southSlope());
        }

        List<Integer> ridgeRows = new ArrayList<>();
        for (int z = layout.roofFrontZ + 1; z < layout.roofBackZ; z++) {
            if (layout.roofRise(z) == layout.maximumRoofRise) {
                ridgeRows.add(z);
            }
        }
        for (int index = 0; index < ridgeRows.size(); index++) {
            int z = ridgeRows.get(index);
            int workRowZ = index == 0 ? z - 1 : z + 1;
            Integer previousRidgeZ = index == 0 ? null : ridgeRows.get(index - 1);
            addRidgeRow(builder, layout, style, z, workRowZ, previousRidgeZ);
        }
    }

    private static void addEaveRow(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style,
            int z,
            int wallZ
    ) {
        int workTerraceZ = z == layout.roofFrontZ
                ? layout.frontTerraceZ
                : layout.rearTerraceZ;
        for (int x = layout.houseX; x <= layout.rightX; x++) {
            builder.add(
                    "roof:eave", x, layout.roofBaseY, z,
                    style.roof().eave(), MaterialRole.ROOF_TRIM, BuildPhase.ROOF,
                    ReplacePolicy.REQUIRE_EMPTY,
                    List.of(
                            builder.idAt(x, layout.wallTopY, wallZ),
                            builder.idAt(x, FOUNDATION_Y, workTerraceZ)), "");
        }
    }

    private static void addPitchRow(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style,
            int z,
            int outwardWorkRowZ,
            BlockStateSpec state
    ) {
        int y = layout.roofSurfaceY(z);
        for (int x = layout.houseX; x <= layout.rightX; x++) {
            List<String> dependencies = new ArrayList<>();
            dependencies.add(builder.idAt(
                    x, layout.roofSurfaceY(outwardWorkRowZ), outwardWorkRowZ));
            dependencies.add(x == layout.houseX
                    ? builder.idAt(x, y - 1, z)
                    : builder.idAt(x - 1, y, z));
            builder.add(
                    "roof:pitch", x, y, z,
                    state, MaterialRole.ROOF, BuildPhase.ROOF,
                    ReplacePolicy.REQUIRE_EMPTY, dependencies, "");
        }
    }

    private static void addRidgeRow(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style,
            int z,
            int workRowZ,
            Integer previousRidgeZ
    ) {
        int y = layout.roofSurfaceY(z);
        for (int x = layout.houseX; x <= layout.rightX; x++) {
            List<String> dependencies = new ArrayList<>();
            dependencies.add(builder.idAt(x, layout.roofSurfaceY(workRowZ), workRowZ));
            if (previousRidgeZ != null) {
                dependencies.add(builder.idAt(x, y, previousRidgeZ));
            }
            dependencies.add(x == layout.houseX
                    ? builder.idAt(x, y - 1, z)
                    : builder.idAt(x - 1, y, z));
            builder.add(
                    "roof:ridge", x, y, z,
                    style.roof().ridge(), MaterialRole.ROOF_TRIM, BuildPhase.ROOF,
                    ReplacePolicy.REQUIRE_EMPTY, dependencies, "");
        }
    }

    private static void addPorch(PlanBuilder builder, Layout layout, HouseMaterialStyle style) {
        for (int z = layout.porchFrontZ; z < layout.houseZ; z++) {
            for (int x = layout.porchStartX; x <= layout.porchEndX; x++) {
                String support;
                if (z == layout.porchFrontZ) {
                    support = builder.idAt(x, FOUNDATION_Y, layout.porchFrontZ);
                } else {
                    support = builder.idAt(x, FOUNDATION_Y, layout.houseZ);
                }
                builder.add(
                        "exterior:porch_deck", x, FLOOR_Y, z,
                        // This deck and its two stairs are construction access, not late decor.
                        // Build them with the foundation so the bot can reach the floor platform
                        // before frame posts and walls begin.
                        style.porchSurface(), MaterialRole.FLOOR, BuildPhase.FOUNDATION,
                        ReplacePolicy.REPLACE_REPLACEABLE, List.of(support), "");
            }
        }

        String stepSupport = builder.idAt(layout.doorX, FOUNDATION_Y, layout.porchStepZ);
        String porchLanding = builder.idAt(layout.doorX, FLOOR_Y, layout.porchFrontZ);
        String lowerStep = builder.add(
                "exterior:porch_step_lower", layout.doorX, FOUNDATION_Y, layout.porchLowerStepZ,
                style.porchStep(), MaterialRole.STAIRS, BuildPhase.FOUNDATION,
                ReplacePolicy.REPLACE_REPLACEABLE,
                List.of(stepSupport), "");
        builder.add(
                "exterior:porch_step_upper", layout.doorX, FLOOR_Y, layout.porchStepZ,
                style.porchStep(), MaterialRole.STAIRS, BuildPhase.FOUNDATION,
                ReplacePolicy.REPLACE_REPLACEABLE,
                // The landing is a same-height southern click face. Besides physical support it
                // lets BlockPlaceContext deterministically predict the requested SOUTH stair.
                List.of(stepSupport, lowerStep, porchLanding), "");
    }

    private static String supportBelow(PlanBuilder builder, int x, int y, int z) {
        return builder.idAt(x, y == WALL_BOTTOM_Y ? FLOOR_Y : y - 1, z);
    }

    private static String placementId(String componentId, int x, int y, int z) {
        return componentId + "@" + x + "," + y + "," + z;
    }

    private static String frontFacadeComponent(Layout layout,
                                               int x,
                                               int y,
                                               Set<Integer> windowPositions) {
        return windowPositions.contains(x) && layout.isWindowLevel(y)
                ? "opening:window_north"
                : "envelope:wall_north";
    }

    private static List<String> facadeDependencies(Layout layout,
                                                   boolean front,
                                                   int x,
                                                   int y,
                                                   int z,
                                                   String support) {
        if (front
                && (x == layout.doorX - 1
                        || x == layout.doorX && y > WALL_BOTTOM_Y + 1)) {
            // Delaying only the two jamb cells would make every cell above them depend on a later
            // phase through supportBelow. Keep the complete west reveal and the cells over the
            // door in the same post-roof phase: the opening stays usable during roof work and the
            // dependency graph remains physically supported as it is closed from bottom to top.
            return List.of(
                    support,
                    placementId("opening:front_door", layout.doorX, WALL_BOTTOM_Y, z));
        }
        return List.of(support);
    }

    private static Set<Integer> windowPositionsAlongX(Layout layout, boolean front) {
        LinkedHashSet<Integer> positions = new LinkedHashSet<>();
        if (front) {
            addIfInterior(positions, layout.doorX - 2, layout.houseX, layout.rightX);
            addIfInterior(positions, layout.doorX + 2, layout.houseX, layout.rightX);
            if (layout.rightX - layout.houseX >= 10) {
                addIfInterior(positions, layout.houseX + 2, layout.houseX, layout.rightX);
                addIfInterior(positions, layout.rightX - 2, layout.houseX, layout.rightX);
            }
        } else {
            addIfInterior(
                    positions,
                    layout.houseX + layout.enclosedWidth / 3,
                    layout.houseX,
                    layout.rightX);
            addIfInterior(
                    positions,
                    layout.houseX + (layout.enclosedWidth * 2) / 3,
                    layout.houseX,
                    layout.rightX);
        }
        positions.remove(layout.doorX);
        return positions;
    }

    private static Set<Integer> windowPositionsAlongZ(Layout layout) {
        LinkedHashSet<Integer> positions = new LinkedHashSet<>();
        addIfInterior(
                positions,
                layout.houseZ + layout.enclosedDepth / 3,
                layout.houseZ,
                layout.backZ);
        addIfInterior(
                positions,
                layout.houseZ + (layout.enclosedDepth * 2) / 3,
                layout.houseZ,
                layout.backZ);
        return positions;
    }

    private static void addIfInterior(Set<Integer> positions, int value, int start, int end) {
        if (value > start && value < end) {
            positions.add(value);
        }
    }

    private static String nearest(
            int value,
            int lowCoordinate,
            int highCoordinate,
            String lowId,
            String highId
    ) {
        return value - lowCoordinate <= highCoordinate - value ? lowId : highId;
    }

    private static int deterministicChoice(long seed, long salt, int bound) {
        long mixed = mix64(seed ^ salt) & Long.MAX_VALUE;
        return (int) (mixed % bound);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class PlanBuilder {
        private final List<PlanPlacement> placements = new ArrayList<>();
        private final Map<Cell, String> occupied = new HashMap<>();

        String add(
                String componentId,
                int x,
                int y,
                int z,
                BlockStateSpec state,
                MaterialRole role,
                BuildPhase phase,
                ReplacePolicy replacePolicy,
                List<String> dependencies,
                String atomicGroup
        ) {
            Cell cell = new Cell(x, y, z);
            if (occupied.containsKey(cell)) {
                throw new IllegalStateException(
                        "generated_house_duplicate_cell: " + x + "," + y + "," + z);
            }
            for (String dependency : dependencies) {
                if (dependency == null || dependency.isBlank()) {
                    throw new IllegalStateException(
                            "generated_house_support_missing: " + componentId + "@" + x + "," + y + "," + z);
                }
            }
            String id = componentId + "@" + x + "," + y + "," + z;
            PlanPlacement placement = new PlanPlacement(
                    id, x, y, z, state,
                    CellOperation.PLACE, replacePolicy, role, phase,
                    componentId, List.copyOf(new LinkedHashSet<>(dependencies)), atomicGroup);
            placements.add(placement);
            occupied.put(cell, id);
            return id;
        }

        String idAt(int x, int y, int z) {
            return occupied.get(new Cell(x, y, z));
        }

        List<PlanPlacement> placements() {
            return List.copyOf(placements);
        }
    }

    private record Cell(int x, int y, int z) {
    }

    private static final class Layout {
        private static final long DOOR_SALT = 0x243f6a8885a308d3L;
        private static final long PORCH_SALT = 0xa4093822299f31d0L;

        final int enclosedWidth;
        final int enclosedDepth;
        final int houseX;
        final int houseZ;
        final int rightX;
        final int backZ;
        final int wallTopY;
        final int roofBaseY;
        final int roofFrontZ;
        final int roofBackZ;
        final int frontTerraceZ;
        final int rearTerraceZ;
        final int maximumRoofRise;
        final int planWidth;
        final int planHeight;
        final int planDepth;
        final int doorX;
        final boolean rightDoorHinge;
        final int porchWidth;
        final int porchStartX;
        final int porchEndX;
        final int porchFrontZ;
        final int porchStepZ;
        final int porchLowerStepZ;

        private Layout(HouseDimensions dimensions, long seed) {
            enclosedWidth = dimensions.width();
            enclosedDepth = dimensions.depth();
            // Side rake overhangs require a work position outside the first/last roof cell. Keep
            // the gable ends flush and retain the visually important front/rear eaves, whose
            // exterior click faces are served by permanent terraces below.
            houseX = 0;
            houseZ = PORCH_DEPTH + EAVE_OVERHANG + ENTRANCE_STAIR_RUN - 1;
            rightX = houseX + enclosedWidth - 1;
            backZ = houseZ + enclosedDepth - 1;
            wallTopY = FLOOR_Y + dimensions.wallHeight();
            // Put the eave row level with the ring beam. This gives strict-survival placement a
            // real adjacent support; starting one block above and one block outside the wall
            // would leave the first overhang row diagonally unsupported.
            roofBaseY = wallTopY;
            roofFrontZ = houseZ - EAVE_OVERHANG;
            roofBackZ = backZ + EAVE_OVERHANG;
            frontTerraceZ = roofFrontZ - 1;
            rearTerraceZ = roofBackZ + 1;
            maximumRoofRise = (roofBackZ - roofFrontZ) / 2;
            planWidth = enclosedWidth;
            planHeight = roofBaseY + maximumRoofRise + 1;
            planDepth = rearTerraceZ + 1;

            int shift = enclosedWidth >= 9 ? deterministicChoice(seed, DOOR_SALT, 3) - 1 : 0;
            doorX = clamp(
                    houseX + enclosedWidth / 2 + shift,
                    houseX + 2,
                    rightX - 2);
            // The generator currently emits LEFT and enforces it through east-before-door,
            // west-after-door jamb dependencies in addFacadeCell.
            rightDoorHinge = false;

            int desiredPorchWidth = deterministicChoice(seed, PORCH_SALT, 2) == 0 ? 3 : 5;
            porchWidth = Math.min(desiredPorchWidth, enclosedWidth - 2);
            int halfWidth = porchWidth / 2;
            porchStartX = clamp(doorX - halfWidth, houseX + 1, rightX - porchWidth);
            porchEndX = porchStartX + porchWidth - 1;
            porchFrontZ = houseZ - PORCH_DEPTH;
            porchStepZ = porchFrontZ - 1;
            porchLowerStepZ = porchStepZ - 1;
        }

        static Layout from(HouseDimensions dimensions, long seed) {
            return new Layout(dimensions, seed);
        }

        boolean isWindowLevel(int y) {
            return y >= WALL_BOTTOM_Y + 1 && y <= Math.min(WALL_BOTTOM_Y + 2, wallTopY - 1);
        }

        int roofRise(int z) {
            return Math.min(z - roofFrontZ, roofBackZ - z);
        }

        int roofSurfaceY(int z) {
            return roofBaseY + roofRise(z);
        }

        /** Whether a straight, two-landings-clear stair can reach a permanent loft. */
        boolean hasLoftAccess() {
            int loftY = wallTopY - 1;
            int stairCount = loftY - WALL_BOTTOM_Y + 1;
            int stairStartZ = houseZ + 1;
            return stairCount > 0 && stairStartZ + stairCount - 1 < backZ;
        }
    }
}
