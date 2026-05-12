package com.LilyBridge.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public class MovementHelper {

    public static boolean isSafeBlock(ServerLevel level, double x, double y, double z, String direction, Vec3 look) {

        double nx = x;
        double nz = z;

        switch (direction) {
            case "forward" -> {
                nx = x + look.x;
                nz = z + look.z;
            }
            case "back" -> {
                nx = x - look.x;
                nz = z - look.z;
            }
            case "left" -> {
                nx = x - look.z;
                nz = z + look.x;
            }
            case "right" -> {
                nx = x + look.z;
                nz = z - look.x;
            }
        }

        BlockPos floor = BlockPos.containing(nx, y - 1, nz);
        BlockPos body = BlockPos.containing(nx, y, nz);

        if (level.getBlockState(floor).isAir()) return false;
        if (!level.getFluidState(floor).isEmpty()) return false;
        if (!level.getFluidState(body).isEmpty()) return false;

        return true;
    }
}