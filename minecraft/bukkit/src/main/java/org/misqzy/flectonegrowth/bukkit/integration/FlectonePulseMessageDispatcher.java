package org.misqzy.flectonegrowth.bukkit.integration;

import net.flectone.pulse.constant.MessageFlag;
import net.flectone.pulse.constant.ModuleName;
import net.flectone.pulse.dispatcher.MessageDispatcher;
import net.flectone.pulse.model.entity.FPlayer;
import net.flectone.pulse.model.event.EventMetadata;
import net.flectone.pulse.model.event.message.context.MessageContext;
import net.flectone.pulse.model.value.Range;
import net.flectone.pulse.service.FPlayerService;
import org.bukkit.entity.Player;

/**
 * Routes a fully-resolved MiniMessage string to one player through FlectonePulse's
 * {@code MessageDispatcher}, so FlectonePulse itself parses it (including
 * {@code <fcolor:N>}) and delivers it. {@link #trySend} is the sole entry point;
 * every call site falls back to local rendering when it returns {@code false}.
 *
 * <p>No tag resolvers and no Adventure objects cross this boundary: the string
 * already has placeholders inlined as escaped text and our own colour tags expanded
 * (see {@code Messages#rawForDispatch}); only plain strings and FlectonePulse's own
 * types are used. This matters because shadow relocates this plugin's
 * {@code net.kyori}, so any Adventure object handed over would be the wrong class
 * at runtime.</p>
 *
 * <p>The message is a system message, not player chat, so input-sanitising and
 * chat-moderation stages are switched off via {@link MessageFlag}s (see
 * {@code DISABLED_FLAGS}; all verified to exist in api 1.14.1). Notably
 * {@code LEGACY_COLOR_CONVERSION} (default on) would re-interpret {@code &} codes in
 * player-supplied names, and {@code REMOVE_DISABLED_TAGS} (default on) could strip our
 * gradients/colours according to permissions. {@code PLAYER_MESSAGE} (default off) is
 * left off, and {@code <fcolor:N>} resolution is not disabled.</p>
 *
 * <p>Dispatched under {@link ModuleName#ADDON}, the tag FlectonePulse provides for
 * third-party plugins, via the non-deprecated {@code dispatch(EventMetadata)} (the module
 * is read from the {@code MessageContext}). Delivery is restricted to exactly the one
 * player with {@code filter(fPlayer)} plus {@code Range.Type.PLAYER}.</p>
 */
public final class FlectonePulseMessageDispatcher {

    private FlectonePulseMessageDispatcher() {}

    /**
     * Hands the message to FlectonePulse. {@code true} means only "accepted for delivery":
     * FlectonePulse builds and sends it on its own schedule (possibly asynchronously), so
     * delivery is not confirmed and ordering relative to locally rendered messages sent
     * right afterwards is not guaranteed. {@code false} (nothing handed over) is returned
     * only when FlectonePulse is unavailable (not installed/ready/disabling, player not in
     * its cache) or the dispatch call threw - the caller then renders locally. An empty
     * receiver set is deliberately not treated as failure, to avoid double-sending.
     *
     * @param resolvedMiniMessage fully-resolved MiniMessage; only {@code <fcolor:N>} is left for FlectonePulse
     */
    public static boolean trySend(Player player, String resolvedMiniMessage) {
        if (player == null || resolvedMiniMessage == null || !FlectonePulseAccess.isPluginEnabled()) return false;
        try {
            return Send.send(player, resolvedMiniMessage);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static final class Send {
        private static final MessageFlag[] DISABLED_FLAGS = {
                MessageFlag.LEGACY_COLOR_CONVERSION,
                MessageFlag.REMOVE_DISABLED_TAGS,
                MessageFlag.URL_PROCESSING,
                MessageFlag.ITEM_DETECTION,
                MessageFlag.MENTION_MODULE,
                MessageFlag.QUESTIONANSWER_MODULE,
                MessageFlag.REPLACEMENT_MODULE,
                MessageFlag.TRANSLATE_MODULE,
                MessageFlag.NICKNAME_MODULE,
                MessageFlag.INVISIBLE_NAME_DETECTION,
                MessageFlag.VIOLATION_PROCESSING,
                MessageFlag.SWEAR_MODULE,
                MessageFlag.CAPS_MODULE,
                MessageFlag.FLOOD_MODULE,
                MessageFlag.FIXATION_MODULE,
                MessageFlag.DELETE_MODULE,
                MessageFlag.PADDING_MODULE,
                MessageFlag.USE_CACHE,
        };

        private Send() {}

        static boolean send(Player player, String message) {
            FPlayerService fPlayerService = FlectonePulseAccess.tryGet(FPlayerService.class);
            MessageDispatcher dispatcher = FlectonePulseAccess.tryGet(MessageDispatcher.class);
            if (fPlayerService == null || dispatcher == null) return false;

            FPlayer fPlayer = fPlayerService.getFPlayer(player.getUniqueId());
            if (fPlayer == null || fPlayer.isUnknown() || !fPlayer.isOnline()) return false;

            dispatcher.dispatch(EventMetadata.builder()
                    .messageContext(receiver -> {
                        var builder = MessageContext.builder()
                                .module(ModuleName.ADDON)
                                .sender(fPlayer)
                                .receiver(receiver)
                                .message(message);
                        for (MessageFlag flag : DISABLED_FLAGS) builder.flag(flag, false);
                        return builder.build();
                    })
                    .filter(fPlayer)
                    .range(Range.Type.PLAYER)
                    .build());
            return true;
        }
    }
}
