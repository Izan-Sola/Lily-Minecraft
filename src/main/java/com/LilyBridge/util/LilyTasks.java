package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.LilyBridge.util.LilyPathfinder;
import com.LilyBridge.util.LilyUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

public class LilyTasks {

    // ---------- Estado de movimiento simple (forward/back/left/right) ----------
    private static volatile boolean      isMoving            = false;
    private static volatile String       currentMoveDirection = null;

    // ---------- Estado de seguimiento de objetivo ----------
    private static volatile Double  targetMoveX          = null;
    private static volatile Double  targetMoveZ          = null;
    private static volatile String  currentTargetDirection = null;

    private static final long TARGET_TASK_INTERVAL_TICKS = 4L; // 0.2s

    private static volatile double  lastStuckX           = 0;
    private static volatile double  lastStuckZ           = 0;
    private static volatile int     stuckTicks           = 0;
    private static final    int     STUCK_THRESHOLD      = 15; // iteraciones del target-task (~3 s)

    // ---------- Tareas Bukkit ----------
    private static BukkitTask movementJumpTask   = null;
    private static BukkitTask movementSafetyTask = null;
    private static BukkitTask movementTargetTask = null;

    // ---------- Approach-and-use (boats, minecarts, beds) ----------
    private static BukkitTask approachTask = null;
    private static final long APPROACH_TASK_INTERVAL_TICKS = 10L; // 0.5s
    private static final double APPROACH_USE_DISTANCE = 2.0;

    // =========================================================================
    // API pública
    // =========================================================================

