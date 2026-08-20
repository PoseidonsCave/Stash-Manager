<div align="center">

# 📦 Stash Manager

**Give a ZenithProxy bot a stash and let it scan, search, organize, and report what it finds.**

[![Build](https://github.com/PoseidonsCave/Stash-Manager/actions/workflows/build.yml/badge.svg)](https://github.com/PoseidonsCave/Stash-Manager/actions/workflows/build.yml)
[![Downloads](https://img.shields.io/github/downloads/PoseidonsCave/Stash-Manager/total)](https://github.com/PoseidonsCave/Stash-Management/releases)
[![License](https://img.shields.io/badge/license-AGPL%20v3-blue.svg)](LICENSE)

[![Join the Discord](https://img.shields.io/badge/Join%20the%20Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/6v5greuSp)

[Quick start](#-quick-start) • [Commands](#-commands) • [Database setup](#-database-setup) • [API](#-rest-api)

</div>

Stash Manager is a [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin for people who
want a bot to understand a Minecraft storage system instead of treating it like a pile of random
chests. It remembers scanned inventories, finds items from Discord, plans dedicated storage lanes,
and produces a shareable workbook that tells you what needs to be built.

## ✨ What it can do

| | Capability | What you get |
|:--:|------------|--------------|
| 🔎 | **Scan a stash** | The bot walks the region, opens supported containers, and records their contents. |
| 📦 | **Understand shulkers** | Nested items, empty boxes, bulk boxes, and mixed kits are kept distinct. |
| 🧭 | **Plan storage lanes** | See how many lanes and double chests each exact item type needs. |
| 🗂️ | **Organize safely** | Known bulk shulkers are sorted first, then loose items are packed into matching boxes. |
| 🔍 | **Find and retrieve items** | Search the index or ask the bot to collect an item or saved kit. |
| 💾 | **Remember everything** | PostgreSQL keeps scans, labels, regions, assignments, and kits across restarts. |
| 📊 | **Share useful reports** | Download a styled XLSX lane plan without exposing stash coordinates. |
| 🤖 | **Connect your stack** | Use the JSON API, Prometheus metrics, Grafana, or webhook events. |

> [!IMPORTANT]
> Run `stash scan` before `stash organize`. The organizer uses the latest scan to protect mixed
> shulkers and size every lane. If permanent space is short, registered import chests can hold
> newly reconciled bulk shulkers until you expand the stash.

## 🚀 Quick start

1. Install the JAR that matches your ZenithProxy target.
2. Stand at one corner of the stash and run `stash pos1`.
3. Stand at the opposite corner and run `stash pos2`.
4. Run `stash scan`, then check the layout with `stash lanes`.
5. Run `stash organize`. A **Good to go** report means everything has permanent space; otherwise,
   make sure import chests are registered for temporary staging.

```text
stash pos1
stash pos2
stash scan
stash lanes
stash organize
```

Use `stash lanes export` whenever you want the full **Overview**, **What to Build**, and
**All Lanes** workbook.

## 📥 Installation

1. Build the plugin JAR (or download from [Releases](https://github.com/PoseidonsCave/Stash-Management/releases))
2. Choose the JAR whose `+<target>` suffix exactly matches your ZenithProxy Minecraft target and place it in ZenithProxy's `plugins/` directory
3. Restart ZenithProxy

Supported targets are `1.21.4`, `1.21.8`, `1.21.11`, `26.1.2` (the 26.1 family), and `26.2.0` (the 26.2 family).

## 🛠️ Building from source

Requires Java 25. Builds for older targets still emit Java 21 bytecode.

```sh
./gradlew test collectVersionJars
```

Stonecutter builds all five targets and collects the JARs in `build/libs/`. Each filename includes
its Minecraft target. To build one target, run a task such as `./gradlew :1.21.4:shadowJar`.

### CI Notes

GitHub Actions protects release builds with dependency review, Gradle wrapper validation, SHA-256
checksums, and artifact provenance. Release tags must match `plugin_version` in
`gradle.properties`. Artifact attestations require a public repository unless the organization
uses a qualifying GitHub plan.

## 🎮 Commands

All commands work through **Discord**, the **terminal**, and **in game chat**.

### 🔎 Scanning

| Command | Description |
|---------|-------------|
| `stash pos1 [x y z]` | Set scan region corner 1 (defaults to player position) |
| `stash pos2 [x y z]` | Set scan region corner 2 (defaults to player position) |
| `stash scan` | Start scanning containers in the defined region |
| `stash stop` | Stop the active scan |
| `stash update` | Check GitHub releases and stage the latest JAR for the next restart |
| `stash update check` | Check whether a newer release exists without downloading it |
| `stash status` | Show scan state, region, container counts, DB/API status |

### 📚 Index and search

| Command | Description |
|---------|-------------|
| `stash list [page]` | Paginated list of indexed containers |
| `stash export` | Export index to CSV (file attachment in Discord) |
| `stash clear` | Clear the memory index while keeping region positions |
| `stash clearall` | Clear both memory index and database |
| `stash summary` | Detailed index summary with item type breakdown |
| `stash label <x> <y> <z> <label>` | Assign a label to a container |
| `stash labels` | List all labeled containers |
| `stashsearch <item>` | Search for containers holding matching items |

### 💾 Database

| Command | Description |
|---------|-------------|
| `stash db status` | Show database connection info and row counts |
| `stash db clear` | Delete all data from the database |

### 🗺️ Saved regions

| Command | Description |
|---------|-------------|
| `stash region save <name>` | Save the current pos1/pos2 as a named region |
| `stash region load <name>` | Load a saved region into pos1/pos2 |
| `stash region list` | List all saved regions |
| `stash region delete <name>` | Delete a saved region |

### 🎒 Kits and retrieval

These commands use the indexed container data stored in PostgreSQL, so the database must be enabled and connected first.

| Command | Description |
|---------|-------------|
| `stash kit list` | List all saved kits |
| `stash kit show <name>` | Show the saved contents of a kit |
| `stash kit snapshot <name>` | Save the player's current main inventory as a kit |
| `stash kit add <name> <item_id> <count>` | Add or replace one item entry in a kit |
| `stash kit remove <name> <item_id>` | Remove one item entry from a kit |
| `stash kit delete <name>` | Delete a saved kit |
| `stash get <item_id> [count]` | Start retrieving one item from indexed containers |
| `stash get kit <name>` | Start retrieving every item listed in a saved kit |
| `stash get status` | Show retrieval progress and remaining items |
| `stash get stop` | Stop the active retrieval task |

### 🧭 Organizer

| Command | Description |
|---------|-------------|
| `stash organize` | Start sorting items across containers by type |
| `stash organize stop` | Stop the organizer during a run |
| `stash organize status` | Show organizer state and progress |
| `stash lanes` | Show lane count, per item lane sizes, and required double chest construction |
| `stash lanes export` | Download the styled, coordinate free lane planning workbook as XLSX |
| `stash import` | Assign the chest currently being faced as an organizer intake chest |
| `stash import remove` | Remove the intake role from the chest currently being faced |
| `stash import list` | List persisted intake chest block positions |
| `stash import purge` | Preview removal of every persisted import assignment |
| `stash import purge confirm` | Remove every import assignment without altering chest contents |

#### How it stays safe

| Step | What happens |
|:---:|--------------|
| 1 | The scan identifies empty, bulk, mixed, and unknown shulkers. |
| 2 | Mixed shulkers and premade kits are left alone. The bot never guesses what they should become. |
| 3 | Every exact bulk item type gets its own lane. Fortune and Silk Touch tools remain separate. |
| 4 | Existing lanes are reused when they are large enough. The bot reports the permanent lane gaps when they are not. |
| 5 | Loose items with lanes are packed into permanent storage. Items without suitable lanes are packed into bulk shulkers and staged in registered import chests. |
| 6 | Partial matching shulkers are filled before empty ones are used. Mixed boxes and kits remain untouched. |

The capacity check uses each item's real stack size and the free room inside matching shulkers.
`stash lanes` turns that into plain lane and double chest counts. `stash lanes export` gives you a
styled workbook with a summary, a build list, and the full lane breakdown.

> [!CAUTION]
> After upgrading from an older version that grouped shulkers by color, run a fresh `stash scan`
> before organizing. Old records stay unclassified so a kit is never mistaken for bulk storage.

#### Import chests

Normal standalone chests are left alone. Face a chest and run `stash import` when you want the
organizer to drain it into the storage lanes. When permanent lane space is short, an import chest
may also hold newly reconciled bulk shulkers as temporary staging. It never becomes a permanent
lane, and a later scan and organize run can move those boxes once suitable lanes exist. Facing
either half of a double chest assigns or removes the whole chest.

The completion message calls out how many boxes and item types are waiting in imports. If every
registered import is full or unreachable, the organizer stops with the packed box still safely in
the bot inventory.

#### Pausing for other work

Scans and organizer runs yield when another Zenith automation request needs Baritone or the
inventory manager. The job keeps a checkpoint, waits through the configured five minute cooldown,
then rebuilds its live container or reconciliation state before continuing.

When someone connects as the controlling proxy client, the active job pauses immediately and sends
a warning in game and on Discord. Use `/swap` to move into spectator mode within ten minutes. If
control is still active when that grace period ends, the in-memory job checkpoint is discarded;
container moves already completed in the world are not rolled back.

`stash debug recent` records successful job starts and progress as well as failures. Handoffs add
`organize_preempted`, `organize_resumed`, `proxy_control_grace_started`,
`proxy_control_released`, or `proxy_control_grace_expired`, including the saved state and task
counts needed to trace a resume.

#### Keeping lane assignments stable

Enable PostgreSQL if you want an item to keep the same lane across future runs. Without the
database, assignments last only for the current proxy session.

<details>
<summary><strong>API fields for lane planning</strong></summary>

The organizer endpoint exposes the same report under `lane_capacity`, `lane_storage`, and
`lane_construction`. Live organizer fields also show whether import staging is active, how many
boxes were staged, and how many permanent lane gaps remain. Coordinates are excluded from the
shareable construction report.

</details>

### 📤 Supply chests

| Command | Description |
|---------|-------------|
| `stashsupply add` | Mark the nearest container as a supply chest |
| `stashsupply remove <id>` | Remove a supply chest by index |
| `stashsupply list` | List all registered supply chests |

### ⚙️ Live configuration

All settings can be viewed and changed at runtime via Discord. Changes are saved automatically.

| Command | Description |
|---------|-------------|
| `stash config` | Show all current configuration values |
| **Scanner** | |
| `stash config scanDelay <ticks>` | Ticks between container reads (1 to 200) |
| `stash config openTimeout <ticks>` | Max wait ticks for container open response (1 to 600) |
| `stash config maxContainers <count>` | Container cap per scan session (1 to 100000) |
| `stash config preemptionCooldown <seconds>` | Minimum scan/organize pause after another automation task takes control (1 to 3600) |
| `stash config controlGrace <seconds>` | Time a controlling proxy client has to use `/swap` before the job is aborted (60 to 3600) |
| `stash config waypointDistance <blocks>` | Walk distance for unloaded chunks (1 to 256) |
| `stash config returnToStart <on\|off>` | Return bot to start position after scan |
| **Database** | |
| `stash config db enable` | Enable PostgreSQL persistence |
| `stash config db disable` | Disable database and disconnect |
| `stash config db url <jdbc-url>` | Set JDBC connection URL |
| `stash config db user <username>` | Set database username |
| `stash config db password <password>` | Set database password |
| `stash config db poolSize <size>` | Connection pool size (1 to 20) |
| `stash config db connect` | Connect (or reconnect) to the database |
| **API** | |
| `stash config api enable` | Enable the REST API |
| `stash config api disable` | Disable API and stop the server |
| `stash config api port <port>` | Set API listen port (1 to 65535) |
| `stash config api bind <address>` | Set API bind address |
| `stash config api key <key>` | Set Bearer token for API authentication |
| `stash config api threads <count>` | Set HTTP thread pool size (1 to 16) |
| `stash config api start` | Start the API server |
| `stash config api stop` | Stop the API server |
| **Webhook** | |
| `stash config webhook <url>` | Set webhook URL (use `off` to clear) |
| **Updates** | |
| `stash config updates` | Show updater settings and the last check result |
| `stash config updates checkOnLoad <on\|off>` | Enable/disable startup update checks |
| `stash config updates autoDownload <on\|off>` | Automatically stage new releases during startup checks |

## ⚙️ Configuration reference

Saved automatically via ZenithProxy's plugin config system.

### 🔎 Scanner

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | `true` | Enable/disable the module |
| `scanDelayTicks` | `5` | Ticks between container reads |
| `openTimeoutTicks` | `400` | Max wait for container open response |
| `maxContainers` | `2048` | Container cap per scan session |
| `waypointDistance` | `48` | Walk distance for unloaded chunks |
| `scanPreemptionCooldownSeconds` | `300` | Minimum scan/organize pause after yielding to another automation task |
| `proxyControlGraceSeconds` | `600` | Grace period for a controlling client to switch to spectator |
| `returnToStart` | `true` | Pathfind back to starting position after scan |

### 🧭 Organizer

| Setting | Default | Description |
|---------|---------|-------------|
| `organizerEnabled` | `true` | Enable/disable the stash organizer |
| `organizerClickCooldownTicks` | `6` | Ticks between inventory slot clicks |
| `organizerOpenTimeoutTicks` | `60` | Max wait ticks for container open |
| `organizerWalkTimeoutTicks` | `1200` | Max walk time for dense storage layouts |
| `condenseMinItems` | `1` | Minimum loose items to justify shulker packing |

### 💾 Database

| Setting | Default | Description |
|---------|---------|-------------|
| `databaseEnabled` | `false` | Enable database persistence |
| `databaseUrl` | `jdbc:postgresql://localhost:5432/stashmanager` | JDBC connection URL |
| `databaseUser` | `stashmanager` | Database username |
| `databasePassword` | *(empty)* | Database password |
| `databasePoolSize` | `3` | HikariCP connection pool size |

### 🌐 API server

| Setting | Default | Description |
|---------|---------|-------------|
| `apiEnabled` | `false` | Enable the embedded HTTP API |
| `apiBindAddress` | `0.0.0.0` | Listen address |
| `apiPort` | `8585` | Listen port |
| `apiThreads` | `2` | HTTP handler thread pool size |
| `apiKey` | *(empty)* | Bearer token for authentication (empty = no auth) |

### 🔔 Webhook

| Setting | Default | Description |
|---------|---------|-------------|
| `webhookUrl` | *(empty)* | URL that receives completed scan payloads |

### 🔄 Plugin updates

The updater reads ZenithProxy's native Minecraft codec version at runtime, then stages only a
release asset whose `+<target>.jar` suffix and plugin metadata match that target. If the installed
plugin targets another version, a compatible JAR with the same release version can replace it on
restart.

| Setting | Default | Description |
|---------|---------|-------------|
| `updateCheckOnLoad` | `true` | Check GitHub for a newer plugin release during startup |
| `updateAutoDownload` | `false` | Download and stage a newer plugin JAR automatically during startup checks |

## 🗄️ Database setup

The database keeps scanned containers available across restarts, so you can search them from
Discord whenever you need them. No separate database tools are required after setup.

### 1. Install PostgreSQL

**Windows:**

1. Download the installer from [postgresql.org/download/windows](https://www.postgresql.org/download/windows/)
2. Run the installer. Keep the defaults and set a password for the `postgres` superuser when prompted.
3. The installer includes **pgAdmin** (a GUI) and adds PostgreSQL as a Windows service that starts automatically

**Linux (Debian/Ubuntu):**

```sh
sudo apt update && sudo apt install postgresql
sudo systemctl enable --now postgresql
```

**macOS (Homebrew):**

```sh
brew install postgresql@16
brew services start postgresql@16
```

### 2. Create the database

Open a terminal (or **SQL Shell (psql)** on Windows, found in your Start menu after installing PostgreSQL).

Connect as the superuser:

```sh
# Linux / macOS
sudo -u postgres psql

# Windows (SQL Shell will prompt you; press Enter for defaults, then enter
# the superuser password you set during install)
```

Then run:

```sql
CREATE USER stashmanager WITH PASSWORD 'pick_a_password';
CREATE DATABASE stashmanager OWNER stashmanager;
\q
```

That is all you need on the database side. The plugin creates its tables automatically.

### 3. Connect the plugin

Run these commands in Discord, the terminal, or in game chat:

```
stash config db url jdbc:postgresql://localhost:5432/stashmanager
stash config db user stashmanager
stash config db password pick_a_password
stash config db enable
stash config db connect
```

You should see a **"Database Connected"** confirmation. From this point on, every scan saves its results to the database and all `stash list`, `stash export`, and `stashsearch` commands query from it automatically.

### ✅ Check the connection

```
stash db status
```

This shows the connection state and how many containers/items are stored.

### What the database gives you

| | Benefit | What it means |
|:--:|---------|---------------|
| 💾 | **Persistence** | Container data survives plugin and proxy restarts. |
| 🔍 | **Faster searches** | `stashsearch` queries stored data instead of scanning memory. |
| 🕒 | **History** | `scan_history` records every run with timestamps and counts. |
| 📄 | **Complete exports** | `stash export` can pull the full index from PostgreSQL. |
| 🧭 | **Stable lane assignments** | Each exact bulk item keeps the same dedicated lane on future organization runs. |

<details>
<summary><strong>Database tables created automatically</strong></summary>

The plugin owns and updates these tables. You do not need to create them by hand.

| Table | Contents |
|-------|----------|
| `containers` | Position, type, dimension, item count, first/last seen timestamps, label |
| `container_items` | Slot, item ID, display name, count per container |
| `container_shulkers` | Physical shulker slot and color per container, including empty boxes |
| `scan_history` | Start/end time, container count, status per scan run |
| `regions` | Named scan regions with pos1/pos2 coordinates |
| `config` | Plugin configuration keys and values |
| `storage_chests` | Registered supply chest positions |
| `keep_items` | Items the organizer should leave in place |
| `column_assignments` | Item type -> assigned organize column (top chest position), kept stable across runs |


</details>

## 🌐 REST API

When enabled, the API server exposes the following endpoints. All endpoints require a `Authorization: Bearer <apiKey>` header if an API key is configured.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/status` | Scanner status (state, region, counts, uptime) |
| `GET` | `/api/v1/containers?page=1&size=50` | Paginated container list |
| `GET` | `/api/v1/search?item=diamond` | Search containers by item name |
| `GET` | `/api/v1/stats` | Aggregate statistics (totals, types, top items) |
| `GET` | `/api/v1/metrics` | Prometheus metrics |
| `GET` | `/api/v1/organizer` | Organizer state and task progress |
| `GET` | `/api/v1/regions` | Saved region list |
| `POST` | `/api/v1/webhook/test` | Send a test webhook payload |

### Example request

```sh
curl -H "Authorization: Bearer mykey" http://localhost:8585/api/v1/stats
```

### 📊 Prometheus and Grafana

The `/api/v1/metrics` endpoint returns metrics in Prometheus exposition format:

```
stashmanager_containers_total 1234
stashmanager_items_total 56789
stashmanager_scan_state 0
stashmanager_database_connected 1
stashmanager_api_uptime_seconds 3600
stash_organizer_active 0
stash_organizer_tasks_completed 0
stash_organizer_tasks_total 0
stash_organizer_preemptions_total 0
stash_organizer_preemption_cooldown_remaining_seconds 0
stash_organizer_staged_shulkers 0
stash_organizer_staging_storage_classes 0
stash_organizer_permanent_lane_gaps 0
stash_lane_capacity_ready 1
stash_lanes_detected 24
stash_lanes_assignable 22
stash_lanes_required 18
stash_lanes_spare 4
stash_lanes_shortfall 0
stash_shulkers_mixed 3
stash_shulkers_unclassified 0
stash_proxy_control_active 0
stash_proxy_control_grace_remaining_seconds 0
```

Add this as a Prometheus scrape target and build Grafana dashboards from the `stashmanager_*` metrics.

### 🔔 n8n and webhook integration

Set a webhook URL and the plugin will POST a JSON payload when each scan completes:

```
stash config webhook https://your-n8n-instance.example.com/webhook/stash
```

Payload format:

```json
{
  "event": "scan_complete",
  "containersScanned": 150,
  "timestamp": "2025-01-15T12:00:00Z"
}
```

## 📜 License

This project is licensed under the [GNU Affero General Public License v3.0 only](LICENSE).
