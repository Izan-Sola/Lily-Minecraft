package com.LilyBridge.commands;

import com.LilyBridge.LilyBridge;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class LilyCommands {

    private static final Gson   GSON     = new Gson();
    private static final String BOT_NAME = LilyBridge.BOT_NAME;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("lily")

                        // /lily bend <element>
                        .then(Commands.literal("bend")
                                .then(Commands.argument("element", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String element = StringArgumentType.getString(ctx, "element").toLowerCase();
                                            return bindElement(ctx.getSource(), element);
                                        })
                                )
                        )

                        // /lily stop
                        .then(Commands.literal("stop")
                                .executes(ctx -> {
                                    LilyBridge.runCommand("player " + BOT_NAME + " stop");
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(BOT_NAME + " stopped."), false
                                    );
                                    return 1;
                                })
                        )

                        // /lily follow <player>
                        .then(Commands.literal("follow")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String target = StringArgumentType.getString(ctx, "player");
                                            if (LilyBridge.wsServer != null) {
                                                JsonObject msg = new JsonObject();
                                                msg.addProperty("type",   "set_follow_target");
                                                msg.addProperty("target", target);
                                                LilyBridge.broadcast(msg);  // ← use helper
                                            }
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal(BOT_NAME + " will now follow " + target), false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        // /lily come
                        .then(Commands.literal("come")
                                .executes(ctx -> {
                                    String caller = ctx.getSource().getTextName();
                                    if (LilyBridge.wsServer != null) {
                                        JsonObject msg = new JsonObject();
                                        msg.addProperty("type",   "set_follow_target");
                                        msg.addProperty("target", caller);
                                        LilyBridge.broadcast(msg);  // ← use helper
                                    }
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(BOT_NAME + " is coming to you!"), false
                                    );
                                    return 1;
                                })
                        )

                        // /lily status
                        .then(Commands.literal("status")
                                .executes(ctx -> {
                                    if (LilyBridge.wsServer != null) {
                                        JsonObject msg = new JsonObject();
                                        msg.addProperty("type", "get_status");
                                        LilyBridge.broadcast(msg);  // ← use helper
                                    }
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Requesting " + BOT_NAME + " status..."), false
                                    );
                                    return 1;
                                })
                        )
        );
    }

    // Bind element (unchanged)
    private static int bindElement(CommandSourceStack source, String element) {
        String[][] bindings = switch (element) {
            case "fire" -> new String[][]{
                    {"1", "FireBall"}, {"2", "FireShots"}, {"3", "Lightning"},
                    {"4", "Discharge"}, {"5", "Combustion"}, {"6", "FireShield"},
                    {"7", "FireBlast"}, {"8", "FireJet"}, {"9", "WallOfFire"},
            };
            default -> null;
        };

        if (bindings == null) {
            source.sendFailure(Component.literal("Unknown element: " + element + ". Valid: fire, water, earth, air"));
            return 0;
        }

        for (int i = 0; i < bindings.length; i++) {
            final String ability = bindings[i][1];
            final String slot    = bindings[i][0];
            final long   delayMs = i * 500L;

            Thread t = new Thread(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                LilyBridge.runCommandAsLily("b b " + ability + " " + slot);
            });
            t.setDaemon(true);
            t.start();
        }

        source.sendSuccess(
                () -> Component.literal("Binding " + element + " abilities for " + BOT_NAME + "!"), false
        );

        // Notify Node.js
        if (LilyBridge.wsServer != null) {
            JsonObject res = new JsonObject();
            res.addProperty("type",    "element_changed");
            res.addProperty("element", element);
            LilyBridge.broadcast(res);  // ← use helper
        }

        return 1;
    }
}