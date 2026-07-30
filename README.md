# FlectoneGrowth

FlectoneGrowth is a Minecraft server plugin that grows a player's in-game
height gradually over played time, instead of every player being the same
fixed size from the moment they join. Height is applied through the vanilla
scale attribute, so it works with any resource pack or skin and needs no
client-side mod.

## Features

### Growth

- **Gradual growth over played time.** Each player grows from a configurable
  minimum height toward a maximum, linearly, based on minutes actually
  played — not wall-clock time, so it can't be cheesed by staying connected
  AFK. Growth can also pause automatically while a player is boxed in by
  solid blocks (`growth.pause-when-boxed-in`).
- **Fixed or randomized growth duration.** Either every player takes the
  same configured number of minutes to reach full height
  (`growth.time-minutes`), or — if a min/max range is configured instead
  (`growth.time-min-minutes` / `growth.time-max-minutes`) — each player is
  assigned their own random target on first join, persisted so it stays the
  same across restarts.
- **Per-gender height caps.** Gender types are fully configurable in
  `gender.yml` — add, rename, or alias as many as you like, each with its
  own maximum height — or turn the whole gender system off and use one
  shared cap for everyone.
- **Manual overrides.** Players (or admins, for other players) can set an
  exact custom height at any time, which is then respected as-is instead of
  being overwritten by the growth curve. Setting a gender that caps below an
  existing custom height clamps it down automatically.

### Networks and cross-server play

- **Centralized playtime tracking.** On a network sharing one database
  (`network.sync-enabled: true`), a player's growth-driving playtime is
  tracked centrally instead of separately per server, so splitting time
  between backend servers doesn't desync their growth. Two settings apply
  per server, not network-wide:
  - `network.per-server` — track this server's played time in its own
    bucket instead of contributing to one network-wide total.
  - `network.include-server` — whether time spent on this server counts
    toward growth at all; set to `false` on a hub/lobby so idling there
    doesn't help a player grow.
- **Live cross-server sync.** A lightweight Velocity proxy relay
  (`minecraft/velocity`) propagates height/gender changes between backend
  servers immediately, instead of players seeing stale values until they
  reconnect. The database stays the single source of truth — sync is a
  latency optimization, not a requirement. Optional: skip the Velocity
  module entirely on a single-server setup.
- **FlectonePulse-aware.** If FlectonePulse is installed, FlectoneGrowth can
  reuse its configured server identifier (through its Java API, not by
  re-parsing its config file) so the two plugins never disagree about which
  backend server they're each running on, route chat messages through
  FlectonePulse's own delivery pipeline, and match its `<fcolor:N>` color
  palette. Each of these is toggled independently in `integrations.yml`.

### Storage

- **Four backends**: H2 (embedded, zero setup, the default), MySQL, MariaDB,
  or plain YAML files — switch with `storage.type`.
- **Configurable table prefix.** `storage.database.table-prefix` (default
  `flectonegrowth`, separator added automatically) lets a network sharing
  one physical MySQL/MariaDB/H2 database run more than one FlectoneGrowth
  instance against it without their tables colliding. Existing tables are
  renamed automatically the first time this changes.
- **Configurable connection parameters.** `storage.database.params` lets you
  override the JDBC URL's query string (e.g. `useSSL=true&characterEncoding=utf8`)
  for MySQL/MariaDB instead of using the plugin's built-in defaults.
- **Automatic fallback.** If the configured backend fails to connect on
  startup, FlectoneGrowth falls back to YAML storage and logs a warning
  instead of leaving the plugin half-broken.

### Integrations

- **PlaceholderAPI support.** A rich set of `%flectonegrowth_*%` placeholders
  covering current/min/max height (in meters and as a raw scale value),
  gender, growth percentage, time remaining, and custom-scale status —
  documented in-game via `/papi info flectonegrowth`. Works for offline
  players too (e.g. leaderboard/hologram plugins), not just those online.
- **API for other plugins.** A typed, read-only Java API (`FlectoneGrowthAPI`)
  registered through Bukkit's `ServicesManager`, exposing the same
  height/gender/growth-progress data as the placeholders above without
  needing PlaceholderAPI installed or string parsing.
- **Per-integration toggles.** `integrations.yml` lets you turn off
  PlaceholderAPI support, or any individual part of the FlectonePulse
  integration (color matching, server-id reuse, message dispatch),
  independently — without removing the other plugin. Everything defaults to
  on.

