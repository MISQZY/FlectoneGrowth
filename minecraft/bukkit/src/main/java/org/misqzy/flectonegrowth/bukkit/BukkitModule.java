package org.misqzy.flectonegrowth.bukkit;

import com.google.inject.AbstractModule;
import lombok.RequiredArgsConstructor;
import org.misqzy.flectonegrowth.common.di.CoreModule;
import org.misqzy.flectonegrowth.common.di.FlectoneGrowthCore;
import org.misqzy.flectonegrowth.common.network.NetworkMessenger;
import org.misqzy.flectonegrowth.common.platform.PlayerLookup;
import org.misqzy.flectonegrowth.common.platform.Scheduler;

/**
 * Binds the Bukkit-specific implementations of the contracts
 * {@link CoreModule} deliberately leaves
 * unbound. Built once in {@link FlectoneGrowthPlugin#onEnable()} and passed
 * into {@link FlectoneGrowthCore#bootstrap}.
 */
@RequiredArgsConstructor
public final class BukkitModule extends AbstractModule {

    private final Scheduler scheduler;
    private final NetworkMessenger networkMessenger;
    private final PlayerLookup playerLookup;

    @Override
    protected void configure() {
        bind(Scheduler.class).toInstance(scheduler);
        bind(NetworkMessenger.class).toInstance(networkMessenger);
        bind(PlayerLookup.class).toInstance(playerLookup);
    }
}
