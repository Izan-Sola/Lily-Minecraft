package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.LilyBridge.util.AbilityDataLoader;
import com.LilyBridge.util.LilyUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import static com.LilyBridge.util.LilyTasks.*;

public class LilyCommandHandler {


    static volatile boolean isMoving = false;
    private static volatile String currentMoveDirection = null;

    private static volatile Double targetMoveX = null;
    private static volatile Double targetMoveZ = null;
    private static volatile String currentTargetDirection = null;

    public static void handleCommand(JsonObject cmd) {
        if (LilyBridge.mcServer == null) return;
        String type = cmd.get("type").getAsString();

        switch (type) {
            case "chat" -> {
                String msg = cmd.get("message").getAsString();
                for (ServerPlayer p : LilyBridge.mcServer.getPlayerList().getPlayers()) {
                    if (!p.getName().getString().equals(LilyBridge.BOT_NAME)) continue;
                    LilyBridge.mcServer.execute(() -> {
                        LilyBridge.mcServer.getPlayerList().broadcastSystemMessage(
                                net.minecraft.network.chat.Component.literal("<" + p.getName().getString() + "> " + msg),
                                false
                        );
                    });
                    break;
                }
            }
            case "request_ability_data" -> AbilityDataLoader.sendAbilityDataToNode();
            case "run_command" -> LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " run " + cmd.get("command").getAsString());
            case "move_to" -> {
                double x = cmd.get("x").getAsDouble();
                double z = cmd.get("z").getAsDouble();

                // Stop any simple direction movement
                stopMovementJumpTask();
                stopMovementSafetyTask();
                currentMoveDirection = null;

                // Set target and start correction
                targetMoveX = x;
                targetMoveZ = z;
                startMovementTargetTask();
            }

            case "move" -> {
                String direction = cmd.get("direction").getAsString();
                if (!direction.equals("stop")) {

                    currentMoveDirection = direction;
                    stopMovementTargetTask();
                    startMovementJumpTask();
                    startMovementSafetyTask();
                } else {
                    stopMovementTargetTask();
                    stopMovementJumpTask();
                    stopMovementSafetyTask();
                    currentMoveDirection = null;
                }
                LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " move " + direction);
            }

