package org.misqzy.flectonegrowth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;

/**
 * Pure byte relay for the {@code flectonegrowth:sync} plugin-messaging
 * channel. Deliberately has no dependency on core: it never decodes a
 * {@code SyncMessage}, it just forwards whatever bytes arrive on this
 * channel from one backend server to every other connected backend server.
 * See {@code docs/ARCHITECTURE.md} "Cross-server sync protocol".
 */
@Plugin(
        id = "flectonegrowth",
        name = "FlectoneGrowth-Velocity",
        version = BuildVersion.VERSION,
        authors = {"MISQZY"},
        description = "Relays FlectoneGrowth cross-server cache-invalidation messages between backend servers."
)
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class FlectoneGrowthVelocityPlugin {

    private static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("flectonegrowth:sync");

    private final ProxyServer server;
    private final Logger logger;

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("Registered {} plugin messaging channel for cross-server growth sync relay.", CHANNEL.getId());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        if (!(event.getSource() instanceof ServerConnection source)) return;

        // Consume it here rather than letting Velocity's default forwarding
        // send it onward to the client connection that carried it - the
        // recipients are sibling backend servers, never the player.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        RegisteredServer sourceServer = source.getServer();
        byte[] data = event.getData();
        for (RegisteredServer other : server.getAllServers()) {
            if (other.equals(sourceServer)) continue;
            other.sendPluginMessage(CHANNEL, data);
        }
    }
}
