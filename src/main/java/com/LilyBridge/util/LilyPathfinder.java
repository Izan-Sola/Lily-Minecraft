package com.LilyBridge.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class LilyPathfinder {

    private static final String[] DIRS = {"forward", "back", "left", "right"};

    public static String getBestDirection(ServerPlayer lily, String currentDir) {
        ServerLevel level = (ServerLevel) lily.level();

        double yaw = Math.toRadians(lily.getYRot());

        double fx = -Math.sin(yaw);
        double fz =  Math.cos(yaw);

        double rx =  Math.cos(yaw);
        double rz =  Math.sin(yaw);

        BlockPos base = lily.blockPosition();

        String bestDir = null;
        double bestScore = -999;

        for (String dir : DIRS) {

            double dx = 0, dz = 0;

            switch (dir) {
                case "forward" -> { dx = fx; dz = fz; }
                case "back"    -> { dx = -fx; dz = -fz; }
                case "left"    -> { dx = -rx; dz = -rz; }
                case "right"   -> { dx = rx; dz = rz; }
            }

            double score = evaluate(level, base, dx, dz);

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        return bestDir;
    }

    private static double evaluate(ServerLevel level, BlockPos base, double dx, double dz) {

        double x = base.getX() + dx;
        double z = base.getZ() + dz;
        int y = base.getY();

        BlockPos feet = new BlockPos((int) x, y, (int) z);
        BlockPos head = feet.above();
        BlockPos below = feet.below();

        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        BlockState belowState = level.getBlockState(below);

        double score = 0;

        // ❌ Blocked path (wall)
        if (!feetState.isAir()) score -= 10;

        // ❌ No space to walk
        if (!headState.isAir()) score -= 10;

        // ❌ Lava / water = huge penalty
        if (!level.getFluidState(feet).isEmpty()) score -= 100;

        // ❌ Cliff detection (no ground below)
        if (belowState.isAir()) score -= 50;

        // ✔ Prefer open space
        if (feetState.isAir() && headState.isAir()) score += 5;

        return score;
    }
}