### Messages and locale

- **Localized messages.** Bundled English and Russian translations, fully
  MiniMessage-formatted so colors and styling are easy to customize; more
  locales can be added by dropping in another `messages_<locale>.yml`.
- **Per-player locale.** Each player sees messages in their own Minecraft
  client language when a matching translation file is bundled, falling back
  to `config.yml`'s configured `locale` otherwise (and for console senders).
- **Theme colors.** Every message supports `<primary>`/`<secondary>`
  theme-color tags, configurable once in `config.yml` so the whole plugin
  can be reskinned from one place, alongside any of MiniMessage's own tags.

### Operations

- **Safe config/database migrations.** Upgrading the plugin merges in newly
  added config keys automatically without discarding your customizations or
  comments, and applies any needed database schema changes — all files are
  backed up first.
- **Update notifications.** Checks GitHub once on startup for a newer
  release; if one exists, warns in console and notifies players with
  `flectonegrowth.update-notify` (default: op) on join. Disable entirely
  with `update-checker.enabled: false`.
- **Live reload.** `/flectonegrowth reload` re-reads every config file and
  re-applies growth settings to already-online players, without a restart —
  runs off the main thread so it doesn't freeze the server, and reports how
  long it took.

## Supported platforms

| Module | Use when... |
|---|---|
| `minecraft/bukkit` | Your backend server — works unmodified on Spigot, CraftBukkit, Paper, Purpur, and any other Bukkit-API server software. Detects Paper's native Adventure support at runtime and uses it automatically when available, with no separate build needed for Paper. |
| `minecraft/velocity` | You run a Velocity proxy and want cross-server sync between backend servers. Optional — skip it entirely on a single-server setup. |

Install `minecraft/bukkit` on every backend server. There is no BungeeCord
support; Velocity is the only supported proxy. Folia is not supported (no
region-scheduler support for entity-affecting work).

## Commands

| Command | Description | Permission (default) |
|---|---|---|
| `/height <meters>` | Set your own custom height. | `flectonegrowth.height` (everyone) |
| `/height remove` | Remove your custom height and resume natural growth. | `flectonegrowth.height` (everyone) |
| `/height set <player> <meters>` | Set another player's custom height. | `flectonegrowth.height.others` (op) |
| `/height remove <player>` | Remove another player's custom height. | `flectonegrowth.height.others` (op) |
| `/gender <type>` | Set your own gender. | `flectonegrowth.gender` (everyone) |
| `/gender set <player> <type>` | Set another player's gender. | `flectonegrowth.gender.others` (op) |
| `/growth summary [player]` | View height, gender, and growth progress. | `flectonegrowth.info` (everyone) / `.others` (op) |
| `/growth height [player]` | View height and growth progress only. | `flectonegrowth.info` (everyone) / `.others` (op) |
| `/growth gender [player]` | View gender only. | `flectonegrowth.info` (everyone) / `.others` (op) |
| `/flectonegrowth reload` (alias `/pg reload`) | Reload config/gender/messages without a restart. | `flectonegrowth.admin.reload` (op) |
| `/flectonegrowth help` (alias `/pg help`) | List every command available to you. | `flectonegrowth.help` (everyone) |

`flectonegrowth.update-notify` (default: op) is not a command permission —
it controls who gets notified in-game about a pending plugin update.

## Configuration

Each installed module ships with:

- **`config.yml`** — server id, growth speed/curve, scale bounds, theme
  colors, storage backend (including table prefix/connection params),
  cross-server network settings, update checker.
- **`gender.yml`** — gender types, aliases, per-type max height.
- **`integrations.yml`** — per-integration on/off toggles (PlaceholderAPI,
  FlectonePulse and its submodules).
- **`localizations/messages_<locale>.yml`** — every player-facing message,
  one file per locale.

All four are commented in place and regenerate automatically if deleted;
existing installs are migrated forward automatically on version upgrades
(with a timestamped backup first) without losing customizations or
comments.

## Building from source

```bash
./gradlew build
```

Builds all three modules (`core`, `minecraft/bukkit`, `minecraft/velocity`).
Each platform module produces a shaded jar under its own `build/libs/`
(e.g. `FlectoneGrowth-Bukkit-<version>.jar`) ready to drop into a
`plugins/` folder.
