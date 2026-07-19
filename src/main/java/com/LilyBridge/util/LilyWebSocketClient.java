package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.google.gson.JsonObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;

public class LilyWebSocketClient extends WebSocketClient {

    private static final String[] URLS = {
            "wss://lilynodeserverpersonal.duckdns.org"
    };
    private static int urlIndex = 0;

    public LilyWebSocketClient() throws Exception {
        super(new URI(URLS[urlIndex]));
    }

    private LilyWebSocketClient(String url) throws Exception {
        super(new URI(url));
    }

    public static LilyWebSocketClient create() {
        try {
            return new LilyWebSocketClient(URLS[urlIndex]);
        } catch (Exception e) {
            LilyBridge.LOGGER.error("[WS] Failed to create client: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        LilyBridge.LOGGER.info("[WS] Connected to Node.js via {}", URLS[urlIndex]);
        urlIndex = 0; // reset to primary on success
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
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                LilyBridge.LOGGER.info("[WS] Reconnecting to Node.js...");
                // cycle to next URL on failure
                if (code == -1) {
                    urlIndex = (urlIndex + 1) % URLS.length;
                    LilyBridge.LOGGER.info("[WS] Trying URL: {}", URLS[urlIndex]);
                }
                LilyBridge.wsClient = LilyWebSocketClient.create();
                if (LilyBridge.wsClient != null) LilyBridge.wsClient.connect();
                LilyCommandHandler.startEnvironmentScanTask();
            } catch (Exception e) {
                LilyBridge.LOGGER.error("[WS] Reconnect failed: {}", e.getMessage());
            }
        }).start();
    }

    @Override
    public void onError(Exception ex) {
        LilyBridge.LOGGER.error("[WS] Error: {}", ex.getMessage());
    }
}