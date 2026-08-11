package com.cosmicraft.morphcosmic;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Morph 100% basado en paquetes (ProtocolLib), sin entidad real de por
 * medio. Reutiliza el entity id real del jugador para el "spawn" visual
 * (así el combate/daño siguen funcionando de forma nativa), pero toma
 * control TOTAL del movimiento y la rotación: cancela los paquetes
 * nativos de mover/mirar del jugador morfeado y reenvía nosotros mismos,
 * cada tick, la posición + yaw/pitch de cuerpo + head yaw, solo a los
 * jugadores que efectivamente lo tienen renderizado (tracked players).
 *
 * Esto evita cualquier ambigüedad de cómo el cliente interpreta
 * paquetes "de jugador" sobre un modelo de mob, y da animación de
 * caminar fluida (interpolación estándar de Minecraft para no-locales)
 * y la cabeza/cuerpo mirando exactamente hacia donde mira el admin.
 */
public class MorphManager {

    private static final Set<PacketType> MOVEMENT_PACKETS = Set.of(
            PacketType.Play.Server.REL_ENTITY_MOVE,
            PacketType.Play.Server.REL_ENTITY_MOVE_LOOK,
            PacketType.Play.Server.ENTITY_LOOK,
            PacketType.Play.Server.ENTITY_HEAD_ROTATION,
            PacketType.Play.Server.ENTITY_TELEPORT
    );

    private final MorphCosmic plugin;
    private final ProtocolManager protocolManager;
    private final Map<UUID, EntityType> morphed = new HashMap<>();
    private BukkitTask syncTask;

    public MorphManager(MorphCosmic plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        registerPacketListener();
        startSyncTask();
    }

    // ---------------------------------------------------------------
    // Paquete de "aparición" (spawn) — reemplaza al jugador por el mob
    // ---------------------------------------------------------------

