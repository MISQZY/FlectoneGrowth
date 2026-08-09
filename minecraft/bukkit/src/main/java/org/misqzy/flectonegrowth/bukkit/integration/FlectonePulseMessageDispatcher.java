package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.execution.dispatcher.MessageDispatcher;
import net.flectone.pulse.model.entity.FPlayer;
import net.flectone.pulse.model.event.EventMetadata;
import net.flectone.pulse.model.event.message.context.MessageContext;
import net.flectone.pulse.service.FPlayerService;
import net.flectone.pulse.util.constant.ModuleName;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

/**
 * Routes a message to a player through FlectonePulse's own
 * {@code MessageDispatcher} - which internally resolves the raw MiniMessage
 * string via its own {@code MessagePipeline} (resolving {@code <fcolor:N>}
 * via FlectonePulse's own {@code FColorModule} listener rather than
 * {@code Messages}' local resolver) and delivers it through FlectonePulse's
 * internal event bus - instead of this plugin's own {@code Messages} +
 * {@code LegacyText#send}. {@link #trySend} is the sole entry point; every
 * call site falls back to local rendering when it returns {@code false}.
 *
 * <p>Same undocumented-internal-API caveat as {@link FlectonePulseAccess}
 * (whose {@link FlectonePulseAccess#tryGet} this is built on): confirmed the
 * exact classes/signatures below by decompiling the real
 * {@code core-1.12.2.jar} with {@code javap}, not guessed from documentation.
 * A few things worth recording since they aren't obvious from the method
 * signatures alone:</p>
 * <ul>
 *   <li>{@code MessageDispatcher.dispatch(ModuleName, EventMetadata)} (the
 *   overload used below) builds the {@code Component} itself - internally it
 *   resolves the message's receiver set, then for each one calls its own
 *   injected {@code MessagePipeline.build(MessageContext)} (the raw-string
 *   parsing step, including {@code <fcolor:N>}) before dispatching. Nothing
 *   here needs to fetch or call {@code MessagePipeline} directly - a
 *   separate {@code tryGet(MessagePipeline.class)} would just be resolving
 *   the same singleton {@code dispatcher} already holds, for a value never
 *   used beyond a null check that {@code dispatcher} being non-null already
 *   covers (its constructor requires a {@code MessagePipeline} to exist).</li>
 *   <li>{@code FColorModule} (reacting to the {@code MessageFormattingEvent}
 *   {@code MessagePipeline.build} dispatches internally) only touches the
 *   message at all if the raw string contains {@code <fcolor}, and adds its
 *   own tag resolver on top of whatever this class supplies - so the
 *   {@link TagResolver} handed in here must cover {@code <primary>}/
 *   {@code <secondary>} and this call's placeholders, but deliberately not
 *   {@code <fcolor:N>} (see {@code Messages#externalDispatchResolvers}).</li>
 *   <li>{@code MessageDispatcher.dispatch(ModuleName, EventMetadata)} resolves
 *   its own receiver set - passing the target as both {@code EventMetadata}'s
 *   {@code messageContext} sender <em>and</em> leaving the default
 *   {@code Range.Type.PLAYER} range (no explicit {@code .filter()}/{@code .range()}
 *   call below) is what narrows delivery to exactly that one player: with the
 *   default range type, {@code RangeFilter} filters receivers down to whoever
 *   equals the message's sender - confirmed by reading {@code RangeFilter}/
 *   {@code EventMetadata.Builder}'s actual source, not assumed.</li>
 *   <li>{@code FPlayer}/{@code MessageContext} are player-centric -
 *   there's no equivalent path for console, so callers keep using local
 *   rendering for non-player senders regardless of FlectonePulse.</li>
 * </ul>
 */
public final class FlectonePulseMessageDispatcher {

    private FlectonePulseMessageDispatcher() {}

    /**
     * Attempts delivery via FlectonePulse. Returns {@code false} (nothing
     * sent) if FlectonePulse isn't installed/ready, the player isn't tracked
     * in its cache yet, or anything about the call throws - the caller
     * should fall back to its own rendering in every such case.
     */
    public static boolean trySend(Player player, String rawMiniMessage, TagResolver tagResolver) {
        try {
            FPlayerService fPlayerService = FlectonePulseAccess.tryGet(FPlayerService.class);
            if (fPlayerService == null) return false;

            FPlayer fPlayer = fPlayerService.getFPlayer(player.getUniqueId());
            if (fPlayer == null || fPlayer.isUnknown() || !fPlayer.isOnline()) return false;

            MessageDispatcher dispatcher = FlectonePulseAccess.tryGet(MessageDispatcher.class);
            if (dispatcher == null) return false;

            dispatcher.dispatch(ModuleName.INTEGRATION, EventMetadata.builder()
                    .messageContext(fResolver -> MessageContext.builder()
                            .sender(fPlayer)
                            .receiver(fResolver)
                            .tagResolver(tagResolver)
                            .message(rawMiniMessage)
                            .build()
                    )
                    .build()
            );
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
