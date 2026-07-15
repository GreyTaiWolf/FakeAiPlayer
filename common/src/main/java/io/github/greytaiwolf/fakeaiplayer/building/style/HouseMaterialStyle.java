package io.github.greytaiwolf.fakeaiplayer.building.style;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;

/**
 * A resolved material palette for the modular house generator.
 *
 * <p>Directional variants are supplied as complete block states instead of being inferred from
 * block names. This keeps the generator usable with modded blocks whose state properties differ
 * from vanilla and gives preview/execution exactly the same final-state contract.</p>
 */
public record HouseMaterialStyle(
        String id,
        BlockStateSpec foundation,
        BlockStateSpec floor,
        BlockStateSpec wall,
        FrameStates frame,
        BlockStateSpec window,
        DoorStates frontDoor,
        RoofStates roof,
        BlockStateSpec porchSurface,
        BlockStateSpec porchStep,
        BlockStateSpec porchSupport
) {
    public HouseMaterialStyle {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("house_style_id_missing");
        }
        requireState(foundation, "foundation");
        requireState(floor, "floor");
        requireState(wall, "wall");
        if (frame == null) {
            throw new IllegalArgumentException("house_style_frame_missing");
        }
        requireState(window, "window");
        if (frontDoor == null) {
            throw new IllegalArgumentException("house_style_front_door_missing");
        }
        if (roof == null) {
            throw new IllegalArgumentException("house_style_roof_missing");
        }
        requireState(porchSurface, "porch_surface");
        requireState(porchStep, "porch_step");
        requireState(porchSupport, "porch_support");
    }

    private static void requireState(BlockStateSpec state, String slot) {
        if (state == null || state.isAir()) {
            throw new IllegalArgumentException("house_style_state_missing: " + slot);
        }
    }

    /** Final states for vertical posts and beams running along each horizontal axis. */
    public record FrameStates(
            BlockStateSpec vertical,
            BlockStateSpec alongX,
            BlockStateSpec alongZ
    ) {
        public FrameStates {
            requireState(vertical, "frame_vertical");
            requireState(alongX, "frame_x");
            requireState(alongZ, "frame_z");
        }
    }

    /** Both halves of the two possible hinges for the north-facing entrance door. */
    public record DoorStates(
            BlockStateSpec leftLower,
            BlockStateSpec leftUpper,
            BlockStateSpec rightLower,
            BlockStateSpec rightUpper
    ) {
        public DoorStates {
            requireState(leftLower, "door_left_lower");
            requireState(leftUpper, "door_left_upper");
            requireState(rightLower, "door_right_lower");
            requireState(rightUpper, "door_right_upper");
        }

        public BlockStateSpec lower(boolean rightHinge) {
            return rightHinge ? rightLower : leftLower;
        }

        public BlockStateSpec upper(boolean rightHinge) {
            return rightHinge ? rightUpper : leftUpper;
        }
    }

    /** Final states for the two roof pitches and their longitudinal ridge. */
    public record RoofStates(
            BlockStateSpec northSlope,
            BlockStateSpec southSlope,
            BlockStateSpec eave,
            BlockStateSpec ridge
    ) {
        public RoofStates {
            requireState(northSlope, "roof_north_slope");
            requireState(southSlope, "roof_south_slope");
            requireState(eave, "roof_eave");
            requireState(ridge, "roof_ridge");
        }
    }
}
