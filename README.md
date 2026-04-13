# Stash Manager

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that scans, indexes, and provides Discord-queryable access to container inventories in a Minecraft world. Supports PostgreSQL persistence, a built-in REST API with Prometheus metrics, webhook notifications, and full configuration via Discord commands.

![Build](https://github.com/PoseidonsCave/Stash-Management/actions/workflows/build.yml/badge.svg)

## Features

- **Region-based container scanning** — tick-driven state machine walks to, opens, and reads every container in a defined area
- **Container types** — chests, barrels, shulker boxes, hoppers, dispensers, droppers
- **Shulker introspection** — reads nested inventory contents via NBT
- **Double chest deduplication** — large chests are merged into a single entry
- **Return-to-start** — bot pathfinds back to its original position after a scan completes
- **Container labels** — assign custom names to containers for easy identification
- **Saved regions** — name and persist scan regions in the database for reuse
- **Stash organizer** — automated item sorting across containers by type with column detection, shulker packing, and overflow handling
- **PostgreSQL persistence** — all scanned containers stored in a database for long-term querying
- **REST API** — embedded HTTP server with JSON endpoints and Prometheus-format metrics
- **Webhook notifications** — POST JSON payloads to external services (n8n, etc.) on scan completion
- **Safe staged updates** — checks GitHub releases, optionally downloads the next plugin JAR, and loads it on the next restart
- **Full Discord configuration** — every setting is viewable and changeable via Discord commands
- **CSV export** — export the full index as a CSV file attachment in Discord

---

## Installation

1. Build the plugin JAR (or download from [Releases](https://github.com/PoseidonsCave/Stash-Management/releases))
2. Place `stash-manager-1.1.0.jar` in ZenithProxy's `plugins/` directory
3. Restart ZenithProxy

## Building

Requires Java 21+.

```sh
./gradlew build
```

The output JAR will be in `build/libs/`.

---

## Commands

All commands work via **Discord**, **terminal**, and **in-game chat**.

### Scanning

| Command | Description |
|---------|-------------|
| `stash pos1 [x y z]` | Set scan region corner 1 (defaults to player position) |
| `stash pos2 [x y z]` | Set scan region corner 2 (defaults to player position) |
| `stash scan` | Start scanning containers in the defined region |
| `stash stop` | Abort an in-progress scan |
| `stash update` | Check GitHub releases and stage the latest JAR for the next restart |
| `stash update check` | Check whether a newer release exists without downloading it |
| `stash status` | Show scan state, region, container counts, DB/API status |

### Index

| Command | Description |
|---------|-------------|
| `stash list [page]` | Paginated list of indexed containers |
| `stash export` | Export index to CSV (file attachment in Discord) |
| `stash clear` | Clear the in-memory index (region positions retained) |
| `stash clearall` | Clear both memory index and database |
| `stash summary` | Detailed index summary with item type breakdown |
| `stash label <x> <y> <z> <label>` | Assign a label to a container |
| `stash labels` | List all labeled containers |
| `stashsearch <item>` | Search for containers holding matching items |

### Database

| Command | Description |
|---------|-------------|
| `stash db status` | Show database connection info and row counts |
| `stash db clear` | Delete all data from the database |

### Regions

| Command | Description |
|---------|-------------|
| `stash region save <name>` | Save the current pos1/pos2 as a named region |
| `stash region load <name>` | Load a saved region into pos1/pos2 |
| `stash region list` | List all saved regions |
| `stash region delete <name>` | Delete a saved region |

### Organizer

| Command | Description |
|---------|-------------|
| `stash organize` | Start sorting items across containers by type |
| `stash organize stop` | Stop the organizer mid-run |
| `stash organize status` | Show organizer state and progress |

### Supply Chests

| Command | Description |
|---------|-------------|
| `stashsupply add` | Mark the nearest container as a supply chest |
| `stashsupply remove <id>` | Remove a supply chest by index |
| `stashsupply list` | List all registered supply chests |

### Configuration

All settings can be viewed and changed at runtime via Discord. Changes are saved automatically.

| Command | Description |
|---------|-------------|
| `stash config` | Show all current configuration values |
| **Scanner** | |
| `stash config scanDelay <ticks>` | Ticks between container reads (1–200) |
| `stash config openTimeout <ticks>` | Max wait ticks for container open response (1–600) |
| `stash config maxContainers <count>` | Container cap per scan session (1–100000) |
| `stash config waypointDistance <blocks>` | Walk distance for unloaded chunks (1–256) |
| `stash config returnToStart <on\|off>` | Return bot to start position after scan |
| **Database** | |
| `stash config db enable` | Enable PostgreSQL persistence |
| `stash config db disable` | Disable database and disconnect |
| `stash config db url <jdbc-url>` | Set JDBC connection URL |
| `stash config db user <username>` | Set database username |
| `stash config db password <password>` | Set database password |
| `stash config db poolSize <size>` | Connection pool size (1–20) |
| `stash config db connect` | Connect (or reconnect) to the database |
| **API** | |
| `stash config api enable` | Enable the REST API |
| `stash config api disable` | Disable API and stop the server |
| `stash config api port <port>` | Set API listen port (1–65535) |
| `stash config api bind <address>` | Set API bind address |
| `stash config api key <key>` | Set Bearer token for API authentication |
| `stash config api threads <count>` | Set HTTP thread pool size (1–16) |
| `stash config api start` | Start the API server |
| `stash config api stop` | Stop the API server |
| **Webhook** | |
| `stash config webhook <url>` | Set webhook URL (use `off` to clear) |
| **Updates** | |
| `stash config updates` | Show updater settings and the last check result |
| `stash config updates checkOnLoad <on\|off>` | Enable/disable startup update checks |
| `stash config updates autoDownload <on\|off>` | Automatically stage new releases during startup checks |

---

## Configuration Reference

Saved automatically via ZenithProxy's plugin config system.

### Scanner

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | `true` | Enable/disable the module |
| `scanDelayTicks` | `5` | Ticks between container reads |
| `openTimeoutTicks` | `60` | Max wait for container open response |
| `maxContainers` | `2048` | Container cap per scan session |
| `waypointDistance` | `48` | Walk distance for unloaded chunks |
| `returnToStart` | `true` | Pathfind back to starting position after scan |

### Organizer

| Setting | Default | Description |
|---------|---------|-------------|
| `organizerEnabled` | `true` | Enable/disable the stash organizer |
| `organizerClickCooldownTicks` | `3` | Ticks between inventory slot clicks |
| `organizerOpenTimeoutTicks` | `60` | Max wait ticks for container open |
| `condenseMinItems` | `1` | Minimum loose items to justify shulker packing |

### Database (PostgreSQL)

| Setting | Default | Description |
|---------|---------|-------------|
| `databaseEnabled` | `false` | Enable database persistence |
| `databaseUrl` | `jdbc:postgresql://localhost:5432/stashmanager` | JDBC connection URL |
| `databaseUser` | `stashmanager` | Database username |
| `databasePassword` | *(empty)* | Database password |
| `databasePoolSize` | `3` | HikariCP connection pool size |

### API Server

| Setting | Default | Description |
|---------|---------|-------------|
| `apiEnabled` | `false` | Enable the embedded HTTP API |
| `apiBindAddress` | `0.0.0.0` | Listen address |
| `apiPort` | `8585` | Listen port |
| `apiThreads` | `2` | HTTP handler thread pool size |
| `apiKey` | *(empty)* | Bearer token for authentication (empty = no auth) |

### Webhook

| Setting | Default | Description |
|---------|---------|-------------|
| `webhookUrl` | *(empty)* | URL to POST scan-completion payloads to |

### Plugin Updates

| Setting | Default | Description |
|---------|---------|-------------|
| `updateCheckOnLoad` | `true` | Check GitHub for a newer plugin release during startup |
| `updateAutoDownload` | `false` | Download and stage a newer plugin JAR automatically during startup checks |

---

## Database Setup

The database lets your scanned containers persist across restarts so you can search them anytime via Discord — no external tools required.

### Step 1: Install PostgreSQL

**Windows:**

1. Download the installer from [postgresql.org/download/windows](https://www.postgresql.org/download/windows/)
2. Run the installer. Use the defaults — just set a password for the `postgres` superuser when prompted (remember this password)
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

### Step 2: Create the database

Open a terminal (or **SQL Shell (psql)** on Windows, found in your Start menu after installing PostgreSQL).

Connect as the superuser:

```sh
# Linux / macOS
sudo -u postgres psql

# Windows (SQL Shell will prompt you — press Enter for defaults, then enter
# the superuser password you set during install)
```

Then run:

```sql
CREATE USER stashmanager WITH PASSWORD 'pick_a_password';
CREATE DATABASE stashmanager OWNER stashmanager;
\q
```

That's it for the database side — the plugin creates all tables automatically.

### Step 3: Connect the plugin

Run these commands in Discord (or terminal / in-game chat):

```
stash config db url jdbc:postgresql://localhost:5432/stashmanager
stash config db user stashmanager
stash config db password pick_a_password
stash config db enable
stash config db connect
```

You should see a **"Database Connected"** confirmation. From this point on, every scan saves its results to the database and all `stash list`, `stash export`, and `stashsearch` commands query from it automatically.

### Verify it's working

```
stash db status
```

This shows the connection state and how many containers/items are stored.

### What the database gives you

- **Persistence** — container data survives plugin/proxy restarts
- **Faster searches** — `stashsearch` queries the database instead of scanning memory
- **History** — `scan_history` table tracks every scan run with timestamps and counts
- **Bulk export** — `stash export` pulls from the database for complete CSV dumps

### Database tables (created automatically)

| Table | Contents |
|-------|----------|
| `containers` | Position, type, dimension, item count, first/last seen timestamps, label |
| `container_items` | Slot, item ID, display name, count per container |
| `scan_history` | Start/end time, container count, status per scan run |
| `regions` | Named scan regions with pos1/pos2 coordinates |
| `config` | Key-value plugin configuration pairs |
| `storage_chests` | Registered supply chest positions |
| `keep_items` | Items the organizer should leave in place |

---

## REST API

When enabled, the API server exposes the following endpoints. All endpoints require a `Authorization: Bearer <apiKey>` header if an API key is configured.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/status` | Scanner status (state, region, counts, uptime) |
| `GET` | `/api/v1/containers?page=1&size=50` | Paginated container list |
| `GET` | `/api/v1/search?item=diamond` | Search containers by item name |
| `GET` | `/api/v1/stats` | Aggregate statistics (totals, types, top items) |
| `GET` | `/api/v1/metrics` | Prometheus-format metrics |
| `GET` | `/api/v1/organizer` | Organizer state and task progress |
| `GET` | `/api/v1/regions` | Saved region list |
| `POST` | `/api/v1/webhook/test` | Send a test webhook payload |

### Example

```sh
curl -H "Authorization: Bearer mykey" http://localhost:8585/api/v1/stats
```

### Prometheus / Grafana

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
```

Add this as a Prometheus scrape target and build Grafana dashboards from the `stashmanager_*` metrics.

### n8n / Webhook Integration

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

---

## License

[MIT](LICENSE)
