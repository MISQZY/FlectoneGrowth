package org.misqzy.flectonegrowth.common.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.misqzy.flectonegrowth.common.config.ConfigView;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Loads and renders MiniMessage strings from every loaded localisation file
 * into a platform-agnostic {@link Component}. Holds every bundled
 * {@code localizations/messages_<code>.yml}, not just config.yml's
 * configured default - every method has a {@code locale}-taking overload
 * for a specific recipient (e.g. a player's own client locale, see
 * {@code FlectoneGrowthMessages}) plus a no-arg overload that uses the
 * configured default, for callers with no specific recipient in mind (a
 * command's tab-completion text, a placeholder). How that {@link Component}
 * actually reaches a player is up to the platform module: on Paper/Purpur the
 * runtime {@code CommandSender} implements kyori's {@code Audience}
 * natively, while plain Spigot/CraftBukkit has no such support, so the
 * Bukkit module detects that at runtime and serialises to a legacy
 * formatted string before sending only when it's missing (see
 * {@code LegacyText} there). Either way, every platform starts from the
 * exact same {@link Component} this class produces.
 *
 * <p>Every message also gets two theme-color tags, resolved as real
 * MiniMessage {@link TagResolver}s (not a hand-rolled pre-processing regex -
 * an earlier version worked that way and broke on nested tags; see below):</p>
 * <ul>
 *   <li>{@code <primary>}/{@code <secondary>} - config.yml's
 *   {@code colors.primary}/{@code colors.secondary}, so a server can
 *   reskin every message's accent colors from one place instead of editing
 *   every localization string.</li>
 *   <li>{@code <fcolor:N>} - FlectonePulse's own configured default color
 *   palette (see the platform module's {@code FlectonePulseColorResolver}),
 *   available whenever FlectonePulse integration is enabled, a no-op
 *   otherwise. Looked up live via a {@link Supplier} on every render, not
 *   cached at construction: FlectonePulse can still be finishing its own
 *   (possibly async) startup at the exact moment this plugin's
 *   {@code onEnable()} runs, even though Bukkit already reports it enabled -
 *   caching an empty snapshot from that instant would leave every
 *   {@code <fcolor:N>} unresolved until the next {@code /flectonegrowth reload}.</li>
 * </ul>
 *
 * <p><b>How this actually works</b> - studied from FlectonePulse's own
 * {@code FColorModule} (decompiled the real published jar,
 * {@code net.flectone.pulse:core}, rather than guessing): the resolved color
 * can be an arbitrary MiniMessage fragment (a bare name, a hex code, or a
 * full gradient), which {@link Tag#styling} can't represent on its own - it
 * only takes {@code StyleBuilderApplicable}s, not markup text. FlectonePulse's
 * fix is {@link Tag#preProcessParsed(String)}: the resolver returns the
 * *opening* markup (e.g. {@code "<gradient:#A6D8FF:#8CC8FF>"}) as a string,
 * which MiniMessage re-injects into the token stream and parses normally
 * from that point on - handling gradients, and nesting, correctly, because
 * it becomes genuine MiniMessage input instead of text this class tries to
 * pattern-match itself.</p>
 *
 * <p>The tradeoff (also taken from FlectonePulse, not invented here):
 * {@code preProcessParsed} only substitutes the *opening* tag - there's no
 * matching close, so {@code </primary>} etc. would otherwise show up as
 * literal, unresolved text in the output (verified empirically). FlectonePulse
 * strips its closing tag with a regex before parsing rather than trying to
 * properly re-close it; this class does the same for all three tags. The
 * practical effect is that these colors apply *forward* until the next color
 * change or end of message, not scoped to a precise span - so, unlike the
 * regex-substitution version this replaced, a localization string that wants
 * to return to an outer color after a short inner span (e.g. secondary text
 * following a primary-colored name) needs to re-open that color explicitly
 * rather than relying on a closing tag to restore it.</p>
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    // preProcessParsed only ever substitutes the opening tag (see class
    // javadoc) - closing tags are meaningless here and would otherwise leak
    // into the output as literal text, so they're stripped before parsing.
    private static final Pattern CLOSING_TAGS = Pattern.compile("</primary>|</secondary>|</fcolor(?::[0-9]+)?>");

    /** Ultimate fallback if every localization file failed to load - every lookup degrades to its own "missing key"/default value instead of throwing. */
    private static final ConfigView EMPTY_VIEW = new ConfigView() {
        @Override public String getString(String path, String def) { return def; }
        @Override public int getInt(String path, int def) { return def; }
        @Override public long getLong(String path, long def) { return def; }
        @Override public double getDouble(String path, double def) { return def; }
        @Override public boolean getBoolean(String path, boolean def) { return def; }
        @Override public List<String> getStringList(String path) { return List.of(); }
        @Override public ConfigView getSection(String path) { return null; }
        @Override public Set<String> keys() { return Set.of(); }
    };

    private final Map<String, ConfigView> locales;
    private final String defaultLocale;
    private final String primaryColor;
    private final String secondaryColor;
    private final Supplier<Map<Integer, String>> fcolors;

    /**
     * @param locales       every loaded {@code localizations/messages_<code>.yml}, keyed by that
     *                      lowercase locale code (e.g. {@code "en"}, {@code "ru"}) - not just
     *                      {@code defaultLocale}'s - so a per-recipient locale (see
     *                      {@link #get(String, String, Map)}) can be served without reloading anything.
     * @param defaultLocale config.yml's configured {@code locale} - used by every overload below that
     *                      doesn't take an explicit locale, and as the fallback when an explicit one
     *                      isn't loaded (e.g. a player's client locale has no matching translation file).
     */
    public Messages(Map<String, ConfigView> locales, String defaultLocale,
                     String primaryColor, String secondaryColor, Supplier<Map<Integer, String>> fcolors) {
        this.locales = Map.copyOf(locales);
        this.defaultLocale = defaultLocale;
        this.primaryColor = primaryColor;
        this.secondaryColor = secondaryColor;
        this.fcolors = fcolors != null ? fcolors : Map::of;
    }

    /** Whether {@code locale} has its own loaded translation file - callers deciding what to resolve a client locale down to should check this first, since {@link #data(String)} silently falls back to {@link #defaultLocale} otherwise. */
    public boolean hasLocale(String locale) {
        return locale != null && locales.containsKey(locale);
    }

    private ConfigView data(String locale) {
        ConfigView view = locale != null ? locales.get(locale) : null;
        if (view != null) return view;

        ConfigView fallback = locales.get(defaultLocale);
        if (fallback != null) return fallback;

        return locales.isEmpty() ? EMPTY_VIEW : locales.values().iterator().next();
    }

    public String raw(String key) {
        return raw(defaultLocale, key);
    }

    public String raw(String locale, String key) {
        return data(locale).getString(key, "<red>Missing message: " + key + "</red>");
    }

    /** Like {@link #raw(String, String)}, but returns {@code null} instead of a visible placeholder string when {@code key} isn't defined - for callers that need to tell "missing" apart from "resolved to text" (e.g. {@link org.misqzy.flectonegrowth.common.domain.GenderRegistry#resolveDisplayName}'s own fallback), rather than pattern-matching the placeholder text. */
    public String rawOrNull(String key) {
        return rawOrNull(defaultLocale, key);
    }

    public String rawOrNull(String locale, String key) {
        return data(locale).getString(key, null);
    }

    public Component get(String key, Map<String, Object> placeholders) {
        return get(defaultLocale, key, placeholders);
    }

    public Component get(String locale, String key, Map<String, Object> placeholders) {
        String raw = inlineTemplateText(raw(locale, key), placeholders);
        return MINI.deserialize(stripClosingTags(raw), themeResolvers(), buildResolver(placeholders));
    }

    public Component get(String key) {
        return get(defaultLocale, key);
    }

    public Component get(String locale, String key) {
        return MINI.deserialize(stripClosingTags(raw(locale, key)), themeResolvers());
    }

    public String heightUnit() {
        return heightUnit(defaultLocale);
    }

    public String heightUnit(String locale) {
        return raw(locale, "height.unit");
    }

    /**
     * The closing-tag-stripped, {@link TemplateText}-inlined raw MiniMessage
     * string for {@code key} - exactly what {@link #get(String, Map)} would
     * hand to its own {@code MiniMessage.deserialize}, exposed so a platform
     * module can instead hand it to FlectonePulse's own
     * {@code MessagePipeline.build} when routing a message through
     * FlectonePulse (see that module's {@code FlectonePulseMessageDispatcher}).
     * {@code <fcolor:N>} is deliberately left unresolved here - FlectonePulse
     * resolves that tag itself as part of its own pipeline, the whole reason
     * for going through it in the first place, so this class must not race
     * it with its own resolution first.
     */
    public String rawForDispatch(String key, Map<String, Object> placeholders) {
        return rawForDispatch(defaultLocale, key, placeholders);
    }

    public String rawForDispatch(String locale, String key, Map<String, Object> placeholders) {
        return stripClosingTags(inlineTemplateText(raw(locale, key), placeholders));
    }

    /**
     * {@code <primary>}/{@code <secondary>} plus this call's placeholders -
     * everything {@link #themeResolvers()} resolves *except* {@code <fcolor:N>}.
     * Paired with {@link #rawForDispatch(String)} for the FlectonePulse
     * dispatch path: FlectonePulse's own pipeline adds its own
     * {@code <fcolor:N>} resolver on top of whatever it's handed, so this
     * class only needs to supply the theme/placeholder tags it alone knows
     * about, not fcolor.
     */
    public TagResolver externalDispatchResolvers(Map<String, Object> placeholders) {
        return TagResolver.resolver(primarySecondaryResolvers(), buildResolver(placeholders));
    }

    private static String stripClosingTags(String message) {
        if (!message.contains("</primary>") && !message.contains("</secondary>") && !message.contains("</fcolor")) {
            return message;
        }
        return CLOSING_TAGS.matcher(message).replaceAll("");
    }

    private TagResolver themeResolvers() {
        return TagResolver.resolver(
                primarySecondaryResolvers(),
                TagResolver.resolver("fcolor", (queue, ctx) -> Tag.preProcessParsed(resolveFColor(queue)))
        );
    }

    private TagResolver primarySecondaryResolvers() {
        return TagResolver.resolver(
                TagResolver.resolver("primary", (queue, ctx) -> Tag.preProcessParsed(asTag(primaryColor))),
                TagResolver.resolver("secondary", (queue, ctx) -> Tag.preProcessParsed(asTag(secondaryColor)))
        );
    }

    /** Mirrors FlectonePulse's own {@code <fcolor:N>} argument handling: missing/non-numeric index -> no-op, not an error. */
    private String resolveFColor(ArgumentQueue queue) {
        if (!queue.hasNext()) return "";
        OptionalInt index = queue.pop().asInt();
        if (index.isEmpty()) return "";

        return asTag(fcolors.get().get(index.getAsInt()));
    }

    /** {@code null}/blank -> no-op; a bare name or hex ({@code "gold"}, {@code "#FFD700"}) -> wrapped ({@code "<gold>"}); already-tagged input passed through as-is. */
    private static String asTag(String value) {
        if (value == null || value.isBlank()) return "";
        return value.startsWith("<") ? value : "<" + value + ">";
    }

    /**
     * A placeholder value that must be inlined as literal, tag-escaped text
     * directly into the raw message string <b>before</b> MiniMessage parses
     * it, rather than resolved via a {@link TagResolver} during parsing -
     * the only way to make a value reachable <b>inside another tag's own
     * quoted argument</b> (e.g. {@code <click:suggest_command:'/<usage>'>}
     * or {@code <hover:show_text:'...<usage>'>}): MiniMessage tokenizes a
     * tag's argument as a literal string up front and never recurses into it
     * to resolve a nested tag or placeholder (verified with a standalone
     * harness - {@code <click:suggest_command:'<command>'>} leaves the
     * argument as the literal, unresolved text {@code "<command>"}). Ported
     * from studying FlectonePulse's own localization templates, which embed
     * {@code <click>}/{@code <hover>} directly around a placeholder the same
     * way - the templates write the tags themselves, this class just needs
     * to make the substitution happen at the right stage.
     *
     * <p>Escaped via {@link MiniMessage#escapeTags(String, TagResolver)}
     * against this class's own {@link #themeResolvers()} - not the
     * no-args overload, which only knows MiniMessage's standard built-in
     * tags and would let a value containing a literal {@code <primary>}/
     * {@code <secondary>}/{@code <fcolor:N>} slip through unescaped (verified
     * empirically: the no-args overload leaves any tag name it doesn't
     * recognize completely untouched, including ours). Plus a defensive
     * single-quote escape, since by convention every tag argument in this
     * project's templates is {@code '...'}-quoted - so a value containing
     * {@code <}/{@code >} (e.g. a Cloud command's own {@code <type>}
     * argument placeholder in a corrected-syntax string) can't be misparsed
     * as a tag once inlined as plain content, and can't prematurely close
     * the argument it's inlined into.</p>
     *
     * <p>One accepted gap: inside another tag's own quoted argument (e.g.
     * {@code suggest_command}'s target), that argument is taken as a fully
     * literal string with no escape processing of its own, so if escaping
     * ever did insert a backslash there (only possible if the inlined value
     * genuinely collided with a real tag name), the argument would carry a
     * stray backslash the plain-content occurrence of the same value
     * wouldn't. Not worth splitting the substitution by context for - every
     * caller today inlines a Cloud command's own literal/argument names,
     * which are developer-chosen and never collide with a real tag name.</p>
     */
    public record TemplateText(String value) {}

    /** Inlines every {@link TemplateText} placeholder into {@code raw} by literal, escaped string substitution - see that record's javadoc for why this has to happen before parsing instead of via a {@link TagResolver}. Every other placeholder type is left untouched here; {@link #buildResolver} handles those during parsing as usual. */
    private String inlineTemplateText(String raw, Map<String, Object> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return raw;
        String result = raw;
        for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
            if (entry.getValue() instanceof TemplateText text) {
                String escaped = MINI.escapeTags(text.value(), themeResolvers()).replace("'", "\\'");
                result = result.replace("<" + entry.getKey() + ">", escaped);
            }
        }
        return result;
    }

    /** Placeholder values are usually stringified ({@link Placeholder#unparsed}); a {@link TemplateText} is skipped here since {@link #inlineTemplateText} already substituted it directly into the raw string before this resolver is ever consulted. */
    private static TagResolver buildResolver(Map<String, Object> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return TagResolver.empty();
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof TemplateText) continue;
            builder.resolver(Placeholder.unparsed(entry.getKey(), String.valueOf(value)));
        }
        return builder.build();
    }
}
