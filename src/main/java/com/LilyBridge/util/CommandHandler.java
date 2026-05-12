package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.LilyBridge.util.AbilityDataLoader;
import com.LilyBridge.util.BukkitHelper;
import com.LilyBridge.util.MovementHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerItemHeldEvent;

public class CommandHandler {

    public static void handleCommand(JsonObject cmd) {

        if (LilyBridge.getServer() == null) return;

        String type = cmd.get("type").getAsString();

        switch (type) {

            case "chat" -> {
                String msg = cmd.get("message").getAsString();

                for (ServerPlayer p : LilyBridge.getServer().getPlayerList().getPlayers()) {

                    if (!p.getName().getString().equals(LilyBridge.BOT_NAME)) continue;

                    LilyBridge.getServer().execute(() -> {
                        LilyBridge.getServer().getPlayerList().broadcastSystemMessage(
                                Component.literal("<" + p.getName().getString() + "> " + msg),
                                false
                        );
                    });

                    break;
                }
            }

            case "request_ability_data" -> {
                AbilityDataLoader.sendAbilityDataToNode();
            }

            case "run_command" -> {
                String command = cmd.get("command").getAsString();

                LilyBridge.runCommand(
                        "player " + LilyBridge.BOT_NAME + " run " + command
                );
            }

            case "move" -> {

                String direction = cmd.get("direction").getAsString();

                if (!direction.equals("stop")) {

                    ServerPlayer lily = null;

                    for (ServerPlayer p : LilyBridge.getServer().getPlayerList().getPlayers()) {
                        if (p.getName().getString().equals(LilyBridge.BOT_NAME)) {
                            lily = p;
                            break;
                        }
                    }

                    if (lily != null &&
                            !MovementHelper.isSafeBlock(
                                    (ServerLevel) lily.level(),
                                    lily.getX(),
                                    lily.getY(),
                                    lily.getZ(),
                                    direction,
                                    lily.getLookAngle()
                            )) {

                        LilyBridge.LOGGER.info(
                                "[MOVE] Unsafe block ahead, blocking move {}",
                                direction
                        );

                        return;
                    }
                }

                LilyBridge.runCommand(
                        "player " + LilyBridge.BOT_NAME + " move " + direction
                );
            }

            case "stop" -> {

                LilyBridge.runCommand(
                        "player " + LilyBridge.BOT_NAME + " stop"
                );

                Player lily = BukkitHelper.getLilyBukkit();

                if (lily != null && lily.isSneaking()) {
                    BukkitHelper.scheduleSneakState(lily, false);
                }
            }

            case "jump" -> LilyBridge.runCommand(
                    "player " + LilyBridge.BOT_NAME + " jump once"
            );

            case "sprint" -> {

                boolean sprinting =
                        cmd.has("value") && cmd.get("value").getAsBoolean();

                LilyBridge.runCommand(
                        "player " + LilyBridge.BOT_NAME +
                                (sprinting ? " sprint" : " unsprint")
                );
            }

            case "kill" -> LilyBridge.runCommand(
                    "player " + LilyBridge.BOT_NAME + " kill"
            );

            case "get_hostiles" -> {

                ServerPlayer lily = null;

                for (ServerPlayer p : LilyBridge.getServer().getPlayerList().getPlayers()) {
                    if (p.getName().getString().equals(LilyBridge.BOT_NAME)) {
                        lily = p;
                        break;
                    }
                }

                if (lily == null) return;

                ServerLevel level = (ServerLevel) lily.level();

                double scanRange =
                        cmd.has("range")
                                ? cmd.get("range").getAsDouble()
                                : 16.0;

                JsonArray hostiles = new JsonArray();

                for (Entity entity :
                        level.getEntities(
                                lily,
                                lily.getBoundingBox().inflate(scanRange)
                        )) {

                    if (!(entity instanceof Monster monster)) continue;
                    if (!monster.isAlive()) continue;

                    JsonObject h = new JsonObject();

                    h.addProperty("type", monster.getType().toShortString());
                    h.addProperty("id", monster.getId());
                    h.addProperty("x", monster.getX());
                    h.addProperty("y", monster.getY());
                    h.addProperty("z", monster.getZ());
                    h.addProperty("hp", monster.getHealth());

                    hostiles.add(h);
                }

                JsonObject res = new JsonObject();

                res.addProperty("type", "hostiles");
                res.add("hostiles", hostiles);

                LilyBridge.broadcast(res);
            }
        }
    }
}