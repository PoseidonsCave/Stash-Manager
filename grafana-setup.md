# Monitor Stash Manager with Grafana

Stash Manager can expose live Prometheus metrics for scans, database health, organization jobs,
shulker reconciliation, and storage-lane capacity. Prometheus saves the history and Grafana turns
it into dashboards and alerts.

You can use this guide with one bot or an entire fleet. Docker Compose is included as the simplest
starting point, but the same configuration works with Dokploy, Portainer, Kubernetes, or native
services.

## What connects to what

```text
ZenithProxy + Stash Manager
       │
       │  /api/v1/metrics
       ▼
   Prometheus
       │
       ▼
     Grafana
```

Stash Manager serves the metrics. Prometheus must be able to reach each bot's API address and port.
Grafana only needs to reach Prometheus.

## Choose the setup that looks like yours

| Your setup | Prometheus target example | Notes |
|---|---|---|
| Everything runs directly on one machine | `127.0.0.1:8585` | Simplest network path |
| Bot runs on the host, Prometheus runs in Docker | `host.docker.internal:8585` | Add the Linux host-gateway mapping shown below |
| Bot and Prometheus share a Docker network | `zenithproxy:8585` | Use the bot's service name, not localhost |
| Several host-networked bots share one machine | `host.docker.internal:8585`, `:8586`, `:8587` | Give every bot a unique API port |
| Several bridge-networked bots share one Docker network | `bot-one:8585`, `bot-two:8585` | They may reuse the same internal port because their DNS names differ |
| Prometheus and the bot are on different machines | `<private-ip>:8585` | Prefer a VPN or private network; do not expose the API publicly |

If you are unsure, start with one bot on port `8585`. Add the fleet configuration only after that
target reports `UP` in Prometheus.

> [!TIP]
> The usual one-bot setup is ZenithProxy on the host with Prometheus and Grafana in Docker. For
> that setup, use `0.0.0.0` as the API bind address, `host.docker.internal:8585` as the Prometheus
> target, and the Docker Compose example in this guide.

## 1. Enable the Stash Manager API

Run these commands through Discord, the ZenithProxy terminal, or in game:

```text
stash config api bind 0.0.0.0
stash config api port 8585
stash config api key <strong-random-key>
stash config api enable
stash config api start
```

Use a different port for each bot only when those bots share a host network. Bridge-networked
containers can normally use port `8585` internally because each one has its own network identity.

### Pick the right bind address

| Bind address | Use it when |
|---|---|
| `127.0.0.1` | Prometheus runs directly on the same machine as ZenithProxy |
| `0.0.0.0` | Prometheus reaches the bot through Docker, a private LAN, or a VPN |

Changing the bind address, port, or thread count requires an API restart:

```text
stash config api stop
stash config api start
```

Restarting the ZenithProxy process or container also applies the saved settings.

### Test the endpoint

On Linux or macOS, this avoids placing the API key directly in shell history:

```sh
read -rsp "Stash API key: " STASH_API_TOKEN
curl --fail --silent --show-error \
  -H "Authorization: Bearer ${STASH_API_TOKEN}" \
  http://127.0.0.1:8585/api/v1/metrics
unset STASH_API_TOKEN
```

Replace `127.0.0.1:8585` with the address Prometheus will use when testing from another machine or
container. A successful response begins with `# HELP` and `# TYPE` lines.

> [!IMPORTANT]
> Always configure an API key. The same API server has authenticated endpoints that can return
> inventory and region information. Keep it on a trusted network even if you only scrape metrics.

## 2. Configure Prometheus

Create a `prometheus.yml` file. Choose either the single-bot or multi-bot example.

### Single bot

This example assumes Prometheus runs in Docker while ZenithProxy runs on the Docker host:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: stashmanager
    metrics_path: /api/v1/metrics
    authorization:
      credentials: <same-key-configured-in-stash-manager>
    static_configs:
      - targets: ['host.docker.internal:8585']
        labels:
          bot: main
          service: stashmanager
```

Change only the target when your network layout differs:

```yaml
# Prometheus and ZenithProxy both run directly on the host
- targets: ['127.0.0.1:8585']

# Both containers share a Docker network and the bot service is named zenithproxy
- targets: ['zenithproxy:8585']

