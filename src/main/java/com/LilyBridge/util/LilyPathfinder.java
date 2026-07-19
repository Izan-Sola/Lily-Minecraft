package com.LilyBridge.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Pathfinder para LilyBridge.
 *
 * Usa un BFS con límite de nodos para encontrar la primera dirección (forward/back/left/right)
 * que lleva al objetivo sin caer en agua, lava ni agujeros, y sorteando paredes bajas
 * mediante salto.  Si el BFS falla (camino completamente bloqueado), devuelve la dirección
 * greedy de emergencia más segura.
 *
 * FIX (2026-07): el BFS original ignoraba por completo el eje Y — cada nodo reutilizaba
 * la altura de partida, así que en cuanto el camino subía o bajaba un bloque, las
 * comprobaciones de "pared" / "techo" se hacían a la altura equivocada. Ahora cada paso
 * calcula y devuelve la posición de pies resultante (subiendo al saltar una pared de 1
 * bloque, o bajando al detectar una caída segura de 1-3 bloques), y el BFS avanza sobre
 * esa posición real en vez de una copia plana en XZ.
 */
public class LilyPathfinder {

    // Cuántos nodos explora el BFS antes de rendirse
    private static final int BFS_LIMIT = 200;
    // Radio horizontal de la exploración (bloques)
    private static final int BFS_RADIUS = 12;
    // Caída máxima considerada seguro (bloques)
    private static final int MAX_SAFE_FALL = 3;

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
        return resolveStep(level, from, worldDX, worldDZ) != null;
    }

    // -----------------------------------------------------------------------
    // BFS
    // -----------------------------------------------------------------------

    private static String bfs(ServerLevel level, BlockPos start,
                              double targetX, double targetZ,
                              int[][] worldDeltas, String[] dirNames) {

        // Estado: (posición de pies REAL tras el paso, primeraDir usada, pasos dados)
        record Node(BlockPos pos, int firstDirIdx, int steps) {}

        Queue<Node> queue   = new ArrayDeque<>();
        Set<Long>   visited = new HashSet<>();

        visited.add(packPos(start));

        // Añade un vecino al BFS si es alcanzable
        for (int i = 0; i < 4; i++) {
            StepResult step = resolveStep(level, start, worldDeltas[i][0], worldDeltas[i][1]);
            if (step == null) continue;

            if (visited.add(packPos(step.newFeet))) {
                queue.add(new Node(step.newFeet, i, 1));
            }
        }

        int explored = 0;
        while (!queue.isEmpty() && explored < BFS_LIMIT) {
            Node cur = queue.poll();
            explored++;

            // ¿Llegamos suficientemente cerca del objetivo? (comparación en XZ, como move_to)
            double distToGoal = Math.hypot(cur.pos.getX() - targetX, cur.pos.getZ() - targetZ);
            if (distToGoal < 1.5) {
                return dirNames[cur.firstDirIdx];
            }

            // Poda: si nos alejamos mucho del start no tiene sentido seguir
            int startDistX = Math.abs(cur.pos.getX() - start.getX());
            int startDistZ = Math.abs(cur.pos.getZ() - start.getZ());
            if (startDistX > BFS_RADIUS || startDistZ > BFS_RADIUS) continue;

            for (int[] delta : worldDeltas) {
                StepResult step = resolveStep(level, cur.pos, delta[0], delta[1]);
                if (step == null) continue;
                if (!visited.add(packPos(step.newFeet))) continue;

                queue.add(new Node(step.newFeet, cur.firstDirIdx, cur.steps + 1));
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
            StepResult step = resolveStep(level, start, worldDeltas[i][0], worldDeltas[i][1]);
            if (step == null) continue;

            // Alineación con el objetivo
            double dot   = worldDeltas[i][0] * normDX + worldDeltas[i][1] * normDZ;
            // Penalizar casillas que requieren salto
            double penalty = step.requiresJump ? 0.2 : 0.0;
            double score   = dot - penalty;

            if (score > bestScore) {
                bestScore = score;
                bestDir   = dirNames[i];
            }
        }

        return bestDir != null ? bestDir : "stop";
    }

    // -----------------------------------------------------------------------
    // Resolución de un paso (con altura real)
    // -----------------------------------------------------------------------

    /** Resultado de intentar dar un paso: dónde acaban los pies, y si hizo falta saltar. */
    private record StepResult(BlockPos newFeet, boolean requiresJump) {}

    /**
     * Calcula si se puede dar un paso desde `feet` en la dirección (dx, dz), y si es así,
     * dónde termina la posición de los pies — que puede ser un bloque más arriba (subida
     * de 1 bloque, con salto) o varios más abajo (caída segura de 1-3 bloques). Devuelve
     * null si el paso es peligroso o está bloqueado.
     */
    private static StepResult resolveStep(ServerLevel level, BlockPos feet, int dx, int dz) {
        BlockPos next     = feet.offset(dx, 0, dz);
        BlockPos nextHead = next.above();

        // Fluidos = peligro absoluto
        if (!level.getFluidState(next).isEmpty())     return null;
        if (!level.getFluidState(nextHead).isEmpty()) return null;

        boolean nextFeetSolid = level.getBlockState(next).isSolid();

        if (nextFeetSolid) {
            // Pared a la altura de los pies — ¿se puede subir de un salto?
            BlockPos landing     = next.above();
            BlockPos landingHead = landing.above();
            if (level.getBlockState(landing).isSolid() || level.getBlockState(landingHead).isSolid()) {
                return null; // pared demasiado alta / sin hueco para subir
            }
            return new StepResult(landing, true);
        }

        // No hay pared a la altura de los pies — comprueba techo a la altura actual
        if (level.getBlockState(nextHead).isSolid()) return null; // techo bajo

        // ¿Hay suelo justo debajo?
        if (level.getBlockState(next.below()).isSolid()) {
            return new StepResult(next, false); // suelo plano
        }

        // Caída — busca suelo dentro de una distancia segura
        for (int fall = 2; fall <= MAX_SAFE_FALL + 1; fall++) {
            BlockPos ground = next.below(fall);
            if (level.getBlockState(ground).isSolid()) {
                return new StepResult(ground.above(), false); // caída segura
            }
        }

        return null; // agujero demasiado profundo / sin fondo visible
    }

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    private static long packPos(BlockPos p) {
        // Paquetiza XZ en un long para el set de visitados (Y ignorada a propósito:
        // el mismo XZ no debería revisitarse aunque varíe la altura en este caso de uso)
        return ((long)(p.getX() + 32768) << 16) | (p.getZ() + 32768);
    }

    private static int roundDir(double v) {
        // Convierte un componente de dirección continuo a -1, 0 o 1
        if (v >  0.5) return  1;
        if (v < -0.5) return -1;
        return 0;
    }
}