package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Handles block breaking via a real, continuous "attack" input (the FakePlayer
 * equivalent of holding down left-click) instead of a scripted fixed-duration break.
 * Break speed is whatever the game actually computes from the block's hardness and
 * the tool Lily is holding (equipped beforehand — see LilyUtils.equipBestToolFor) —
 * not a guessed timer.
 *
 * Only one target is tracked at a time. Once mine() starts a target, it doesn't stop
 * or switch targets on its own — mine() ignores new targets while one is already in
 * progress — it just keeps holding attack and polling the block's state every tick
 * until the block is actually gone, then reports back to Node via a "block_broken"
 * broadcast. A tick-count timeout is kept purely as a safety net (wrong/missing tool,
 * unbreakable block, obstruction) so a bad target can't hang the bridge forever; it
 * still reports back in that case, just with broken:false.
 */
public class MiningManager {

    /** Safety-net cap on how long a single block is allowed to take before giving up. */
    private static final int TIMEOUT_TICKS = 200; // 10s

    private static volatile BlockPos target = null;
    private static volatile int ticks = 0;

    /** Starts mining `pos`. Ignored if already mining something — see class doc. */
    public static void mine(BlockPos pos) {
        if (target != null) {
            LilyBridge.LOGGER.warn("[MINE] Already mining {} — ignoring new target {}", target, pos);
            return;
        }
        target = pos;
        ticks = 0;
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " attack continue");
    }

    /** Stops mining immediately without reporting a block_broken event — used when
     *  something else interrupts (e.g. Node leaving the MINING state for combat). */
    public static void cancel() {
        if (target == null) return;
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " attack stop");
        target = null;
        ticks = 0;
    }

    public static boolean isMining() {
        return target != null;
    }

    /** Called every server tick — same driver that called the old miningManager.tick(). */
    public static void tick(ServerPlayer lily) {
        if (target == null) return;

        ServerLevel level = (ServerLevel) lily.level();
        BlockState state  = level.getBlockState(target);

        if (state.isAir()) {
            finish(true);
            return;
        }

        ticks++;
        if (ticks >= TIMEOUT_TICKS) {
            LilyBridge.LOGGER.warn("[MINE] Timed out breaking block at {} — giving up (wrong/missing tool?)", target);
            finish(false);
        }
    }

    private static void finish(boolean broken) {
        BlockPos pos = target;
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " attack stop");
        target = null;
        ticks  = 0;

        JsonObject res = new JsonObject();
        res.addProperty("type", "block_broken");
        res.addProperty("broken", broken);
        if (pos != null) {
            res.addProperty("x", pos.getX());
            res.addProperty("y", pos.getY());
            res.addProperty("z", pos.getZ());
        }
        LilyUtils.broadcast(res);
    }
}