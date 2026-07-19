package com.LilyBridge.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;

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
        return getBestDirection(lily, targetX, targetZ, null);
    }

    /**
     * Same as {@link #getBestDirection(ServerPlayer, double, double)}, but takes the
     * direction Lily is currently committed to (if any) and biases tie-breaking toward
     * keeping it. Without this, two equally-short routes around a wide obstacle (e.g.
     * a wall dead ahead with roughly symmetric space to either side) can flip which one
     * "wins" from one recompute to the next as her position shifts slightly, causing her
     * to zigzag left/right instead of committing to one side and getting around it.
     */
    public static String getBestDirection(ServerPlayer lily, double targetX, double targetZ, String preferredDir) {
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

        // Si ya hay una dirección "preferida" (la actual), la ponemos primera para que,
        // ante caminos de igual longitud, el BFS la encuentre y devuelva antes que las
        // alternativas — sesga el desempate hacia mantener el rumbo.
        if (preferredDir != null) {
            for (int i = 1; i < dirNames.length; i++) {
                if (dirNames[i].equals(preferredDir)) {
                    String tmpName = dirNames[0]; dirNames[0] = dirNames[i]; dirNames[i] = tmpName;
                    int[] tmpDelta = worldDeltas[0]; worldDeltas[0] = worldDeltas[i]; worldDeltas[i] = tmpDelta;
                    break;
                }
            }
        }

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

        // Lava es el único fluido que tratamos como peligro absoluto — el agua es
        // perfectamente transitable (caminar por el fondo o nadar), no hace falta evitarla.
        if (level.getFluidState(next).is(FluidTags.LAVA))     return null;
        if (level.getFluidState(nextHead).is(FluidTags.LAVA)) return null;

        boolean nextIsWater = !level.getFluidState(next).isEmpty();

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

        // Agua: el fluido la sostiene, no hace falta suelo sólido ni comprobar caída.
        if (nextIsWater) {
            return new StepResult(next, false);
        }

        // ¿Hay suelo justo debajo?
        if (level.getBlockState(next.below()).isSolid()) {
            return new StepResult(next, false); // suelo plano
        }

        // Caída — busca suelo (o agua) dentro de una distancia segura
        for (int fall = 2; fall <= MAX_SAFE_FALL + 1; fall++) {
            BlockPos ground = next.below(fall);
            if (level.getBlockState(ground).isSolid()) {
                return new StepResult(ground.above(), false); // caída segura sobre suelo
            }
            if (!level.getFluidState(ground).isEmpty() && !level.getFluidState(ground).is(FluidTags.LAVA)) {
                return new StepResult(ground.above(), false); // caída segura sobre agua
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