#!/usr/bin/env python3
"""Build the portable, coordinate-free Stash Manager Grafana dashboard."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "grafana" / "dashboards" / "stash-manager-overview.json"
DATASOURCE = {"type": "prometheus", "uid": "${DS_PROMETHEUS}"}
BOT_FILTER = 'service="stashmanager",bot=~"$bot"'


def target(expr: str, legend: str = "{{bot}}", ref_id: str = "A", instant: bool = True) -> dict:
    return {
        "datasource": DATASOURCE,
        "editorMode": "code",
        "expr": expr,
        "instant": instant,
        "legendFormat": legend,
        "range": not instant,
        "refId": ref_id,
    }


def thresholds(red_below_one: bool = False, red_above_zero: bool = False) -> dict:
    if red_below_one:
        return {
            "mode": "absolute",
            "steps": [{"color": "red"}, {"color": "green", "value": 1}],
        }
    if red_above_zero:
        return {
            "mode": "absolute",
            "steps": [{"color": "green"}, {"color": "red", "value": 1}],
        }
    return {"mode": "absolute", "steps": [{"color": "green"}]}


def base_panel(panel_id: int, title: str, description: str, x: int, y: int, w: int, h: int) -> dict:
    return {
        "id": panel_id,
        "title": title,
        "description": description,
        "datasource": DATASOURCE,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
    }


def stat(
    panel_id: int,
    title: str,
    expr: str,
    description: str,
    x: int,
    y: int,
    w: int = 4,
    h: int = 4,
    unit: str = "short",
    red_below_one: bool = False,
    red_above_zero: bool = False,
    mappings: list[dict] | None = None,
    legend: str = "{{bot}}",
) -> dict:
    panel = base_panel(panel_id, title, description, x, y, w, h)
    panel.update({
        "type": "stat",
        "fieldConfig": {
            "defaults": {
                "color": {"mode": "thresholds"},
                "mappings": mappings or [],
                "thresholds": thresholds(red_below_one, red_above_zero),
                "unit": unit,
            },
            "overrides": [],
        },
        "options": {
            "colorMode": "background",
            "graphMode": "area",
            "justifyMode": "auto",
            "orientation": "auto",
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "textMode": "auto",
            "wideLayout": True,
        },
        "targets": [target(expr, legend)],
    })
    return panel


def gauge(
    panel_id: int,
    title: str,
    expr: str,
    description: str,
    x: int,
    y: int,
    w: int = 4,
    h: int = 4,
    unit: str = "percent",
    minimum: float = 0,
    maximum: float | None = 100,
) -> dict:
    panel = base_panel(panel_id, title, description, x, y, w, h)
    defaults = {
        "color": {"mode": "thresholds"},
        "mappings": [],
        "min": minimum,
        "thresholds": {
            "mode": "absolute",
            "steps": [
                {"color": "red"},
                {"color": "yellow", "value": 50},
                {"color": "green", "value": 90},
            ],
        },
        "unit": unit,
    }
    if maximum is not None:
        defaults["max"] = maximum
    panel.update({
        "type": "gauge",
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {
            "minVizHeight": 75,
            "minVizWidth": 75,
            "orientation": "auto",
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "showThresholdLabels": False,
            "showThresholdMarkers": True,
            "sizing": "auto",
        },
        "targets": [target(expr)],
    })
    return panel


def timeseries(
    panel_id: int,
    title: str,
    expr: str,
    description: str,
    x: int,
    y: int,
    w: int = 12,
    h: int = 6,
    unit: str = "short",
) -> dict:
    panel = base_panel(panel_id, title, description, x, y, w, h)
    panel.update({
        "type": "timeseries",
        "fieldConfig": {
            "defaults": {
                "color": {"mode": "palette-classic"},
                "custom": {
                    "axisCenteredZero": False,
                    "axisColorMode": "text",
                    "axisLabel": "",
                    "axisPlacement": "auto",
                    "drawStyle": "line",
                    "fillOpacity": 18,
                    "gradientMode": "opacity",
                    "lineInterpolation": "smooth",
                    "lineWidth": 2,
                    "pointSize": 5,
                    "scaleDistribution": {"type": "linear"},
                    "showPoints": "never",
                    "spanNulls": False,
                    "stacking": {"group": "A", "mode": "none"},
                },
                "mappings": [],
                "thresholds": {"mode": "absolute", "steps": [{"color": "green"}]},
                "unit": unit,
            },
            "overrides": [],
        },
        "options": {
            "legend": {"calcs": ["lastNotNull"], "displayMode": "table", "placement": "bottom", "showLegend": True},
            "tooltip": {"hideZeros": False, "mode": "multi", "sort": "desc"},
        },
        "targets": [target(expr, "{{bot}}", instant=False)],
    })
    return panel


def row(panel_id: int, title: str, y: int) -> dict:
    return {
        "id": panel_id,
        "title": title,
        "type": "row",
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y},
        "panels": [],
    }


health_mappings = [{
    "options": {
        "0": {"color": "red", "index": 0, "text": "Needs attention"},
        "1": {"color": "green", "index": 1, "text": "Healthy"},
    },
    "type": "value",
}]

activity_mappings = [{
    "options": {
        "0": {"color": "green", "index": 0, "text": "Idle"},
        "1": {"color": "blue", "index": 1, "text": "Running"},
    },
    "type": "value",
}]

scanner_mappings = [{
    "options": {
        str(value): {"index": value, "text": name}
        for value, name in enumerate([
            "Idle", "Finding containers", "Walking", "Opening", "Reading",
            "Closing", "Walking to zone", "Returning", "Paused", "Done",
        ])
    },
    "type": "value",
}]


panels = [
    row(1, "Fleet health", 0),
    stat(2, "Bots online", f'sum(up{{{BOT_FILTER}}})', "Selected bot APIs currently reachable by Prometheus.", 0, 1, 4, 4),
    stat(3, "Bots selected", f'count(up{{{BOT_FILTER}}})', "Number of bots covered by the current dashboard filter.", 4, 1, 4, 4),
    stat(4, "Database connections", f'sum(stash_database_connected{{{BOT_FILTER}}})', "Selected bots with an active PostgreSQL connection.", 8, 1, 4, 4),
    stat(5, "Indexed containers", f'sum(stash_containers_total{{{BOT_FILTER}}})', "Containers currently present in the selected bots' indexes.", 12, 1, 4, 4),
    stat(6, "Oldest scan age", f'max(time() - stash_last_scan_timestamp_seconds{{{BOT_FILTER}}})', "Age of the stalest latest scan among selected bots.", 16, 1, 4, 4, unit="s"),
    stat(7, "Write health", f'min(stash_database_write_healthy{{{BOT_FILTER}}})', "Healthy only when every selected bot's latest persistence attempt succeeded.", 20, 1, 4, 4, red_below_one=True, mappings=health_mappings, legend="All selected bots"),

    row(8, "Scanner", 5),
    stat(9, "Scanner state", f'stash_scanner_state{{{BOT_FILTER}}}', "Current scanner state for each selected bot. Paused is a normal task handoff.", 0, 6, 6, 4, mappings=scanner_mappings),
    gauge(10, "Scan progress", f'100 * stash_scan_processed_ratio{{{BOT_FILTER}}}', "Containers processed from the current scan queue.", 6, 6, 6, 4),
    gauge(11, "Scan success", f'100 * stash_scan_success_rate{{{BOT_FILTER}}}', "Share of processed containers indexed successfully.", 12, 6, 6, 4),
    stat(12, "Failed containers", f'sum(stash_scan_containers_failed{{{BOT_FILTER}}})', "Container targets that exhausted their retry policy.", 18, 6, 6, 4, red_above_zero=True),
    timeseries(13, "Pending scan work", f'stash_scan_containers_pending{{{BOT_FILTER}}}', "Remaining container targets over time.", 0, 10, 12, 6),
    timeseries(14, "Scan handoffs", f'stash_scan_preemptions_total{{{BOT_FILTER}}}', "Cooperative scanner interruptions during the current or last run.", 12, 10, 12, 6),

    row(15, "Organizer and recovery", 16),
    stat(16, "Organizer active", f'stash_organizer_active{{{BOT_FILTER}}}', "Whether each selected bot is organizing now.", 0, 17, 4, 4, mappings=activity_mappings),
    gauge(17, "Organizer progress", f'100 * stash_organizer_tasks_completed{{{BOT_FILTER}}} / clamp_min(stash_organizer_tasks_total{{{BOT_FILTER}}}, 1)', "Completed organizer tasks divided by the current plan.", 4, 17, 4, 4),
    stat(18, "Saved checkpoints", f'sum(stash_organizer_durable_checkpoint{{{BOT_FILTER}}})', "Bots with a restart-safe organizer checkpoint on disk.", 8, 17, 4, 4),
    stat(19, "Restart recoveries", f'sum(stash_organizer_restart_recovery_loaded{{{BOT_FILTER}}})', "Bots waiting to resume a checkpoint loaded after restart.", 12, 17, 4, 4),
    stat(20, "Connection recovery", f'sum(stash_connection_recovery_pending{{{BOT_FILTER}}})', "Active jobs waiting for their game connection to recover.", 16, 17, 4, 4, red_above_zero=True),
    stat(21, "Proxy control grace", f'max(stash_proxy_control_grace_remaining_seconds{{{BOT_FILTER}}})', "Longest remaining client-control grace period among selected bots.", 20, 17, 4, 4, unit="s"),
    timeseries(22, "Organizer task progress", f'stash_organizer_tasks_completed{{{BOT_FILTER}}}', "Completed task count over time. A flat active line is worth checking against cooldown and recovery panels.", 0, 21, 12, 6),
    timeseries(23, "Organizer handoffs", f'stash_organizer_preemptions_total{{{BOT_FILTER}}}', "Cooperative organizer interruptions in the current or last run.", 12, 21, 12, 6),

    row(24, "Permanent storage plan", 27),
    stat(25, "Capacity ready", f'min(stash_lane_capacity_ready{{{BOT_FILTER}}})', "Healthy when every selected bot has a trusted scan and enough permanent or registered staging capacity.", 0, 28, 4, 4, red_below_one=True, mappings=health_mappings, legend="All selected bots"),
    stat(26, "Lanes detected", f'sum(stash_lanes_detected{{{BOT_FILTER}}})', "Storage lanes detected by the latest scans.", 4, 28, 4, 4),
    stat(27, "Lanes required", f'sum(stash_lanes_required{{{BOT_FILTER}}})', "Dedicated item lanes required by current contents.", 8, 28, 4, 4),
    stat(28, "New lanes to build", f'sum(stash_lane_construction_new_lanes{{{BOT_FILTER}}})', "New dedicated lanes recommended by the construction plan.", 12, 28, 4, 4),
    stat(29, "Lanes to expand", f'sum(stash_lane_construction_expansions{{{BOT_FILTER}}})', "Existing lanes that need more double chests.", 16, 28, 4, 4),
    stat(30, "Double chests to add", f'sum(stash_lane_construction_double_chests_to_add{{{BOT_FILTER}}})', "Total double chests recommended across new and expanded lanes.", 20, 28, 4, 4),
    timeseries(31, "Shulker slot demand", f'stash_lane_shulker_slots_required{{{BOT_FILTER}}}', "Current shulker-slot demand. One double chest holds 54 shulkers; each shulker has 27 item slots.", 0, 32, 12, 6),
    timeseries(32, "Shulker slots after compaction", f'stash_lane_shulker_slots_compacted{{{BOT_FILTER}}}', "Projected slot demand after compatible partial shulkers are consolidated.", 12, 32, 12, 6),

    row(33, "Shulkers and temporary staging", 38),
    stat(34, "Bulk shulkers", f'sum(stash_shulkers_bulk{{{BOT_FILTER}}})', "Homogeneous boxes ready for dedicated lanes.", 0, 39, 4, 4),
    stat(35, "Mixed shulkers", f'sum(stash_shulkers_mixed{{{BOT_FILTER}}})', "Mixed boxes or returned kits awaiting exact-item separation.", 4, 39, 4, 4),
    stat(36, "Empty shulkers", f'sum(stash_shulkers_empty{{{BOT_FILTER}}})', "Empty boxes available for reconciliation and packing.", 8, 39, 4, 4),
    stat(37, "Unclassified shulkers", f'sum(stash_shulkers_unclassified{{{BOT_FILTER}}})', "Boxes that need a fresh scan before safe organization.", 12, 39, 4, 4, red_above_zero=True),
    stat(38, "Boxes waiting in imports", f'sum(stash_organizer_staged_shulkers{{{BOT_FILTER}}})', "Reconciled boxes temporarily stored in import chests.", 16, 39, 4, 4),
    stat(39, "Permanent lane gaps", f'sum(stash_organizer_permanent_lane_gaps{{{BOT_FILTER}}})', "Exact item classes that still lack a suitable permanent lane.", 20, 39, 4, 4, red_above_zero=True),
]


dashboard = {
    "annotations": {"list": []},
    "description": "Portable, coordinate-free operations dashboard for one Stash Manager bot or a fleet.",
    "editable": True,
    "fiscalYearStartMonth": 0,
    "graphTooltip": 1,
    "links": [],
    "liveNow": False,
    "panels": panels,
    "refresh": "30s",
    "schemaVersion": 41,
    "tags": ["stash-manager", "zenithproxy", "sanitized"],
    "templating": {
        "list": [
            {
                "current": {},
                "hide": 0,
                "includeAll": False,
                "label": "Prometheus",
                "multi": False,
                "name": "DS_PROMETHEUS",
                "options": [],
                "query": "prometheus",
                "refresh": 1,
                "regex": "",
                "skipUrlSync": False,
                "type": "datasource",
            },
            {
                "allValue": ".*",
                "current": {},
                "datasource": DATASOURCE,
                "definition": 'label_values(stash_containers_total{service="stashmanager"}, bot)',
                "hide": 0,
                "includeAll": True,
                "label": "Bot",
                "multi": True,
                "name": "bot",
                "options": [],
                "query": {
                    "query": 'label_values(stash_containers_total{service="stashmanager"}, bot)',
                    "refId": "PrometheusVariableQueryEditor-VariableQuery",
                },
                "refresh": 1,
                "regex": "",
                "skipUrlSync": False,
                "sort": 1,
                "type": "query",
            },
        ]
    },
    "time": {"from": "now-6h", "to": "now"},
    "timepicker": {},
    "timezone": "browser",
    "title": "Stash Manager Operations",
    "version": 1,
    "weekStart": "",
}


OUTPUT.parent.mkdir(parents=True, exist_ok=True)
OUTPUT.write_text(json.dumps(dashboard, indent=2) + "\n", encoding="utf-8")
print(OUTPUT)
