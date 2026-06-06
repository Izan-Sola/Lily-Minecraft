package com.LilyBridge.commands;

import com.LilyBridge.LilyBridge;
import com.LilyBridge.util.LilyCommandHandler;
import com.LilyBridge.util.LilyUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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

                        // /lily bend <element> (preset bindings)
                        .then(Commands.literal("bend")
                                .then(Commands.argument("element", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String element = StringArgumentType.getString(ctx, "element").toLowerCase();
                                            return bindElement(ctx.getSource(), element);
                                        })
                                )
                        )

                        // /lily bind <ability> <slot>
                        .then(Commands.literal("bind")
                                .then(Commands.argument("ability", StringArgumentType.word())
                                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, 9))
                                                .executes(ctx -> {
                                                    String ability = StringArgumentType.getString(ctx, "ability");
                                                    int slot = IntegerArgumentType.getInteger(ctx, "slot");
                                                    LilyUtils.runCommandAsLily("b b " + ability + " " + slot);
                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("Bound " + ability + " to slot " + slot + " for " + BOT_NAME), false
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                        )

                        // /lily duel ... (subcommands)
                        .then(Commands.literal("duel")
                                .then(Commands.literal("easy")
                                        .executes(ctx -> startDuel(ctx.getSource(), "easy"))
                                )
                                .then(Commands.literal("medium").executes(ctx -> startDuel(ctx.getSource(), "medium"))
                                )
                                .then(Commands.literal("hard")
                                        .executes(ctx -> startDuel(ctx.getSource(), "hard"))
                                )
                                .then(Commands.literal("stop")
                                        .executes(ctx -> {

                                            if (LilyBridge.wsClient != null) {

                                                JsonObject msg = new JsonObject();
                                                msg.addProperty("type", "set_duel_target");
                                                msg.addProperty("target", "");
                                                msg.addProperty("difficulty", "");

                                                LilyUtils.broadcast(msg);
                                            }

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Duel ended for " + BOT_NAME),
                                                    false
                                            );

                                            return 1;
                                        })
                                )
                        )

                        // /lily stop (general stop movement, does NOT end duel)
                        .then(Commands.literal("stop")
                                .executes(ctx -> {
                                    LilyUtils.runCommand("player " + BOT_NAME + " stop");
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(BOT_NAME + " stopped moving."), false
                                    );
                                    return 1;
                                })
                        )
                        .then(Commands.literal("mode")
                                .then(Commands.literal("bendcraft")
                                        .executes(ctx -> {
                                            JsonObject msg = new JsonObject();
                                            msg.addProperty("type", "set_mode");
                                            msg.addProperty("mode", "bendcraft");
                                            LilyUtils.broadcast(msg);
                                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("[Lily] Mode set to bendcraft"), false);
                                            return 1;
                                        }))
                                .then(Commands.literal("survival")
                                        .executes(ctx -> {
                                            JsonObject msg = new JsonObject();
                                            msg.addProperty("type", "set_mode");
                                            msg.addProperty("mode", "survival");
                                            LilyUtils.broadcast(msg);
                                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("[Lily] Mode set to survival"), false);
                                            return 1;
                                        })))
                        // /lily follow <player>
                        .then(Commands.literal("follow")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String target = StringArgumentType.getString(ctx, "player");
                                            if (LilyBridge.wsClient != null) {
                                                JsonObject msg = new JsonObject();
                                                msg.addProperty("type", "set_follow_target");
                                                msg.addProperty("target", target);
                                                LilyUtils.broadcast(msg);
                                            }
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal(BOT_NAME + " will now follow " + target), false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        // /lily come  (follow the command sender)
                        .then(Commands.literal("come")
                                .executes(ctx -> {
                                    String caller = ctx.getSource().getTextName();
                                    if (LilyBridge.wsClient != null) {
                                        JsonObject msg = new JsonObject();
                                        msg.addProperty("type", "set_follow_target");
                                        msg.addProperty("target", caller);
                                        LilyUtils.broadcast(msg);
                                    }
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(BOT_NAME + " is coming to you!"), false
                                    );
                                    return 1;
                                })
                        )

                        // /lily status
//                        .then(Commands.literal("status")
//                                .executes(ctx -> {
//                                    if (LilyBridge.wsClient != null) {
//                                        JsonObject msg = new JsonObject();
//                                        msg.addProperty("type", "get_status");
//                                        LilyUtils.broadcast(msg);
//                                    }
//                                    ctx.getSource().sendSuccess(
//                                            () -> Component.literal("Requesting " + BOT_NAME + " status..."), false
//                                    );
//                                    return 1;
//                                })
//                        )
        );
    }


    private static int bindElement(CommandSourceStack source, String element) {
        String[][] bindings = switch (element) {
            case "fire" -> new String[][]{
                    {"1", "FireBall"}, {"2", "FireShots"}, {"3", "Lightning"},
                    {"4", "Discharge"}, {"5", "Combustion"}, {"6", "FireShield"},
                    {"7", "FireBlast"}, {"8", "FireJet"}, {"9", "WallOfFire"},
            };
            case "earth" -> new String[][]{
                    {"1", "EarthSmash"}, {"2", "Shockwave"}, {"3", "Catapult"},
                    {"4", "EarthWall"}, {"5", "LavaThrow"}, {"6", "LavaDisc"},
                    {"7", "EarthBlast"}, {"8", "EarthKick"}, {"9", "EarthArmor"},
            };

            case "air" -> new String[][]{
                    {"1", "AirBlade"}, {"2", "AirPunch"}, {"3", "AirSwipe"},
                    {"4", "AirBurst"}, {"5", "AirBreath"}, {"6", "AirShield"},
                    {"7", "Tornado"}, {"8", "AirBlast"}, {"9", "AirSuction"},
            };
            case "water" -> new String[][]{
                    {"1", "WaterManipulation"}, {"2", "Torrent"}, {"3", "AirSwipe"},
                    {"4", "IceSpike"}, {"5", "IceWall"}, {"6", "FrostBreath"},
                    {"7", "Surge"}, {"8", "WaterSpout"}, {"9", "PhaseChange"},
            };
            case "chi" -> new String[][]{
                    {"1", "QuckStrike"}, {"2", "SwiftKick"}, {"3", "SmokeScreen"},
                    {"4", "DaggerThrow"}, {"5", "BackStab"}, {"6", "RapidPunch"},
                    {"7", "Paralyze"}, {"8", "HighJump"}, {"9", "NinjaStance"},
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
                LilyUtils.runCommandAsLily("b b " + ability + " " + slot);
            });
            t.setDaemon(true);
            t.start();
        }

        source.sendSuccess(
                () -> Component.literal("Binding " + element + " abilities for " + BOT_NAME + "!"), false
        );

        // Notify Node.js
        if (LilyBridge.wsClient != null) {
            JsonObject res = new JsonObject();
            res.addProperty("type",    "element_changed");
            res.addProperty("element", element);
            LilyUtils.broadcast(res);
        }

        return 1;
    }

    private static int startDuel(CommandSourceStack source, String difficulty) {

        String executor = source.getTextName();

        if (LilyBridge.wsClient != null) {

            JsonObject msg = new JsonObject();

            msg.addProperty("type", "set_duel_target");
            msg.addProperty("target", executor);
            msg.addProperty("difficulty", difficulty);

            LilyUtils.broadcast(msg);
        }

        source.sendSuccess(
                () -> Component.literal(
                        "Lily will duel you on " + difficulty + " difficulty."
                ),
                false
        );

        return 1;
    }
}