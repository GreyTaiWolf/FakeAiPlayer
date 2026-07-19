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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic compiler for large, modular, multi-storey vanilla buildings.
 *
 * <p>The compiler emits final-state cells, semantic modules and an acyclic construction graph.
 * Every level is reached by a permanent supported switchback stair before that level's slab is
 * expanded from the landing. This makes vertical connectivity part of the design contract rather
 * than an executor-only assumption.</p>
 */
public final class MultiStoreyBuildingGenerator {
    public static final String GENERATOR_VERSION = "multi-storey-building-1";

    private static final int FOUNDATION_Y = 0;
    private static final int GROUND_FLOOR_Y = 1;
    private static final int STAIR_STEPS_PER_LEVEL = MultiStoreyBuildingRequest.FLOOR_HEIGHT;
    private static final int[][] HORIZONTAL_NEIGHBOURS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1}
    };

    public BuildingPlan generate(MultiStoreyBuildingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("multi_storey_request_missing");
        }

        Layout layout = new Layout(request);
        PlanBuilder builder = new PlanBuilder();
        addFoundationAndGroundFloor(builder, layout, request.materialStyle());
        Landing roofLanding = addVerticalCoreAndUpperFloors(
                builder, layout, request.materialStyle());
        addFrame(builder, layout, request.materialStyle());
        addEnvelopeAndEntrance(builder, layout, request.materialStyle());
        addInteriorPartitions(builder, layout, request.materialStyle());
        RoofResult roof = addRoof(builder, layout, request.materialStyle(), request.roofType(), roofLanding);

        if (builder.size() > BuildingPlanValidator.MAX_PLACEMENTS) {
            throw new IllegalStateException(
                    "generated_multi_storey_too_many_placements: " + builder.size());
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("building_kind", "multi_storey");
        metadata.put("building_code", request.buildingCode().value());
        metadata.put("catalog_version", request.catalogVersion());
        metadata.put("generator_version", GENERATOR_VERSION);
        metadata.put("archetype", request.archetype().id());
        metadata.put("style", request.materialStyle().id());
        metadata.put("material_style", request.materialStyle().id());
        metadata.put("dimensions", request.width() + "x" + request.depth());
        metadata.put("floors", Integer.toString(request.floors()));
        metadata.put("floor_height", Integer.toString(MultiStoreyBuildingRequest.FLOOR_HEIGHT));
        metadata.put("entropy", Long.toUnsignedString(request.entropy()));
        metadata.put("entrance_facing", "north");
        metadata.put("roof_module", request.roofType().id());
        metadata.put("roof_rise", Integer.toString(roof.rise()));
        metadata.put("vertical_core", "permanent_supported_switchback_stair");
        metadata.put("vertical_core_x", Integer.toString(layout.coreX));
        metadata.put("vertical_core_z", layout.coreStartZ + ".." + layout.coreEndZ);
        metadata.put("served_levels", "ground..roof");
        metadata.put("module_set",
                "foundation,ground_slab,upper_slabs,frame,envelope,windows,entrance,"
                        + "interior_partitions,vertical_core," + request.roofType().id());
        metadata.put("placement_count", Integer.toString(builder.size()));

        BuildingPlan plan = new BuildingPlan(
                BuildingPlan.CURRENT_SCHEMA_VERSION,
                request.planId(),
                0,
                request.name(),
                request.width(),
                roof.planHeight(),
                request.depth(),
                request.entropy(),
                GENERATOR_VERSION,
                builder.placements(),
                metadata);

        BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validate(plan);
        if (!validation.valid()) {
            BuildingPlanValidator.Problem first = validation.problems().get(0);
            throw new IllegalStateException(
                    "generated_multi_storey_invalid: " + first.code() + ": " + first.detail());
        }
        return plan;
    }

    private static void addFoundationAndGroundFloor(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style
    ) {
        for (int z = 0; z < layout.depth; z++) {
            for (int x = 0; x < layout.width; x++) {
                builder.add(
                        "foundation:raft", x, FOUNDATION_Y, z,
                        style.foundation(), MaterialRole.FOUNDATION, BuildPhase.FOUNDATION,
                        ReplacePolicy.REPLACE_NATURAL, List.of(), "");
            }
        }
        for (int z = 0; z < layout.depth; z++) {
            for (int x = 0; x < layout.width; x++) {
                builder.add(
                        "floor:ground_slab", x, GROUND_FLOOR_Y, z,
                        style.floor(), MaterialRole.FLOOR, BuildPhase.FOUNDATION,
                        ReplacePolicy.REQUIRE_EMPTY,
                        List.of(builder.requireIdAt(x, FOUNDATION_Y, z)), "");
            }
        }
    }

    private static Landing addVerticalCoreAndUpperFloors(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style
    ) {
        Landing landing = new Landing(
                layout.coreX,
                GROUND_FLOOR_Y,
                layout.coreStartZ,
                builder.requireIdAt(layout.coreX, GROUND_FLOOR_Y, layout.coreStartZ));

        // One transition per occupied floor, including a final permanent flight onto the roof.
        for (int transition = 0; transition < layout.floors; transition++) {
            boolean towardSouth = transition % 2 == 0;
            int sourceY = floorY(transition);
            int expectedSourceZ = towardSouth ? layout.coreStartZ : layout.coreEndZ;
            if (landing.y != sourceY || landing.z != expectedSourceZ) {
                throw new IllegalStateException("multi_storey_vertical_core_landing_disconnected");
            }

            String previousStair = "";
            for (int step = 0; step < STAIR_STEPS_PER_LEVEL; step++) {
                int z = towardSouth
                        ? layout.coreStartZ + step
                        : layout.coreEndZ - step;
                int y = sourceY + step + 1;
                String support = ensureStairSupport(
                        builder, layout.coreX, sourceY, y, z, style);
                List<String> dependencies = previousStair.isBlank()
                        ? List.of(support)
                        : List.of(support, previousStair);
                previousStair = builder.add(
                        "vertical_core:stair", layout.coreX, y, z,
                        towardSouth
                                ? style.roof().northSlope()
                                : style.roof().southSlope(),
                        MaterialRole.STAIRS, BuildPhase.CONSTRUCTION_ACCESS,
                        ReplacePolicy.REQUIRE_EMPTY, dependencies, "");
            }

            landing = new Landing(
                    layout.coreX,
                    sourceY + MultiStoreyBuildingRequest.FLOOR_HEIGHT,
                    towardSouth ? layout.coreEndZ : layout.coreStartZ,
                    previousStair);
            if (transition < layout.floors - 1) {
                addSlabFromLanding(
                        builder,
                        layout,
                        landing,
                        style.floor(),
                        "floor:level_" + (transition + 2),
                        BuildPhase.CONSTRUCTION_ACCESS);
            }
        }
        return landing;
    }

    private static String ensureStairSupport(
            PlanBuilder builder,
            int x,
            int sourceY,
            int stairY,
            int z,
            HouseMaterialStyle style
    ) {
        String support = builder.idAt(x, sourceY, z);
        if (support == null) {
            throw new IllegalStateException(
                    "multi_storey_stair_source_slab_missing: " + x + "," + sourceY + "," + z);
        }
        for (int y = sourceY + 1; y < stairY; y++) {
            String existing = builder.idAt(x, y, z);
            if (existing != null) {
                support = existing;
                continue;
            }
            support = builder.add(
                    "vertical_core:support", x, y, z,
                    style.foundation(), MaterialRole.FRAME, BuildPhase.CONSTRUCTION_ACCESS,
                    ReplacePolicy.REQUIRE_EMPTY, List.of(support), "");
        }
        return support;
    }

    private static void addSlabFromLanding(
            PlanBuilder builder,
            Layout layout,
            Landing landing,
            BlockStateSpec floor,
            String component,
            BuildPhase phase
    ) {
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Set<Cell> visited = new LinkedHashSet<>();
        Cell landingCell = new Cell(landing.x, landing.y, landing.z);
        queue.addLast(landingCell);
        visited.add(landingCell);

        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            String dependency = builder.requireIdAt(current.x, landing.y, current.z);
            for (int[] direction : HORIZONTAL_NEIGHBOURS) {
                Cell next = new Cell(current.x + direction[0], landing.y, current.z + direction[1]);
                if (!layout.containsHorizontal(next.x, next.z) || !visited.add(next)) {
                    continue;
                }
                if (!builder.isOccupied(next.x, landing.y, next.z)) {
                    builder.add(
                            component, next.x, landing.y, next.z,
                            floor, MaterialRole.FLOOR, phase,
                            ReplacePolicy.REQUIRE_EMPTY, List.of(dependency), "");
                }
                queue.addLast(next);
            }
        }
        int expectedCells = layout.width * layout.depth;
        long cellsAtLevel = builder.placements.stream()
                .filter(placement -> placement.dy() == landing.y)
                .count();
        if (cellsAtLevel < expectedCells) {
            throw new IllegalStateException(
                    "multi_storey_slab_not_connected: y=" + landing.y + ", cells=" + cellsAtLevel);
        }
    }

    private static void addFrame(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style
    ) {
        List<Integer> frameXs = frameCoordinates(layout.width, layout.frameBay);
        List<Integer> frameZs = frameCoordinates(layout.depth, layout.frameBay);
        LinkedHashSet<Cell> perimeterPosts = new LinkedHashSet<>();
        for (int x : frameXs) {
            perimeterPosts.add(new Cell(x, 0, 0));
            perimeterPosts.add(new Cell(x, 0, layout.depth - 1));
        }
        for (int z : frameZs) {
            perimeterPosts.add(new Cell(0, 0, z));
            perimeterPosts.add(new Cell(layout.width - 1, 0, z));
        }

        for (int floor = 0; floor < layout.floors; floor++) {
            int baseY = floorY(floor);
            for (Cell post : perimeterPosts) {
                if (floor == 0 && post.z == 0
                        && (post.x == layout.doorX || post.x == layout.doorX - 1)) {
                    continue;
                }
                addPost(builder, post.x, post.z, baseY, 2, style);
            }
            for (int x : frameXs) {
                if (x == 0 || x == layout.width - 1) {
                    continue;
                }
                for (int z : frameZs) {
                    if (z == 0 || z == layout.depth - 1
                            || layout.inCoreClearance(x, z)) {
                        continue;
                    }
                    addPost(builder, x, z, baseY, 3, style);
                }
            }
            addRingBeam(builder, layout, style, baseY + 3);
        }
    }

    private static void addPost(
            PlanBuilder builder,
            int x,
            int z,
            int baseY,
            int height,
            HouseMaterialStyle style
    ) {
        String dependency = builder.requireIdAt(x, baseY, z);
        for (int offset = 1; offset <= height; offset++) {
            int y = baseY + offset;
            if (builder.isOccupied(x, y, z)) {
                dependency = builder.requireIdAt(x, y, z);
                continue;
            }
            dependency = builder.add(
                    "frame:post", x, y, z,
                    style.frame().vertical(), MaterialRole.FRAME, BuildPhase.FRAME,
                    ReplacePolicy.REQUIRE_EMPTY, List.of(dependency), "");
        }
    }

    private static void addRingBeam(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style,
            int y
    ) {
        List<Cell> perimeter = perimeter(layout.width, layout.depth);
        String previous = builder.requireIdAt(0, y - 1, 0);
        for (Cell cell : perimeter) {
            BlockStateSpec state = (cell.z == 0 || cell.z == layout.depth - 1)
                    ? style.frame().alongX()
                    : style.frame().alongZ();
            previous = builder.add(
                    "frame:ring_beam", cell.x, y, cell.z,
                    state, MaterialRole.FRAME, BuildPhase.FRAME,
                    ReplacePolicy.REQUIRE_EMPTY, List.of(previous), "");
        }
    }

    private static void addEnvelopeAndEntrance(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style
    ) {
        List<Cell> perimeter = perimeter(layout.width, layout.depth);
        for (int floor = 0; floor < layout.floors; floor++) {
            int baseY = floorY(floor);
            for (int layer = 1; layer <= 2; layer++) {
                int y = baseY + layer;
                for (Cell cell : perimeter) {
                    if (builder.isOccupied(cell.x, y, cell.z)) {
                        continue;
                    }
                    if (floor == 0 && cell.z == 0 && cell.x == layout.doorX) {
                        continue;
                    }
                    boolean window = layer == 2 && layout.isWindow(floor, cell.x, cell.z);
                    List<String> dependencies = new ArrayList<>();
                    dependencies.add(builder.requireIdAt(cell.x, y - 1, cell.z));
                    if (floor == 0 && cell.z == 0 && cell.x == layout.doorX - 1) {
                        // Facing south, vanilla chooses LEFT when the east jamb exists and the
                        // west jamb does not. Delay the complete west reveal until after the door
                        // click so execution reproduces the reviewed final BlockState.
                        dependencies.add(placementId(
                                "opening:main_door", layout.doorX, GROUND_FLOOR_Y + 1, 0));
                    }
                    builder.add(
                            window ? "opening:window" : "envelope:wall",
                            cell.x, y, cell.z,
                            window ? style.window() : style.wall(),
                            window ? MaterialRole.WINDOW : MaterialRole.WALL,
                            BuildPhase.WALLS_AND_OPENINGS,
                            ReplacePolicy.REQUIRE_EMPTY,
                            dependencies, "");
                }
            }
        }

        int lowerY = GROUND_FLOOR_Y + 1;
        String eastLowerJamb = builder.requireIdAt(layout.doorX + 1, lowerY, 0);
        String eastUpperJamb = builder.requireIdAt(layout.doorX + 1, lowerY + 1, 0);
        String lower = builder.add(
                "opening:main_door", layout.doorX, lowerY, 0,
                style.frontDoor().lower(false), MaterialRole.DOOR,
                BuildPhase.WALLS_AND_OPENINGS, ReplacePolicy.REQUIRE_EMPTY,
                List.of(
                        builder.requireIdAt(layout.doorX, GROUND_FLOOR_Y, 0),
                        eastLowerJamb,
                        eastUpperJamb),
                "door:main");
        builder.add(
                "opening:main_door", layout.doorX, lowerY + 1, 0,
                style.frontDoor().upper(false), MaterialRole.DOOR,
                BuildPhase.WALLS_AND_OPENINGS, ReplacePolicy.REQUIRE_EMPTY,
                List.of(lower), "door:main");
    }

    private static void addInteriorPartitions(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style
    ) {
        for (int floor = 0; floor < layout.floors; floor++) {
            int baseY = floorY(floor);
            boolean primaryAlongZ = choice(layout.entropy, 0x5714c8a2L + floor, 2) == 0;
            if (primaryAlongZ) {
                addPartitionAlongZ(builder, layout, style, floor, baseY, layout.partitionX(floor));
            } else {
                addPartitionAlongX(builder, layout, style, floor, baseY, layout.partitionZ(floor));
            }
            if (layout.width >= 19 && layout.depth >= 19) {
                if (primaryAlongZ) {
                    addPartitionAlongX(builder, layout, style, floor, baseY, layout.partitionZ(floor));
                } else {
                    addPartitionAlongZ(builder, layout, style, floor, baseY, layout.partitionX(floor));
                }
            }
        }
    }

    /** A line varying X at fixed Z. */
    private static void addPartitionAlongX(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style,
            int floor,
            int baseY,
            int z
    ) {
        int doorwayX = 2 + choice(layout.entropy, 0x6a09e667L + floor, layout.width - 4);
        for (int x = 1; x < layout.width - 1; x++) {
            if (x == doorwayX || layout.inCoreClearance(x, z)) {
                continue;
            }
            addPartitionColumn(builder, style, floor, baseY, x, z, "interior:partition_x");
        }
    }

    /** A line varying Z at fixed X. */
    private static void addPartitionAlongZ(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style,
            int floor,
            int baseY,
            int x
    ) {
        int doorwayZ = 2 + choice(layout.entropy, 0xbb67ae85L + floor, layout.depth - 4);
        for (int z = 1; z < layout.depth - 1; z++) {
            if (z == doorwayZ || layout.inCoreClearance(x, z)) {
                continue;
            }
            addPartitionColumn(builder, style, floor, baseY, x, z, "interior:partition_z");
        }
    }

    private static void addPartitionColumn(
            PlanBuilder builder,
            HouseMaterialStyle style,
            int floor,
            int baseY,
            int x,
            int z,
            String component
    ) {
        String dependency = builder.idAt(x, baseY, z);
        if (dependency == null) {
            return;
        }
        for (int layer = 1; layer <= 2; layer++) {
            int y = baseY + layer;
            if (builder.isOccupied(x, y, z)) {
                dependency = builder.requireIdAt(x, y, z);
                continue;
            }
            dependency = builder.add(
                    component + ":floor_" + (floor + 1), x, y, z,
                    style.wall(), MaterialRole.WALL, BuildPhase.INTERIOR_DETAILS,
                    ReplacePolicy.REQUIRE_EMPTY, List.of(dependency), "");
        }
    }

    private static RoofResult addRoof(
            PlanBuilder builder,
            Layout layout,
            HouseMaterialStyle style,
            BuildingRoofType roofType,
            Landing roofLanding
    ) {
        addSlabFromLanding(
                builder, layout, roofLanding, style.floor(), "roof:deck", BuildPhase.ROOF);
        if (roofType == BuildingRoofType.FLAT_PARAPET) {
            for (Cell cell : perimeter(layout.width, layout.depth)) {
                builder.add(
                        "roof:parapet", cell.x, layout.roofY + 1, cell.z,
                        style.roof().ridge(), MaterialRole.ROOF_TRIM, BuildPhase.ROOF,
                        ReplacePolicy.REQUIRE_EMPTY,
                        List.of(builder.requireIdAt(cell.x, layout.roofY, cell.z)), "");
            }
            return new RoofResult(layout.roofY + 2, 0);
        }

        int maximumAllowedRise = Math.min(7, (layout.depth - 1) / 2);
        int maximumRise = 2 + choice(
                layout.entropy,
                0x3c6ef372fe94f82bL,
                maximumAllowedRise - 1);
        for (int z = 0; z < layout.depth; z++) {
            int rise = Math.min(Math.min(z, layout.depth - 1 - z), maximumRise);
            int surfaceY = layout.roofY + 1 + rise;
            for (int x : new int[]{0, layout.width - 1}) {
                String dependency = builder.requireIdAt(x, layout.roofY, z);
                for (int y = layout.roofY + 1; y < surfaceY; y++) {
                    dependency = builder.add(
                            "roof:gable_infill", x, y, z,
                            style.wall(), MaterialRole.WALL, BuildPhase.ROOF,
                            ReplacePolicy.REQUIRE_EMPTY, List.of(dependency), "");
                }
            }

            BlockStateSpec surface;
            MaterialRole role;
            if (rise == maximumRise) {
                surface = style.roof().ridge();
                role = MaterialRole.ROOF_TRIM;
            } else if (z < layout.depth / 2) {
                surface = style.roof().northSlope();
                role = MaterialRole.ROOF;
            } else {
                surface = style.roof().southSlope();
                role = MaterialRole.ROOF;
            }
            String previous = builder.requireIdAt(0, surfaceY - 1, z);
            for (int x = 0; x < layout.width; x++) {
                previous = builder.add(
                        rise == maximumRise ? "roof:ridge" : "roof:pitch",
                        x, surfaceY, z,
                        surface, role, BuildPhase.ROOF,
                        ReplacePolicy.REQUIRE_EMPTY, List.of(previous), "");
            }
        }
        return new RoofResult(layout.roofY + maximumRise + 2, maximumRise);
    }

    private static List<Integer> frameCoordinates(int length, int bay) {
        LinkedHashSet<Integer> coordinates = new LinkedHashSet<>();
        coordinates.add(0);
        for (int value = bay; value < length - 1; value += bay) {
            coordinates.add(value);
        }
        coordinates.add(length - 1);
        return List.copyOf(coordinates);
    }

    private static List<Cell> perimeter(int width, int depth) {
        List<Cell> result = new ArrayList<>((width + depth) * 2 - 4);
        for (int x = 0; x < width; x++) {
            result.add(new Cell(x, 0, 0));
        }
        for (int z = 1; z < depth; z++) {
            result.add(new Cell(width - 1, 0, z));
        }
        for (int x = width - 2; x >= 0; x--) {
            result.add(new Cell(x, 0, depth - 1));
        }
        for (int z = depth - 2; z > 0; z--) {
            result.add(new Cell(0, 0, z));
        }
        return result;
    }

    private static int floorY(int floorIndex) {
        return GROUND_FLOOR_Y + floorIndex * MultiStoreyBuildingRequest.FLOOR_HEIGHT;
    }

    private static String placementId(String componentId, int x, int y, int z) {
        return componentId + "@" + x + "," + y + "," + z;
    }

    private static int choice(long entropy, long salt, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("deterministic_choice_bound_non_positive: " + bound);
        }
        return (int) Long.remainderUnsigned(mix64(entropy ^ salt), bound);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static final class Layout {
        final int width;
        final int depth;
        final int floors;
        final long entropy;
        final int roofY;
        final int coreX;
        final int coreStartZ;
        final int coreEndZ;
        final int doorX;
        final int frameBay;
        final int windowOffset;

        Layout(MultiStoreyBuildingRequest request) {
            width = request.width();
            depth = request.depth();
            floors = request.floors();
            entropy = request.entropy();
            roofY = floorY(floors);
            coreX = 2 + choice(entropy, 0xa54ff53a5f1d36f1L, width - 4);
            coreStartZ = 2 + choice(entropy, 0x510e527fade682d1L, depth - 6);
            coreEndZ = coreStartZ + STAIR_STEPS_PER_LEVEL - 1;
            doorX = 2 + choice(entropy, 0x9b05688c2b3e6c1fL, width - 4);
            frameBay = 4 + choice(entropy, 0x1f83d9abfb41bd6bL, 3);
            windowOffset = choice(entropy, 0x5be0cd19137e2179L, 4);
        }

        boolean containsHorizontal(int x, int z) {
            return x >= 0 && x < width && z >= 0 && z < depth;
        }

        boolean inCoreClearance(int x, int z) {
            return Math.abs(x - coreX) <= 1
                    && z >= coreStartZ - 1
                    && z <= coreEndZ + 1;
        }

        boolean isWindow(int floor, int x, int z) {
            if (z == 0 || z == depth - 1) {
                return x > 0 && x < width - 1
                        && !(floor == 0 && z == 0 && x == doorX)
                        && Math.floorMod(x + floor + windowOffset, 4) == 1;
            }
            return z > 0 && z < depth - 1
                    && Math.floorMod(z + floor + windowOffset, 4) == 1;
        }

        int partitionX(int floor) {
            return 2 + choice(entropy, 0xcbbb9d5dc1059ed8L + floor, width - 4);
        }

        int partitionZ(int floor) {
            return 2 + choice(entropy, 0x629a292a367cd507L + floor, depth - 4);
        }
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
                ReplacePolicy policy,
                List<String> dependencies,
                String atomicGroup
        ) {
            if (placements.size() >= BuildingPlanValidator.MAX_PLACEMENTS) {
                throw new IllegalStateException("generated_multi_storey_placement_limit_exceeded");
            }
            Cell cell = new Cell(x, y, z);
            if (occupied.containsKey(cell)) {
                throw new IllegalStateException(
                        "generated_multi_storey_duplicate_cell: " + x + "," + y + "," + z);
            }
            LinkedHashSet<String> normalizedDependencies = new LinkedHashSet<>();
            for (String dependency : dependencies) {
                if (dependency == null || dependency.isBlank()) {
                    throw new IllegalStateException(
                            "generated_multi_storey_dependency_missing: "
                                    + componentId + "@" + x + "," + y + "," + z);
                }
                normalizedDependencies.add(dependency);
            }
            String id = componentId + "@" + x + "," + y + "," + z;
            placements.add(new PlanPlacement(
                    id, x, y, z, state, CellOperation.PLACE, policy, role, phase,
                    componentId, List.copyOf(normalizedDependencies), atomicGroup));
            occupied.put(cell, id);
            return id;
        }

        boolean isOccupied(int x, int y, int z) {
            return occupied.containsKey(new Cell(x, y, z));
        }

        String idAt(int x, int y, int z) {
            return occupied.get(new Cell(x, y, z));
        }

        String requireIdAt(int x, int y, int z) {
            String id = idAt(x, y, z);
            if (id == null) {
                throw new IllegalStateException(
                        "generated_multi_storey_support_missing: " + x + "," + y + "," + z);
            }
            return id;
        }

        int size() {
            return placements.size();
        }

        List<PlanPlacement> placements() {
            return List.copyOf(placements);
        }
    }

    private record Cell(int x, int y, int z) {
    }

    private record Landing(int x, int y, int z, String id) {
    }

    private record RoofResult(int planHeight, int rise) {
    }
}
