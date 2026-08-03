# FlectoneGrowth — Release Notes (v0.1.1 → v0.2.4)

A summary of everything new for players and server owners since v0.1.1,
newest first.

---

## v0.2.4

- **Fixed schema migrations ignoring a custom database table prefix.**
  If `storage.database.table-prefix` (added in v0.2.3) was changed from its
  default, the automatic schema-upgrade steps that run on startup still
  targeted the old, hardcoded table names underneath - on MySQL/MariaDB/H2
  this could fail outright or silently patch the wrong table. Migrations
  now always target the actually-configured table names.
- **Safer config/data file writes.** `player_data.yml` (YAML storage) and
  every migrated config file (`config.yml`, `gender.yml`, `integrations.yml`,
  translation files) are now written to a temporary file and atomically
  swapped into place, instead of being overwritten in place - a crash or
  power loss mid-write can no longer leave a truncated file behind. Config
  migration also refuses to rewrite a file if backing it up first failed,
  rather than proceeding without a safety copy.
- **More reliable plugin reloads when only connection details change.**
  `/flectonegrowth reload` now also reconnects storage if you edit the DB
  host, port, credentials, table prefix, or connection-pool settings while
  keeping the same backend - previously only switching backend entirely
  triggered a reconnect, so those edits silently kept using the old
  connection until a full restart.
- Storage errors are now logged with their full stack trace instead of
  just a one-line message, making real problems much easier to diagnose
  from the console.
- Backend groundwork for an upcoming playtime-import admin command (adds
  to a player's existing tracked playtime instead of overwriting it - no
  user-facing change yet on its own).
- Fixed a rare race where a player quitting (and possibly rejoining) while
  a height/gender change or profile load was still being written to
  storage could resurrect a stale profile or clobber a newer one.
- Internal cleanup: case-insensitive comparisons (gender keys, storage
  type parsing) no longer depend on the server's default locale, which
  could otherwise behave unexpectedly under some regional JVM locale
  settings.

---

## v0.2.3

- **New: configurable database table prefix.** `storage.database.table-prefix`
  in `config.yml` (default `flectonegrowth`) lets a network sharing one
  physical MySQL/MariaDB/H2 database run more than one FlectoneGrowth
  instance against it without their tables colliding - useful if you already
  run another plugin, or another FlectoneGrowth install, on the same
  database. No need to type the trailing separator yourself, it's added
  automatically. If you change it on an existing install, your data is
  renamed over automatically rather than starting empty.
- **New: configurable database connection parameters.**
  `storage.database.params` lets you override the JDBC connection string's
  parameters for MySQL/MariaDB (e.g. to require SSL) instead of the plugin's
  built-in defaults. Leave it blank (the default) and nothing changes.
- **Fixed a brief flash to maximum height right when a player joins**, on
  servers with the "pause growth while boxed in" check enabled - a joining
  player could very briefly appear at full height before settling into
  their real, in-progress size a moment later.
- **Reduced the performance cost of the "pause growth while boxed in"
  check**, especially on servers with many online players - a stationary
  player no longer re-scans the blocks around them on every growth tick,
  only when they've actually moved.
- **More reliable plugin reloads** when switching database backends via
  `/flectonegrowth reload` - closed a narrow timing window that could
  otherwise cause a transient storage error during the switch.
- The startup console notice **and** the in-game update notification now
  both correctly link to this project's tags page instead of a releases
  page that was never used.
- **`/flectonegrowth help` and `/growth summary`/`height`/`gender` now
  arrive as one message** instead of several separate chat lines - easier
  to read, and no longer possible for another plugin's chat message to end
  up sandwiched in the middle of the output.
- Internal cleanup and code organization improvements (deduplicated storage
  code, simplified some integration internals). No other changes to how the
  plugin looks, feels, or behaves.

---

## v0.2.2

- **Config files keep their comments after an update.** Upgrading the
  plugin used to lose every explanatory comment in `config.yml`/`gender.yml`
  when they were automatically updated to the new version's shape - your
  settings were always kept, just not the comments describing them. They're
  now preserved.
- **Translation files now keep your edits across updates too**, instead of
  being fully replaced by the new bundled translation on every upgrade -
  any wording you customized in `messages_en.yml`/`messages_ru.yml` stays
  put, and newly added messages are still added automatically. (One
  tradeoff: if a future update improves the wording of a message you've
  already customized, you won't get that improvement automatically anymore -
  delete the file to get the fresh version if you want it.)
- **Fixed being unable to set your height to a value the plugin itself just
  showed you.** `/height` displays its min/max limits rounded to 2 decimal
  places; typing that exact displayed value back could be rejected due to
  rounding, e.g. a configured minimum of `0.3` displayed as `0.56` m, but
  `/height 0.56` could fail. Setting your height to the displayed limit now
  always works.
