package ru.altergen.virus;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Рецепт и обработка настоящего лечебного отвара. */
public final class MedicineListener implements Listener {

    private final VirusManager virusManager;
    private final NamespacedKey medicineKey;

    public MedicineListener(VirusManager virusManager, NamespacedKey medicineKey) {
        this.virusManager = virusManager;
        this.medicineKey = medicineKey;
    }

    public static ShapelessRecipe createRecipe(NamespacedKey key) {
        ShapelessRecipe recipe = new ShapelessRecipe(key, createMedicine(key));
        recipe.addIngredient(Material.SUSPICIOUS_STEW);
        recipe.addIngredient(Material.GLOWSTONE_DUST);
        recipe.addIngredient(Material.GHAST_TEAR);
        return recipe;
    }

    public static ItemStack createMedicine(NamespacedKey key) {
        ItemStack item = new ItemStack(Material.SUSPICIOUS_STEW);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(ChatColor.GOLD + "Лечебный отвар");
        meta.setLore(List.of(ChatColor.GRAY + "Убивает любой вирус"));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!isMedicine(event.getItem())) {
            return;
        }

        Player player = event.getPlayer();
        if (!virusManager.cure(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Вы не заражены вирусом.");
        }
    }

    private boolean isMedicine(ItemStack item) {
        if (item == null || item.getType() != Material.SUSPICIOUS_STEW || !item.hasItemMeta()) {
            return false;
        }

        Byte marker = item.getItemMeta()
                .getPersistentDataContainer()
                .get(medicineKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }
}
