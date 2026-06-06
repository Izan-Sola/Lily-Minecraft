package com.LilyBridge.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.*;

/**
 * Pathfinder para LilyBridge.
 *
 * Usa un BFS con límite de nodos para encontrar la primera dirección (forward/back/left/right)
 * que lleva al objetivo sin caer en agua, lava ni agujeros, y sorteando paredes bajas
 * mediante salto.  Si el BFS falla (camino completamente bloqueado), devuelve la dirección
 * greedy de emergencia más segura.
 */
public class LilyPathfinder {

    // Cuántos bloques hacia adelante explora el BFS antes de rendirse
    private static final int BFS_LIMIT = 200;
    // Radio horizontal de la exploración (bloques)
    private static final int BFS_RADIUS = 12;

    // -----------------------------------------------------------------------
    // API pública
    // -----------------------------------------------------------------------

    /**
     * Devuelve la mejor dirección (forward/back/left/right) para llegar a (targetX, targetZ).
     * Retorna "stop" si ya está suficientemente cerca.
     */
    public static String getBestDirection(ServerPlayer lily, double targetX, double targetZ) {
        ServerLevel level = (ServerLevel) lily.level();
        BlockPos start    = lily.blockPosition();

        double dx = targetX - lily.getX();
        double dz = targetZ - lily.getZ();
        if (Math.hypot(dx, dz) < 1.0) return "stop";

        // Convierte el yaw a vectores world-space para las 4 direcciones relativas
        float  yaw   = lily.getYRot();
        double sinY  = Math.sin(Math.toRadians(yaw));
        double cosY  = Math.cos(Math.toRadians(yaw));

        // forward = (-sinY, cosY), right = (cosY, sinY) en XZ
        int[][] worldDeltas = {
                { roundDir(-sinY),  roundDir( cosY) }, // forward
                { roundDir( sinY),  roundDir(-cosY) }, // back
                { roundDir(-cosY),  roundDir(-sinY) }, // left
                { roundDir( cosY),  roundDir( sinY) }, // right
        };
        String[] dirNames = { "forward", "back", "left", "right" };

        // BFS desde la posición actual
        String bfsResult = bfs(level, start, targetX, targetZ, worldDeltas, dirNames);
        if (bfsResult != null) return bfsResult;

        // Fallback greedy: dirección válida más alineada con el objetivo
        return greedyFallback(level, start, dx, dz, worldDeltas, dirNames);
    }

    /**
     * Comprueba si una celda específica (un paso en cierta dirección) es transitable.
     * Se usa desde LilyTasks para el safety-check.
     */
    public static boolean isSafeStep(ServerLevel level, BlockPos from, int worldDX, int worldDZ) {
        return cellType(level, from.offset(worldDX, 0, worldDZ)) != CellType.DANGER;
    }

    // -----------------------------------------------------------------------
    // BFS
    // -----------------------------------------------------------------------

    private static String bfs(ServerLevel level, BlockPos start,
                              double targetX, double targetZ,
                              int[][] worldDeltas, String[] dirNames) {

        // Estado: (blockPos, primeraDir usada, pasos dados)
        record Node(BlockPos pos, int firstDirIdx, int steps) {}

        Queue<Node> queue   = new ArrayDeque<>();
        Set<Long>   visited = new HashSet<>();

        visited.add(packPos(start));

        // Añade un vecino al BFS si es alcanzable
        for (int i = 0; i < 4; i++) {
            BlockPos next = start.offset(worldDeltas[i][0], 0, worldDeltas[i][1]);
            CellType ct   = cellType(level, next);
            if (ct == CellType.DANGER) continue;

            // Si hay pared de 1 bloque (JUMPABLE), se puede pasar con salto
            BlockPos above = next.above();
            if (ct == CellType.JUMPABLE && cellType(level, above) == CellType.DANGER) continue;

            if (visited.add(packPos(next))) {
                queue.add(new Node(next, i, 1));
            }
        }

        int explored = 0;
        while (!queue.isEmpty() && explored < BFS_LIMIT) {
            Node cur = queue.poll();
            explored++;

            // ¿Llegamos suficientemente cerca del objetivo?
            double distToGoal = Math.hypot(cur.pos.getX() - targetX, cur.pos.getZ() - targetZ);
            if (distToGoal < 1.5) {
                return dirNames[cur.firstDirIdx];
            }

            // Poda: si nos alejamos mucho del start no tiene sentido seguir
            int startDistX = Math.abs(cur.pos.getX() - start.getX());
            int startDistZ = Math.abs(cur.pos.getZ() - start.getZ());
            if (startDistX > BFS_RADIUS || startDistZ > BFS_RADIUS) continue;

            for (int[] delta : worldDeltas) {
                BlockPos next = cur.pos.offset(delta[0], 0, delta[1]);
                if (!visited.add(packPos(next))) continue;

                CellType ct = cellType(level, next);
                if (ct == CellType.DANGER) continue;
                if (ct == CellType.JUMPABLE) {
                    // Solo transitable si hay espacio encima
                    if (cellType(level, next.above()) == CellType.DANGER) continue;
                }

                queue.add(new Node(next, cur.firstDirIdx, cur.steps + 1));
            }
        }

        return null; // BFS no encontró camino
    }

