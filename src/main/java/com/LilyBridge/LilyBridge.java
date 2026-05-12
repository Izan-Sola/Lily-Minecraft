package com.LilyBridge;

import com.LilyBridge.commands.LilyCommands;
import com.LilyBridge.util.AbilityDataLoader;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;

@Mod("lilyminecraftbridge")
public class LilyBridge {

    public static final String  MODID    = "lilyminecraftbridge";
    public static final Logger  LOGGER   = LogManager.getLogger(MODID);
    private static final Gson   GSON     = new Gson();
    public static final String  BOT_NAME = "Lily";

    public static LilyWebSocketServer wsServer = null;
    private static MinecraftServer    mcServer  = null;

    public LilyBridge(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("LilyBotBridge loaded!");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LilyCommands.register(event.getDispatcher());
    }

    private static Player getLilyBukkit() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equals(BOT_NAME)) {
                return p;
            }
        }
        return null;
    }

    // ─── helpers ────────────────────────────────────────────────────
    private static boolean isSafeBlock(ServerLevel level, double x, double y, double z, String direction, Vec3 look) {
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
        if (wsServer == null) return;
        wsServer.broadcast(GSON.toJson(msg));
    }

    private static void broadcast(String type, String... keyValues) {
        if (wsServer == null) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            msg.addProperty(keyValues[i], keyValues[i + 1]);
        }
        wsServer.broadcast(GSON.toJson(msg));
    }

    // ─── Server lifecycle ─────────────────────────────────────────────────────

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        mcServer = event.getServer();
        wsServer = new LilyWebSocketServer(8765);
        wsServer.start();
        LOGGER.info("LilyBotBridge WebSocket started on port 8765");

        scheduleCommand(60,  "bot load " + BOT_NAME);
        scheduleCommand(100, "player " + BOT_NAME + " run /k home");

        AbilityDataLoader.loadAll();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        runCommand("player " + BOT_NAME + " kill");
        if (wsServer != null) {
            try { wsServer.stop(); } catch (Exception e) {
                LOGGER.error("Error stopping WebSocket: " + e.getMessage());
            }
        }
        mcServer = null;
    }

    // ─── Game events → Node.js ────────────────────────────────────────────────

    @SubscribeEvent
    public void onPlayerChat(ServerChatEvent event) {
        if (event.getPlayer().getName().getString().equals(BOT_NAME)) return;
        broadcast("chat",
                "player",  event.getPlayer().getName().getString(),
                "message", event.getMessage().getString()
        );
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        String name = event.getEntity().getName().getString();
        if (name.equals(BOT_NAME)) return;
        broadcast("player_join", "player", name);
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        String name = event.getEntity().getName().getString();
        if (name.equals(BOT_NAME)) return;
        broadcast("player_leave", "player", name);
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        broadcast("player_death",
                "player", player.getName().getString(),
                "cause",  event.getSource().getLocalizedDeathMessage(player).getString()
        );
    }

    // ─── Node.js → Game commands ──────────────────────────────────────────────

    public static void handleCommand(JsonObject cmd) {
        if (mcServer == null) return;
        String type = cmd.get("type").getAsString();

        switch (type) {
            case "chat" -> {
                String msg = cmd.get("message").getAsString();

                for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                    if (!p.getName().getString().equals(BOT_NAME)) continue;

                    mcServer.execute(() -> {
                        mcServer.getPlayerList().broadcastSystemMessage(
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
                runCommand("player " + BOT_NAME + " run " + command);
            }

            case "move" -> {
                String direction = cmd.get("direction").getAsString();

                if (!direction.equals("stop")) {
                    ServerPlayer lily = null;
                    for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                        if (p.getName().getString().equals(BOT_NAME)) { lily = p; break; }
                    }

                    if (lily != null && !isSafeBlock((ServerLevel) lily.level(), lily.getX(), lily.getY(), lily.getZ(), direction, lily.getLookAngle())) {
                        LOGGER.info("[MOVE] Unsafe block ahead, blocking move {}", direction);
                        return;
                    }
                }

                runCommand("player " + BOT_NAME + " move " + direction);
            }

            case "stop" -> {
                runCommand("player " + BOT_NAME + " stop");
                Player lilyBukkit = getLilyBukkit();
                if (lilyBukkit != null && lilyBukkit.isSneaking()) {
                    scheduleSneakState(lilyBukkit, false);
                }
            }

            case "look_at" -> {
                double x = cmd.get("x").getAsDouble();
                double y = cmd.get("y").getAsDouble();
                double z = cmd.get("z").getAsDouble();
                runCommand(String.format("player %s look at %.4f %.4f %.4f", BOT_NAME, x, y, z));
            }

            case "attack" -> {
                String mode = cmd.has("mode") ? cmd.get("mode").getAsString() : "once";
                if (mode.equals("stop")) {
                    runCommand("player " + BOT_NAME + " stop");
                    Player lilyBukkit = getLilyBukkit();
                    if (lilyBukkit != null && lilyBukkit.isSneaking()) {
                        scheduleSneakState(lilyBukkit, false);
                    }
                } else {
                    runCommand("player " + BOT_NAME + " attack " + mode);
                    Player lilyBukkit = getLilyBukkit();
                    if (lilyBukkit != null) {
                        Bukkit.getPluginManager().callEvent(
                                new PlayerAnimationEvent(lilyBukkit, PlayerAnimationType.ARM_SWING)
                        );
                    }
                }
            }

            case "use" -> {
                String mode = cmd.has("mode") ? cmd.get("mode").getAsString() : "once";
                runCommand("player " + BOT_NAME + " use " + mode);
                Player lilyBukkit = getLilyBukkit();
                if (lilyBukkit != null) {
                    Bukkit.getPluginManager().callEvent(
                            new PlayerAnimationEvent(lilyBukkit, PlayerAnimationType.ARM_SWING)
                    );
                }
            }

            case "jump" -> runCommand("player " + BOT_NAME + " jump once");

            case "sneak" -> {
                boolean sneaking = cmd.has("value") && cmd.get("value").getAsBoolean();
                Player lily = getLilyBukkit();
                if (lily == null) {
                    LOGGER.warn("[SNEAK] Lily not found");
                    return;
                }
                Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugins()[0],
                        () -> {
                            PlayerToggleSneakEvent event = new PlayerToggleSneakEvent(lily, sneaking);
                            Bukkit.getPluginManager().callEvent(event);
                            if (!event.isCancelled()) {
                                lily.setSneaking(sneaking);
                                LOGGER.info("[SNEAK] setSneaking({}) for {}", sneaking, BOT_NAME);
                            }
                        }
                );
            }

            case "sprint" -> {
                boolean sprinting = cmd.has("value") && cmd.get("value").getAsBoolean();
                runCommand("player " + BOT_NAME + (sprinting ? " sprint" : " unsprint"));
            }

            case "hotbar" -> {
                int slot = cmd.get("slot").getAsInt();
                runCommand("player " + BOT_NAME + " hotbar " + slot);
                Player lilyBukkit = getLilyBukkit();
                if (lilyBukkit != null) {
                    int newSlot  = slot - 1;
                    int prevSlot = lilyBukkit.getInventory().getHeldItemSlot();
                    PlayerItemHeldEvent heldEvent = new PlayerItemHeldEvent(lilyBukkit, prevSlot, newSlot);
                    Bukkit.getPluginManager().callEvent(heldEvent);
                    if (!heldEvent.isCancelled()) {
                        lilyBukkit.getInventory().setHeldItemSlot(newSlot);
                    }
                }
            }

            case "spawn" -> {
                if (cmd.has("x")) {
                    double x = cmd.get("x").getAsDouble();
                    double y = cmd.get("y").getAsDouble();
                    double z = cmd.get("z").getAsDouble();
                    runCommand(String.format("player %s spawn at %.2f %.2f %.2f", BOT_NAME, x, y, z));
                } else {
                    runCommand("bot load " + BOT_NAME);
                }
            }

            case "kill" -> runCommand("player " + BOT_NAME + " kill");

            case "fire_pk_event" -> {
                String pkEvent = cmd.get("event").getAsString();
                Player lilyBukkit = getLilyBukkit();
                if (lilyBukkit == null) {
                    LOGGER.warn("[PK] Lily not found for event: {}", pkEvent);
                    return;
                }

                switch (pkEvent) {
                    case "click" -> {
                        Bukkit.getPluginManager().callEvent(
                                new PlayerAnimationEvent(lilyBukkit, PlayerAnimationType.ARM_SWING)
                        );
                        LOGGER.info("[PK] Fired ARM_SWING for {}", BOT_NAME);
                    }
                    case "sneak" -> {
                        final Player captured = lilyBukkit;
                        Bukkit.getScheduler().runTask(
                                Bukkit.getPluginManager().getPlugins()[0],
                                () -> {
                                    PlayerToggleSneakEvent event = new PlayerToggleSneakEvent(captured, true);
                                    Bukkit.getPluginManager().callEvent(event);
                                    if (!event.isCancelled()) captured.setSneaking(true);
                                    LOGGER.info("[PK] Fired SNEAK for {}", BOT_NAME);
                                }
                        );
                    }
                    case "unsneak" -> {
                        final Player captured = lilyBukkit;
                        Bukkit.getScheduler().runTask(
                                Bukkit.getPluginManager().getPlugins()[0],
                                () -> {
                                    PlayerToggleSneakEvent event = new PlayerToggleSneakEvent(captured, false);
                                    Bukkit.getPluginManager().callEvent(event);
                                    if (!event.isCancelled()) captured.setSneaking(false);
                                    LOGGER.info("[PK] Fired UNSNEAK for {}", BOT_NAME);
                                }
                        );
                    }
                    case "slot" -> {
                        int newSlot  = cmd.get("slot").getAsInt() - 1;
                        int prevSlot = lilyBukkit.getInventory().getHeldItemSlot();
                        PlayerItemHeldEvent heldEvent = new PlayerItemHeldEvent(lilyBukkit, prevSlot, newSlot);
                        Bukkit.getPluginManager().callEvent(heldEvent);
                        if (!heldEvent.isCancelled()) {
                            lilyBukkit.getInventory().setHeldItemSlot(newSlot);
                        }
                        LOGGER.info("[PK] Fired SLOT_CHANGE {} → {} for {}", prevSlot, newSlot, BOT_NAME);
                    }
                    default -> LOGGER.warn("[PK] Unknown pk event: {}", pkEvent);
                }
            }

            case "get_players" -> {
                if (wsServer == null) return;
                StringBuilder players = new StringBuilder();
                for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                    String name = p.getName().getString();
                    if (name.equals(BOT_NAME)) continue;
                    players.append(name)
                            .append(":").append(p.getX())
                            .append(",").append(p.getY())
                            .append(",").append(p.getZ())
                            .append(",hp=").append(p.getHealth())
                            .append(";");
                }
                JsonObject res = new JsonObject();
                res.addProperty("type",    "players_list");
                res.addProperty("players", players.toString());
                wsServer.broadcast(GSON.toJson(res));
            }

            case "get_lily_state" -> {
                if (wsServer == null) return;
                for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                    if (!p.getName().getString().equals(BOT_NAME)) continue;
                    JsonObject res = new JsonObject();
                    res.addProperty("type", "lily_state");
                    res.addProperty("x",    p.getX());
                    res.addProperty("y",    p.getY());
                    res.addProperty("z",    p.getZ());
                    res.addProperty("hp",   p.getHealth());
                    res.addProperty("food", p.getFoodData().getFoodLevel());
                    wsServer.broadcast(GSON.toJson(res));
                    break;
                }
            }

            case "get_hostiles" -> {
                if (wsServer == null) return;
                ServerPlayer lily = null;
                for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                    if (p.getName().getString().equals(BOT_NAME)) { lily = p; break; }
                }
                if (lily == null) return;

                ServerLevel level     = (ServerLevel) lily.level();
                double      scanRange = cmd.has("range") ? cmd.get("range").getAsDouble() : 16.0;

                JsonArray hostiles = new JsonArray();
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
                wsServer.broadcast(GSON.toJson(res));
            }

            case "get_duel_data" -> {
                Player lily = getLilyBukkit();
                if (lily == null) return;

                String opponentName = cmd.has("opponent") ? cmd.get("opponent").getAsString() : null;
                if (opponentName == null) return;

                ServerPlayer opponent = null;
                for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                    if (p.getName().getString().equals(opponentName)) {
                        opponent = p;
                        break;
                    }
                }
                if (opponent == null) return;

                JsonObject res = new JsonObject();
                res.addProperty("type", "duel_data");

                // Lily
                JsonObject lilyData = new JsonObject();
                lilyData.addProperty("x", lily.getLocation().getX());
                lilyData.addProperty("y", lily.getLocation().getY());
                lilyData.addProperty("z", lily.getLocation().getZ());
                lilyData.addProperty("hp", lily.getHealth());
                res.add("lily", lilyData);

                // Opponent
                JsonObject oppData = new JsonObject();
                oppData.addProperty("x", opponent.getX());
                oppData.addProperty("y", opponent.getY());
                oppData.addProperty("z", opponent.getZ());
                oppData.addProperty("hp", opponent.getHealth());
                oppData.addProperty("name", opponentName);
                res.add("opponent", oppData);

                // Bindings from scoreboard – CORRECT BUKKIT API
                JsonObject bindings = new JsonObject();
                org.bukkit.scoreboard.Scoreboard bukkitBoard = lily.getScoreboard();
                org.bukkit.scoreboard.Objective objective = bukkitBoard.getObjective(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
                if (objective != null) {
                    for (String entry : bukkitBoard.getEntries()) {
                        org.bukkit.scoreboard.Score score = objective.getScore(entry);
                        if (score == null || !score.isScoreSet()) continue;

                        int scoreValue = score.getScore(); // -1 through -9
                        if (scoreValue >= -9 && scoreValue <= -1) {
                            int slot = -scoreValue;

                            // The actual text is in the team prefix+suffix attached to this entry
                            org.bukkit.scoreboard.Team team = bukkitBoard.getEntryTeam(entry);
                            String ability = "";
                            if (team != null) {
                                ability = team.getPrefix() + entry + team.getSuffix();
                                LOGGER.info("[DUEL DEBUG] Team prefix: '" + team.getPrefix() + "' suffix: '" + team.getSuffix() + "'");
                            } else {
                                ability = entry;
                            }

                            // Strip color codes
                            ability = ability.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();

                            if (!ability.isEmpty()) {
                                bindings.addProperty(String.valueOf(slot), ability);
                                LOGGER.info("[DUEL] Bound slot " + slot + " -> " + ability);
                            }
                        }
                    }
                }
                res.add("bindings", bindings);
                broadcast(res);
                LOGGER.info("[DUEL] Sent duel_data with " + bindings.size() + " bindings");
            }
        }
    }

    private static void scheduleSneakState(Player player, boolean sneaking) {
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    PlayerToggleSneakEvent event = new PlayerToggleSneakEvent(player, sneaking);
                    Bukkit.getPluginManager().callEvent(event);
                    if (!event.isCancelled()) {
                        player.setSneaking(sneaking);
                    }
                }
        );
    }

    public static void runCommandAsLily(String command) {
        if (mcServer == null) return;
        mcServer.execute(() -> {
            for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                if (!p.getName().getString().equals(BOT_NAME)) continue;
                mcServer.getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withMaximumPermission(4),
                        command
                );
                break;
            }
        });
    }

    public static void runCommand(String command) {
        if (mcServer == null) return;
        mcServer.execute(() ->
                mcServer.getCommands().performPrefixedCommand(
                        mcServer.createCommandSourceStack().withMaximumPermission(4),
                        command
                )
        );
    }

    public static void scheduleCommand(int delayTicks, String command) {
        if (mcServer == null) return;
        mcServer.execute(() -> {
            try { Thread.sleep(delayTicks * 50L); } catch (InterruptedException ignored) {}
            runCommand(command);
        });
    }

    public static MinecraftServer getServer() { return mcServer; }

    // ─── WebSocket server (static nested class) ───────────────────────────────
    static class LilyWebSocketServer extends WebSocketServer {
        public LilyWebSocketServer(int port) {
            super(new InetSocketAddress(port));
        }
        @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
            LOGGER.info("Node.js connected: {}", conn.getRemoteSocketAddress());
        }
        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            LOGGER.info("Node.js disconnected: {}", reason);
        }
        @Override public void onMessage(WebSocket conn, String message) {
            try {
                JsonObject cmd = GSON.fromJson(message, JsonObject.class);
                if (mcServer != null) {
                    mcServer.execute(() -> handleCommand(cmd));
                }
            } catch (Exception e) {
                LOGGER.error("Command error: {}", e.getMessage());
            }
        }
        @Override public void onError(WebSocket conn, Exception ex) {
            LOGGER.error("WS error: {}", ex.getMessage());
        }
        @Override public void onStart() {
            LOGGER.info("WebSocket ready");
        }
    }
}