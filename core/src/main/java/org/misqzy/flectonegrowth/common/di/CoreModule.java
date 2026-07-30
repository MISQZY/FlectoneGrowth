package org.misqzy.flectonegrowth.common.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.misqzy.flectonegrowth.common.network.NetworkMessenger;
import org.misqzy.flectonegrowth.common.platform.Platform;
import org.misqzy.flectonegrowth.common.platform.PlayerLookup;
import org.misqzy.flectonegrowth.common.platform.Scheduler;
import org.misqzy.flectonegrowth.common.config.CoreConfig;
import org.misqzy.flectonegrowth.common.domain.GenderRegistry;
import org.misqzy.flectonegrowth.common.domain.ProfileCache;
import org.misqzy.flectonegrowth.common.storage.Storage;

/**
 * Binds the pieces every platform shares. {@link Platform},
 * {@link Scheduler}, {@link NetworkMessenger}
 * and {@link PlayerLookup} are intentionally
 * <b>not</b> bound here - each platform module supplies its own Guice module
 * for those, since only the platform knows how to implement them.
 *
 * <p>{@link CoreConfig}, {@link Storage} and {@link GenderRegistry} are
 * bound as plain instances handed in at construction time (see
 * {@link FlectoneGrowthCore}) rather than built lazily by Guice, because they
 * are hot-swapped as a unit on {@code /flectonegrowth reload} - Guice's providers would
 * make that swap harder to reason about than a small owning facade class.</p>
 */
@RequiredArgsConstructor
public final class CoreModule extends AbstractModule {

    private final CoreConfig config;
    private final Storage storage;
    private final GenderRegistry genderRegistry;

    @Override
    protected void configure() {
        bind(ProfileCache.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    CoreConfig provideConfig() {
        return config;
    }

    @Provides
    @Singleton
    Storage provideStorage() {
        return storage;
    }

    @Provides
    @Singleton
    GenderRegistry provideGenderRegistry() {
        return genderRegistry;
    }
}
