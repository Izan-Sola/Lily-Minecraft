package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleSneakEvent;

public class BukkitHelper {

    public static Player getLilyBukkit() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equals(LilyBridge.BOT_NAME)) {
                return p;
            }
        }

        return null;
    }

    public static void scheduleSneakState(Player player, boolean sneaking) {

        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {

                    PlayerToggleSneakEvent event =
                            new PlayerToggleSneakEvent(player, sneaking);

                    Bukkit.getPluginManager().callEvent(event);

                    if (!event.isCancelled()) {
                        player.setSneaking(sneaking);
                    }
                }
        );
    }
}