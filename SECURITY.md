# Security policy

## Reporting a vulnerability

Please avoid posting exploit details, live stash coordinates, credentials, or
inventory snapshots in a public issue. Use GitHub's **Report a vulnerability**
option in the repository's Security tab when it is available. If it is unavailable,
contact the maintainers through the project's [Discord](https://discord.gg/6v5greuSp)
without including exploit details and request a private reporting channel.

Include the affected release or commit, supported Minecraft target, expected and
observed behavior, impact, and a minimal reproduction using synthetic data. The
maintainers will acknowledge the report, assess affected versions, coordinate a
fix, and publish an advisory or release note when users need to take action.
There is no promised response deadline or bug bounty.

## Scope

The current `main` branch and published release JARs are in scope. Include
ZenithProxy and Minecraft target versions in reports because behavior can differ
by target. Issues in upstream dependencies should also be reported privately to
their maintainers when the affected component is known.

## Operator guidance

Keep proxy, API, and database credentials out of the repository. Verify a release
JAR against its `.sha256` file before installation. Do not share diagnostic logs
until they have been checked for coordinates and inventory data.
