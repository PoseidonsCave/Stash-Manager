# Contributing

StashManager moves real inventory and persists its decisions. A small change to a
selector, click sequence, or resume guard can affect items after a restart. Please
keep the proof for those decisions close to the code that uses it.

## Find the right boundary

- `kit/` holds plans, physical variant rules, checkpoint evidence, and recovery
  decisions. `KitItemSourcePolicy` contains the common live source selection rules.
- `organizer/` executes packing and export; `retriever/` obtains stock; `travel/`
  moves the bot. `index/` and `database/` maintain the persisted view.
- `StashManagerModule` wires these parts together. Prefer a focused class in the
  owning package over adding another policy branch to the module or coordinator.
- Keep exact physical variant checks separate from item ID counts. An indexed
  count is planning input; a live server window is required before a transfer.

## Change and review

1. State which inventory, checkpoint, API, or release behavior the change affects.
   For movement code, identify the live evidence that authorizes each step and
   what happens if that evidence is missing or a controller takes over.
2. Add a focused test when changing a decision boundary. Prefer a test that would
   fail for a wrong variant, duplicate transfer, or stale checkpoint over a test
   that merely repeats the implementation.
3. Run `./gradlew test collectVersionJars` with Java 25. This builds all supported
   targets. CI also validates the Gradle wrapper and reviews dependencies.
4. Describe any required database migration or operator action in the pull request.
   Do not put live coordinates, access tokens, database passwords, or player data
   in code, tests, logs, or issue reports.
5. Review changes to `.github/workflows/`, release configuration, and dependency
   sources as supply chain changes. Existing actions use full commit SHAs; keep
   that convention and allow Dependabot to propose updates.

Security reports follow [SECURITY.md](SECURITY.md). The project's current SSDF
practice and open gaps are described in
[docs/SECURE_DEVELOPMENT.md](docs/SECURE_DEVELOPMENT.md).
