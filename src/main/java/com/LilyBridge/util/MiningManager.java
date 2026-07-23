package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Owns the FULL mining lifecycle: approach → face target → attack → poll for
 * break → chain to next block. Node only ever hears "mining_started" /
 * "block_broken" — it no longer drives movement or look_at for mining.
 *
 * Movement and attacking are never run concurrently. Concurrent attack+move
 * was the original bug: LilyTasks' arrival logic sends a blanket
 * "player <bot> stop" once within 1 block of the goal, which (Carpet fake
 * player semantics) also kills any in-progress attack. And while walking,
 * facing was driven by movement direction, fighting Node's look_at spam —
 * so an active attack mid-walk connected with whatever was actually in the
 * crosshair (e.g. grass on the path) instead of the intended block. Now:
 * walk with no attack held, arrive, stop, face precisely, THEN attack.
 */
public class MiningManager {
    private static final int    ATTACK_TIMEOUT_TICKS   = 200; // 10s once actually swinging
    private static final int    APPROACH_TIMEOUT_TICKS = 300; // 15s safety net for the whole walk-over
    private static final double REACH_DISTANCE         = 2.0; // must be this close before attacking
    private static final int    CHAIN_SEARCH_RADIUS    = 32;
    private static final int    FACE_SETTLE_TICKS      = 4;   // let rotation register before swinging

    private enum Phase { IDLE, APPROACHING, FACING, ATTACKING }

    private static volatile Phase    phase     = Phase.IDLE;
    private static volatile BlockPos target    = null;
    private static volatile int      ticks     = 0;
    private static volatile String   blockName = null;
    private static volatile int      remaining = 0;

    public static void mine(BlockPos pos, String blockNameForChaining, int amount) {
        if (phase != Phase.IDLE) {
            LilyBridge.LOGGER.warn("[MINE] Already mining {} — ignoring new target {}", target, pos);
            return;
        }
        blockName = blockNameForChaining;
        remaining = Math.max(0, amount - 1);
        beginApproach(pos);
    }

    public static void cancel() {
        if (phase == Phase.IDLE) return;
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " attack stop");
        LilyTasks.stopAllMovement();
        phase = Phase.IDLE;
        target = null;
        ticks = 0;
        blockName = null;
        remaining = 0;
    }

    public static boolean isMining() {
        return phase != Phase.IDLE;
    }

    /** Called every server tick regardless of phase. */
    public static void tick(ServerPlayer lily) {
        if (phase == Phase.IDLE) return;
        ticks++;

        if (phase == Phase.APPROACHING) {
            if (ticks >= APPROACH_TIMEOUT_TICKS) {
                LilyBridge.LOGGER.warn("[MINE] Gave up walking to {} — stuck or unreachable", target);
                LilyTasks.stopAllMovement();
                finish(false, lily);
            }
            return; // arrival is reported via onArrived(), not polled here
        }

        if (phase == Phase.FACING) {
            if (ticks >= FACE_SETTLE_TICKS) beginAttack();
            return;
        }

        // phase == ATTACKING
        ServerLevel level = (ServerLevel) lily.level();
        BlockState state = level.getBlockState(target);

        if (state.isAir()) {
            finish(true, lily);
            return;
        }
        if (ticks >= ATTACK_TIMEOUT_TICKS) {
            LilyBridge.LOGGER.warn("[MINE] Timed out breaking block at {} — giving up (wrong/missing tool?)", target);
            finish(false, lily);
        }
    }

    private static void beginApproach(BlockPos pos) {
        ServerPlayer lily = LilyUtils.getLilyServerPlayer();
        if (lily == null) { phase = Phase.IDLE; return; }

        target = pos;
        ticks = 0;

        JsonObject started = new JsonObject();
        started.addProperty("type", "mining_started");
        started.addProperty("x", pos.getX());
        started.addProperty("y", pos.getY());
        started.addProperty("z", pos.getZ());
        LilyUtils.broadcast(started);

        LilyUtils.equipBestToolFor(pos);

        double dist = lily.position().distanceTo(
                new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));

        if (dist <= REACH_DISTANCE) {
            beginFacing();
        } else {
            // ROOT CAUSE FIX: without this, she's still facing wherever the
            // last block she mined was (beginFacing() locked her yaw onto
            // it) — an essentially random direction relative to THIS block.
            // The pathfinder computes forward/back/left/right relative to
            // her current facing, so she'd often need to walk "back" or
            // "left" just to reach a block that's actually straight ahead
            // of a sane approach. Facing the destination first keeps
            // "forward" meaningful, which also matters for shouldJump()
            // below (it checks obstacles in her look direction).
            LilyUtils.runCommand(String.format("player %s look at %.4f %.4f %.4f",
                    LilyBridge.BOT_NAME, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            phase = Phase.APPROACHING;
            // ROOT CAUSE FIX: the walk target here is the CENTER of a solid
            // block (the one we're about to mine) — she can never stand inside
            // it. LilyTasks used to only ever consider "arrived" at distance
            // < 1.0, which is right at (or past) the closest she can physically
            // get from an adjacent tile, so arrival could fail to fire depending
            // on approach angle, leaving her walking the block's face until the
            // 15s APPROACH_TIMEOUT_TICKS gave up. Use the same REACH_DISTANCE
            // we already trust for "close enough to skip walking" above.
            LilyTasks.startMoveTo(pos.getX() + 0.5, pos.getZ() + 0.5, REACH_DISTANCE, MiningManager::onArrived);
        }
    }

    /** LilyTasks callback — fires once movement has actually stopped near the target. */
    private static void onArrived() {
        if (phase != Phase.APPROACHING) return; // cancelled/superseded meanwhile
        beginFacing();
    }

    private static void beginFacing() {
        phase = Phase.FACING;
        ticks = 0;
        if (target == null) return;
        LilyUtils.runCommand(String.format("player %s look at %.4f %.4f %.4f",
                LilyBridge.BOT_NAME, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
    }

    private static void beginAttack() {
        phase = Phase.ATTACKING;
        ticks = 0;
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " attack continue");
    }

    private static void finish(boolean broken, ServerPlayer lily) {
        BlockPos pos = target;
        LilyUtils.runCommand("player " + LilyBridge.BOT_NAME + " attack stop");
        phase = Phase.IDLE;
        target = null;
        ticks = 0;

        BlockPos next = null;
        if (broken && remaining > 0 && blockName != null) {
            next = BlockFinder.findClosestBlock(lily, blockName, CHAIN_SEARCH_RADIUS);
        }

        JsonObject res = new JsonObject();
        res.addProperty("type", "block_broken");
        res.addProperty("broken", broken);
        if (pos != null) {
            res.addProperty("x", pos.getX());
            res.addProperty("y", pos.getY());
            res.addProperty("z", pos.getZ());
        }

        if (next != null) {
            remaining--;
            res.addProperty("done", false);
            res.addProperty("nextX", next.getX());
            res.addProperty("nextY", next.getY());
            res.addProperty("nextZ", next.getZ());
            LilyUtils.broadcast(res);
            beginApproach(next); // chain step now goes through the same approach→face→attack flow
            return;
        }

        res.addProperty("done", true);
        blockName = null;
        remaining = 0;
        LilyUtils.broadcast(res);
    }
}