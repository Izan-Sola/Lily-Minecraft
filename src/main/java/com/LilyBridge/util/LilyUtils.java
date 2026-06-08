package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LilyUtils {
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    /**
     * Short alias → full Minecraft registry ID.
     * Combo authors write e.g. "grass" or "cobble"; this resolves the real name.
     * Full IDs (containing ":") are passed through unchanged.
     */
    private static final java.util.Map<String, String> BLOCK_ALIASES = Map.of(
            "dirt",       "minecraft:dirt",
            "grass",      "minecraft:grass_block",
            "sand",       "minecraft:sand",
            "stone",      "minecraft:stone",
            "cobble",     "minecraft:cobblestone",
            "cobblestone","minecraft:cobblestone",
            "gravel",     "minecraft:gravel",
            "water",      "minecraft:water",
            "lava",       "minecraft:lava",
            "ice",        "minecraft:ice"
    );

    /** All blocks recognised as valid sources when no filter is specified. */
    private static final Set<String> DEFAULT_SOURCE_BLOCKS = Set.of(
            "minecraft:grass_block", "minecraft:sand", "minecraft:dirt",
            "minecraft:cobblestone", "minecraft:stone", "minecraft:gravel"
    );

    public static Player getLilyBukkit() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equals(LilyBridge.BOT_NAME)) return p;
        }
        return null;
    }

    public static ServerPlayer getLilyServerPlayer() {
        if (LilyBridge.mcServer == null) return null;
        for (ServerPlayer p : LilyBridge.mcServer.getPlayerList().getPlayers()) {
            if (p.getName().getString().equals(LilyBridge.BOT_NAME)) return p;
        }
        return null;
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

    public static boolean isSafeBlock(ServerLevel level, double x, double y, double z, String direction, Vec3 look) {
        double nx = x, nz = z;
        switch (direction) {
            case "forward" -> { nx = x + look.x; nz = z + look.z; }
            case "back"    -> { nx = x - look.x; nz = z - look.x; }
            case "left"    -> { nx = x - look.z; nz = z + look.x; }
            case "right"   -> { nx = x + look.z; nz = z - look.x; }
        }

        for (int dy = -6; dy <= 6; dy++) {
            BlockPos pos = BlockPos.containing(nx, y + dy, nz);
            if (!level.getFluidState(pos).isEmpty()) return false;
        }

        boolean groundFound = false;
        for (int dy = 1; dy <= 6; dy++) {
            BlockPos pos = BlockPos.containing(nx, y - dy, nz);
            if (!level.getBlockState(pos).isAir()) {
                groundFound = true;
                break;
            }
        }
        return groundFound;
    }

    public static void broadcast(JsonObject msg) {
        if (LilyBridge.wsClient == null) return;
        LilyBridge.wsClient.send(LilyBridge.GSON.toJson(msg));
    }

    public static void broadcast(String type, String... keyValues) {
        if (LilyBridge.wsClient == null) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            msg.addProperty(keyValues[i], keyValues[i + 1]);
        }
        LilyBridge.wsClient.send(LilyBridge.GSON.toJson(msg));
    }

    public static void runCommand(String command) {
        if (LilyBridge.mcServer == null) return;
        LilyBridge.mcServer.execute(() ->
                LilyBridge.mcServer.getCommands().performPrefixedCommand(
                        LilyBridge.mcServer.createCommandSourceStack().withMaximumPermission(4),
                        command
                )
        );
    }

    public static void runCommandAsLily(String command) {
        if (LilyBridge.mcServer == null) return;
        LilyBridge.mcServer.execute(() -> {
            for (ServerPlayer p : LilyBridge.mcServer.getPlayerList().getPlayers()) {
                if (!p.getName().getString().equals(LilyBridge.BOT_NAME)) continue;
                LilyBridge.mcServer.getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withMaximumPermission(4),
                        command
                );
                break;
            }
        });
    }

    public static void scheduleCommand(int delayTicks, String command) {
        if (LilyBridge.mcServer == null) return;
        long delayMs = delayTicks * 50L;
        SCHEDULER.schedule(() -> {
            LilyBridge.mcServer.execute(() -> runCommand(command));
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public static void scheduleSneakState(Player player, boolean sneaking) {
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    PlayerToggleSneakEvent event = new PlayerToggleSneakEvent(player, sneaking);
                    Bukkit.getPluginManager().callEvent(event);
                    if (!event.isCancelled()) player.setSneaking(sneaking);
                }
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SOURCE BLOCK SEARCH
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Finds the nearest valid source block in front of Lily.
     *
     * @param lily          The bot's ServerPlayer instance.
     * @param allowedBlocks Block IDs to accept (e.g. ["minecraft:water", "minecraft:ice"]).
     *                      Pass null or an empty collection to use the default set.
     */
    /** Default max forward distance when none is specified. */
    private static final int DEFAULT_SOURCE_DISTANCE = 8;

    public static BlockPos findSourceBlock(ServerPlayer lily, Collection<String> allowedBlocks, int maxDistance) {
        ServerLevel level = (ServerLevel) lily.level();
        BlockPos feet = lily.blockPosition();
        Vec3 look = lily.getLookAngle();

        double flatLen = Math.sqrt(look.x * look.x + look.z * look.z);
        if (flatLen < 1e-4) return null;

        double fx = look.x / flatLen;
        double fz = look.z / flatLen;

        final int scanDist = maxDistance > 0 ? maxDistance : DEFAULT_SOURCE_DISTANCE;

        // Determine which set to match against
        final Set<String> validBlocks;
        if (allowedBlocks == null || allowedBlocks.isEmpty()) {
            validBlocks = DEFAULT_SOURCE_BLOCKS;
        } else {
            Set<String> resolved = new java.util.HashSet<>();
            for (String b : allowedBlocks) {
                if (b.contains(":")) {
                    resolved.add(b);
                } else {
                    String mapped = BLOCK_ALIASES.get(b);
                    if (mapped != null) {
                        resolved.add(mapped);
                    } else {
                        LilyBridge.LOGGER.warn("[Source] Unknown block alias '{}' — skipped", b);
                    }
                }
            }
            validBlocks = resolved;
        }

        BlockPos best = null;
        double bestDist = 0;

        for (int forward = 1; forward <= scanDist; forward++) {
            for (int side = -1; side <= 1; side++) {

                double sx = -fz * side;
                double sz =  fx * side;

                int bx = (int) Math.floor(feet.getX() + fx * forward + sx);
                int bz = (int) Math.floor(feet.getZ() + fz * forward + sz);

                BlockPos pos = new BlockPos(bx, feet.getY() - 1, bz);

                String id = level.getBlockState(pos).getBlockHolder()
                        .unwrapKey().map(k -> k.location().toString()).orElse("");

                if (!validBlocks.contains(id)) continue;

                if (level.getBlockState(pos.above()).isSolid()) continue;

                double dist = feet.distSqr(pos);

                if (dist > bestDist) {
                    bestDist = dist;
                    best = pos;
                }
            }
        }

        return best;
    }

    /** Convenience overload — uses the default block set and default distance. */
    public static BlockPos findSourceBlock(ServerPlayer lily) {
        return findSourceBlock(lily, null, 0);
    }

    /** Convenience overload — uses the default distance. */
    public static BlockPos findSourceBlock(ServerPlayer lily, Collection<String> allowedBlocks) {
        return findSourceBlock(lily, allowedBlocks, 0);
    }
}