    // -----------------------------------------------------------------------
    // Greedy fallback
    // -----------------------------------------------------------------------

    private static String greedyFallback(ServerLevel level, BlockPos start,
                                         double dx, double dz,
                                         int[][] worldDeltas, String[] dirNames) {
        double len      = Math.hypot(dx, dz);
        double normDX   = dx / len;
        double normDZ   = dz / len;

        String bestDir   = null;
        double bestScore = -9999;

        for (int i = 0; i < 4; i++) {
            BlockPos next = start.offset(worldDeltas[i][0], 0, worldDeltas[i][1]);
            CellType ct   = cellType(level, next);
            if (ct == CellType.DANGER) continue;

            // Alineación con el objetivo
            double dot   = worldDeltas[i][0] * normDX + worldDeltas[i][1] * normDZ;
            // Penalizar casillas que requieren salto
            double penalty = (ct == CellType.JUMPABLE) ? 0.2 : 0.0;
            double score   = dot - penalty;

            if (score > bestScore) {
                bestScore = score;
                bestDir   = dirNames[i];
            }
        }

        return bestDir != null ? bestDir : "stop";
    }

    // -----------------------------------------------------------------------
    // Clasificación de celdas
    // -----------------------------------------------------------------------

    private enum CellType {
        OPEN,      // transitable sin salto
        JUMPABLE,  // hay un bloque a la altura de los pies pero la cabeza está libre → salto
        DANGER     // agua, lava, vacío profundo, techo bajo, etc.
    }

    /**
     * Clasifica la celda (blockPos = posición de los pies del jugador).
     * Asume que el jugador ocupa feet y feet+1 (cabeza).
     */
    private static CellType cellType(ServerLevel level, BlockPos feet) {
        BlockPos head  = feet.above();
        BlockPos below = feet.below();

        // Fluidos = peligro absoluto
        if (!level.getFluidState(feet).isEmpty()) return CellType.DANGER;
        if (!level.getFluidState(head).isEmpty()) return CellType.DANGER;

        // Agujero profundo: busca suelo en los 4 bloques inferiores
        if (level.getBlockState(below).isAir()) {
            boolean groundFound = false;
            for (int dy = 2; dy <= 4; dy++) {
                if (!level.getBlockState(feet.below(dy)).isAir()) {
                    groundFound = true;
                    break;
                }
            }
            if (!groundFound) return CellType.DANGER;
            // Caída de 1-3 bloques: válida pero puntúa peor → tratada como OPEN
            // (el jugador puede caer; no bloqueante)
        }

        // Pared a la altura de los pies (block sólido)
        BlockState feetState = level.getBlockState(feet);
        if (feetState.isSolid()) {
            // ¿Hay espacio encima para saltar?
            if (!level.getBlockState(head).isSolid()
                    && !level.getBlockState(head.above()).isSolid()) {
                return CellType.JUMPABLE;
            }
            return CellType.DANGER; // pared sin espacio para saltar
        }

        // Techo bajo (head sólido)
        if (level.getBlockState(head).isSolid()) return CellType.DANGER;

        return CellType.OPEN;
    }

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    private static long packPos(BlockPos p) {
        // Paquetiza XZ en un long para el set de visitados (Y ignorada)
        return ((long)(p.getX() + 32768) << 16) | (p.getZ() + 32768);
    }

    private static int roundDir(double v) {
        // Convierte un componente de dirección continuo a -1, 0 o 1
        if (v >  0.5) return  1;
        if (v < -0.5) return -1;
        return 0;
    }
}