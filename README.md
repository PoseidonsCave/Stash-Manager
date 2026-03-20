# Stash Management

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that scans, indexes, and provides cross-platform access to container inventories in a Minecraft world.

![Build](https://github.com/PoseidonsCave/Stash-Management/actions/workflows/build.yml/badge.svg)

## Features

- Region-based container scanning with tick-driven state machine
- Indexes chests, barrels, shulker boxes, hoppers, dispensers, droppers
- Shulker box introspection, reads nested inventory contents via NBT
- Double chest detection and deduplication
- Full-text item search across all indexed containers
- Paginated container listing
- CSV export with Discord file attachment support
- Supply chest management
- All commands work via Discord, terminal, and in-game chat

## Commands

| Command | Description |
|---------|-------------|
| `stash pos1 [x y z]` | Set region corner 1 (defaults to player position) |
| `stash pos2 [x y z]` | Set region corner 2 (defaults to player position) |
| `stash scan` | Scan containers in the defined region |
| `stash stop` | Abort an in-progress scan |
| `stash status` | Show scan state, region info, and container counts |
| `stash list [page]` | Paginated list of indexed containers |
| `stash export` | Export index to CSV (attached as file in Discord) |
| `stash clear` | Clear the index (region positions retained) |
| `stashsearch <item>` | Search index for containers holding matching items |
| `stashsupply add` | Mark the nearest container as a supply chest |
| `stashsupply remove <id>` | Remove a supply chest by index |
| `stashsupply list` | List all registered supply chests |

## Installation

1. Build the plugin JAR (or download from [Releases](https://github.com/PoseidonsCave/Stash-Management/releases))
2. Place `stash-manager-1.0.0.jar` in ZenithProxy's `plugins/` directory
3. Restart or reload ZenithProxy

## Building

Requires Java 21+.

```sh
gradle build
```

The output JAR will be in `build/libs/`.

## Configuration

Saved automatically via ZenithProxy's plugin config system. Defaults:

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | `true` | Enable/disable the module |
| `scanDelayTicks` | `5` | Ticks between container reads |
| `openTimeoutTicks` | `60` | Max wait for container open response |
| `maxContainers` | `2048` | Container cap per scan session |
| `waypointDistance` | `48` | Incremental walk distance for unloaded chunks |

## License

[MIT](LICENSE)