# The bot is on another machine's private network address
- targets: ['10.0.0.25:8585']
```

### Multiple bots using the same API key

One scrape job can contain every bot. Always give each target a unique `bot` label.

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: stashmanager
    metrics_path: /api/v1/metrics
    authorization:
      credentials: <shared-stash-api-key>
    static_configs:
      # Host-networked bots need unique ports.
      - targets: ['host.docker.internal:8585']
        labels:
          bot: bot-one
          service: stashmanager
      - targets: ['host.docker.internal:8586']
        labels:
          bot: bot-two
          service: stashmanager
      - targets: ['host.docker.internal:8587']
        labels:
          bot: bot-three
          service: stashmanager
```

If the bots share a bridge network, use service names instead:

```yaml
      - targets: ['bot-one:8585']
        labels:
          bot: bot-one
          service: stashmanager
      - targets: ['bot-two:8585']
        labels:
          bot: bot-two
          service: stashmanager
```

### Multiple bots using different API keys

Prometheus authorization is configured per scrape job. Give each key its own job, but keep the
same `service` label so one Grafana dashboard can find every bot.

```yaml
scrape_configs:
  - job_name: stashmanager-bot-one
    metrics_path: /api/v1/metrics
    authorization:
      credentials: <bot-one-key>
    static_configs:
      - targets: ['host.docker.internal:8585']
        labels:
          bot: bot-one
          service: stashmanager

  - job_name: stashmanager-bot-two
    metrics_path: /api/v1/metrics
    authorization:
      credentials: <bot-two-key>
    static_configs:
      - targets: ['host.docker.internal:8586']
        labels:
          bot: bot-two
          service: stashmanager
```

Do not commit real keys to a repository. For a long-lived deployment, mount the Prometheus
configuration from a protected file or use the secret mechanism provided by your platform.

## 3. Run Prometheus and Grafana

### Docker Compose

Create this `docker-compose.yml` beside `prometheus.yml`:

```yaml
services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "127.0.0.1:9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus_data:/prometheus
    extra_hosts:
      - "host.docker.internal:host-gateway"
    restart: unless-stopped

  grafana:
    image: grafana/grafana:latest
    ports:
      - "127.0.0.1:3000:3000"
    volumes:
      - grafana_data:/var/lib/grafana
    depends_on:
      - prometheus
    restart: unless-stopped

volumes:
  prometheus_data:
  grafana_data:
```

The `extra_hosts` entry makes `host.docker.internal` work on typical Linux Docker installations.
Docker Desktop already provides that hostname.

Start the stack:

```sh
docker compose up -d
```

Open Prometheus at `http://localhost:9090/targets`. Do not continue until the Stash Manager target
shows `UP`.

The example binds both web interfaces to the local machine. If this is a remote server, use an SSH
tunnel, VPN, or authenticated reverse proxy to reach Grafana. Do not remove the `127.0.0.1` binding
and expose either service directly to the public internet.

### Dokploy, Portainer, or another container platform

Deploy `prom/prometheus:latest` and `grafana/grafana:latest` as separate services.

- Persist `/prometheus` for Prometheus history.
- Persist `/var/lib/grafana` for dashboards and users.
- Mount `prometheus.yml` at `/etc/prometheus/prometheus.yml`.
- Attach Grafana and Prometheus to the same private Docker network.
- Give Prometheus a stable service name or network alias.
- Add a host-gateway mapping when Prometheus must scrape a host-networked bot.

Use your platform's generated domain only for the Grafana interface. Prometheus and bot API ports
should normally remain private.

### Native services

If Prometheus and Grafana run directly on the same machine, point Prometheus at
`127.0.0.1:<bot-port>` and point Grafana at `http://127.0.0.1:9090`. Use the operating system's
service manager to keep both processes running and store their data outside temporary directories.

## 4. Connect Grafana to Prometheus

In Grafana, open **Connections → Data sources → Add data source → Prometheus**.

| Grafana deployment | Prometheus URL example |
|---|---|
| Both are in the Compose file above | `http://prometheus:9090` |
| Both run directly on one host | `http://127.0.0.1:9090` |
| Both share another Docker network | `http://<prometheus-service-name>:9090` |
| Prometheus is on another private machine | `http://<private-prometheus-ip>:9090` |

