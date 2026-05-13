package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.google.gson.JsonObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

public class LilyWebSocketClient extends WebSocketClient {

    private static final String NODE_URL = "wss://monthly-devoted-pug.ngrok-free.app";
    private volatile boolean intentionallyClosed = false;

    public LilyWebSocketClient() throws Exception {
        super(new URI(NODE_URL));
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        LilyBridge.LOGGER.info("[WS] Connected to Node.js at {}", NODE_URL);

        // Identify ourselves to Node so it knows Java is ready
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "java_connected");
        send(LilyBridge.GSON.toJson(hello));

        // Send ability data now that connection is live
        if (LilyBridge.mcServer != null) {
            LilyBridge.mcServer.execute(AbilityDataLoader::sendAbilityDataToNode);
        }
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject cmd = LilyBridge.GSON.fromJson(message, JsonObject.class);
            if (LilyBridge.mcServer != null) {
                LilyBridge.mcServer.execute(() -> LilyCommandHandler.handleCommand(cmd));
            }
        } catch (Exception e) {
            LilyBridge.LOGGER.error("[WS] Command error: {}", e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        LilyBridge.LOGGER.info("[WS] Disconnected from Node.js (code={}, reason={}, remote={})", code, reason, remote);
        if (!intentionallyClosed) {
            scheduleReconnect();
        }
    }

    @Override
    public void onError(Exception ex) {
        LilyBridge.LOGGER.error("[WS] Error: {}", ex.getMessage());
    }

    /** Call this before closing the server intentionally so reconnect doesn't fire. */
    public void closeGracefully() {
        intentionallyClosed = true;
        close();
    }

    private void scheduleReconnect() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000);
                if (!intentionallyClosed && LilyBridge.mcServer != null) {
                    LilyBridge.LOGGER.info("[WS] Reconnecting to Node.js...");
                    reconnect();
                }
            } catch (Exception e) {
                LilyBridge.LOGGER.error("[WS] Reconnect failed: {}", e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }
}