- **New `integrations.yml` file** lets you turn off individual integrations
  (or just part of one) without removing the other plugin - e.g. disable
  PlaceholderAPI support entirely, or keep FlectonePulse's server-id/message
  routing but turn off just its color-palette matching. Everything defaults
  to on, so nothing changes unless you edit this file.

---

## v0.2.1

- **Messages now show in each player's own client language** instead of a
  single language for the whole server - if a translation for a player's
  client locale isn't bundled, they fall back to the language set in
  `config.yml` (as before).
- **PlaceholderAPI placeholders now work for offline players**, not just
  players currently online - useful for leaderboard/hologram plugins that
  query offline players' height, gender, or growth progress.
- **Simplified network playtime settings.** The old `network.blocklist`
  (a list every server on the network had to keep in sync) is replaced by
  two settings each server sets for itself: whether to track its own
  playtime separately (`network.per-server`) and whether its playtime
  counts toward growth at all (`network.include-server`). Existing installs
  are migrated automatically - a server that was previously blocklisted
  keeps behaving the same way.
- **More reliable diagnostics** when FlectonePulse is installed - a single
  consolidated health-check now logs the status of server-id detection,
  theme-color matching, and message dispatch on startup/reload, instead of
  each one failing silently on its own.

---

## v0.2.0

Version housekeeping release. Includes everything listed under v0.1.7
below - no additional changes on top of it.

---

## v0.1.7

- **Growth now stays consistent across your whole server network**, not
  just on whichever server a player happens to be standing on - when you
  run multiple servers sharing one network (with server sync turned on),
  a player's growth progress is tracked centrally instead of separately on
  each server.
  - If you only run a single server, nothing changes for you.
  - New setting: a "blocklist" for servers (like a hub or lobby) whose
    playtime shouldn't count toward growth - so idling in a lobby doesn't
    help a player grow.
- **Supports a wider range of server versions.** The plugin previously
  required the very latest Minecraft server version to even load; it now
  works on older servers too (back to Minecraft 1.20.5), while still fully
  supporting the newest versions.

---

## v0.1.6

- **Fixed two crashes** that could happen on server startup or when using
  plugin commands if FlectonePulse wasn't installed. FlectoneGrowth now works
  correctly whether or not FlectonePulse is present.

---

## v0.1.5

- Internal cleanup and code organization improvements. No changes to how
  the plugin looks, feels, or behaves for players or server owners.

---

## v0.1.4

- **One universal plugin file now supports every common server type**
  (Spigot, CraftBukkit, Paper, and Purpur) - if you were previously using a
  separate Paper-only version, switch to the standard version on your next
  update; everything works the same, including full support for nicer
  in-game text on Paper/Purpur servers.
- **Added a shorter command alias**: `/pg` now works anywhere `/flectonegrowth`
  does (e.g. `/pg reload`, `/pg help`).
- **Added an automatic update checker.** The plugin now checks for new
  versions on startup, logs a note in the console if one is available, and
  can notify operators in-game the next time they join. This can be turned
  off in the config if you'd rather not be notified.

---

## v0.1.3

- **Reloading the plugin no longer freezes the server**, even briefly -
  reloading is now smooth and also tells you how long it took.
- **Translations now update properly when you upgrade the plugin.** Before,
  only brand-new messages were added on upgrade; existing message text and
  colors could get stuck on old wording. Now the full translation always
  updates to match the new version (your own custom settings in the main
  config are still preserved).
- **Chat messages now match FlectonePulse's own styling more closely** when
  FlectonePulse is installed, including working correctly with things like
  Discord bridges.

---

## v0.1.2

- **Deeper FlectonePulse integration** - the plugin now talks to
  FlectonePulse directly instead of guessing settings from its files,
  making server-identification more reliable on networks.
- **Added customizable theme colors.** You can now set your own primary and
  secondary colors for FlectoneGrowth's messages from the config, and the
  plugin can also match FlectonePulse's own configured colors automatically.
- **Fixed colored text gradients showing as a single flat color** instead
  of a proper gradient on some servers.
- The config file was reorganized for clarity; existing installs are
  updated automatically - nothing you need to do manually.

---

## v0.1.1

- **Rebuilt the plugin's internal foundations** for better long-term
  stability and easier future updates. This is an under-the-hood change and
  doesn't affect how the plugin looks or plays.
- **Added a read-only API** so other plugins on your server can read a
  player's height, gender, and growth progress directly, without needing
  PlaceholderAPI as a middle step.
