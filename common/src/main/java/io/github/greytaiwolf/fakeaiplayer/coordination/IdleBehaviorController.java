package io.github.greytaiwolf.fakeaiplayer.coordination;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.DangerCheck;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.LocalOpenness;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Low-priority, action-only behaviour used only while no Task, Goal, Brain or player lock exists. */
public final class IdleBehaviorController {
    public static final IdleBehaviorController INSTANCE = new IdleBehaviorController();

    static final int WARMUP_TICKS = 40;
    static final int ANCHOR_RADIUS = 10;
    static final int VERTICAL_RADIUS = 3;
    static final int MAX_CANDIDATES = 12;
    static final int NO_CANDIDATE_COOLDOWN = 60;
    static final int MOVE_TIMEOUT = 240;
    static final int NO_PROGRESS_TIMEOUT = 70;
    private static final double LOOK_RANGE_SQUARED = 12.0D * 12.0D;
    private static final int[] Y_OFFSETS = {0, 1, -1, 2, -2, 3, -3};

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> episodes = new ConcurrentHashMap<>();

    private IdleBehaviorController() {
    }

    public boolean tick(AIPlayerEntity bot) {
        int now = bot.getServer().getTickCount();
        Session session = sessions.computeIfAbsent(bot.getUUID(), ignored -> newSession(bot, now));

        if (bot.getActionPack().isSuspended() || bot.isInWater()
                || horizontalDistanceSquared(bot.blockPosition(), session.anchor) > (ANCHOR_RADIUS + 1) * (ANCHOR_RADIUS + 1)) {
            cancel(bot, "ambient_safety_boundary");
            return false;
        }

        if (session.state != State.MOVING) {
            tickLook(bot, session, now);
        }

        return switch (session.state) {
            case WARMUP -> {
                if (now - session.stateSince >= WARMUP_TICKS) {
                    beginSearch(session, now);
                    chooseAndStartRoute(bot, session, now);
                }
                yield true;
            }
            case SEARCHING -> {
                chooseAndStartRoute(bot, session, now);
                yield true;
            }
            case MOVING -> {
                tickMovement(bot, session, now);
                yield true;
            }
            case WAITING, COOLDOWN -> {
                if (now >= session.nextTransitionTick) {
                    beginSearch(session, now);
                    chooseAndStartRoute(bot, session, now);
                }
                yield true;
            }
        };
    }

    public boolean ownsActiveAction(AIPlayerEntity bot) {
        Session session = sessions.get(bot.getUUID());
        return session != null && session.state == State.MOVING && session.ownsMovement;
    }

    public boolean cancel(AIPlayerEntity bot, String reason) {
        Session session = sessions.remove(bot.getUUID());
        if (session == null) {
            return false;
        }
        if (session.ownsMovement) {
            bot.getActionPack().stopAll();
        }
        BotLog.action(bot, "ambient_cancelled", "reason", reason, "state", session.state);
        return true;
    }

    public void clear(AIPlayerEntity bot) {
        cancel(bot, "runtime_clear");
        episodes.remove(bot.getUUID());
    }

    public void clearAll() {
        sessions.clear();
        episodes.clear();
    }

    private Session newSession(AIPlayerEntity bot, int now) {
        long episode = episodes.merge(bot.getUUID(), 1L, Long::sum);
        UUID id = bot.getUUID();
        long seed = id.getMostSignificantBits()
                ^ Long.rotateLeft(id.getLeastSignificantBits(), 19)
                ^ episode * 0x9E3779B97F4A7C15L;
        return new Session(bot.blockPosition().immutable(), now, RandomSource.create(seed));
    }

    private void chooseAndStartRoute(AIPlayerEntity bot, Session session, int now) {
        ServerLevel world = bot.serverLevel();
        int attempt = session.candidateAttempts++;
        int radius = 4 + session.random.nextInt(7);
        double angle = session.random.nextDouble() * Math.PI * 2.0D;
        int dx = Mth.floor(Math.cos(angle) * radius);
        int dz = Mth.floor(Math.sin(angle) * radius);
        BlockPos candidate = dryCandidateInColumn(
                world, session.anchor.offset(dx, 0, dz), session.anchor);
        if (candidate != null
                && LocalOpenness.isOpen(world, candidate, TraversalPolicy.AMBIENT_DRY_OPEN)) {
            ActionResult result = bot.getActionPack().startAmbientPathTo(candidate, session.anchor);
            if (!result.isFailed()) {
                session.state = State.MOVING;
                session.stateSince = now;
                session.target = candidate;
                session.ownsMovement = true;
                session.candidateAttempts = 0;
                session.lastPosition = bot.position();
                session.lastProgressTick = now;
                BotLog.action(bot, "ambient_wander_started", "anchor", session.anchor.toShortString(),
                        "target", candidate.toShortString(), "attempt", attempt + 1);
                return;
            }
        }
        if (session.candidateAttempts >= MAX_CANDIDATES) {
            session.candidateAttempts = 0;
            enterCooldown(session, now, NO_CANDIDATE_COOLDOWN);
        } else {
            session.nextTransitionTick = now + 1;
        }
    }

    private static void beginSearch(Session session, int now) {
        session.state = State.SEARCHING;
        session.stateSince = now;
        session.candidateAttempts = 0;
        session.nextTransitionTick = now;
    }

