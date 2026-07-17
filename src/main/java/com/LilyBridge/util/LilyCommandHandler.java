package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.LilyBridge.util.AbilityDataLoader;
import com.LilyBridge.util.LilyUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

import static com.LilyBridge.LilyBridge.LOGGER;
import static com.LilyBridge.util.LilyTasks.*;

public class LilyCommandHandler {

    static volatile boolean isMoving = false;
    private static volatile String currentMoveDirection = null;

    private static volatile Double targetMoveX = null;
    private static volatile Double targetMoveZ = null;
    private static volatile String currentTargetDirection = null;

    // ─────────────────────────────────────────────────────────────────────────
    // CONFIG — tune behavior here, nothing else needs to change
    // ─────────────────────────────────────────────────────────────────────────

    /** How long Lily keeps swinging on a "break" command before giving up if the block hasn't broken (safety net for obstructed/wrong-tool cases). */
    private static final long BREAK_TIMEOUT_TICKS = 100L; // 5 seconds

    /** All tunables for the periodic environment scan. Change values here only. */
    private static final class EnvScanConfig {
        /** How often the scan runs. 20 ticks = 1 second. */
        static final long INTERVAL_TICKS = 100L; // every 3 seconds

        /** Entities: sphere radius in blocks, and max entities reported per scan (nearest-first). */
        static final double ENTITY_RADIUS = 24.0;
        static final int    ENTITY_LIMIT  = 10;

        /** Blocks of interest: horizontal radius in blocks (circular scan, not square). */
        static final int BLOCK_RADIUS = 16;
        /** Vertical band around Lily: how many blocks above her head / below her feet to include.
         *  Default (1,1) = 4 blocks tall total: 1 below feet, feet, head, 1 above head. */
        static final int BLOCK_HEIGHT_ABOVE_HEAD = 2;
        static final int BLOCK_HEIGHT_BELOW_FEET = 2;
        static final int BLOCK_LIMIT = 20;
    }

    public static void handleCommand(JsonObject cmd) {
        if (LilyBridge.mcServer == null) return;
        String type = cmd.get("type").getAsString();

        switch (type) {
            case "chat" -> {
                String msg = cmd.get("message").getAsString();
                LilyUtils.runCommandAsLily("say " + msg);
            }

            case "request_ability_data" -> AbilityDataLoader.sendAbilityDataToNode();

            case "get_bindings" -> sendBindingsToNode();

            case "run_command" -> LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " run " + cmd.get("command").getAsString());

            case "move_to" -> {
                double x = cmd.get("x").getAsDouble();
                double z = cmd.get("z").getAsDouble();
                stopMovementJumpTask();
                stopMovementSafetyTask();
                currentMoveDirection = null;
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

            case "break" -> {
                BlockPos pos = new BlockPos(cmd.get("x").getAsInt(), cmd.get("y").getAsInt(), cmd.get("z").getAsInt());
                miningManager.mine(pos);
            }

            case "use" -> {
                String mode = cmd.has("mode") ? cmd.get("mode").getAsString() : "once";
                LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " use " + mode);
                Player lilyBukkit = LilyUtils.getLilyBukkit();
                if (lilyBukkit != null) {
                    Bukkit.getPluginManager().callEvent(new PlayerAnimationEvent(lilyBukkit, PlayerAnimationType.ARM_SWING));
                }
            }

            case "jump"    -> LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " jump once");

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

