package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

public class LilyTasks {

    // Movement state
    private static volatile boolean isMoving = false;
    private static volatile String currentMoveDirection = null;

    // Target‑following state
    private static volatile Double targetMoveX = null;
    private static volatile Double targetMoveZ = null;
    private static volatile String currentTargetDirection = null;

    // Task instances
    private static BukkitTask movementJumpTask = null;
    private static BukkitTask movementSafetyTask = null;
    private static BukkitTask movementTargetTask = null;

    // ---------- Public API for LilyCommandHandler ----------

    /** Start simple directional movement (forward/back/left/right/stop) */
    public static void startSimpleMove(String direction) {
        // Stop any ongoing target following
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

    /** Start moving toward a specific coordinate (x, z) – Java will recalc direction every second */
    public static void startMoveTo(double x, double z) {
        // Stop any simple movement
        stopMovementJumpTask();
        stopMovementSafetyTask();
        currentMoveDirection = null;

        targetMoveX = x;
        targetMoveZ = z;
        startMovementTargetTask();
    }

    /** Stop all movement and cancel all tasks */
    public static void stopAllMovement() {
        stopMovementJumpTask();
        stopMovementSafetyTask();
        stopMovementTargetTask();
        targetMoveX = null;
        targetMoveZ = null;
        currentMoveDirection = null;
        currentTargetDirection = null;
        isMoving = false;
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " stop");
    }

    // ---------- Internal Tasks ----------

    /** Jump task – runs every 2 ticks, jumps if needed */
    static void startMovementJumpTask() {
        if (movementJumpTask != null && !movementJumpTask.isCancelled()) return;
        isMoving = true;
        movementJumpTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    if (!isMoving) return;
                    ServerPlayer lily = LilyUtils.getLilyServerPlayer();
                    if (lily == null || !lily.onGround()) return;

                    Vec3 look = lily.getLookAngle();
                    double x = lily.getX();
                    double y = lily.getY();
                    double z = lily.getZ();

                    if (shouldJump(lily, currentMoveDirection)) {
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

    /** Safety task – runs every second, reverses direction if unsafe ahead */
    static void startMovementSafetyTask() {
        if (movementSafetyTask != null && !movementSafetyTask.isCancelled()) return;
        movementSafetyTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    if (!isMoving || currentMoveDirection == null) return;
                    ServerPlayer lily = LilyUtils.getLilyServerPlayer();
                    if (lily == null) return;

                    Vec3 look = lily.getLookAngle();
                    double x = lily.getX();
                    double y = lily.getY();
                    double z = lily.getZ();
                    double distance = 2.0;
                    double nx = x, nz = z;
                    switch (currentMoveDirection) {
                        case "forward" -> { nx = x + look.x * distance; nz = z + look.z * distance; }
                        case "back"    -> { nx = x - look.x * distance; nz = z - look.z * distance; }
                        case "left"    -> { nx = x - look.z * distance; nz = z + look.x * distance; }
                        case "right"   -> { nx = x + look.z * distance; nz = z - look.x * distance; }
                        default -> { return; }
                    }

                    var level = (ServerLevel) lily.level();
                    boolean safe = true;

                    // Check for fluids in a 13‑block column
                    for (int dy = -6; dy <= 6; dy++) {
                        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(nx, y + dy, nz);
                        if (!level.getFluidState(pos).isEmpty()) {
                            safe = false;
                            break;
                        }
                    }
                    // Check for solid ground within 6 blocks below
                    if (safe) {
                        boolean groundFound = false;
                        for (int dy = 1; dy <= 6; dy++) {
                            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(nx, y - dy, nz);
                            if (!level.getBlockState(pos).isAir()) {
                                groundFound = true;
                                break;
                            }
                        }
                        if (!groundFound) safe = false;
                    }

                    if (!safe) {
                        String newDir = oppositeDirection(currentMoveDirection);
                        LilyBridge.LOGGER.info("[SAFETY] Unsafe ahead, reversing {} -> {}", currentMoveDirection, newDir);
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

    /** Target‑following task – runs every second, updates direction to target */
    static void startMovementTargetTask() {
        if (movementTargetTask != null && !movementTargetTask.isCancelled()) return;
        movementTargetTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    if (targetMoveX == null || targetMoveZ == null) {
                        stopMovementTargetTask();
                        return;
                    }
                    ServerPlayer lily = LilyUtils.getLilyServerPlayer();
                    if (lily == null) return;

                    double dx = targetMoveX - lily.getX();
                    double dz = targetMoveZ - lily.getZ();
                    double dist = Math.hypot(dx, dz);

                    if (dist < 1.0) {
                        // Reached target
                        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " move stop");
                        stopMovementTargetTask();
                        targetMoveX = null;
                        targetMoveZ = null;
                        currentTargetDirection = null;
                        if (currentMoveDirection == null) {
                            stopMovementJumpTask();
                            stopMovementSafetyTask();
                        }
                        return;
                    }

                    // Compute relative direction based on current yaw
                    float yaw = lily.getYRot();
                    double forwardX = -Math.sin(Math.toRadians(yaw));
                    double forwardZ =  Math.cos(Math.toRadians(yaw));
                    double rightX   =  Math.cos(Math.toRadians(yaw));
                    double rightZ   =  Math.sin(Math.toRadians(yaw));

                    double len = Math.hypot(dx, dz);
                    double targetX = dx / len;
                    double targetZ = dz / len;

                    double dotForward = targetX * forwardX + targetZ * forwardZ;
                    double dotRight   = targetX * rightX   + targetZ * rightZ;

                    String newDir;
                    if (Math.abs(dotForward) >= Math.abs(dotRight)) {
                        newDir = dotForward >= 0 ? "forward" : "back";
                    } else {
                        newDir = dotRight >= 0 ? "right" : "left";
                    }

                    if (!newDir.equals(currentTargetDirection)) {
                        currentTargetDirection = newDir;
                        currentMoveDirection = newDir;
                        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " move " + newDir);

                        // Ensure jump and safety tasks are active
                        if (movementJumpTask == null || movementJumpTask.isCancelled())
                            startMovementJumpTask();
                        if (movementSafetyTask == null || movementSafetyTask.isCancelled())
                            startMovementSafetyTask();
                    }
                },
                0L, 20L
        );
    }

    static void stopMovementTargetTask() {
        if (movementTargetTask != null) {
            movementTargetTask.cancel();
            movementTargetTask = null;
        }
    }

    // ---------- Helpers ----------

    private static boolean shouldJump(ServerPlayer lily, String moveDir) {
        var level = (ServerLevel) lily.level();

        BlockPos center = lily.blockPosition();
        int y = center.getY();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);

                if (level.getBlockState(pos).isSolid()) {
                    return true;
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