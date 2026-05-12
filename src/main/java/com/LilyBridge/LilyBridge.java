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

    public static MinecraftServer mcServer = null;
    public static LilyWebSocketServer wsServer = null;

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
        wsServer = new LilyWebSocketServer(8765);
        wsServer.start();
        LOGGER.info("LilyBotBridge WebSocket started on port 8765");

        LilyUtils.scheduleCommand(60,  "bot load " + BOT_NAME);
        LilyUtils.scheduleCommand(100, "player " + BOT_NAME + " run /k home");

        AbilityDataLoader.loadAll();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LilyUtils.runCommand("player " + BOT_NAME + " kill");
        LilyCommandHandler.stopMovementJumpTask();
        if (wsServer != null) {
            try { wsServer.stop(); } catch (Exception e) {
                LOGGER.error("Error stopping WebSocket: " + e.getMessage());
            }
        }
        mcServer = null;
    }
}