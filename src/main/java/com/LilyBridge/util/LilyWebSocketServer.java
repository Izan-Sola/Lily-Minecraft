//package com.LilyBridge.util;
//
//import com.LilyBridge.LilyBridge;
//import com.google.gson.JsonObject;
//import org.java_websocket.WebSocket;
//import org.java_websocket.handshake.ClientHandshake;
//import org.java_websocket.server.WebSocketServer;
//import java.net.InetSocketAddress;
//
//public class LilyWebSocketServer extends WebSocketServer {
//
//    public LilyWebSocketServer(int port) {
//        super(new InetSocketAddress(port));
//    }
//
//    @Override
//    public void onOpen(WebSocket conn, ClientHandshake handshake) {
//        LilyBridge.LOGGER.info("Node.js connected: {}", conn.getRemoteSocketAddress());
//    }
//
//    @Override
//    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
//        LilyBridge.LOGGER.info("Node.js disconnected: {}", reason);
//    }
//
//    @Override
//    public void onMessage(WebSocket conn, String message) {
//        try {
//            JsonObject cmd = LilyBridge.GSON.fromJson(message, JsonObject.class);
//            if (LilyBridge.mcServer != null) {
//                LilyBridge.mcServer.execute(() -> LilyCommandHandler.handleCommand(cmd));
//            }
//        } catch (Exception e) {
//            LilyBridge.LOGGER.error("Command error: {}", e.getMessage());
//        }
//    }
//
//    @Override
//    public void onError(WebSocket conn, Exception ex) {
//        LilyBridge.LOGGER.error("WS error: {}", ex.getMessage());
//    }
//
//    @Override
//    public void onStart() {
//        LilyBridge.LOGGER.info("WebSocket ready");
//    }
//}