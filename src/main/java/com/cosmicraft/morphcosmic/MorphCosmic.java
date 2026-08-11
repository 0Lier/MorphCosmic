package com.cosmicraft.morphcosmic;

import org.bukkit.plugin.java.JavaPlugin;

public final class MorphCosmic extends JavaPlugin {

    private MorphManager morphManager;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib no está instalado. Este plugin lo necesita para funcionar. Deshabilitando...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.morphManager = new MorphManager(this);

        getCommand("morph").setExecutor(new MorphCommand(this));
        getServer().getPluginManager().registerEvents(new MorphListener(this), this);

        getLogger().info("[DifficultyCosmic] Plugin habilitado correctamente.");
    }

    @Override
    public void onDisable() {
        if (morphManager != null) {
            morphManager.cleanupAll();
        }
        getLogger().info("[DifficultyCosmic] Plugin deshabilitado.");
    }

    public MorphManager getMorphManager() {
        return morphManager;
    }
}