#!/usr/bin/env python3
"""Validate dashboard JSON and reject environment-specific or sensitive values."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
    "grafana/dashboards/stash-manager-overview.json"
)
payload = json.loads(path.read_text(encoding="utf-8"))
text = json.dumps(payload, sort_keys=True)
dashboard = payload.get("dashboard", payload)

problems: list[str] = []
for forbidden_key in ("id", "uid"):
    if forbidden_key in dashboard and dashboard[forbidden_key] not in (None, ""):
        problems.append(f"dashboard has a fixed {forbidden_key}")

patterns = {
    "IPv4 address": r"\b(?:\d{1,3}\.){3}\d{1,3}\b",
    "HTTP URL": r"https?://",
    "authorization header": r"(?i)authorization",
    "API key field": r"(?i)(api[_ -]?key|bearer\s+[a-z0-9])",
    "Minecraft coordinate triple": r"-?\d{4,}\s*,\s*-?\d+\s*,\s*-?\d{4,}",
    "deployment container name": r"(?i)stasis-pearls|zenithproxy-[0-9]",
}
for label, pattern in patterns.items():
    if re.search(pattern, text):
        problems.append(f"contains {label}")

datasource_uids = {
    value.get("uid")
    for panel in dashboard.get("panels", [])
    for value in [panel.get("datasource", {})]
    if isinstance(value, dict)
}
if datasource_uids - {"${DS_PROMETHEUS}", None}:
    problems.append("contains a fixed datasource UID")

if problems:
    raise SystemExit("Dashboard validation failed: " + "; ".join(problems))

print(f"Validated {path}: portable and sanitized")