            case "unsprint" -> LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " unsprint");

            case "hotbar" -> {
                int slot = cmd.get("slot").getAsInt();
                LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " hotbar " + slot);
                Player lilyBukkit = LilyUtils.getLilyBukkit();
                if (lilyBukkit != null) {
                    int newSlot  = slot - 1;
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
                startEnvironmentScanTask();
            }

            case "look_dir" -> {
                ServerPlayer lily = LilyUtils.getLilyServerPlayer();
                if (lily == null) return;

                String direction = cmd.has("direction") ? cmd.get("direction").getAsString() : "forward";
                // degrees: how far to rotate from current look (default 90)
                double degrees   = cmd.has("degrees")   ? cmd.get("degrees").getAsDouble()   : 90.0;
                double radians   = Math.toRadians(degrees);

                Vec3 look  = lily.getLookAngle();
                double px  = lily.getX();
                double py  = lily.getY() + lily.getEyeHeight();
                double pz  = lily.getZ();

                double flatLen = Math.sqrt(look.x * look.x + look.z * look.z);
                double fx = flatLen > 1e-4 ? look.x / flatLen : 0;
                double fz = flatLen > 1e-4 ? look.z / flatLen : 1;

                // Perpendicular right vector (rotate forward 90° CW)
                double rx = fz, rz = -fx;

                // D is the projection distance — fixed at 10 so the angle is what matters
                final double D = 10.0;

                double tx, ty, tz;
                switch (direction) {
                    case "back"  -> { tx = px - fx * D;                    ty = py;                  tz = pz - fz * D; }
                    case "left"  -> { tx = px - rx * Math.sin(radians) * D; ty = py;                 tz = pz - rz * Math.sin(radians) * D; }
                    case "right" -> { tx = px + rx * Math.sin(radians) * D; ty = py;                 tz = pz + rz * Math.sin(radians) * D; }
                    case "up"    -> { tx = px + fx * D;                    ty = py + Math.tan(radians) * D; tz = pz + fz * D; }
                    case "down"  -> { tx = px + fx * D;                    ty = py - Math.tan(radians) * D; tz = pz + fz * D; }
                    default      -> { tx = px + fx * D;                    ty = py;                  tz = pz + fz * D; } // forward
                }

                LilyUtils.runCommand(String.format("player %s look at %.4f %.4f %.4f",
                        LilyBridge.BOT_NAME, tx, ty, tz));

                // No explicit release needed — Node's lockLookUntil expiry resumes opponent tracking
            }

            case "kill" -> {
                stopEnvironmentScanTask();
                LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " kill");
            }

            case "fire_pk_event" -> firePkEvent(cmd);
            case "get_players"   -> sendPlayersList();
            case "get_lily_state"-> sendLilyState();
            case "get_hostiles"  -> sendHostiles(cmd);

            // On-demand environment scan (entities + blocks of interest), in addition
            // to the automatic periodic one started on spawn.
            case "get_environment_scan" -> performEnvironmentScan();

            // Manual control over the periodic scan, independent of spawn/kill —
            // e.g. {"type":"toggle_env_scan","value":false} to pause it mid-session.
            case "toggle_env_scan" -> {
                boolean enable = cmd.has("value") && cmd.get("value").getAsBoolean();
                if (enable) startEnvironmentScanTask(); else stopEnvironmentScanTask();
            }

            case "get_duel_data" -> {
                String opponentName = cmd.has("opponent") ? cmd.get("opponent").getAsString() : null;
                if (opponentName != null) {
                    LilyBridge.isDuelActive = true;
                    LilyBridge.currentOpponentName = opponentName;
                    LilyUtils.runCommand("gamemode survival " + LilyBridge.BOT_NAME);
                }
                sendDuelData(cmd);
            }

            case "get_source_block" -> {
                ServerPlayer lily = LilyUtils.getLilyServerPlayer();
                if (lily == null) return;

                // Parse optional block filter list sent by Node
                List<String> allowedBlocks = parseBlockList(cmd);
                int distance = cmd.has("distance") ? cmd.get("distance").getAsInt() : 0;

                BlockPos src = LilyUtils.findSourceBlock(lily, allowedBlocks.isEmpty() ? null : allowedBlocks, distance);

                JsonObject res = new JsonObject();
                res.addProperty("type", "source_block");

                if (src != null) {
                    res.addProperty("found", true);
                    res.addProperty("x", src.getX() + 0.5);
                    res.addProperty("y", src.getY() + 1.0);
                    res.addProperty("z", src.getZ() + 0.5);
                } else {
                    res.addProperty("found", false);
                }

                LilyUtils.broadcast(res);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENVIRONMENT AWARENESS SCAN (entities + blocks of interest)
    // ─────────────────────────────────────────────────────────────────────────

    private static volatile BukkitTask envScanTask = null;

    /**
     * Starts the periodic environment scan (entities + blocks of interest).
     * Safe to call repeatedly — a second call while already running is a no-op,
     * so hooking this into "spawn" every time is fine.
     */
    public static void startEnvironmentScanTask() {

        if (envScanTask != null) return;
        envScanTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugins()[0],
                LilyCommandHandler::performEnvironmentScan,
                0L,
                EnvScanConfig.INTERVAL_TICKS
        );
        LOGGER.info("SCANNING ENVIRONMENT FOR LILY");
    }

    /** Stops the periodic environment scan, if one is running. Safe to call when already stopped. */
    public static void stopEnvironmentScanTask() {
        if (envScanTask == null) return;
        envScanTask.cancel();
        envScanTask = null;
    }

    /**
     * Runs one scan pass and broadcasts the result to Node as a single
     * "environment_scan" message containing both nearby entities and nearby
     * blocks of interest, each independently capped so a busy area never
     * bloats the payload (and downstream, the LLM prompt).
     */
    private static void performEnvironmentScan() {
        ServerPlayer lily = LilyUtils.getLilyServerPlayer();
        if (lily == null) return;

        JsonArray entities = LilyUtils.scanNearbyEntities(
                lily, EnvScanConfig.ENTITY_RADIUS, EnvScanConfig.ENTITY_LIMIT);

        JsonArray blocks = LilyUtils.scanNearbyBlocksOfInterest(
                lily,
                EnvScanConfig.BLOCK_RADIUS,
                EnvScanConfig.BLOCK_HEIGHT_ABOVE_HEAD,
                EnvScanConfig.BLOCK_HEIGHT_BELOW_FEET,
                EnvScanConfig.BLOCK_LIMIT
        );

        JsonArray hostiles = new JsonArray();
        JsonArray passives = new JsonArray();
        for (JsonElement el : entities) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("name")) continue;
            if (o.has("hostile") && o.get("hostile").getAsBoolean()) hostiles.add(o);
            else passives.add(o);
        }

        JsonObject hotbar = LilyUtils.scanHotbar(lily); // NEW

        JsonObject res = new JsonObject();
        res.addProperty("type", "environment_scan");
        res.add("hostiles", hostiles);
        res.add("passives", passives);
        res.add("blocks_of_interest", blocks);
        res.add("hotbar", hotbar); // NEW
        LilyUtils.broadcast(res);
    }
    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the optional "blocks" JSON array from a command object.
     * Values are trimmed and lowercased; alias resolution to full registry IDs
     * is handled by LilyUtils.findSourceBlock via BLOCK_ALIASES.
     */
    private static List<String> parseBlockList(JsonObject cmd) {
        List<String> result = new ArrayList<>();
        if (!cmd.has("blocks") || !cmd.get("blocks").isJsonArray()) return result;
        for (JsonElement el : cmd.getAsJsonArray("blocks")) {
            String name = el.getAsString().trim().toLowerCase();
            if (!name.isEmpty()) result.add(name);
        }
        return result;
    }

    // ─── Bindings ──────────────────────────────────────────────────────────────

    private static void sendBindingsToNode() {
        Player lily = LilyUtils.getLilyBukkit();
        if (lily == null) return;

        JsonObject bindings = new JsonObject();
        org.bukkit.scoreboard.Scoreboard  bukkitBoard = lily.getScoreboard();
        org.bukkit.scoreboard.Objective   objective   = bukkitBoard.getObjective(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);

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

        JsonObject res = new JsonObject();
        res.addProperty("type", "bindings_update");
        res.add("bindings", bindings);
        LilyUtils.broadcast(res);
        LOGGER.info("[Bindings] Sent {} bindings to Node", bindings.size());
    }

    // ─── Helper methods ────────────────────────────────────────────────────────

    private static void firePkEvent(JsonObject cmd) {
        Player lily = LilyUtils.getLilyBukkit();
        if (lily == null) return;
        String pkEvent = cmd.get("event").getAsString();
        switch (pkEvent) {
            case "click"   -> Bukkit.getPluginManager().callEvent(new PlayerAnimationEvent(lily, PlayerAnimationType.ARM_SWING));
            case "sneak"   -> LilyUtils.scheduleSneakState(lily, true);
            case "unsneak" -> LilyUtils.scheduleSneakState(lily, false);
            case "slot" -> {
                int newSlot  = cmd.get("slot").getAsInt() - 1;
                int prevSlot = lily.getInventory().getHeldItemSlot();
                PlayerItemHeldEvent heldEvent = new PlayerItemHeldEvent(lily, prevSlot, newSlot);
                Bukkit.getPluginManager().callEvent(heldEvent);
                if (!heldEvent.isCancelled()) lily.getInventory().setHeldItemSlot(newSlot);
            }
            default -> LOGGER.warn("[PK] Unknown pk event: {}", pkEvent);
        }
    }

    private static void sendPlayersList() {
        if (LilyBridge.wsClient == null) return;
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
        LilyUtils.broadcast(res);
    }

    private static void sendLilyState() {
        if (LilyBridge.mcServer == null || LilyBridge.wsClient == null) return;

        ServerPlayer p = LilyBridge.mcServer.getPlayerList().getPlayerByName(LilyBridge.BOT_NAME);
        if (p == null || p.isDeadOrDying()) return;

        JsonObject res = new JsonObject();
        res.addProperty("type", "lily_state");
        res.addProperty("x", p.getX());
        res.addProperty("y", p.getY());
        res.addProperty("z", p.getZ());
        res.addProperty("hp", p.getHealth());
        res.addProperty("food", p.getFoodData().getFoodLevel());
        res.addProperty("armor", p.getArmorValue());
        res.addProperty("onGround", p.onGround());
        res.addProperty("vx", p.getDeltaMovement().x);
        res.addProperty("vy", p.getDeltaMovement().y);
        res.addProperty("vz", p.getDeltaMovement().z);

        LilyUtils.broadcast(res);
    }

    private static void sendHostiles(JsonObject cmd) {
        if (LilyBridge.wsClient == null) return;
        ServerPlayer lily = LilyUtils.getLilyServerPlayer();
        if (lily == null) return;
        ServerLevel level     = (ServerLevel) lily.level();
        double      scanRange = cmd.has("range") ? cmd.get("range").getAsDouble() : 16.0;
        JsonArray   hostiles  = new JsonArray();
        for (Entity entity : level.getEntities(lily, lily.getBoundingBox().inflate(scanRange))) {
            if (!(entity instanceof Monster monster)) continue;
            if (!monster.isAlive()) continue;
            JsonObject h = new JsonObject();
            h.addProperty("type", monster.getType().toShortString());
            h.addProperty("id",   monster.getId());
            h.addProperty("x",    monster.getX());
            h.addProperty("y",    monster.getY());
            h.addProperty("z",    monster.getZ());
            h.addProperty("hp",   monster.getHealth());
            hostiles.add(h);
        }
        JsonObject res = new JsonObject();
        res.addProperty("type", "hostiles");
        res.add("hostiles", hostiles);
        LilyUtils.broadcast(res);
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

        JsonObject lilyData = new JsonObject();
        lilyData.addProperty("x", lily.getLocation().getX());
        lilyData.addProperty("y", lily.getLocation().getY());
        lilyData.addProperty("z", lily.getLocation().getZ());
        lilyData.addProperty("hp", lily.getHealth());
        res.addProperty("type", "duel_data");
        res.add("lily", lilyData);

        JsonObject oppData = new JsonObject();
        oppData.addProperty("x", opponent.getX());
        oppData.addProperty("y", opponent.getY());
        oppData.addProperty("z", opponent.getZ());
        oppData.addProperty("hp", opponent.getHealth());
        oppData.addProperty("name", opponentName);
        res.add("opponent", oppData);

        JsonObject bindings = new JsonObject();
        org.bukkit.scoreboard.Scoreboard board = lily.getScoreboard();
        org.bukkit.scoreboard.Objective objective =
                board.getObjective(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);

        if (objective != null) {
            for (String entry : board.getEntries()) {
                org.bukkit.scoreboard.Score score = objective.getScore(entry);
                if (score == null || !score.isScoreSet()) continue;

                int scoreValue = score.getScore();
                if (scoreValue >= -9 && scoreValue <= -1) {
                    int slot = -scoreValue;

                    org.bukkit.scoreboard.Team team = board.getEntryTeam(entry);
                    String ability = (team != null)
                            ? team.getPrefix() + entry + team.getSuffix()
                            : entry;

                    ability = ability.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();

                    if (!ability.isEmpty()) {
                        bindings.addProperty(String.valueOf(slot), ability);
                    }
                }
            }
        }

        res.add("bindings", bindings);
        LilyUtils.broadcast(res);
    }
}