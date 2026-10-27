package org.misqzy.flectonegrowth.common.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.misqzy.flectonegrowth.common.config.ConfigView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Loads and renders MiniMessage strings from every loaded localisation file
 * into a platform-agnostic {@link Component}. Holds every bundled
 * {@code localizations/messages_<code>.yml}, not just config.yml's
 * configured default - every method has a {@code locale}-taking overload
 * for a specific recipient plus a no-arg overload that uses the configured
 * default. How the {@link Component} reaches a player is up to the platform
 * module.
 *
 * <p>Every message also supports theme-color tags, expanded by a single
 * string pre-processor ({@link #process}) <b>before</b> MiniMessage parses
 * the message - not by MiniMessage {@code TagResolver}s:</p>
 * <ul>
 *   <li>{@code <primary>}/{@code <secondary>} - config.yml's
 *   {@code colors.primary}/{@code colors.secondary}. The opening tag becomes
 *   the value's normalised opening markup ({@code gold} -> {@code <gold>},
 *   {@code &6} -> {@code <gold>}, {@code <gradient:#a:#b>} kept) and
 *   {@code </primary>} the matching closing markup ({@code </gold>},
 *   {@code </gradient>}; several tags close in reverse order), so colors
 *   really end where the template says.</li>
 *   <li>{@code <fcolor:N>} - FlectonePulse's default color palette, looked up
 *   live via a {@link Supplier} on every render. {@code </fcolor:N>} closes
 *   that index's markup, bare {@code </fcolor>} the most recently opened one.
 *   Missing/non-numeric index or unknown closer -> empty string.</li>
 * </ul>
 *
 * <p>The pre-processor leaves backslash-escaped tags ({@code \<primary>}) alone
 * and, inside a tag's quoted arguments, only substitutes placeholders (and
 * theme tags only inside {@code <hover>}).</p>
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();


    private static final String[] LEGACY_NAMES = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
    };

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
    private final Map<CacheKey, String> expanded = new ConcurrentHashMap<>();
    /** Safety cap on {@link #expanded}: keys come from code-defined message keys x loaded locales, so this is never reached in practice. */
    private static final int MAX_CACHED = 1024;

    /**
     * @param locales       every loaded {@code localizations/messages_<code>.yml}, keyed by that
     *                      lowercase locale code (e.g. {@code "en"}, {@code "ru"}).
     * @param defaultLocale config.yml's configured {@code locale} - used by every overload below that
     *                      doesn't take an explicit locale, and as the fallback when an explicit one
     *                      isn't loaded.
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
        String value = data(locale).getString(key, null);
        return value != null ? value : "<red>Missing message: " + key + "</red>";
    }

    /** Like {@link #raw(String, String)}, but returns {@code null} instead of a visible placeholder string when {@code key} isn't defined. */
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
        Map<String, String> templates = templateValues(placeholders, false);
        String text = templates.isEmpty() ? processCached(locale, key, false) : process(raw(locale, key), templates, false);
        return MINI.deserialize(text, buildResolver(placeholders));
    }

    public Component get(String key) {
        return get(defaultLocale, key);
    }

    public Component get(String locale, String key) {
        return MINI.deserialize(processCached(locale, key, false));
    }

    public String heightUnit() {
        return heightUnit(defaultLocale);
    }

    public String heightUnit(String locale) {
        return raw(locale, "height.unit");
    }

    public String rawForDispatch(String key, Map<String, Object> placeholders) {
        return rawForDispatch(defaultLocale, key, placeholders);
    }

    /**
     * One fully-resolved MiniMessage string, to be handed to FlectonePulse's
     * pipeline as <b>plain text</b> (no adventure types cross over):
     * <ul>
     *   <li>every placeholder is inlined: {@link TemplateText} as today, every
     *   other value as literal text with {@code \} and {@code <} backslash-escaped
     *   and {@code §} defanged by
     *   {@link #neutralizeLegacy(String)};</li>
     *   <li>{@code <primary>}/{@code <secondary>} and their closers are expanded;</li>
     *   <li>{@code <fcolor:N>} is left untouched for FlectonePulse's per-player
     *   palette, while {@code </fcolor...>} closers are <b>stripped</b>
     *   (FlectonePulse's handling of them is unverified), so an fcolor applies
     *   forward until the next color change.</li>
     * </ul>
     */
    public String rawForDispatch(String locale, String key, Map<String, Object> placeholders) {
        Map<String, String> templates = templateValues(placeholders, true);
        return templates.isEmpty() ? processCached(locale, key, true) : process(raw(locale, key), templates, true);
    }

    /**
     * Defangs the section sign: MiniMessage throws on a raw {@code §} in its input, so a zero-width
     * space is inserted after every one (invisible in the client). {@code &} is left untouched - the
     * dispatcher disables FlectonePulse's legacy {@code &} conversion.
     */
    public static String neutralizeLegacy(String value) {
        if (value == null || value.isEmpty() || value.indexOf('§') < 0) return value;
        return value.replace("§", "§​");
    }

    /**
     * A placeholder value that must be inlined as literal, escaped text
     * directly into the raw message string <b>before</b> MiniMessage parses
     * it - the only way to make a value reachable inside another tag's quoted
     * argument (e.g. {@code <click:suggest_command:'/<usage>'>}), since
     * MiniMessage never resolves a nested placeholder there.
     */
    public record TemplateText(String value) {}

    // ---------------------------------------------------------------- placeholders

    /** key (lowercase) -> raw value to inline. Always TemplateText; with {@code all}, every other value too (legacy-neutralised). */
    private static Map<String, String> templateValues(Map<String, Object> placeholders, boolean all) {
        if (placeholders == null || placeholders.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Object> e : placeholders.entrySet()) {
            Object v = e.getValue();
            if (e.getKey() == null) continue;
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (v instanceof TemplateText t) {
                out.put(key, neutralizeLegacy(t.value() == null ? "" : t.value()));
            } else if (all) {
                out.put(key, neutralizeLegacy(String.valueOf(v)));
            }
        }
        return out;
    }

    /**
     * Escapes a value for inlining. Backslash always. Plain text: also {@code <}. Inside a tag's
     * quoted argument: the enclosing quote char (if any), and {@code <} only when the argument is
     * re-parsed as MiniMessage ({@code hover}); a click/url payload keeps {@code <} literal.
     */
    private static String escapeLiteral(String value, boolean inArg, boolean reparsed, char quote) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean esc = c == '\\'
                    || (!inArg && c == '<')
                    || (inArg && reparsed && c == '<')
                    || (inArg && quote != 0 && c == quote);
            if (esc) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /** Placeholder values are stringified via {@link Placeholder#unparsed} (never interpreted); a {@link TemplateText} is skipped since it was already inlined. */
    private static TagResolver buildResolver(Map<String, Object> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return TagResolver.empty();
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof TemplateText || entry.getKey() == null) continue;
            try {
                builder.resolver(Placeholder.unparsed(entry.getKey().toLowerCase(Locale.ROOT), String.valueOf(value)));
            } catch (IllegalArgumentException ignored) {
                // key MiniMessage refuses as a tag name - skip it rather than fail the whole message
            }
        }
        return builder.build();
    }

    // ---------------------------------------------------------------- pre-processor

    /** An opened generated tag: {@code kind} is primary/secondary/fcolor, {@code idx} the fcolor index (else -1), {@code close} its closing markup. */
    private record Open(String kind, int idx, String close) {}

    private static final class Ctx {
        final Map<String, String> templates;
        final boolean dispatch;
        final Supplier<Map<Integer, String>> fcolors;
        private Map<Integer, String> palette;
        /** Whether the output depends on the live FlectonePulse palette (an {@code <fcolor:N>} opener was expanded). */
        boolean paletteUsed;
        List<Open> stack = new ArrayList<>();

        Ctx(Map<String, String> templates, boolean dispatch, Supplier<Map<Integer, String>> fcolors) {
            this.templates = templates;
            this.dispatch = dispatch;
            this.fcolors = fcolors;
        }

        /** Looked up lazily, on the first {@code <fcolor:N>} actually met: most messages have none, and the live lookup goes through FlectonePulse's service container. */
        Map<Integer, String> palette() {
            paletteUsed = true;
            if (palette == null) {
                Map<Integer, String> read;
                try {
                    read = fcolors.get();
                } catch (RuntimeException e) {
                    read = null;
                }
                palette = read != null ? read : Map.of();
            }
            return palette;
        }
    }

    /** (locale or null, key, dispatch) -> pre-expanded template; see {@link #processCached}. */
    private record CacheKey(String locale, String key, boolean dispatch) {}

    /**
     * Placeholder-free messages expand to the same string every time, so the result is cached per
     * (locale, key, mode) - unless it depended on FlectonePulse's live fcolor palette (which can change
     * on its reload), in which case it is recomputed each call. A new {@link Messages} is built on every
     * plugin reload, so the cache never outlives the config/localisation it was derived from.
     */
    private String processCached(String locale, String key, boolean dispatch) {
        CacheKey cacheKey = new CacheKey(hasLocale(locale) ? locale : null, key, dispatch);
        String cached = expanded.get(cacheKey);
        if (cached != null) return cached;

        String message = raw(locale, key);
        if (message.isEmpty()) return message;
        Ctx ctx = new Ctx(Map.of(), dispatch, fcolors);
        String result = scan(neutralizeLegacy(message), ctx, true, null);
        if (!ctx.paletteUsed && expanded.size() < MAX_CACHED) expanded.put(cacheKey, result);
        return result;
    }

    private String process(String message, Map<String, String> templates, boolean dispatch) {
        if (message == null || message.isEmpty()) return message;
        return scan(neutralizeLegacy(message), new Ctx(templates, dispatch, fcolors), true, null);
    }

    /** @param argTag null for message text; else the (lowercase) name of the tag whose arguments {@code s} is. */
    private String scan(String s, Ctx ctx, boolean theme, String argTag) {
        int n = s.length();
        if (s.indexOf('<') < 0) return s; // no tag can start: output equals input
        StringBuilder out = new StringBuilder(n + 16);
        char quote = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                out.append(c).append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c != '<') {
                if (argTag != null) {
                    if (quote == 0 && (c == '\'' || c == '"') && i > 0 && s.charAt(i - 1) == ':') quote = c;
                    else if (quote != 0 && c == quote) quote = 0;
                }
                out.append(c);
                i++;
                continue;
            }
            int end = findTagEnd(s, i);
            if (end < 0) {
                out.append(c);
                i++;
                continue;
            }
            String inner = s.substring(i + 1, end);
            String repl = handleTag(inner, ctx, theme, argTag, quote);
            if (repl != null) {
                out.append(repl);
            } else {
                int colon = inner.indexOf(':');
                String name = (colon < 0 ? inner : inner.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
                boolean hover = name.equals("hover");
                out.append('<');
                if (hover) {
                    // a hover argument is a separate MiniMessage parse: theme tags there open and close on their own
                    List<Open> saved = ctx.stack;
                    ctx.stack = new ArrayList<>();
                    out.append(scan(inner, ctx, theme, name));
                    ctx.stack = saved;
                } else {
                    out.append(scan(inner, ctx, false, name));
                }
                out.append('>');
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** Index of the '>' closing the tag opened at {@code start}, honouring quoted args and escapes; -1 if this '<' is plain text. */
    private static int findTagEnd(String s, int start) {
        char quote = 0;
        for (int j = start + 1; j < s.length(); j++) {
            char ch = s.charAt(j);
            if (ch == '\\') { j++; continue; }
            if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
            } else if (ch == '>') {
                return j;
            } else if (ch == '<') {
                return -1;
            }
        }
        return -1;
    }

    /** Replacement text for a tag we own, or {@code null} to leave it (and scan its arguments). */
    private String handleTag(String inner, Ctx ctx, boolean theme, String argTag, char quote) {
        boolean closing = inner.startsWith("/");
        String body = closing ? inner.substring(1) : inner;
        int colon = body.indexOf(':');
        String name = (colon < 0 ? body : body.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        String arg = colon < 0 ? null : body.substring(colon + 1).trim();

        if (!closing) {
            String value = ctx.templates.get(name);
            if (value != null) return escapeLiteral(value, argTag != null, "hover".equals(argTag), quote);
        }
        if (!theme) return null;

        switch (name) {
            case "primary", "secondary" -> {
                if (arg != null) return null;
                if (closing) return closeOpen(ctx, name, -1);
                String opening = asTag(name.equals("primary") ? primaryColor : secondaryColor);
                ctx.stack.add(new Open(name, -1, closeFor(opening)));
                return opening;
            }
            case "fcolor" -> {
                if (ctx.dispatch) return closing ? "" : null;
                if (!closing) {
                    int idx = parseIndex(arg);
                    String opening = idx < 0 ? "" : asTag(ctx.palette().get(idx));
                    ctx.stack.add(new Open("fcolor", idx, closeFor(opening)));
                    return opening;
                }
                if (arg == null) return closeOpen(ctx, "fcolor", -1);
                int idx = parseIndex(arg);
                return idx < 0 ? "" : closeOpen(ctx, "fcolor", idx);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Closer for the most recent still-open {@code kind} (and {@code idx} if >= 0), or "" if none is open.
     * Tags opened after it are implicitly closed by MiniMessage too, so their closers are emitted first -
     * which also keeps a later crossed closer from becoming a stray one.
     */
    private static String closeOpen(Ctx ctx, String kind, int idx) {
        List<Open> stack = ctx.stack;
        for (int k = stack.size() - 1; k >= 0; k--) {
            Open o = stack.get(k);
            if (o.kind().equals(kind) && (idx < 0 || o.idx() == idx)) {
                StringBuilder sb = new StringBuilder();
                for (int j = stack.size() - 1; j >= k; j--) sb.append(stack.get(j).close());
                stack.subList(k, stack.size()).clear();
                return sb.toString();
            }
        }
        return "";
    }

    private static int parseIndex(String arg) {
        if (arg == null || arg.isEmpty() || arg.length() > 9) return -1;
        for (int i = 0; i < arg.length(); i++) {
            char ch = arg.charAt(i);
            if (ch < '0' || ch > '9') return -1;
        }
        return Integer.parseInt(arg);
    }

    // ---------------------------------------------------------------- color normalisation

    private static final Set<String> NAMED = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey",
            "dark_gray", "dark_grey", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "obf",
            "rainbow", "gradient", "transition");

    /** Tags with no closing counterpart. */
    private static final Set<String> VOID = Set.of(
            "reset", "br", "newline", "lang", "lang_or", "tr", "translate", "tr_or", "translate_or",
            "key", "keybind", "sel", "selector", "score", "nbt", "data", "head", "sprite");

    private static final Pattern PARAM_TAG = Pattern.compile("(?i)(gradient|rainbow|transition|color|colour|c|shadow):[^<>\\s]*");

    /**
     * Normalised opening markup for a configured color; anything unrecognised -> {@code ""} (never throws,
     * never leaks literal text, never returns a raw {@code §}). Accepts: already-tagged markup made only of
     * tags ({@code "<gradient:#a:#b>"}); names ({@code "gold"}); {@code #rgb}, {@code #rrggbb}, bare {@code rrggbb};
     * legacy {@code &6}, {@code §6§l}, {@code &#rrggbb&l}, {@code &x&f&f&0&0&0&0} (either sign);
     * and comma/space separated lists of those. A legacy {@code &r} drops everything before it.
     */
    static String asTag(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.isEmpty()) return "";
        if (v.startsWith("<")) return sanitizeTagged(v);

        StringBuilder sb = new StringBuilder();
        for (String token : v.split("[,\\s]+")) {
            if (!token.isEmpty()) sb.append(tokenTag(token));
        }
        return sb.toString();
    }

    /** Only tags allowed (whitespace between them ignored); a raw section sign or stray text -> "". */
    private static String sanitizeTagged(String v) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < v.length()) {
            char ch = v.charAt(i);
            if (Character.isWhitespace(ch)) { i++; continue; }
            if (ch != '<') return "";
            int end = findTagEnd(v, i);
            if (end < 0) return "";
            String tag = v.substring(i, end + 1);
            if (tag.indexOf('§') >= 0) return "";
            sb.append(tag);
            i = end + 1;
        }
        return sb.toString();
    }

    private static String tokenTag(String t) {
        char first = t.charAt(0);
        if (first == '&' || first == '§') return legacyTags(t);
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("#")) {
            String hex = lower.substring(1);
            if (isHex(hex, 6)) return "<#" + hex + ">";
            if (isHex(hex, 3)) return "<#" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2) + ">";
            return "";
        }
        if (isHex(lower, 6)) return "<#" + lower + ">";
        if (NAMED.contains(lower) || PARAM_TAG.matcher(t).matches()) return "<" + t + ">";
        return "";
    }

    private static boolean isHex(String s, int len) {
        if (s.length() != len) return false;
        for (int i = 0; i < len; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private static boolean isSign(char c) {
        return c == '&' || c == '§';
    }

    /** Parses one legacy run ({@code &6&l}, {@code &#rrggbb&l}, {@code &x&r&r&g&g&b&b}); any malformed part -> "". */
    private static String legacyTags(String t) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = t.length();
        while (i < n) {
            if (!isSign(t.charAt(i)) || i + 1 >= n) return "";
            char code = Character.toLowerCase(t.charAt(i + 1));
            if (code == '#') {
                if (i + 8 > n || !isHex(t.substring(i + 2, i + 8), 6)) return "";
                sb.append("<#").append(t.substring(i + 2, i + 8).toLowerCase(Locale.ROOT)).append('>');
                i += 8;
            } else if (code == 'x') {
                if (i + 14 > n) return "";
                StringBuilder hex = new StringBuilder();
                for (int k = 0; k < 6; k++) {
                    int p = i + 2 + k * 2;
                    if (!isSign(t.charAt(p)) || Character.digit(t.charAt(p + 1), 16) < 0) return "";
                    hex.append(Character.toLowerCase(t.charAt(p + 1)));
                }
                sb.append("<#").append(hex).append('>');
                i += 14;
            } else if (code == 'r') {
                sb.setLength(0);
                i += 2;
            } else {
                String tag = legacyTag(code);
                if (tag == null) return "";
                sb.append(tag);
                i += 2;
            }
        }
        return sb.toString();
    }

    private static String legacyTag(char code) {
        int d = Character.digit(code, 16);
        if (d >= 0) return "<" + LEGACY_NAMES[d] + ">";
        return switch (code) {
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            default -> null;
        };
    }

    /** Closing markup for an opening-markup string: {@code </name>} of each top-level closable tag, in reverse order. Negations ({@code <!b>}) and void tags ({@code <reset>}, {@code <br>}) have none. */
    static String closeFor(String opening) {
        if (opening == null || opening.isEmpty()) return "";
        List<String> closers = new ArrayList<>();
        int i = 0;
        while (i < opening.length()) {
            if (opening.charAt(i) == '\\') { i += 2; continue; }
            if (opening.charAt(i) != '<') { i++; continue; }
            int end = findTagEnd(opening, i);
            if (end < 0) { i++; continue; }
            String inner = opening.substring(i + 1, end);
            int colon = inner.indexOf(':');
            String name = (colon < 0 ? inner : inner.substring(0, colon)).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && name.charAt(0) != '!' && name.charAt(0) != '/' && !VOID.contains(lower)) {
                closers.add("</" + name + ">");
            }
            i = end + 1;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = closers.size() - 1; k >= 0; k--) sb.append(closers.get(k));
        return sb.toString();
    }
}
