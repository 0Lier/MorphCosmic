package com.cosmicraft.morphcosmic;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MorphCommand implements CommandExecutor, TabCompleter {

    private final MorphCosmic plugin;
    private final List<String> mobNames = new ArrayList<>();

    public MorphCommand(MorphCosmic plugin) {
        this.plugin = plugin;

        for (EntityType type : EntityType.values()) {
            if (isValidMob(type)) {
                mobNames.add(type.name().toLowerCase());
            }
        }
        mobNames.add("off");
    }

    /**
     * Filtra únicamente los EntityType que son mobs vivos disguisables
     * (excluye jugadores, entidades no vivas como flechas/items/armor stand, etc.)
     */
    public static boolean isValidMob(EntityType type) {
        return type != EntityType.PLAYER
                && type.getEntityClass() != null
                && LivingEntity.class.isAssignableFrom(type.getEntityClass());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo un jugador puede usar este comando.");
            return true;
        }

        if (!player.hasPermission("morphcosmic.morph")) {
            player.sendMessage(ChatColor.RED + "No tienes permiso para usar este comando.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Uso: /morph <mob|off>");
            return true;
        }

        String arg = args[0].toLowerCase();

        if (arg.equals("off")) {
            if (plugin.getMorphManager().isMorphed(player)) {
                plugin.getMorphManager().unmorph(player);
                player.sendMessage(ChatColor.GREEN + "Has vuelto a tu forma normal.");
            } else {
                player.sendMessage(ChatColor.RED + "No estás transformado.");
            }
            return true;
        }

        EntityType type;
        try {
            type = EntityType.valueOf(arg.toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(ChatColor.RED + "Mob inválido: " + args[0]);
            return true;
        }

        if (!isValidMob(type)) {
            player.sendMessage(ChatColor.RED + "Ese mob no se puede usar para morph.");
            return true;
        }

        plugin.getMorphManager().morph(player, type);
        player.sendMessage(ChatColor.GREEN + "Te has transformado en "
                + ChatColor.YELLOW + type.name().toLowerCase() + ChatColor.GREEN + ".");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return mobNames.stream()
                    .filter(name -> name.startsWith(partial))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}