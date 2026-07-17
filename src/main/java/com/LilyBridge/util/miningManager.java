package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.LilyBridge.util.LilyUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

public class miningManager {
    private static final int SWING_INTERVAL_TICKS = 6;
    private static final int MINE_DURATION_TICKS = 30;

    private static BlockPos target = null;
    private static int ticks = 0;

    public static void mine(BlockPos pos) {
        target = pos;
        ticks = 0;
    }

    public void cancel() {
        target = null;
        ticks = 0;
    }

    public static void tick(ServerPlayer lily) {
        if (target == null) return;

        if (ticks % SWING_INTERVAL_TICKS == 0) lily.swing(InteractionHand.MAIN_HAND, true);
        ticks++;

        if (ticks >= MINE_DURATION_TICKS) {
            lily.gameMode.destroyBlock(target);
            target = null;
            ticks = 0;
        }
    }
}