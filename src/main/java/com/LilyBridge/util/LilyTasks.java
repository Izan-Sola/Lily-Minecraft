package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    // Anti-stuck: guarda la posición hace 3 s y cuenta ticks sin avance
    private static volatile double  lastStuckX           = 0;
    private static volatile double  lastStuckZ           = 0;
    private static volatile int     stuckTicks           = 0;
    private static final    int     STUCK_THRESHOLD      = 3; // iteraciones del target-task (~3 s)

    // ---------- Tareas Bukkit ----------
    private static BukkitTask movementJumpTask   = null;
    private static BukkitTask movementSafetyTask = null;
    private static BukkitTask movementTargetTask = null;

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
        targetMoveX            = null;
        targetMoveZ            = null;
        currentMoveDirection   = null;
        currentTargetDirection = null;
        isMoving               = false;
        stuckTicks             = 0;
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " stop");
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
    //   Solo para movimiento simple; el target-task gestiona su propia seguridad
    //   a través del pathfinder.
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

                    // Vector del paso siguiente según la dirección actual
                    double nx, nz;
                    switch (currentMoveDirection) {
                        case "forward" -> { nx = x + look.x; nz = z + look.z; }
                        case "back"    -> { nx = x - look.x; nz = z - look.z; }
                        case "left"    -> { nx = x - look.z; nz = z + look.x; }
                        case "right"   -> { nx = x + look.z; nz = z - look.x; }
                        default        -> { return; }
                    }

                    BlockPos nextFeet = BlockPos.containing(nx, y, nz);

                    // Comprobación rápida mediante cellType del pathfinder
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

                    // ── Llegamos ──────────────────────────────────────────────
                    if (distToGoal < 1.0) {
                        LilyBridge.LOGGER.info("[TARGET] Objetivo alcanzado ({}, {})", targetMoveX, targetMoveZ);
                        stopAllMovement();
                        return;
                    }

                    // ── Anti-stuck ────────────────────────────────────────────
                    double movedSinceLastCheck = Math.hypot(lily.getX() - lastStuckX, lily.getZ() - lastStuckZ);
                    if (movedSinceLastCheck < 0.5) {
                        stuckTicks++;
                    } else {
                        stuckTicks = 0;
                    }
                    lastStuckX = lily.getX();
                    lastStuckZ = lily.getZ();

                    if (stuckTicks >= STUCK_THRESHOLD) {
                        // Forzar salto y continuar; el BFS elegirá otra ruta la próxima iteración
                        LilyBridge.LOGGER.warn("[STUCK] Sin movimiento {} iteraciones, forzando salto", stuckTicks);
                        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " jump once");
                        stuckTicks = 0;
                        // Opcionalmente, invalidar la dirección actual para que el BFS la recalcule
                        currentTargetDirection = null;
                    }

                    // ── Pathfinding ───────────────────────────────────────────
                    String newDir = LilyPathfinder.getBestDirection(lily, targetMoveX, targetMoveZ);

                    if ("stop".equals(newDir)) {
                        stopAllMovement();
                        return;
                    }

                    if (!newDir.equals(currentTargetDirection)) {
                        // FIX: SLF4J solo entiende marcadores "{}", no formatos tipo "{:.1f}" —
                        // eso imprimía el texto literal en vez del valor. Se formatea antes.
                        LilyBridge.LOGGER.info("[TARGET] Nueva dirección: {} (dist={})",
                                newDir, String.format("%.1f", distToGoal));
                        currentTargetDirection = newDir;
                        currentMoveDirection   = newDir;
                        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " move " + newDir);
                    }

                    // Asegura que jump y safety estén activas
                    isMoving = true;
                    if (movementJumpTask == null || movementJumpTask.isCancelled())
                        startMovementJumpTask();
                    // Nota: NO arrancamos movementSafetyTask aquí porque el pathfinder
                    // ya garantiza que el paso elegido es seguro.
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

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Decide si Lily debe saltar: hay algún bloque sólido a la altura de los pies
     * en los 3×3 bloques delante de ella (filtra los que están detrás según la dirección).
     */
    private static boolean shouldJump(ServerPlayer lily) {
        ServerLevel level  = (ServerLevel) lily.level();
        BlockPos    center = lily.blockPosition();

        Vec3 look = lily.getLookAngle();
        // Solo comprueba en semicírculo frontal para no saltar hacia atrás
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                // ¿Este bloque está "adelante" (producto escalar positivo)?
                double dot = dx * look.x + dz * look.z;
                if (dot <= 0) continue;

                BlockPos pos = center.offset(dx, 0, dz);
                if (level.getBlockState(pos).isSolid()) {
                    // Solo saltar si hay espacio encima (no techos)
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