Select **Save & test**. Do not use `localhost` when Grafana is a container and Prometheus is a
different container; `localhost` would point back to Grafana itself.

## 5. Add a bot selector

For a single bot this is optional. For multiple bots, create a dashboard variable named `bot`:

```promql
label_values(stash_containers_total{service="stashmanager"}, bot)
```

Enable **Multi-value** and **Include All** if you want fleet views. Panels can then use:

```promql
stash_containers_total{service="stashmanager",bot=~"$bot"}
```

Use `service="stashmanager"` instead of filtering on a specific job name. This keeps dashboards
working when each bot has its own scrape job or API key.

## 6. Build a useful starter dashboard

### Basic health

| Panel | PromQL | Display |
|---|---|---|
| Bot API up | `up{service="stashmanager",bot=~"$bot"}` | Stat |
| Indexed containers | `stash_containers_total{bot=~"$bot"}` | Stat |
| Database connected | `stash_database_connected{bot=~"$bot"}` | Stat |
| Database writes healthy | `stash_database_write_healthy{bot=~"$bot"}` | Stat |
| Persistence failures | `stash_database_write_failures_total{bot=~"$bot"}` | Time series |
| Last scan age | `time() - stash_last_scan_timestamp_seconds{bot=~"$bot"}` | Stat, seconds |

Use green for `1` and red for `0` on health panels.

### Scanner

| Panel | PromQL | Display |
|---|---|---|
| Scanner state | `stash_scanner_state{bot=~"$bot"}` | Stat |
| Scan progress | `100 * stash_scan_processed_ratio{bot=~"$bot"}` | Gauge, percent |
| Scan success | `100 * stash_scan_success_rate{bot=~"$bot"}` | Gauge, percent |
| Failed containers | `stash_scan_containers_failed{bot=~"$bot"}` | Stat |
| Pending containers | `stash_scan_containers_pending{bot=~"$bot"}` | Time series |
| Task handoffs | `stash_scan_preemptions_total{bot=~"$bot"}` | Stat |
| Resume cooldown | `stash_scan_preemption_cooldown_remaining_seconds{bot=~"$bot"}` | Gauge, seconds |

Scanner state mappings:

| Value | State |
|---:|---|
| 0 | Idle |
| 1 | Zone scanning |
| 2 | Walking |
| 3 | Opening |
| 4 | Reading |
| 5 | Closing |
| 6 | Walking to zone |
| 7 | Returning |
| 8 | Yielded to another task |
| 9 | Done |

`Yielded` is healthy during cooperative task handoff. Pair it with the cooldown panel rather than
treating it as a stalled scan.

### Organizer and temporary import storage

| Panel | PromQL | Display |
|---|---|---|
| Organizer active | `stash_organizer_active{bot=~"$bot"}` | Stat |
| Organizer progress | `100 * stash_organizer_tasks_completed{bot=~"$bot"} / clamp_min(stash_organizer_tasks_total{bot=~"$bot"}, 1)` | Gauge, percent |
| Organizer handoffs | `stash_organizer_preemptions_total{bot=~"$bot"}` | Stat |
| Organizer resume cooldown | `stash_organizer_preemption_cooldown_remaining_seconds{bot=~"$bot"}` | Gauge, seconds |
| Proxy control active | `stash_proxy_control_active{bot=~"$bot"}` | Stat |
| Proxy control grace | `stash_proxy_control_grace_remaining_seconds{bot=~"$bot"}` | Gauge, seconds |
| Boxes waiting in imports | `stash_organizer_staged_shulkers{bot=~"$bot"}` | Stat |
| Staged item types | `stash_organizer_staging_storage_classes{bot=~"$bot"}` | Stat |
| Permanent lane gaps | `stash_organizer_permanent_lane_gaps{bot=~"$bot"}` | Stat |
| Import chest blocks | `stash_import_chest_blocks{bot=~"$bot"}` | Stat |

The three organizer staging values describe the current or most recent organizer run and reset when
the plugin restarts. The lane-capacity metrics below are the better source for a durable construction
backlog after a fresh scan.

### Storage lanes and compaction