    private void tickMovement(AIPlayerEntity bot, Session session, int now) {
        double movedSquared = bot.position().distanceToSqr(session.lastPosition);
        if (movedSquared >= 0.04D) {
            session.lastPosition = bot.position();
            session.lastProgressTick = now;
        }

        if (now - session.stateSince > MOVE_TIMEOUT || now - session.lastProgressTick > NO_PROGRESS_TIMEOUT) {
            stopOwnedMovement(bot, session);
            enterCooldown(session, now, NO_CANDIDATE_COOLDOWN);
            return;
        }

        if (!bot.getActionPack().isPathExecutorIdle()) {
            return;
        }

        boolean arrived = session.target != null && bot.blockPosition().closerThan(session.target, 2.25D);
        session.ownsMovement = false;
        session.target = null;
        if (arrived) {
            session.state = State.WAITING;
            session.stateSince = now;
            session.nextTransitionTick = now + 40 + session.random.nextInt(61);
        } else {
            enterCooldown(session, now, NO_CANDIDATE_COOLDOWN);
        }
    }

    private static void stopOwnedMovement(AIPlayerEntity bot, Session session) {
        if (session.ownsMovement) {
            bot.getActionPack().stopAll();
        }
        session.ownsMovement = false;
        session.target = null;
    }

    private static void enterCooldown(Session session, int now, int ticks) {
        session.state = State.COOLDOWN;
        session.stateSince = now;
        session.nextTransitionTick = now + ticks;
        session.ownsMovement = false;
        session.target = null;
    }

    private void tickLook(AIPlayerEntity bot, Session session, int now) {
        if (session.lookTargetPlayer != null) {
            ServerPlayer target = bot.getServer().getPlayerList().getPlayer(session.lookTargetPlayer);
            if (!validLookTarget(bot, target)) {
                session.lookTargetPlayer = null;
            }
        }
        if (now >= session.lookUntil || (session.lookTargetPlayer == null && session.lookPoint == null)) {
            ServerPlayer player = chooseLookPlayer(bot);
            session.lookTargetPlayer = player == null ? null : player.getUUID();
            if (player == null) {
                double angle = session.random.nextDouble() * Math.PI * 2.0D;
                session.lookPoint = bot.getEyePosition().add(Math.cos(angle) * 8.0D, 0.0D, Math.sin(angle) * 8.0D);
            } else {
                session.lookPoint = null;
            }
            session.lookUntil = now + 20 + session.random.nextInt(41);
        }

        Vec3 targetPoint = session.lookPoint;
        if (session.lookTargetPlayer != null) {
            ServerPlayer target = bot.getServer().getPlayerList().getPlayer(session.lookTargetPlayer);
            if (validLookTarget(bot, target)) {
                targetPoint = target.getEyePosition();
            }
        }
        if (targetPoint != null) {
            smoothHeadToward(bot, targetPoint);
        }
    }

    private static ServerPlayer chooseLookPlayer(AIPlayerEntity bot) {
        UUID ownerId = AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
        if (ownerId != null) {
            ServerPlayer owner = bot.getServer().getPlayerList().getPlayer(ownerId);
            if (validLookTarget(bot, owner)) {
                return owner;
            }
        }
        return bot.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> validLookTarget(bot, player))
                .min(java.util.Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    private static boolean validLookTarget(AIPlayerEntity bot, ServerPlayer player) {
        return player != null
                && player != bot
                && !(player instanceof AIPlayerEntity)
                && !player.isSpectator()
                && player.isAlive()
                && player.serverLevel() == bot.serverLevel()
                && bot.distanceToSqr(player) <= LOOK_RANGE_SQUARED
                && bot.hasLineOfSight(player)
                && ObservableWorldQuery.canObserveEntity(bot, player);
    }

    private static void smoothHeadToward(AIPlayerEntity bot, Vec3 target) {
        Vec3 delta = target.subtract(bot.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontal < 1.0E-4D) {
            return;
        }
        float desiredYaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D));
        float desiredPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -75.0F, 75.0F);
        // ServerPlayer synchronisation pulls the head back toward the player yaw every tick.  A
        // head-only interpolation therefore never accumulates beyond one small step.  Ambient
        // LOOK only runs while the bot is standing still, so turn the stationary body with the
        // gaze and keep all three server-side yaw values coherent.
        float nextYaw = Mth.approachDegrees(bot.getYRot(), desiredYaw, 7.5F);
        bot.setYRot(nextYaw);
        bot.setYHeadRot(nextYaw);
        bot.setYBodyRot(Mth.approachDegrees(bot.yBodyRot, nextYaw, 7.5F));
        bot.setXRot(Mth.approachDegrees(bot.getXRot(), desiredPitch, 5.0F));
    }

    private static BlockPos dryCandidateInColumn(ServerLevel world, BlockPos column, BlockPos anchor) {
        for (int dy : Y_OFFSETS) {
            BlockPos candidate = new BlockPos(column.getX(), anchor.getY() + dy, column.getZ());
            if (isDryStandable(world, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private static boolean isDryStandable(ServerLevel world, BlockPos pos) {
        return Standability.isDryStandable(world, pos)
                && DangerCheck.scan(world, pos) == null;
    }

    private static int horizontalDistanceSquared(BlockPos first, BlockPos second) {
        int dx = first.getX() - second.getX();
        int dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private enum State {
        WARMUP,
        SEARCHING,
        MOVING,
        WAITING,
        COOLDOWN
    }

    private static final class Session {
        private final BlockPos anchor;
        private final RandomSource random;
        private State state = State.WARMUP;
        private int stateSince;
        private int nextTransitionTick;
        private BlockPos target;
        private boolean ownsMovement;
        private Vec3 lastPosition;
        private int lastProgressTick;
        private UUID lookTargetPlayer;
        private Vec3 lookPoint;
        private int lookUntil;
        private int candidateAttempts;

        private Session(BlockPos anchor, int now, RandomSource random) {
            this.anchor = anchor;
            this.stateSince = now;
            this.random = random;
            this.lastPosition = Vec3.atBottomCenterOf(anchor);
        }
    }
}
