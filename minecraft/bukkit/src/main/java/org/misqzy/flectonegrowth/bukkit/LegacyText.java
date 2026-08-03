package org.misqzy.flectonegrowth.bukkit;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;

/**
 * The single message-delivery path this module uses, on any Bukkit-API
 * server (Spigot, CraftBukkit, Paper, Purpur, ...) - this module is compiled
 * only against {@code spigot-api}, never {@code paper-api}, so it has to
 * work on the lowest common denominator by default.
 *
 * <p><b>Native Adventure delivery (the Paper-only optimization).</b> Before
 * falling back to the conversion below, {@link #send} checks whether the
 * runtime {@code CommandSender} implements Adventure's {@link Audience} -
 * true for Paper/Purpur's own {@code CommandSender} implementation, false
 * for plain Spigot/CraftBukkit's. This needs no {@code paper-api} dependency
 * at all: {@code Audience} lives in {@code adventure-api}, already a
 * dependency here for MiniMessage, not in {@code paper-api}. When it's
 * available, {@code audience.sendMessage(component)} sends the real
 * {@link Component} directly - no conversion at all, and (same as the
 * fallback below) hover/click events and true RGB survive intact.</p>
 *
 * <p><b>Fallback conversion (the Bukkit-API baseline).</b> Vanilla Spigot/
 * CraftBukkit's {@code CommandSender} has no {@code sendMessage(Component)}
 * overload and isn't an {@code Audience}, so the {@link Component} is walked
 * and rebuilt as a BungeeCord {@link BaseComponent} tree instead - the format
 * {@code CommandSender#spigot().sendMessage(BaseComponent...)} accepts, which
 * every Bukkit implementation has always had.</p>
 *
 * <p>This used to go through {@code adventure-text-serializer-legacy} (to a
 * {@code §}-formatted string) then {@code TextComponent.fromLegacyText}
 * (back into a {@code BaseComponent[]}) - a real live-deploy regression once
 * this plugin started sending clickable/hoverable text
 * ({@code command.invalid-syntax}'s {@code <click:suggest_command:...>}):
 * a legacy string has no representation for hover/click at all, so both
 * silently vanished for every sender on this fallback path (i.e. every
 * non-Paper server, this module's own stated baseline). Converting the
 * {@link Component} tree directly, node by node, keeps them intact -
 * verified end-to-end with a standalone harness (color, decorations, click,
 * hover, and a real {@code <gradient>} - the last one being the exact case
 * the old string round-trip existed to get right in the first place, per its
 * own history below - all came through correctly).</p>
 *
 * <p>Adventure never eagerly resolves a child's color/decorations onto that
 * child - an unset attribute is left {@code null}/{@code NOT_SET}, meaning
 * "inherit from the parent at render time" (verified empirically: a
 * MiniMessage-parsed {@code <primary>hello <bold>world</bold>}'s "world"
 * child has {@code style().color() == null}, not the resolved color).
 * BungeeCord's {@code BaseComponent} tree uses the exact same convention
 * (it <i>is</i> Minecraft's own JSON chat component format, where an
 * {@code extra} entry inherits whatever its parent doesn't override) - so
 * {@link #convert} only ever calls a setter when the source node's own style
 * actually has that attribute, and lets the client resolve inheritance the
 * same way it always does for vanilla JSON components. No manual style-
 * flattening needed.</p>
 *
 * <p>Only {@code click}'s text-based actions (open URL/run/suggest command,
 * copy to clipboard) and {@code hover}'s {@code show_text} are converted -
 * the only ones this plugin actually produces today. {@code change_page},
 * {@code show_item}/{@code show_entity}, and anything {@code custom}/
 * {@code show_dialog} are silently dropped rather than guessed at; add a
 * case to {@link #bungeeClickAction}/{@link #applyHover} if a future message
 * ever needs one.</p>
 */
// The whole net.md_5.bungee.api.chat package (ClickEvent/HoverEvent/TextComponent's
// constructors used below) is soft-deprecated in favour of kyori Adventure - but
// there's no Adventure-based replacement reachable from vanilla CraftBukkit's
// CommandSender, which is exactly why this class exists. Deliberate, not an oversight.
@SuppressWarnings("deprecation")
public final class LegacyText {

    private LegacyText() {}

    public static void send(CommandSender sender, Component component) {
        if (sender instanceof Audience audience) {
            audience.sendMessage(component);
            return;
        }

        sender.spigot().sendMessage(toBungee(component));
    }

    private static BaseComponent[] toBungee(Component component) {
        return new BaseComponent[]{convert(component)};
    }

    private static BaseComponent convert(Component component) {
        TextComponent bungee = new TextComponent(
                component instanceof net.kyori.adventure.text.TextComponent text ? text.content() : "");
        applyStyle(bungee, component.style());
        applyClick(bungee, component);
        applyHover(bungee, component);
        for (Component child : component.children()) {
            bungee.addExtra(convert(child));
        }
        return bungee;
    }

    /** {@code hexColors()}/{@code .of(String)} - not the 16 basic legacy colors - so a gradient (or any other true-RGB color) doesn't get flattened to the nearest one, the exact bug this class's history documents against the old string-based path. */
    private static void applyStyle(BaseComponent bungee, Style style) {
        TextColor color = style.color();
        if (color != null) bungee.setColor(ChatColor.of(color.asHexString()));
        bungee.setBold(decorationState(style, TextDecoration.BOLD));
        bungee.setItalic(decorationState(style, TextDecoration.ITALIC));
        bungee.setUnderlined(decorationState(style, TextDecoration.UNDERLINED));
        bungee.setStrikethrough(decorationState(style, TextDecoration.STRIKETHROUGH));
        bungee.setObfuscated(decorationState(style, TextDecoration.OBFUSCATED));
    }

    /** {@code null} (BungeeCord's own "inherit" value) for {@code NOT_SET} - see the class javadoc on why this class never flattens inheritance itself. */
    private static Boolean decorationState(Style style, TextDecoration decoration) {
        return switch (style.decoration(decoration)) {
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            case NOT_SET -> null;
        };
    }

    private static void applyClick(BaseComponent bungee, Component component) {
        ClickEvent<?> click = component.clickEvent();
        if (click == null) return;
        if (!(click.payload() instanceof ClickEvent.Payload.Text text)) return;
        net.md_5.bungee.api.chat.ClickEvent.Action bungeeAction = bungeeClickAction(click.action());
        if (bungeeAction == null) return;
        bungee.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(bungeeAction, text.value()));
    }

    private static net.md_5.bungee.api.chat.ClickEvent.Action bungeeClickAction(ClickEvent.Action<?> action) {
        if (action == ClickEvent.Action.OPEN_URL) return net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL;
        if (action == ClickEvent.Action.RUN_COMMAND) return net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND;
        if (action == ClickEvent.Action.SUGGEST_COMMAND) return net.md_5.bungee.api.chat.ClickEvent.Action.SUGGEST_COMMAND;
        if (action == ClickEvent.Action.COPY_TO_CLIPBOARD) return net.md_5.bungee.api.chat.ClickEvent.Action.COPY_TO_CLIPBOARD;
        return null;
    }

    private static void applyHover(BaseComponent bungee, Component component) {
        HoverEvent<?> hover = component.hoverEvent();
        if (hover == null || hover.action() != HoverEvent.Action.SHOW_TEXT) return;
        Component hoverText = (Component) hover.value();
        bungee.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT, toBungee(hoverText)));
    }
}