    private void registerPacketListener() {
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH,
                PacketType.Play.Server.SPAWN_ENTITY,
                PacketType.Play.Server.ENTITY_METADATA,
                PacketType.Play.Server.REL_ENTITY_MOVE,
                PacketType.Play.Server.REL_ENTITY_MOVE_LOOK,
                PacketType.Play.Server.ENTITY_LOOK,
                PacketType.Play.Server.ENTITY_HEAD_ROTATION,
                PacketType.Play.Server.ENTITY_TELEPORT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                PacketType type = event.getPacketType();

                if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                    handleSpawn(event, packet);
                } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
                    handleMetadata(event, packet);
                } else if (MOVEMENT_PACKETS.contains(type)) {
                    // Estos los mandamos nosotros a mano en el sync task,
                    // así que si el entity id pertenece a un jugador
                    // morfeado, cancelamos la versión nativa para no
                    // pisarnos ni duplicar tráfico.
                    Integer entityId = readEntityId(packet, type);
                    if (entityId != null) {
                        Player target = getPlayerById(entityId);
                        if (target != null && morphed.containsKey(target.getUniqueId())) {
                            event.setCancelled(true);
                        }
                    }
                }
            }
        });
    }

    private Integer readEntityId(PacketContainer packet, PacketType type) {
        try {
            return packet.getIntegers().read(0);
        } catch (Exception e) {
            return null;
        }
    }

    private void handleSpawn(PacketEvent event, PacketContainer packet) {
        UUID targetId = packet.getUUIDs().read(0);
        if (targetId == null) return;

        EntityType type = morphed.get(targetId);
        if (type == null) return;

        Player target = plugin.getServer().getPlayer(targetId);
        if (target == null) return;

        event.setCancelled(true);
        sendMobSpawnPacket(event.getPlayer(), target, type);
    }

    private void handleMetadata(PacketEvent event, PacketContainer packet) {
        int entityId = packet.getIntegers().read(0);
        Player target = getPlayerById(entityId);

        if (target != null && morphed.containsKey(target.getUniqueId())) {
            // Evitamos mandar metadata específica de Jugador: corrompe el
            // DataWatcher del mob en el cliente y congela sus animaciones.
            // Solo dejamos pasar los índices base (0-6), comunes a toda
            // entidad viva (fuego, sneak, pociones, pose).
            var dataValues = packet.getDataValueCollectionModifier().read(0);
            if (dataValues == null) return;

            var filtered = new java.util.ArrayList<>(dataValues);
            filtered.removeIf(value -> value.getIndex() > 6);

            PacketContainer cloned = packet.deepClone();
            cloned.getDataValueCollectionModifier().write(0, filtered);
            event.setPacket(cloned);
        }
    }

    private Player getPlayerById(int entityId) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getEntityId() == entityId) return p;
        }
        return null;
    }

    private void sendMobSpawnPacket(Player viewer, Player target, EntityType type) {
        Location loc = target.getLocation();

        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);

        packet.getIntegers().write(0, target.getEntityId());
        packet.getUUIDs().write(0, UUID.randomUUID());
        packet.getEntityTypeModifier().write(0, type);

        packet.getDoubles()
                .write(0, loc.getX())
                .write(1, loc.getY())
                .write(2, loc.getZ());

        byte pitch = angleToByte(loc.getPitch());
        byte yaw = angleToByte(loc.getYaw());
        packet.getBytes()
                .write(0, pitch)
                .write(1, yaw)
                .write(2, yaw); // head yaw inicial

        trySend(viewer, packet);
    }

    // ---------------------------------------------------------------
    // Sincronización de movimiento/mirada, tick a tick, solo a quien
    // realmente tiene la entidad renderizada.
    // ---------------------------------------------------------------

    private void startSyncTask() {
        syncTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (morphed.isEmpty()) return;

            for (UUID id : new HashSet<>(morphed.keySet())) {
                Player target = plugin.getServer().getPlayer(id);
                if (target == null) continue;
                syncMovement(target);
            }
        }, 1L, 1L);
    }

    private void syncMovement(Player target) {
        Set<Player> viewers = target.getTrackedPlayers();
        if (viewers.isEmpty()) return;

        Location loc = target.getLocation();
        byte yaw = angleToByte(loc.getYaw());
        byte pitch = angleToByte(loc.getPitch());

        PacketContainer teleport = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
        teleport.getIntegers().write(0, target.getEntityId());
        teleport.getDoubles()
                .write(0, loc.getX())
                .write(1, loc.getY())
                .write(2, loc.getZ());
        teleport.getBytes().write(0, yaw).write(1, pitch);
        teleport.getBooleans().write(0, target.isOnGround());

        PacketContainer headRotation = protocolManager.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        headRotation.getIntegers().write(0, target.getEntityId());
        headRotation.getBytes().write(0, yaw);

        for (Player viewer : viewers) {
            if (viewer.equals(target)) continue;
            trySend(viewer, teleport);
            trySend(viewer, headRotation);
        }
    }

    private byte angleToByte(float degrees) {
        return (byte) (degrees * 256.0F / 360.0F);
    }

    private void trySend(Player viewer, PacketContainer packet) {
        try {
            protocolManager.sendServerPacket(viewer, packet);
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo enviar paquete de morph a "
                    + viewer.getName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // API pública
    // ---------------------------------------------------------------

    public boolean isMorphed(Player player) {
        return morphed.containsKey(player.getUniqueId());
    }

    public EntityType getMorph(Player player) {
        return morphed.get(player.getUniqueId());
    }

    public void morph(Player player, EntityType type) {
        morphed.put(player.getUniqueId(), type);
        refreshVisibilityForAll(player);
    }

    public void unmorph(Player player) {
        if (!morphed.containsKey(player.getUniqueId())) return;
        morphed.remove(player.getUniqueId());
        refreshVisibilityForAll(player);
    }

    private void refreshVisibilityForAll(Player target) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.equals(target)) continue;
            viewer.hidePlayer(plugin, target);
            viewer.showPlayer(plugin, target);
        }
    }

    public void refreshForViewer(Player viewer) {
        for (UUID id : morphed.keySet()) {
            Player target = plugin.getServer().getPlayer(id);
            if (target != null && !target.equals(viewer)) {
                viewer.hidePlayer(plugin, target);
                viewer.showPlayer(plugin, target);
            }
        }
    }

    public void cleanupAll() {
        for (UUID id : new HashSet<>(morphed.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) unmorph(p);
        }
        if (syncTask != null) {
            syncTask.cancel();
        }
        protocolManager.removePacketListeners(plugin);
    }
}