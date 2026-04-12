# Grafana Monitoring Setup for Stash Manager

This guide walks you through connecting the Stash Manager plugin to Grafana so you can visualize your container scanning data on a live dashboard. Everything runs on your local machine — no cloud servers needed.

## How it works

```
┌──────────────────┐       ┌──────────────┐       ┌─────────────┐
│  Stash Manager   │──────▶│  Prometheus   │──────▶│   Grafana   │
│  (port 8585)     │scrape │  (port 9090)  │query  │  (port 3000)│
│  /api/v1/metrics │every  │  stores data  │       │  dashboards │
└──────────────────┘ 30s   └──────────────┘       └─────────────┘
```

- **Stash Manager** exposes live metrics at an HTTP endpoint
- **Prometheus** polls that endpoint on a timer and stores the history
- **Grafana** reads from Prometheus and renders graphs/panels

## Prerequisites

- Docker and Docker Compose installed ([get Docker](https://docs.docker.com/get-docker/))
- ZenithProxy running with the Stash Manager plugin loaded

---

## Step 1: Enable the plugin's API server

Run these commands in Discord (or terminal/in-game):

```
stash config api key pick_a_secret_key
stash config api port 8585
stash config api enable
stash config api start
```

Verify it's working:

```sh
curl -H "Authorization: Bearer pick_a_secret_key" http://localhost:8585/api/v1/metrics
```

You should see text output like:

```
# HELP stash_containers_total Total number of indexed containers
# TYPE stash_containers_total gauge
stash_containers_total 0

# HELP stash_scanner_state Current scanner state
# TYPE stash_scanner_state gauge
stash_scanner_state 0

# HELP stash_database_connected Whether the database is connected
# TYPE stash_database_connected gauge
stash_database_connected 0
```

If you have the database connected and containers scanned, you'll also see `stash_items_total`, `stash_unique_item_types`, and `stash_shulkers_total`.

---

## Step 2: Create the config files

Create a new folder somewhere convenient (e.g. `stash-monitoring/`) and add these two files:

### docker-compose.yml

```yaml
services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    extra_hosts:
      - "host.docker.internal:host-gateway"
    restart: unless-stopped

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    volumes:
      - grafana_data:/var/lib/grafana
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=admin
    restart: unless-stopped

volumes:
  prometheus_data:
  grafana_data:
```

### prometheus.yml

```yaml
global:
  scrape_interval: 30s

scrape_configs:
  - job_name: 'stashmanager'
    metrics_path: /api/v1/metrics
    authorization:
      credentials: pick_a_secret_key
    static_configs:
      - targets: ['host.docker.internal:8585']
```

> **Important:** The `credentials` value must match exactly what you set with `stash config api key`. The target `host.docker.internal:8585` tells the Docker container to reach your host machine's port 8585 where ZenithProxy is running.

---

## Step 3: Start the stack

From the `stash-monitoring/` folder:

```sh
docker compose up -d
```

This downloads and starts both Prometheus and Grafana in the background.

---

## Step 4: Verify Prometheus is scraping

1. Open **http://localhost:9090/targets** in your browser
2. You should see a `stashmanager` target with State **UP**
3. If it says **DOWN**, check that:
   - The API server is running (`stash config api start`)
   - The API key matches between `prometheus.yml` and what you set in the plugin
   - Port 8585 isn't blocked by a firewall

---

## Step 5: Connect Grafana to Prometheus

1. Open **http://localhost:3000** in your browser
2. Log in with `admin` / `admin` (it will ask you to change the password — you can skip)
3. Go to **Connections** → **Data Sources** → **Add data source**
4. Select **Prometheus**
5. Set the URL to: `http://prometheus:9090`
6. Click **Save & Test** — you should see a green "Successfully queried the Prometheus API" message

---

## Step 6: Create your first dashboard

1. Click **Dashboards** → **New** → **New Dashboard** → **Add visualization**
2. Select your **Prometheus** data source
3. In the query field at the bottom, type a metric name. Start with: `stash_containers_total`
4. Click **Run queries** — you should see data appear
5. Give the panel a title (e.g. "Total Containers") and click **Apply**

Repeat for more panels. Here are useful queries:

| Panel Title | Query | Visualization |
|-------------|-------|---------------|
| Total Containers | `stash_containers_total` | Stat |
| Total Items | `stash_items_total` | Stat |
| Unique Item Types | `stash_unique_item_types` | Stat |
| Shulker Boxes | `stash_shulkers_total` | Stat |
| Scanner State | `stash_scanner_state` | Stat |
| Database Connected | `stash_database_connected` | Stat |
| Scan Progress | `stash_scan_containers_indexed` | Time series |
| Containers Found vs Indexed | `stash_scan_containers_found` and `stash_scan_containers_indexed` | Time series |
| Failed Reads | `stash_scan_containers_failed` | Time series |
| Pending Containers | `stash_scan_containers_pending` | Time series |
| Organizer Active | `stash_organizer_active` | Stat |
| Organizer Progress | `stash_organizer_tasks_completed` / `stash_organizer_tasks_total` | Gauge / Bar gauge |

> **Tip:** For the scanner state panel, you can use **Value mappings** in Grafana to show human-readable names: 0=Idle, 1=Scanning, 2=Walking, 3=Opening, 4=Reading, 5=Closing, 6=Walking to Zone, 7=Returning, 8=Done.

---

## Available metrics reference

| Metric | Description | Requires DB |
|--------|-------------|-------------|
| `stash_containers_total` | Number of containers in the index | No |
| `stash_scanner_state` | Scanner state as a number (0–8) | No |
| `stash_scan_containers_found` | Containers found in current/last scan | No |
| `stash_scan_containers_indexed` | Containers successfully read | No |
| `stash_scan_containers_failed` | Containers that failed to read | No |
| `stash_scan_containers_pending` | Containers remaining in current scan | No |
| `stash_last_scan_timestamp_seconds` | Unix timestamp of last scan completion | No |
| `stash_database_connected` | 1 if database is connected, 0 if not | No |
| `stash_items_total` | Total item count across all containers | Yes |
| `stash_unique_item_types` | Number of distinct item types | Yes |
| `stash_shulkers_total` | Total shulker boxes found | Yes |
| `stash_organizer_active` | 1 if the organizer is running, 0 if not | No |
| `stash_organizer_tasks_completed` | Move tasks completed in the current organizer run | No |
| `stash_organizer_tasks_total` | Total move tasks planned for the current run | No |

---

## Example PromQL queries

Scan progress percentage:

```promql
stash_scan_containers_indexed / stash_scan_containers_found
```

Alert if scan is stalled for 10 minutes:

```promql
stash_scanner_state > 0 and changes(stash_scan_containers_indexed[10m]) == 0
```

Organizer progress percentage:

```promql
stash_organizer_tasks_completed / stash_organizer_tasks_total
```

---

## Stopping / restarting

```sh
# Stop everything (data is preserved in Docker volumes)
docker compose down

# Start again
docker compose up -d

# Remove everything including stored data
docker compose down -v
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Prometheus target shows DOWN | Run `stash status` to confirm the API is running. Check the API key matches. |
| Grafana shows "No data" | Wait 30–60 seconds for Prometheus to scrape. Check the data source test passes. |
| Can't reach localhost:3000 | Run `docker compose ps` to confirm containers are running. |
| Metrics don't include item totals | Connect the database (`stash config db connect`). Item/shulker metrics require it. |
| Changed the API port | Update the `targets` value in `prometheus.yml` and restart: `docker compose restart prometheus` |
