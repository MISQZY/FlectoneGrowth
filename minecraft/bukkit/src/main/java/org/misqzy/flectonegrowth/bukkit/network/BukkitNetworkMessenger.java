package org.misqzy.flectonegrowth.bukkit.network;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.misqzy.flectonegrowth.common.network.NetworkMessenger;
import org.misqzy.flectonegrowth.common.network.SyncMessage;
import org.misqzy.flectonegrowth.common.network.SyncMessageCodec;

import java.util.function.Consumer;

/**
 * Implements {@link NetworkMessenger} over Bukkit plugin messaging. Bukkit
 * only lets a plugin message be sent *through* an online player - there is
 * no server-to-proxy channel independent of a player connection - so
 * {@link #broadcast} picks any currently online player as the carrier. If
 * nobody is online, there is nobody on the other end of the network to act
 * on the message at that instant either, so it is silently dropped rather
 * than queued for later.
 */
@RequiredArgsConstructor
public final class BukkitNetworkMessenger implements NetworkMessenger, PluginMessageListener {

    private final JavaPlugin plugin;
    private volatile Consumer<SyncMessage> handler;

    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SyncMessageCodec.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SyncMessageCodec.CHANNEL, this);
    }

    public void unregister() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, SyncMessageCodec.CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, SyncMessageCodec.CHANNEL, this);
    }

    @Override
    public void broadcast(SyncMessage message) {
        Player carrier = Bukkit.getServer().getOnlinePlayers().stream().findAny().orElse(null);
        if (carrier == null) {
            plugin.getLogger().warning("Could not relay a " + message.type() + " sync message for "
                    + message.playerUuid() + " - no online player on this server to carry it. Other servers will "
                    + "pick up the change from the database on this player's next join/tick instead.");
            return;
        }
        carrier.sendPluginMessage(plugin, SyncMessageCodec.CHANNEL, SyncMessageCodec.encode(message));
    }

    @Override
    public void onReceive(Consumer<SyncMessage> handler) {
        this.handler = handler;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (!SyncMessageCodec.CHANNEL.equals(channel)) return;
        Consumer<SyncMessage> currentHandler = handler;
        if (currentHandler == null) return;
        try {
            currentHandler.accept(SyncMessageCodec.decode(bytes));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Failed to decode incoming sync message: " + e.getMessage());
        }
    }
}
