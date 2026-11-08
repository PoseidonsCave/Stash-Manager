# Logistics orchestration simulation

StashManager's logistics behavior is split into two layers:

- `LogisticsOrchestrator` is a deterministic state machine driven by logical ticks.
- A `LogisticsOrchestrator.Port` performs runtime operations. Production uses Zenith-backed
  ports; tests use a virtual world with scripted latency and failures.

The orchestration layer must not depend on Zenith globals, Minecraft screens, wall-clock time,
threads, or network delivery order. This makes failure ordering reproducible and lets the same
task semantics support organization, kit assembly, and deliveries.

## Retry classification

| Failure | Disposition |
| --- | --- |
| Source open fails before mutation | Bounded retry at queue tail |
| Source contains no matching item | Terminal stale-source failure |
| Destination open fails while cargo is held | Bounded in-place cargo retry |
| Transfer confirmation times out | Block pending reconciliation |
| Result arrives for an expired open operation | Close the orphaned window |
| Destination retry budget is exhausted | Block with cargo preserved |

Task totals describe submitted logical work and never grow because an operation was retried.
Every task has a stable ID, and only one operation ID owns an outstanding runtime action.

## Continuance playbooks

| Interruption | Checkpoint policy | Resume policy | Abort policy |
| --- | --- | --- | --- |
| Another Zenith automation task | Release owned Baritone/inventory work and retain the scan or organizer checkpoint | Wait for the five minute hold plus a quiet resource window, then revalidate the live target | Keep waiting; the interrupting task has priority |
| Controlling proxy client | Pause immediately and start a ten minute wall-clock grace period | The controller uses `/swap`; resume after the normal hold and quiet window | Discard the in-memory checkpoint when the grace period expires |

Organizer checkpoints record whether the bot was moving loose cargo, moving a filled shulker, or
inside the temporary place/fill/break/pickup cycle. Resumption never assumes an old GUI is still
valid. It reconstructs the next state from the player inventory, the worksite block, and the
current destination.

## Required invariants

1. Items are conserved across source containers, player inventory, and destinations.
2. A cargo-bearing task never returns to the normal queue.
3. An untouched failure cannot starve unrelated tasks.
4. A late open cannot leave an unowned window active.
5. An ambiguous mutating action stops later work until its postcondition is reconciled.
6. Exact item variants remain distinct throughout planning and transfer.
7. Retry budgets are bounded and task totals remain immutable.
8. No paused organizer resumes from an old container window or assumes a mined shulker was picked up.

## Running the suite

```shell
./gradlew :1.21.4:test --tests \
  com.zenith.plugin.stashmanager.orchestration.LogisticsOrchestratorSimulationTest
```

The virtual port intentionally allows cancelled operations to respond later. Real servers can
deliver packets after local cancellation, so silently removing scheduled callbacks would hide
the most important class of proxy orchestration bugs.

## Integration path

The existing organizer is being migrated incrementally. Source walk/open failures already use
the same tail-retry classification, while cargo destination failures retain priority ownership.
Future adapters should move direct Baritone, Inventory Manager, and packet-cache calls behind
the port boundary one operation family at a time.
