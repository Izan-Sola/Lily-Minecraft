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

public class LilyUtils {

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

    public static boolean isSafeBlock(ServerLevel level, double x, double y, double z, String direction, Vec3 look) {
        double nx = x, nz = z;
        switch (direction) {
            case "forward" -> { nx = x + look.x; nz = z + look.z; }
            case "back"    -> { nx = x - look.x; nz = z - look.z; }
            case "left"    -> { nx = x - look.z; nz = z + look.x; }
            case "right"   -> { nx = x + look.z; nz = z - look.x; }
        }
        BlockPos floor = BlockPos.containing(nx, y - 1, nz);
        BlockPos body  = BlockPos.containing(nx, y,     nz);
        if (level.getBlockState(floor).isAir()) return false;
        if (!level.getFluidState(floor).isEmpty()) return false;
        if (!level.getFluidState(body).isEmpty()) return false;
        return true;
    }

    public static void broadcast(JsonObject msg) {
        if (LilyBridge.wsServer == null) return;
        LilyBridge.wsServer.broadcast(LilyBridge.GSON.toJson(msg));
    }

    public static void broadcast(String type, String... keyValues) {
        if (LilyBridge.wsServer == null) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            msg.addProperty(keyValues[i], keyValues[i + 1]);
        }
        LilyBridge.wsServer.broadcast(LilyBridge.GSON.toJson(msg));
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
        LilyBridge.mcServer.execute(() -> {
            try { Thread.sleep(delayTicks * 50L); } catch (InterruptedException ignored) {}
            runCommand(command);
        });
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
}