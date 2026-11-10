package ru.altergen.virus;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Точка входа плагина.
 */
public final class VirusPlugin extends JavaPlugin {

    private VirusManager virusManager;
    private NamespacedKey medicineKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        medicineKey = new NamespacedKey(this, "medicine_stew");
        virusManager = new VirusManager(this);
        virusManager.load();

        Bukkit.getPluginManager().registerEvents(
                new InfectionListener(virusManager), this
        );
        Bukkit.getPluginManager().registerEvents(
                new MedicineListener(virusManager, medicineKey), this
        );

        VirusCommand virusCommand = new VirusCommand(virusManager);
        if (getCommand("virus") != null) {
            getCommand("virus").setExecutor(virusCommand);
            getCommand("virus").setTabCompleter(virusCommand);
        }

        registerMedicineRecipe();
        virusManager.startTasks();
        getLogger().info("VirusPlugin включен.");
    }

    @Override
    public void onDisable() {
        if (virusManager != null) {
            virusManager.shutdown();
        }
        getLogger().info("VirusPlugin выключен.");
    }

    private void registerMedicineRecipe() {
        // Важно при /reload, чтобы не осталось старой версии рецепта.
        Bukkit.removeRecipe(medicineKey);
        Bukkit.addRecipe(MedicineListener.createRecipe(medicineKey));
    }
}