| Panel | PromQL | Display |
|---|---|---|
| Permanent capacity ready | `stash_lane_capacity_ready{bot=~"$bot"}` | Stat |
| Capacity status | `stash_lane_capacity_status{bot=~"$bot"}` | State timeline or table |
| Lanes detected | `stash_lanes_detected{bot=~"$bot"}` | Stat |
| Lanes assignable | `stash_lanes_assignable{bot=~"$bot"}` | Stat |
| Lanes required | `stash_lanes_required{bot=~"$bot"}` | Stat |
| More lanes needed | `stash_lanes_shortfall{bot=~"$bot"}` | Stat |
| Item types without room | `stash_lane_storage_classes_unassigned{bot=~"$bot"}` | Stat |
| Shulker spots available | `stash_lane_shulker_slots_assignable{bot=~"$bot"}` | Stat |
| Shulker spots needed now | `stash_lane_shulker_slots_required{bot=~"$bot"}` | Stat |
| Spots needed after compaction | `stash_lane_shulker_slots_compacted{bot=~"$bot"}` | Stat |
| Reclaimable spots | `stash_lane_shulker_slots_reclaimable{bot=~"$bot"}` | Stat |
| Unknown stack sizes | `stash_lane_stack_sizes_unresolved{bot=~"$bot"}` | Stat |
| New lanes to build | `stash_lane_construction_new_lanes{bot=~"$bot"}` | Stat |
| Lanes to expand | `stash_lane_construction_expansions{bot=~"$bot"}` | Stat |
| Double chests to add | `stash_lane_construction_double_chests_to_add{bot=~"$bot"}` | Stat |
| Double chests needed now | `stash_lane_required_dedicated_double_chests{bot=~"$bot"}` | Stat |
| Double chests after compaction | `stash_lane_compacted_required_dedicated_double_chests{bot=~"$bot"}` | Stat |

`stash_lane_capacity_ready = 0` means permanent storage is incomplete. Organization can still pack
loose items when registered import chests are available, but those boxes remain temporary until the
user adds suitable lanes and scans again.

Capacity uses 27 slots per shulker, each item's real maximum stack size, and 54 shulker slots per
double chest. Non-stackable tools consume much more lane space than 64-stack items. Unknown item IDs
are conservatively treated as non-stackable.

### Shulker health

| Panel | PromQL | Display |
|---|---|---|
| Bulk shulkers | `stash_shulkers_bulk{bot=~"$bot"}` | Stat |
| Empty shulkers | `stash_shulkers_empty{bot=~"$bot"}` | Stat |
| Mixed or kit shulkers | `stash_shulkers_mixed{bot=~"$bot"}` | Stat |
| Unclassified shulkers | `stash_shulkers_unclassified{bot=~"$bot"}` | Stat |

Unclassified shulkers are a safety blocker and require a fresh scan. Mixed shulkers are deliberately
preserved and should not be treated as failures.

## 7. Add alerts

Use a waiting period so normal restarts and scan transitions do not trigger unnecessary alerts.

| Alert | PromQL expression | Suggested wait |
|---|---|---|
| Bot API unavailable | `up{service="stashmanager"} == 0` | 2 minutes |
| Database disconnected | `stash_database_connected{service="stashmanager"} == 0` | 5 minutes |
| Database writes unhealthy | `stash_database_write_healthy{service="stashmanager"} == 0` | 2 minutes |
| Active scan stopped progressing | `(stash_scanner_state{service="stashmanager"} > 0 and stash_scanner_state{service="stashmanager"} < 8) and changes(stash_scan_containers_processed{service="stashmanager"}[15m]) == 0` | 5 minutes |
| Proxy control grace nearly expired | `stash_proxy_control_active{service="stashmanager"} == 1 and stash_proxy_control_grace_remaining_seconds{service="stashmanager"} < 120` | 1 minute |
| Permanent lanes missing | `stash_lanes_shortfall{service="stashmanager"} > 0 or stash_lane_storage_classes_unassigned{service="stashmanager"} > 0` | 15 minutes |
| Unclassified shulkers found | `stash_shulkers_unclassified{service="stashmanager"} > 0` | 15 minutes |

Include `{{ $labels.bot }}` in alert titles so a fleet notification says which bot needs attention.

## Metric reference

Operational metrics are available whenever the API is running. Aggregate item totals require a
connected PostgreSQL database.

