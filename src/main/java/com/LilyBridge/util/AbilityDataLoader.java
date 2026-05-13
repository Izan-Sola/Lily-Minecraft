package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AbilityDataLoader {

    private static final Map<String, AbilityInfo> ABILITY_CACHE = new HashMap<>();
    private static boolean loaded = false;

    public static class AbilityInfo {
        public final double range;
        public final long cooldown;
        public AbilityInfo(double range, long cooldown) {
            this.range = range;
            this.cooldown = cooldown;
        }
    }

    public static void loadAll() {
        if (loaded) return;
        loadProjectKorraAbilities();
        loadJedCoreAbilities();
        loaded = true;
        LilyBridge.LOGGER.info("[AbilityData] Loaded " + ABILITY_CACHE.size() + " abilities from configs");
    }

    private static void loadProjectKorraAbilities() {
        File configFile = new File("plugins/ProjectKorra/config.yml");
        if (!configFile.exists()) {
            LilyBridge.LOGGER.warn("[AbilityData] ProjectKorra config.yml not found");
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        if (!config.contains("Abilities")) return;
        scanForAbilities(config.getConfigurationSection("Abilities"), "");
    }

    private static void loadJedCoreAbilities() {
        File configFile = new File("plugins/JedCore/config.yml");
        if (!configFile.exists()) {
            LilyBridge.LOGGER.warn("[AbilityData] JedCore config.yml not found");
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        if (!config.contains("Abilities")) return;
        scanForAbilities(config.getConfigurationSection("Abilities"), "");
    }

    private static void scanForAbilities(ConfigurationSection section, String parentPath) {
        if (section == null) return;
        Set<String> keys = section.getKeys(false);
        for (String key : keys) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection) {
                ConfigurationSection sub = (ConfigurationSection) value;
                // Look for Range or Cooldown – that identifies an ability leaf
                if (sub.contains("Range") || sub.contains("Cooldown")) {
                    double range = sub.getDouble("Range", 10.0);
                    long cooldown = sub.getLong("Cooldown", 0L);
                    ABILITY_CACHE.put(key, new AbilityInfo(range, cooldown));
                    LilyBridge.LOGGER.debug("[AbilityData] Found ability: " + key + " range=" + range + " cooldown=" + cooldown);
                } else {
                    // Recurse deeper (handles element sections, combo subgroups, etc.)
                    scanForAbilities(sub, parentPath.isEmpty() ? key : parentPath + "." + key);
                }
            }
        }
    }

    public static void sendAbilityDataToNode() {
        if (LilyBridge.wsClient == null) {
            LilyBridge.LOGGER.warn("[AbilityData] WebSocket not available");
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "ability_data");
        JsonObject abilitiesJson = new JsonObject();
        for (Map.Entry<String, AbilityInfo> entry : ABILITY_CACHE.entrySet()) {
            JsonObject ab = new JsonObject();
            ab.addProperty("range", entry.getValue().range);
            ab.addProperty("cooldown", entry.getValue().cooldown);
            abilitiesJson.add(entry.getKey(), ab);
        }
        msg.add("abilities", abilitiesJson);
        LilyUtils.broadcast(msg);
        LilyBridge.LOGGER.info("[AbilityData] Sent " + ABILITY_CACHE.size() + " ability stats to Node.js");
    }

    public static AbilityInfo getAbilityInfo(String name) {
        return ABILITY_CACHE.get(name);
    }
}