    /** Movimiento simple (forward/back/left/right/stop). */
    public static void startSimpleMove(String direction) {
        stopMovementTargetTask();
        targetMoveX = null;
        targetMoveZ = null;

        if ("stop".equals(direction)) {
            stopAllMovement();
            return;
        }

        currentMoveDirection = direction;
        isMoving = true;
        startMovementJumpTask();
        startMovementSafetyTask();
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " move " + direction);
    }

    /** Mueve hacia unas coordenadas mundo (X, Z). */
    public static void startMoveTo(double x, double z) {
        stopMovementJumpTask();
        stopMovementSafetyTask();
        currentMoveDirection   = null;
        currentTargetDirection = null;

        targetMoveX = x;
        targetMoveZ = z;
        stuckTicks  = 0;
        lastStuckX  = 0;
        lastStuckZ  = 0;

        startMovementTargetTask();
    }

    /** Para todo movimiento y cancela todas las tareas. */
    public static void stopAllMovement() {
        stopMovementJumpTask();
        stopMovementSafetyTask();
        stopMovementTargetTask();
        cancelApproachTask(); // a manual stop should also abort any in-progress approach
        targetMoveX            = null;
        targetMoveZ            = null;
        currentMoveDirection   = null;
        currentTargetDirection = null;
        isMoving               = false;
        stuckTicks             = 0;
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " stop");
    }

    // =========================================================================
    // Approach-and-use — walk to a target (moving entity or fixed block), face
    // it once close, then issue "use once". Used for boats/minecarts (via
    // EntityMountEvent) and beds (via CanPlayerSleepEvent).
    // =========================================================================

    /**
     * Approach a vehicle-like entity (boat/minecart) that may move slightly
     * while Lily walks toward it (current, being pushed, etc.), and "use" it
     * once close enough. Re-issues the move command only when the target has
     * drifted meaningfully, rather than restarting pathfinding every tick.
     */
    public static void approachAndUse(ServerPlayer lily, Entity target) {
        cancelApproachTask();
        final double[] lastCommanded = { Double.NaN, Double.NaN };

        approachTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    ServerPlayer l = LilyUtils.getLilyServerPlayer();
                    if (l == null || !target.isAlive() || l.isPassenger()) {
                        cancelApproachTask();
                        return;
                    }

                    double tx = target.getX();
                    double tz = target.getZ();
                    double dist = l.position().distanceTo(target.position());

                    if (dist <= APPROACH_USE_DISTANCE) {
                        cancelApproachTask();
                        stopAllMovement();
                        faceTarget(l, tx, target.getY(), tz);
                        Bukkit.getScheduler().runTaskLater(
                                Bukkit.getPluginManager().getPlugins()[0],
                                () -> LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " use once"),
                                4L
                        );
                        return;
                    }

                    if (Double.isNaN(lastCommanded[0]) || Math.hypot(tx - lastCommanded[0], tz - lastCommanded[1]) > 1.0) {
                        startMoveTo(tx, tz);
                        lastCommanded[0] = tx;
                        lastCommanded[1] = tz;
                    }
                },
                0L, APPROACH_TASK_INTERVAL_TICKS
        );
    }

    /**
     * Approach a fixed block position (a bed) and "use" it once close enough.
     */
    public static void approachAndUse(ServerPlayer lily, BlockPos target) {
        cancelApproachTask();
        final double tx = target.getX() + 0.5;
        final double ty = target.getY();
        final double tz = target.getZ() + 0.5;

        startMoveTo(tx, tz);

        approachTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    ServerPlayer l = LilyUtils.getLilyServerPlayer();
                    if (l == null) {
                        cancelApproachTask();
                        return;
                    }

                    double dist = l.position().distanceTo(new Vec3(tx, ty, tz));
                    if (dist <= APPROACH_USE_DISTANCE) {
                        cancelApproachTask();
                        stopAllMovement();
                        faceTarget(l, tx, ty, tz);
                        Bukkit.getScheduler().runTaskLater(
                                Bukkit.getPluginManager().getPlugins()[0],
                                () -> LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " use once"),
                                4L
                        );
                    }
                },
                0L, APPROACH_TASK_INTERVAL_TICKS
        );
    }

    private static void cancelApproachTask() {
        if (approachTask != null) {
            approachTask.cancel();
            approachTask = null;
        }
    }

    /**
     * Rotates Lily to face a world point by computing yaw/pitch and issuing
     * the fake-player "look" command.
     *
     * VERIFY THIS COMMAND SYNTAX against your fake-player mod before relying
     * on it — this is written assuming Carpet-style
     * `player <name> look <yaw> <pitch>`, matching the style of your other
     * commands (move/jump/use/hotbar all take this "player <name> <verb> ..."
     * shape). If your mod's actual "look" subcommand takes a different form
     * (e.g. "look at <x> <y> <z>"), this is the one line to change.
     */
    private static void faceTarget(ServerPlayer lily, double tx, double ty, double tz) {
        double dx = tx - lily.getX();
        double dy = ty - (lily.getY() + lily.getEyeHeight());
        double dz = tz - lily.getZ();

        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, horizDist));

        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " look "
                + String.format("%.1f", yaw) + " " + String.format("%.1f", pitch));
    }

    // =========================================================================
    // Tarea de salto — cada 10 ticks (0.5 s)
    // =========================================================================

    static void startMovementJumpTask() {
        if (movementJumpTask != null && !movementJumpTask.isCancelled()) return;
        isMoving       = true;
        movementJumpTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    if (!isMoving) return;
                    ServerPlayer lily = LilyUtils.getLilyServerPlayer();
                    if (lily == null || !lily.onGround()) return;
                    if (shouldJump(lily)) {
                        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " jump once");
                    }
                },
                0L, 10L
        );
    }

    static void stopMovementJumpTask() {
        isMoving = false;
        if (movementJumpTask != null) {
            movementJumpTask.cancel();
            movementJumpTask = null;
        }
    }

    // =========================================================================
    // Tarea de seguridad — cada 20 ticks (1 s)
    // =========================================================================

    static void startMovementSafetyTask() {
        if (movementSafetyTask != null && !movementSafetyTask.isCancelled()) return;
        movementSafetyTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    if (!isMoving || currentMoveDirection == null) return;
                    ServerPlayer lily = LilyUtils.getLilyServerPlayer();
                    if (lily == null) return;

                    ServerLevel level = (ServerLevel) lily.level();
                    Vec3 look  = lily.getLookAngle();
                    double x   = lily.getX();
                    double y   = lily.getY();
                    double z   = lily.getZ();

                    double nx, nz;
                    switch (currentMoveDirection) {
                        case "forward" -> { nx = x + look.x; nz = z + look.z; }
                        case "back"    -> { nx = x - look.x; nz = z - look.z; }
                        case "left"    -> { nx = x - look.z; nz = z + look.x; }
                        case "right"   -> { nx = x + look.z; nz = z - look.x; }
                        default        -> { return; }
                    }

                    BlockPos nextFeet = BlockPos.containing(nx, y, nz);

                    boolean safe = LilyPathfinder.isSafeStep(level, lily.blockPosition(),
                            nextFeet.getX() - lily.blockPosition().getX(),
                            nextFeet.getZ() - lily.blockPosition().getZ());

                    if (!safe) {
                        String newDir = oppositeDirection(currentMoveDirection);
                        LilyBridge.LOGGER.info("[SAFETY] Peligro al frente, invirtiendo {} -> {}", currentMoveDirection, newDir);
                        currentMoveDirection = newDir;
                        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " move " + newDir);
                    }
                },
                0L, 20L
        );
    }

    static void stopMovementSafetyTask() {
        if (movementSafetyTask != null) {
            movementSafetyTask.cancel();
            movementSafetyTask = null;
        }
    }

    // =========================================================================
    // Tarea de seguimiento de objetivo — cada 20 ticks (1 s)
    // =========================================================================

    static void startMovementTargetTask() {
        if (movementTargetTask != null && !movementTargetTask.isCancelled()) return;
        movementTargetTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    if (targetMoveX == null || targetMoveZ == null) {
                        stopAllMovement();
                        return;
                    }

                    ServerPlayer lily = LilyUtils.getLilyServerPlayer();
                    if (lily == null) return;

                    double distToGoal = Math.hypot(targetMoveX - lily.getX(), targetMoveZ - lily.getZ());

                    if (distToGoal < 1.0) {
                        LilyBridge.LOGGER.info("[TARGET] Objetivo alcanzado ({}, {})", targetMoveX, targetMoveZ);
                        stopAllMovement();
                        return;
                    }

                    double movedSinceLastCheck = Math.hypot(lily.getX() - lastStuckX, lily.getZ() - lastStuckZ);
                    if (movedSinceLastCheck < 0.5) {
                        stuckTicks++;
                    } else {
                        stuckTicks = 0;
                    }
                    lastStuckX = lily.getX();
                    lastStuckZ = lily.getZ();

                    if (stuckTicks >= STUCK_THRESHOLD) {
                        LilyBridge.LOGGER.warn("[STUCK] Sin movimiento {} iteraciones, forzando salto", stuckTicks);
                        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " jump once");
                        stuckTicks = 0;
                        currentTargetDirection = null;
                    }

                    String newDir = LilyPathfinder.getBestDirection(lily, targetMoveX, targetMoveZ, currentTargetDirection);

                    if ("stop".equals(newDir)) {
                        stopAllMovement();
                        return;
                    }

                    if (!newDir.equals(currentTargetDirection)) {
                        LilyBridge.LOGGER.info("[TARGET] Nueva dirección: {} (dist={})",
                                newDir, String.format("%.1f", distToGoal));
                        currentTargetDirection = newDir;
                        currentMoveDirection   = newDir;
                        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " move " + newDir);
                    }

                    isMoving = true;
                    if (movementJumpTask == null || movementJumpTask.isCancelled())
                        startMovementJumpTask();
                },
                0L, TARGET_TASK_INTERVAL_TICKS
        );
    }

    static void stopMovementTargetTask() {
        if (movementTargetTask != null) {
            movementTargetTask.cancel();
            movementTargetTask = null;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean shouldJump(ServerPlayer lily) {
        ServerLevel level  = (ServerLevel) lily.level();
        BlockPos    center = lily.blockPosition();

        Vec3 look = lily.getLookAngle();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                double dot = dx * look.x + dz * look.z;
                if (dot <= 0) continue;

                BlockPos pos = center.offset(dx, 0, dz);
                if (level.getBlockState(pos).isSolid()) {
                    if (!level.getBlockState(pos.above()).isSolid()) return true;
                }
            }
        }
        return false;
    }

    private static String oppositeDirection(String dir) {
        return switch (dir) {
            case "forward" -> "back";
            case "back"    -> "forward";
            case "left"    -> "right";
            case "right"   -> "left";
            default        -> "stop";
        };
    }
}