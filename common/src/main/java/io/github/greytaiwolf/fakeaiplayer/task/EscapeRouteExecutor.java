package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionPack;
import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.WalkToController;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.DangerCheck;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.LocalOpenness;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.MoveType;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NeighborEnumerator;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Node;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalPolicy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

/**
 * Executes a pre-validated escape path without digging, placing blocks, or silently replanning
 * with broader permissions. A failed route is returned to {@link EvadeTask}, which can choose a
 * different route or escalate to break-contact combat.
 */
final class EscapeRouteExecutor {
    private static final int MAX_TICKS = 360;
    private static final int NO_PROGRESS_LIMIT = 50;
    private static final int ROUTE_REVALIDATE_LOOKAHEAD = 3;

    private final List<Node> path;
    private final boolean terminalOpenRequired;
    private int index = 1;
    private WalkToController walker;
    private Vec3 lastPos;
    private int noProgressTicks;
    private int elapsed;

    EscapeRouteExecutor(List<Node> path) {
        this(path, true);
    }

    EscapeRouteExecutor(List<Node> path, boolean terminalOpenRequired) {
        this.path = List.copyOf(path);
        this.terminalOpenRequired = terminalOpenRequired;
    }

    ActionResult tick(ActionPack pack) {
        elapsed++;
        if (elapsed > MAX_TICKS) {
            cleanup(pack);
            return ActionResult.failed("escape_route_timeout");
        }
        while (index < path.size() && arrivedAt(pack.player().blockPosition(), path.get(index).pos())) {
            index++;
            walker = null;
        }
        if (index >= path.size()) {
            cleanup(pack);
            return ActionResult.SUCCESS;
        }

        Node next = path.get(index);
        if (!supports(next.moveType())) {
            cleanup(pack);
            return ActionResult.failed("escape_route_world_edit_step:" + next.moveType());
        }
        String invalid = validateUpcomingRoute(pack);
        if (invalid != null) {
            cleanup(pack);
            return ActionResult.failed("escape_route_invalidated:" + invalid);
        }

        if (walker == null) {
            walker = new WalkToController(Vec3.atCenterOf(next.pos()));
        }
        ActionResult movement = walker.tick(pack);
        // Escape movement intentionally overrides the normal short-target sprint threshold.
        pack.setSprinting(true);
        if (movement.isSuccess()) {
            index++;
            walker = null;
        } else if (movement.isFailed()) {
            cleanup(pack);
            return ActionResult.failed("escape_route_walk_failed:" + movement.reason());
        }

        Vec3 current = pack.player().position();
        if (lastPos != null && current.distanceTo(lastPos) < 0.025D) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
        }
        lastPos = current;
        if (noProgressTicks > NO_PROGRESS_LIMIT) {
            cleanup(pack);
            return ActionResult.failed("escape_route_no_progress");
        }
        return ActionResult.IN_PROGRESS;
    }

    void abort(ActionPack pack) {
        cleanup(pack);
    }

    private String validateUpcomingRoute(ActionPack pack) {
        var world = pack.player().serverLevel();
        Standability.clearCache();
        NeighborEnumerator validator = new NeighborEnumerator(
                false, false, TraversalPolicy.ESCAPE_DRY_OPEN);
        validator.setPathGoal(path.get(path.size() - 1).pos());
        int last = Math.min(path.size() - 1, index + ROUTE_REVALIDATE_LOOKAHEAD - 1);
        for (int candidateIndex = index; candidateIndex <= last; candidateIndex++) {
            Node candidate = path.get(candidateIndex);
            if (!supports(candidate.moveType())) {
                return "world_edit_step:" + candidate.moveType();
            }
            String danger = DangerCheck.scan(
                    world, candidate.pos(), TraversalPolicy.ESCAPE_DRY_OPEN);
            if (danger != null) {
                return "danger:" + danger;
            }
            Node previous = path.get(candidateIndex - 1);
            boolean transitionValid = validator.hasTransition(
                    previous.pos(), candidate.pos(), candidate.moveType(), world);
            if (!transitionValid) {
                return "transition_blocked";
            }
            if (hostileOccupiesNextStep(pack, candidate.pos())) {
                return "hostile_blocked";
            }
        }
        if (terminalOpenRequired && last == path.size() - 1 && !LocalOpenness.isOpen(
                world, path.get(last).pos(), TraversalPolicy.ESCAPE_DRY_OPEN)) {
            return "terminal_dead_end";
        }
        return null;
    }

    private static boolean hostileOccupiesNextStep(ActionPack pack, BlockPos next) {
        for (LivingEntity entity : pack.player().serverLevel().getEntitiesOfClass(
                LivingEntity.class,
                pack.player().getBoundingBox().inflate(8.0D),
                candidate -> candidate instanceof Monster && candidate.isAlive() && candidate != pack.player())) {
            if (!ObservableWorldQuery.canObserveEntity(pack.player(), entity)
                    || !CombatCore.hasLineOfSight(pack.player(), entity)) {
                continue;
            }
            Vec3 toNext = Vec3.atCenterOf(next).subtract(pack.player().position());
            Vec3 toEntity = entity.position().subtract(pack.player().position());
            if (toNext.dot(toEntity) <= 0.0D) {
                continue; // A pursuer already behind the bot should not invalidate the forward route.
            }
            double exclusionRadius = entity instanceof Creeper ? 5.0D : 2.5D;
            if (entity.position().distanceTo(Vec3.atCenterOf(next)) <= exclusionRadius) {
                return true;
            }
        }
        return false;
    }

    static boolean supports(MoveType type) {
        return type == MoveType.WALK
                || type == MoveType.DIAGONAL
                || type == MoveType.JUMP_UP
                || type == MoveType.DROP_DOWN;
    }

    private static boolean arrivedAt(BlockPos current, BlockPos target) {
        int dx = current.getX() - target.getX();
        int dz = current.getZ() - target.getZ();
        return dx * dx + dz * dz <= 1 && Math.abs(current.getY() - target.getY()) <= 1;
    }

    private void cleanup(ActionPack pack) {
        walker = null;
        pack.stopMovement();
    }
}
