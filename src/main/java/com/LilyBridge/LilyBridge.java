package com.LilyBridge;

import com.LilyBridge.commands.LilyCommands;
import com.LilyBridge.util.AbilityDataLoader;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.ServerChatEvent;  // ← Evento de chat de NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.LilyBridge.util.*;

@Mod(LilyBridge.MODID)
public class LilyBridge {

    public static final String  MODID    = "lilyminecraftbridge";
    public static final Logger  LOGGER   = LogManager.getLogger(MODID);
    public static final Gson    GSON     = new Gson();
    public static final String  BOT_NAME = "Lily";

    public static MinecraftServer      mcServer = null;
    public static LilyWebSocketClient  wsClient = null;
    public static volatile boolean isDuelActive = false;
    public static volatile String currentOpponentName = null;

    public LilyBridge(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("LilyBotBridge loaded!");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LilyCommands.register(event.getDispatcher());
    }

    // ✅ Chat listener convertido a @SubscribeEvent de NeoForge
    @SubscribeEvent
    public void onPlayerChat(ServerChatEvent event) {
        String playerName = String.valueOf(event.getPlayer().getName());
        String message = event.getMessage().getString();

        // No reenviar mensajes de la propia Lily
        if (playerName.equals(LilyBridge.BOT_NAME)) return;

        LilyUtils.broadcast("chat",
                "player",  playerName,
                "message", message
        );
    }
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ServerPlayer lily = LilyUtils.getLilyServerPlayer(); // your accessor for the underlying entity
        if (lily != null) miningManager.tick(lily);
    }
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        mcServer = event.getServer();

        try {
            wsClient = new LilyWebSocketClient();
            wsClient.connect();
            LOGGER.info("LilyBotBridge connecting to Node.js WebSocket server...");
        } catch (Exception e) {
            LOGGER.error("Failed to create WebSocket client: {}", e.getMessage());
        }

        LilyUtils.scheduleCommand(60,  "bot load " + BOT_NAME);
        LilyUtils.scheduleCommand(100, "player " + BOT_NAME + " run /k home");

        AbilityDataLoader.loadAll();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LilyTasks.stopAllMovement();
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " kill");

        if (wsClient != null) {
            try { wsClient.close(); } catch (Exception e) {
                LOGGER.error("Error closing WebSocket client: {}", e.getMessage());
            }
            wsClient = null;
        }

        mcServer = null;
    }
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        String playerName = player.getGameProfile().getName();

        // Automatically set survival mode if Lily joins while a duel is running
        if (playerName.equals(LilyBridge.BOT_NAME) && LilyBridge.isDuelActive) {
            player.setGameMode(GameType.SURVIVAL);
            LilyBridge.LOGGER.info("[LilyForge] Forcing Lily into SURVIVAL mode via duel hook.");
        }
    }
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        // We only care about players dying
        if (!(event.getEntity() instanceof ServerPlayer deadPlayer)) return;

        String deadName = deadPlayer.getGameProfile().getName();

        // 1. Auto-reload Lily if she dies
        if (deadName.equals(LilyBridge.BOT_NAME)) {
            LilyBridge.LOGGER.info("[LilyForge] Lily died! Queueing automatic bot load...");
            // Use your scheduler to reload her safely 10 ticks later
            LilyUtils.scheduleCommand(10, "bot load " + LilyBridge.BOT_NAME);
        }

        // 2. Track Duel Completion
        if (LilyBridge.isDuelActive && LilyBridge.currentOpponentName != null) {
            String opponent = LilyBridge.currentOpponentName;

            if (deadName.equals(LilyBridge.BOT_NAME) || deadName.equalsIgnoreCase(opponent)) {
                String winner = deadName.equals(LilyBridge.BOT_NAME) ? opponent : LilyBridge.BOT_NAME;
                String loser = deadName.equals(LilyBridge.BOT_NAME) ? LilyBridge.BOT_NAME : opponent;

                sendDuelResultToNode(winner, loser);

                // Tear down duel tracking states
                LilyBridge.isDuelActive = false;
                LilyBridge.currentOpponentName = null;
            }
        }

    }
    private static void sendDuelResultToNode(String winner, String loser) {
        JsonObject res = new JsonObject();
        res.addProperty("type", "duel_result");
        res.addProperty("winner", winner);
        res.addProperty("loser", loser);

        LilyUtils.broadcast(res);
        LilyBridge.LOGGER.info("[LilyForge Duel] Result sent over bridge! Winner: {}, Loser: {}", winner, loser);
    }
}