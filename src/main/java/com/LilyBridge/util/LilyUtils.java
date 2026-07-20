package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.LilyBridge.LilyBridge.LOGGER;

public class LilyUtils {
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    /**
     * Short alias → full Minecraft registry ID.
     * Combo authors write e.g. "grass" or "cobble"; this resolves the real name.
     * Full IDs (containing ":") are passed through unchanged.
     */
    private static final java.util.Map<String, String> BLOCK_ALIASES = Map.of(
            "dirt",       "minecraft:dirt",
            "grass",      "minecraft:grass_block",
            "sand",       "minecraft:sand",
            "stone",      "minecraft:stone",
            "cobble",     "minecraft:cobblestone",
            "cobblestone","minecraft:cobblestone",
            "gravel",     "minecraft:gravel",
            "water",      "minecraft:water",
            "lava",       "minecraft:lava",
            "ice",        "minecraft:ice"
    );

    /** All blocks recognised as valid sources when no filter is specified. */
    private static final Set<String> DEFAULT_SOURCE_BLOCKS = Set.of(
            "minecraft:grass_block", "minecraft:sand", "minecraft:dirt",
            "minecraft:cobblestone", "minecraft:stone", "minecraft:gravel"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // BLOCKS OF INTEREST — full registry ID → friendly category.
    // Add/remove entries here to change what the environment scan reports;
    // nothing else needs to change.
    // ─────────────────────────────────────────────────────────────────────────
    private static final Map<String, String> BLOCKS_OF_INTEREST = buildBlocksOfInterest();

    private static Map<String, String> buildBlocksOfInterest() {
        Map<String, String> m = new HashMap<>();

        // Wood (logs/stems)
        for (String log : new String[]{
                "oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log",
                "dark_oak_log", "mangrove_log", "cherry_log", "crimson_stem", "warped_stem"
        }) {
            m.put("minecraft:" + log, "wood");
        }

        // Ores (overworld + deepslate variants)
        for (String ore : new String[]{
                "coal_ore", "deepslate_coal_ore",
                "iron_ore", "deepslate_iron_ore",
                "copper_ore", "deepslate_copper_ore",
                "gold_ore", "deepslate_gold_ore",
                "redstone_ore", "deepslate_redstone_ore",
                "lapis_ore", "deepslate_lapis_ore",
                "diamond_ore", "deepslate_diamond_ore",
                "emerald_ore", "deepslate_emerald_ore",
                "ancient_debris"
        }) {
            m.put("minecraft:" + ore, "ore");
        }

        // Food sources
        m.put("minecraft:sweet_berry_bush", "food");
        m.put("minecraft:melon",            "food");
        m.put("minecraft:pumpkin",          "food");
        m.put("minecraft:wheat",            "food");
        m.put("minecraft:carrots",          "food");
        m.put("minecraft:potatoes",         "food");

        return m;
    }

    public static Player getLilyBukkit() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equals(LilyBridge.BOT_NAME)) return p;
        }
        return null;
    }

    public static ServerPlayer getLilyServerPlayer() {
        if (LilyBridge.mcServer == null) return null;
        for (ServerPlayer p : LilyBridge.mcServer.getPlayerList().getPlayers()) {
            if (p.getName().getString().equals(LilyBridge.BOT_NAME)) return p;
        }
        return null;
    }

    private static String oppositeDirection(String dir) {
        return switch (dir) {
            case "forward" -> "back";
            case "back"    -> "forward";
            case "left"    -> "right";
            case "right"   -> "left";
            default        -> "stop";
        };
    }

    public static boolean isSafeBlock(ServerLevel level, double x, double y, double z, String direction, Vec3 look) {
        double nx = x, nz = z;
        switch (direction) {
            case "forward" -> { nx = x + look.x; nz = z + look.z; }
            case "back"    -> { nx = x - look.x; nz = z - look.x; }
            case "left"    -> { nx = x - look.z; nz = z + look.x; }
            case "right"   -> { nx = x + look.z; nz = z - look.x; }
        }

        for (int dy = -6; dy <= 6; dy++) {
            BlockPos pos = BlockPos.containing(nx, y + dy, nz);
            if (!level.getFluidState(pos).isEmpty()) return false;
        }

        boolean groundFound = false;
        for (int dy = 1; dy <= 6; dy++) {
            BlockPos pos = BlockPos.containing(nx, y - dy, nz);
            if (!level.getBlockState(pos).isAir()) {
                groundFound = true;
                break;
            }
        }
        return groundFound;
    }

    public static void broadcast(JsonObject msg) {
        if (LilyBridge.wsClient == null || !LilyBridge.wsClient.isOpen()) return;
        LilyBridge.wsClient.send(LilyBridge.GSON.toJson(msg));
    }

    public static void broadcast(String type, String... keyValues) {
        if (LilyBridge.wsClient == null || !LilyBridge.wsClient.isOpen()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            msg.addProperty(keyValues[i], keyValues[i + 1]);
        }
        LilyBridge.wsClient.send(LilyBridge.GSON.toJson(msg));
    }

    public static void runCommand(String command) {
        if (LilyBridge.mcServer == null) return;
        LilyBridge.mcServer.execute(() ->
                LilyBridge.mcServer.getCommands().performPrefixedCommand(
                        LilyBridge.mcServer.createCommandSourceStack().withMaximumPermission(4),
                        command
                )
        );
    }

    public static void runCommandAsLily(String command) {
        if (LilyBridge.mcServer == null) return;
        LilyBridge.mcServer.execute(() -> {
            for (ServerPlayer p : LilyBridge.mcServer.getPlayerList().getPlayers()) {
                if (!p.getName().getString().equals(LilyBridge.BOT_NAME)) continue;
                LilyBridge.mcServer.getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withMaximumPermission(4),
                        command
                );
                break;
            }
        });
    }

    public static void scheduleCommand(int delayTicks, String command) {
        if (LilyBridge.mcServer == null) return;
        long delayMs = delayTicks * 50L;
        SCHEDULER.schedule(() -> {
            LilyBridge.mcServer.execute(() -> runCommand(command));
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public static void scheduleSneakState(Player player, boolean sneaking) {
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugins()[0],
                () -> {
                    PlayerToggleSneakEvent event = new PlayerToggleSneakEvent(player, sneaking);
                    Bukkit.getPluginManager().callEvent(event);
                    if (!event.isCancelled()) player.setSneaking(sneaking);
                }
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENVIRONMENT AWARENESS — entity scan + blocks-of-interest scan.
    // Both are capped and sorted nearest-first so a busy area never floods
    // the result (or, downstream, the LLM prompt) regardless of how much is
    // actually around.
    // ─────────────────────────────────────────────────────────────────────────
// Replaces the old scanHotbar(ServerPlayer) method in LilyUtils.java.
// Same 1-based slot numbering used everywhere else in the codebase (1-9 hotbar,
// 10-36 main inventory), but now covers the whole 36-slot inventory instead of
// just the hotbar, and skips empty slots entirely instead of sending "empty" —
// keeps the payload small since most of a 36-slot inventory is usually empty.
    public static JsonObject scanInventory(ServerPlayer lily) {
        JsonObject inventory = new JsonObject();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = lily.getInventory().getItem(slot);
            if (stack.isEmpty()) continue; // ignorado, no se envía
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            String display = stack.getCount() > 1 ? id + " x" + stack.getCount() : id;
            inventory.addProperty(String.valueOf(slot + 1), display);
        }
        return inventory;
    }
    /**
     * Returns up to {@code limit} entities (any type — players, mobs, items,
     * etc.) within {@code radius} blocks of Lily, nearest first. Lily herself
     * is excluded.
     */
    public static JsonArray scanNearbyEntities(ServerPlayer lily, double radius, int limit) {
        ServerLevel level = (ServerLevel) lily.level();

        List<Entity> found = new ArrayList<>(level.getEntities(lily, lily.getBoundingBox().inflate(radius)));
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(lily)));

        JsonArray arr = new JsonArray();
        int count = 0;
        for (Entity e : found) {
            if (!(e instanceof LivingEntity)) continue;
            if (count >= limit) break;

            JsonObject o = new JsonObject();
            o.addProperty("type", e.getType().toShortString());
            o.addProperty("id", e.getId());
            o.addProperty("x", e.getX());
            o.addProperty("y", e.getY());
            o.addProperty("z", e.getZ());

            if (e instanceof ServerPlayer sp) {
                o.addProperty("name", sp.getName().getString());
            }
            if (e instanceof Enemy) {
                o.addProperty("hostile", true);
            }

            arr.add(o);
            count++;
        }
        return arr;
    }

    /**
     * Scans a circular area around Lily (radius in blocks, horizontal) within
     * a vertical band relative to her position — heightBelowFeet blocks below
     * her feet up through heightAboveHead blocks above her head — for any
     * block registered in {@link #BLOCKS_OF_INTEREST}. Returns up to
     * {@code limit} hits, nearest first.
     */
    public static JsonArray scanNearbyBlocksOfInterest(
            ServerPlayer lily, int radius, int heightAboveHead, int heightBelowFeet, int limit) {

        ServerLevel level = (ServerLevel) lily.level();
        BlockPos feet = lily.blockPosition();

        int minY = feet.getY() - heightBelowFeet;
        int maxY = feet.getY() + 1 + heightAboveHead; // +1 accounts for the head block

        List<JsonObject> hits = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue; // circular scan, not square

                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(feet.getX() + dx, y, feet.getZ() + dz);

                    String id = level.getBlockState(pos).getBlockHolder()
                            .unwrapKey().map(k -> k.location().toString()).orElse("");

                    String category = BLOCKS_OF_INTEREST.get(id);
                    if (category == null) continue;

                    JsonObject o = new JsonObject();
                    o.addProperty("block", id.replace("minecraft:", ""));
                    o.addProperty("category", category);
                    o.addProperty("x", pos.getX());
                    o.addProperty("y", pos.getY());
                    o.addProperty("z", pos.getZ());
                    hits.add(o);
                }
            }
        }

        hits.sort(Comparator.comparingDouble(o -> feet.distSqr(
                new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()))));

        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) arr.add(hits.get(i));
        return arr;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SOURCE BLOCK SEARCH
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Finds the nearest valid source block in front of Lily.
     *
     * @param lily          The bot's ServerPlayer instance.
     * @param allowedBlocks Block IDs to accept (e.g. ["minecraft:water", "minecraft:ice"]).
     *                      Pass null or an empty collection to use the default set.
     */
    /** Default max forward distance when none is specified. */
    private static final int DEFAULT_SOURCE_DISTANCE = 8;

    public static BlockPos findSourceBlock(ServerPlayer lily, Collection<String> allowedBlocks, int maxDistance) {
        ServerLevel level = (ServerLevel) lily.level();
        BlockPos feet = lily.blockPosition();
        Vec3 look = lily.getLookAngle();

        double flatLen = Math.sqrt(look.x * look.x + look.z * look.z);
        if (flatLen < 1e-4) return null;

        double fx = look.x / flatLen;
        double fz = look.z / flatLen;

        final int scanDist = maxDistance > 0 ? maxDistance : DEFAULT_SOURCE_DISTANCE;

        // Determine which set to match against
        final Set<String> validBlocks;
        if (allowedBlocks == null || allowedBlocks.isEmpty()) {
            validBlocks = DEFAULT_SOURCE_BLOCKS;
        } else {
            Set<String> resolved = new java.util.HashSet<>();
            for (String b : allowedBlocks) {
                if (b.contains(":")) {
                    resolved.add(b);
                } else {
                    String mapped = BLOCK_ALIASES.get(b);
                    if (mapped != null) {
                        resolved.add(mapped);
                    } else {
                        LOGGER.warn("[Source] Unknown block alias '{}' — skipped", b);
                    }
                }
            }
            validBlocks = resolved;
        }

        BlockPos best = null;
        double bestDist = 0;

        for (int forward = 1; forward <= scanDist; forward++) {
            for (int side = -1; side <= 1; side++) {

                double sx = -fz * side;
                double sz =  fx * side;

                int bx = (int) Math.floor(feet.getX() + fx * forward + sx);
                int bz = (int) Math.floor(feet.getZ() + fz * forward + sz);

                BlockPos pos = new BlockPos(bx, feet.getY() - 1, bz);

                String id = level.getBlockState(pos).getBlockHolder()
                        .unwrapKey().map(k -> k.location().toString()).orElse("");

                if (!validBlocks.contains(id)) continue;

                if (level.getBlockState(pos.above()).isSolid()) continue;

                double dist = feet.distSqr(pos);

                if (dist > bestDist) {
                    bestDist = dist;
                    best = pos;
                }
            }
        }

        return best;
    }

    /** Convenience overload — uses the default block set and default distance. */
    public static BlockPos findSourceBlock(ServerPlayer lily) {
        return findSourceBlock(lily, null, 0);
    }

    /** Convenience overload — uses the default distance. */
    public static BlockPos findSourceBlock(ServerPlayer lily, Collection<String> allowedBlocks) {
        return findSourceBlock(lily, allowedBlocks, 0);
    }

    // ─── Tool selection ─────────────────────────────────────────────────────────────

    /**
     * Ranks a {@link Tier} by mining quality. NeoForge 1.21.1 removed
     * {@code Tier#getLevel()} (Mojang dropped the numeric "harvest level"
     * system in favor of tag-based tool requirements), so there's no single
     * built-in ordinal anymore.
     *
     * Vanilla tiers get an explicit rank via identity comparison against
     * {@link Tiers}. Any other tier (modded tools) falls back to a rank
     * derived from {@link Tier#getSpeed()}, scaled down so it always sorts
     * below WOOD unless it's a genuinely fast modded tool — keeps custom
     * tiers comparable without needing to know about every modpack's tools.
     */
    private static int toolRank(Tier tier) {
        if (tier == Tiers.NETHERITE) return 5;
        if (tier == Tiers.DIAMOND)   return 4;
        if (tier == Tiers.IRON)      return 3;
        if (tier == Tiers.STONE)     return 2;
        if (tier == Tiers.WOOD)      return 1;
        if (tier == Tiers.GOLD)      return 1; // fast but fragile — treat as low tier

        // Unknown/modded tier: approximate a rank from mining speed so it
        // still slots in somewhere sensible relative to the vanilla tiers.
        return (int) Math.floor(tier.getSpeed() / 2.0);
    }

    /**
     * Picks the best tool in Lily's whole inventory for the given block and switches to
     * it (via switchToSlot) if one is found. "Best" means highest tool rank among
     * matching tools (see {@link #toolRank(Tier)}) — enchantments aren't considered.
     *
     * Blocks tagged as needing a pickaxe/axe/shovel are matched to that tool type via
     * BlockTags; that also naturally covers "dirt/sand can be hand-mined but use a
     * shovel if she has one" without a special case, since dirt/sand are themselves
     * tagged MINEABLE_WITH_SHOVEL — shovel is preferred if owned, otherwise this method
     * finds no match and does nothing, leaving her mining bare-handed. Blocks that need
     * no specific tool at all are also left alone.
     */
    public static void equipBestToolFor(BlockPos pos) {
        ServerPlayer lily = getLilyServerPlayer();
        if (lily == null) return;

        BlockState state = lily.level().getBlockState(pos);
        Class<? extends Item> requiredType = requiredToolType(state);
        if (requiredType == null) return; // no specific tool needed for this block

        int bestSlot  = -1;
        int bestRank  = Integer.MIN_VALUE;

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = lily.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (!requiredType.isInstance(item)) continue;

            int rank = (item instanceof TieredItem tiered) ? toolRank(tiered.getTier()) : 0;
            if (rank > bestRank) {
                bestRank = rank;
                bestSlot = slot;
            }
        }

        if (bestSlot == -1) return; // doesn't own a matching tool — mine with whatever's held

        switchToSlot(bestSlot + 1); // +1: inventory here is 0-based, switchToSlot is 1-based
    }

    private static Class<? extends Item> requiredToolType(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return PickaxeItem.class;
        if (state.is(BlockTags.MINEABLE_WITH_AXE))     return AxeItem.class;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL))  return ShovelItem.class;
        return null;
    }

