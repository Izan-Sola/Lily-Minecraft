package com.LilyBridge;

import com.LilyBridge.commands.LilyCommands;
import com.LilyBridge.util.AbilityDataLoader;
import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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

    public LilyBridge(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("LilyBotBridge loaded!");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LilyCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        mcServer = event.getServer();

        // Connect to Node.js WebSocket server
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
}