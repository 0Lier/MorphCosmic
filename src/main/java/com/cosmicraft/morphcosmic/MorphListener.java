package com.cosmicraft.morphcosmic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class MorphListener implements Listener {

    private final MorphCosmic plugin;

    public MorphListener(MorphCosmic plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.getMorphManager().isMorphed(player)) {
            plugin.getMorphManager().unmorph(player);
        }
    }

    /**
     * Si alguien se conecta mientras hay jugadores morfeados, hay que
     * hacer que también los vea como mobs (si no, los vería normales
     * hasta que salgan de su rango de visión y vuelvan a entrar).
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getMorphManager().refreshForViewer(event.getPlayer());
    }
}