// ─── Inventory slot handling ────────────────────────────────────────────────
// (Relocated from LilyCommandHandler — this is where slot-swap consumers other
//  than command handling, like equipBestToolFor above, can reach it too.)

    /**
     * Switches Lily's held hotbar slot to whatever item lives at `slot` — a 1-based
     * index across her whole inventory (1-9 = hotbar, 10-36 = main inventory, i.e.
     * Bukkit's PlayerInventory indices 0-35 offset by one to match the existing
     * "hotbar 1-9" convention). If the item isn't already in the hotbar, it's swapped
     * into hotbar slot 1 first — displacing whatever was there into the now-vacated
     * inventory slot — and Lily switches to slot 1 instead.
     */
    public static void switchToSlot(int slot) {
        Player lilyBukkit = getLilyBukkit();
        if (lilyBukkit == null) return;

        int hotbarSlot = resolveHotbarSlot(lilyBukkit, slot);

        runCommand("player " + LilyBridge.BOT_NAME + " hotbar " + hotbarSlot);

        int newSlot  = hotbarSlot - 1;
        int prevSlot = lilyBukkit.getInventory().getHeldItemSlot();
        org.bukkit.event.player.PlayerItemHeldEvent heldEvent =
                new org.bukkit.event.player.PlayerItemHeldEvent(lilyBukkit, prevSlot, newSlot);
        org.bukkit.Bukkit.getPluginManager().callEvent(heldEvent);
        if (!heldEvent.isCancelled()) lilyBukkit.getInventory().setHeldItemSlot(newSlot);
    }

    /**
     * Resolves an arbitrary 1-based inventory slot to a hotbar slot (1-9), swapping
     * the item into hotbar slot 1 first if it currently lives in the main inventory.
     */
    private static int resolveHotbarSlot(Player lily, int slot) {
        if (slot < 1 || slot > 36) {
            LOGGER.warn("[INVENTORY] Slot {} out of range (expected 1-36), leaving current slot", slot);
            return lily.getInventory().getHeldItemSlot() + 1;
        }

        if (slot <= 9) {
            return slot; // ya está en la hotbar
        }

        int mainInvIndex = slot - 1;
        org.bukkit.inventory.PlayerInventory inv = lily.getInventory();

        final int scratchHotbarSlot = 1; // siempre se intercambia a través de la hotbar 1
        org.bukkit.inventory.ItemStack targetItem = inv.getItem(mainInvIndex);
        org.bukkit.inventory.ItemStack hotbarItem = inv.getItem(scratchHotbarSlot - 1);

        inv.setItem(mainInvIndex, hotbarItem);
        inv.setItem(scratchHotbarSlot - 1, targetItem);

        LOGGER.info("[INVENTORY] Swapped slot {} into hotbar slot {}", slot, scratchHotbarSlot);
        return scratchHotbarSlot;
    }
}