            case "stop" -> {
                stopMovementJumpTask();
                stopMovementSafetyTask();
                stopMovementTargetTask();
                targetMoveX = null;
                targetMoveZ = null;
                currentMoveDirection = null;
                currentTargetDirection = null;
                LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " stop");
                Player lilyBukkit = LilyUtils.getLilyBukkit();
                if (lilyBukkit != null && lilyBukkit.isSneaking()) {
                    LilyUtils.scheduleSneakState(lilyBukkit, false);
                }
            }

            case "look_at" -> {
                double x = cmd.get("x").getAsDouble();
                double y = cmd.get("y").getAsDouble();
                double z = cmd.get("z").getAsDouble();
                LilyUtils.runCommand(String.format("player %s look at %.4f %.4f %.4f", LilyBridge.BOT_NAME, x, y, z));
            }

            case "attack" -> {
                String mode = cmd.has("mode") ? cmd.get("mode").getAsString() : "once";
                if (mode.equals("stop")) {
                    stopMovementJumpTask();
                    stopMovementSafetyTask();
                    currentMoveDirection = null;
                    LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " stop");
                    Player lilyBukkit = LilyUtils.getLilyBukkit();
                    if (lilyBukkit != null && lilyBukkit.isSneaking()) {
                        LilyUtils.scheduleSneakState(lilyBukkit, false);
                    }
                } else {
                    LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " attack " + mode);
                    Player lilyBukkit = LilyUtils.getLilyBukkit();
                    if (lilyBukkit != null) {
                        Bukkit.getPluginManager().callEvent(new PlayerAnimationEvent(lilyBukkit, PlayerAnimationType.ARM_SWING));
                    }
                }
            }

            case "use" -> {
                String mode = cmd.has("mode") ? cmd.get("mode").getAsString() : "once";
                LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " use " + mode);
                Player lilyBukkit = LilyUtils.getLilyBukkit();
                if (lilyBukkit != null) {
                    Bukkit.getPluginManager().callEvent(new PlayerAnimationEvent(lilyBukkit, PlayerAnimationType.ARM_SWING));
                }
            }

            case "jump" -> LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " jump once");
            case "sneak" -> {
                boolean sneaking = cmd.has("value") && cmd.get("value").getAsBoolean();
                Player lily = LilyUtils.getLilyBukkit();
                if (lily == null) return;
                LilyUtils.scheduleSneakState(lily, sneaking);
            }
            case "sprint" -> {
                boolean sprinting = cmd.has("value") && cmd.get("value").getAsBoolean();
                LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + (sprinting ? " sprint" : " unsprint"));
            }
            case "unsprint" -> {
                LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " unsprint");
            }
            case "hotbar" -> {
                int slot = cmd.get("slot").getAsInt();
                LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " hotbar " + slot);
                Player lilyBukkit = LilyUtils.getLilyBukkit();
                if (lilyBukkit != null) {
                    int newSlot = slot - 1;
                    int prevSlot = lilyBukkit.getInventory().getHeldItemSlot();
                    PlayerItemHeldEvent heldEvent = new PlayerItemHeldEvent(lilyBukkit, prevSlot, newSlot);
                    Bukkit.getPluginManager().callEvent(heldEvent);
                    if (!heldEvent.isCancelled()) lilyBukkit.getInventory().setHeldItemSlot(newSlot);
                }
            }
            case "spawn" -> {
                if (cmd.has("x")) {
                    double x = cmd.get("x").getAsDouble();
                    double y = cmd.get("y").getAsDouble();
                    double z = cmd.get("z").getAsDouble();
                    LilyUtils.runCommand(String.format("player %s spawn at %.2f %.2f %.2f", LilyBridge.BOT_NAME, x, y, z));
                } else {
                    LilyUtils.runCommand("bot load " + LilyBridge.BOT_NAME);
                }
            }
            case "kill" -> LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " kill");
            case "fire_pk_event" -> firePkEvent(cmd);
            case "get_players" -> sendPlayersList();
            case "get_lily_state" -> sendLilyState();
            case "get_hostiles" -> sendHostiles(cmd);
            case "get_duel_data" -> sendDuelData(cmd);
        }
    }




    // ---------- Helper methods for other commands ----------
    private static void firePkEvent(JsonObject cmd) {
        Player lily = LilyUtils.getLilyBukkit();
        if (lily == null) return;
        String pkEvent = cmd.get("event").getAsString();
        switch (pkEvent) {
            case "click" -> Bukkit.getPluginManager().callEvent(new PlayerAnimationEvent(lily, PlayerAnimationType.ARM_SWING));
            case "sneak" -> LilyUtils.scheduleSneakState(lily, true);
            case "unsneak" -> LilyUtils.scheduleSneakState(lily, false);
            case "slot" -> {
                int newSlot = cmd.get("slot").getAsInt() - 1;
                int prevSlot = lily.getInventory().getHeldItemSlot();
                PlayerItemHeldEvent heldEvent = new PlayerItemHeldEvent(lily, prevSlot, newSlot);
                Bukkit.getPluginManager().callEvent(heldEvent);
                if (!heldEvent.isCancelled()) lily.getInventory().setHeldItemSlot(newSlot);
            }
            default -> LilyBridge.LOGGER.warn("[PK] Unknown pk event: {}", pkEvent);
        }
    }

    private static void sendPlayersList() {
        if (LilyBridge.wsServer == null) return;
        StringBuilder players = new StringBuilder();
        for (ServerPlayer p : LilyBridge.mcServer.getPlayerList().getPlayers()) {
            String name = p.getName().getString();
            if (name.equals(LilyBridge.BOT_NAME)) continue;
            players.append(name).append(":").append(p.getX()).append(",").append(p.getY()).append(",").append(p.getZ())
                    .append(",hp=").append(p.getHealth()).append(";");
        }
        JsonObject res = new JsonObject();
        res.addProperty("type", "players_list");
        res.addProperty("players", players.toString());
        LilyBridge.wsServer.broadcast(LilyBridge.GSON.toJson(res));
    }

    private static void sendLilyState() {
        if (LilyBridge.wsServer == null) return;
        for (ServerPlayer p : LilyBridge.mcServer.getPlayerList().getPlayers()) {
            if (!p.getName().getString().equals(LilyBridge.BOT_NAME)) continue;
            JsonObject res = new JsonObject();
            res.addProperty("type", "lily_state");
            res.addProperty("x", p.getX());
            res.addProperty("y", p.getY());
            res.addProperty("z", p.getZ());
            res.addProperty("hp", p.getHealth());
            res.addProperty("food", p.getFoodData().getFoodLevel());
            LilyBridge.wsServer.broadcast(LilyBridge.GSON.toJson(res));
            break;
        }
    }

    private static void sendHostiles(JsonObject cmd) {
        if (LilyBridge.wsServer == null) return;
        ServerPlayer lily = LilyUtils.getLilyServerPlayer();
        if (lily == null) return;
        ServerLevel level = (ServerLevel) lily.level();
        double scanRange = cmd.has("range") ? cmd.get("range").getAsDouble() : 16.0;
        JsonArray hostiles = new JsonArray();
        for (Entity entity : level.getEntities(lily, lily.getBoundingBox().inflate(scanRange))) {
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
        LilyBridge.wsServer.broadcast(LilyBridge.GSON.toJson(res));
    }

    private static void sendDuelData(JsonObject cmd) {
        Player lily = LilyUtils.getLilyBukkit();
        if (lily == null) return;
        String opponentName = cmd.has("opponent") ? cmd.get("opponent").getAsString() : null;
        if (opponentName == null) return;
        ServerPlayer opponent = null;
        for (ServerPlayer p : LilyBridge.mcServer.getPlayerList().getPlayers()) {
            if (p.getName().getString().equals(opponentName)) {
                opponent = p;
                break;
            }
        }
        if (opponent == null) return;
        JsonObject res = new JsonObject();
        res.addProperty("type", "duel_data");
        JsonObject lilyData = new JsonObject();
        lilyData.addProperty("x", lily.getLocation().getX());
        lilyData.addProperty("y", lily.getLocation().getY());
        lilyData.addProperty("z", lily.getLocation().getZ());
        lilyData.addProperty("hp", lily.getHealth());
        res.add("lily", lilyData);
        JsonObject oppData = new JsonObject();
        oppData.addProperty("x", opponent.getX());
        oppData.addProperty("y", opponent.getY());
        oppData.addProperty("z", opponent.getZ());
        oppData.addProperty("hp", opponent.getHealth());
        oppData.addProperty("name", opponentName);
        res.add("opponent", oppData);

        JsonObject bindings = new JsonObject();
        org.bukkit.scoreboard.Scoreboard bukkitBoard = lily.getScoreboard();
        org.bukkit.scoreboard.Objective objective = bukkitBoard.getObjective(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
        if (objective != null) {
            for (String entry : bukkitBoard.getEntries()) {
                org.bukkit.scoreboard.Score score = objective.getScore(entry);
                if (score == null || !score.isScoreSet()) continue;
                int scoreValue = score.getScore();
                if (scoreValue >= -9 && scoreValue <= -1) {
                    int slot = -scoreValue;
                    org.bukkit.scoreboard.Team team = bukkitBoard.getEntryTeam(entry);
                    String ability = (team != null) ? team.getPrefix() + entry + team.getSuffix() : entry;
                    ability = ability.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
                    if (!ability.isEmpty()) bindings.addProperty(String.valueOf(slot), ability);
                }
            }
        }
        res.add("bindings", bindings);
        LilyUtils.broadcast(res);
    }
}