package com.LilyBridge.util;

import com.LilyBridge.LilyBridge;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

public class LilyArmorManager {

    /**
     * If `picked` is armor and beats what's currently in that slot (or the
     * slot is empty), equips it and returns true. Caller should cancel the
     * pickup event in that case so the piece doesn't also land in her
     * general inventory.
     */
    public static boolean tryAutoEquip(ServerPlayer lily, ItemStack pickedUpStack) {
        if (!(pickedUpStack.getItem() instanceof ArmorItem armorItem)) return false;
        if (!(armorItem instanceof Equipable equipable)) return false;

        EquipmentSlot slot = equipable.getEquipmentSlot();
        ItemStack current = lily.getItemBySlot(slot);

        double newScore = armorScore(lily, pickedUpStack);
        double curScore = current.isEmpty() ? -1 : armorScore(lily, current);

        if (newScore <= curScore) return false;

        // Find and remove one matching piece from her inventory
        ItemStack toEquip = null;
        for (int i = 0; i < lily.getInventory().getContainerSize(); i++) {
            ItemStack stack = lily.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, pickedUpStack)) {
                toEquip = stack.split(1);
                break;
            }
        }
        if (toEquip == null) return false; // shouldn't happen, safety guard

        lily.setItemSlot(slot, toEquip);

        if (!current.isEmpty()) {
            if (!lily.getInventory().add(current)) {
                lily.drop(current, false);
            }
        }

        LilyBridge.LOGGER.info("[ARMOR] Lily equipped {} in slot {} (score {} > {})",
                toEquip.getItem(), slot, newScore, curScore);
        return true;
    }

    /**
     * Score = base defense (dominant factor) + toughness + a small nudge
     * for Protection level, so an enchanted piece of the same material
     * edges out an unenchanted one but never beats a materially better one.
     */
    private static double armorScore(ServerPlayer lily, ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem armorItem)) return -1;

        double defense   = armorItem.getDefense();
        double toughness = armorItem.getMaterial().value().toughness();
        double protection = protectionLevel(lily, stack);

        // Weighted so 1 point of defense always outweighs enchants/toughness
        // on a lesser material (defense * 10 keeps material tier dominant).
        return (defense * 10) + toughness + (protection * 0.5);
    }

    private static int protectionLevel(ServerPlayer lily, ItemStack stack) {
        Holder<Enchantment> protection = lily.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.PROTECTION);
        return EnchantmentHelper.getItemEnchantmentLevel(protection, stack);
    }
}