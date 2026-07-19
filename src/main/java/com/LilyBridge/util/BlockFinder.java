package com.LilyBridge.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Finds the closest block of a given type near a player, for "mine some
 * stone" style requests where the LLM has no coordinates — only a block
 * name. Complements the coordinate-based mining path (Blocks of Interest),
 * which stays exact-position-only.
 */
public class BlockFinder {

    private static final int DEFAULT_RADIUS = 16;

    private static final int VERTICAL_RADIUS = 1;

    /**
     * @param player     the player (Lily) searching around herself
     * @param blockName  raw block name from the tool call, e.g. "stone" or
     *                   "minecraft:oak_log". A bare name is assumed to be
     *                   in the "minecraft" namespace.
     * @param radius     horizontal search radius in blocks; falls back to
     *                   DEFAULT_RADIUS if null or <= 0.
     * @return the closest matching BlockPos, or null if none found or the
     *         block name doesn't resolve to a real block.
     */
    public static BlockPos findClosestBlock(ServerPlayer player, String blockName, Integer radius) {
        Block target = resolveBlock(blockName);
        if (target == null) return null;

        int r = (radius == null || radius <= 0) ? DEFAULT_RADIUS : radius;
        ServerLevel level = (ServerLevel) player.level();
        BlockPos origin = player.blockPosition();

        BlockPos closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() != target) continue;

                    double distSq = pos.distSqr(origin);
                    if (distSq < closestDistSq) {
                        closestDistSq = distSq;
                        closest = pos;
                    }
                }
            }
        }

        return closest;
    }

    private static Block resolveBlock(String rawName) {
        if (rawName == null || rawName.isBlank()) return null;
        String normalized = rawName.trim().toLowerCase();
        ResourceLocation id = normalized.contains(":")
                ? ResourceLocation.tryParse(normalized)
                : ResourceLocation.tryBuild("minecraft", normalized);
        if (id == null) return null;

        // NeoForge 1.20.x+: BuiltInRegistries.BLOCK. If you're on an older
        // mapping that still uses ForgeRegistries.BLOCKS, swap this line —
        // everything else in this class is unaffected.
        if (!BuiltInRegistries.BLOCK.containsKey(id)) return null;
        return BuiltInRegistries.BLOCK.get(id);
    }
}