| Group | Metrics | Database required |
|---|---|:---:|
| Inventory totals | `stash_containers_total` | No |
| Item totals | `stash_items_total`, `stash_unique_item_types`, `stash_shulkers_total` | Yes |
| Scanner | `stash_scanner_state`, `stash_scan_containers_found`, `stash_scan_containers_indexed`, `stash_scan_containers_failed`, `stash_scan_containers_pending`, `stash_scan_containers_processed`, `stash_scan_processed_ratio`, `stash_scan_success_rate`, `stash_scan_failure_rate`, `stash_last_scan_timestamp_seconds` | No |
| Preemption | `stash_scan_preemptions_total`, `stash_scan_preemption_cooldown_remaining_seconds` | No |
| Database | `stash_database_connected`, `stash_database_write_healthy`, `stash_database_write_failures_total` | No |
| Organizer | `stash_organizer_active`, `stash_organizer_tasks_completed`, `stash_organizer_tasks_total`, `stash_organizer_preemptions_total`, `stash_organizer_preemption_cooldown_remaining_seconds`, `stash_organizer_staged_shulkers`, `stash_organizer_staging_storage_classes`, `stash_organizer_permanent_lane_gaps` | No |
| Proxy control | `stash_proxy_control_active`, `stash_proxy_control_grace_remaining_seconds` | No |
| Lane counts | `stash_lane_capacity_ready`, `stash_lane_capacity_status`, `stash_lanes_detected`, `stash_lanes_protected`, `stash_lanes_assignable`, `stash_lanes_required`, `stash_lanes_spare`, `stash_lanes_shortfall` | No |
| Lane storage | `stash_lane_shulker_slots_assignable`, `stash_lane_shulker_slots_required`, `stash_lane_shulker_slots_compacted`, `stash_lane_shulker_slots_reclaimable`, `stash_lane_stack_sizes_unresolved`, `stash_lane_storage_classes_unassigned`, `stash_lane_shulker_slots_unassigned_required` | No |
| Construction | `stash_lane_construction_new_lanes`, `stash_lane_construction_expansions`, `stash_lane_construction_double_chests_to_add`, `stash_lane_required_dedicated_double_chests`, `stash_lane_compacted_required_dedicated_double_chests` | No |
| Shulkers and imports | `stash_shulkers_bulk`, `stash_shulkers_empty`, `stash_shulkers_mixed`, `stash_shulkers_unclassified`, `stash_import_chest_blocks` | No |

## Troubleshooting

| Symptom | What to check |
|---|---|
| Prometheus shows `DOWN` | Test the exact target URL from the Prometheus host or container. Check the bot API, bind address, port, firewall, and Docker network. |
| Target returns `401` | The Prometheus credential does not match that bot's current API key. |
| Target says connection refused | Start the API and confirm nothing else is using the configured port. |
| `host.docker.internal` does not resolve | Add `host.docker.internal:host-gateway` to the Prometheus container or use the host's private Docker gateway address. |
| Container name does not resolve | Put Prometheus and the bot on the same Docker network and use a stable service name or network alias. |
| Grafana cannot reach Prometheus | Use the Prometheus service name rather than localhost when they are separate containers. |
| Dashboard shows only one bot | Remove hard-coded `job` filters and query with `service="stashmanager"` plus the `bot` variable. |
| Two bots overwrite each other in charts | Give every Prometheus target a unique `bot` label. |
| Item totals are missing | Connect PostgreSQL. Operational, scan, organizer, and lane metrics still work without aggregate database statistics. |
| Lane panels are empty or blocked | Run a fresh stash scan and inspect `stash_lane_capacity_status` plus `stash_shulkers_unclassified`. |
| New metrics do not appear | Update Stash Manager, restart the bot, and wait for the next Prometheus scrape. |

## Security and sharing

- Keep bot API ports behind a firewall, private Docker network, private LAN, or VPN.
- Do not add a public reverse-proxy route for the Stash Manager API.
- Use a strong key and rotate it if it appears in logs, screenshots, tickets, chat, or Git history.
- Avoid publishing Prometheus unless it has its own authentication and access controls.
- Share dashboards using bot labels and totals rather than coordinates or container records.

The metrics endpoint does not export Minecraft coordinates. Other authenticated Stash Manager API
endpoints do return container and region positions, which is why the whole API must remain private.
