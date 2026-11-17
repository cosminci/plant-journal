# Pipeline operations

> Standard: Agentic Engineering Standards v1.2.0

## Engine storage

Before the first local Dagger command, run `python3 scripts/configure_dagger_engine.py`; the pre-push hook and build/publish workflows run it automatically. The installer writes the [retention policy](../../scripts/dagger-engine.json) under `$XDG_CONFIG_HOME/dagger/engine.json` when set, otherwise under `~/Library/Application Support/dagger/engine.json` on macOS or `~/.config/dagger/engine.json` on Linux; this is a shared user configuration, and unrelated settings are preserved.

The policy makes entries unused for 72 hours eligible for removal and gives each engine a 20 GB GC budget, a 2 GB reserve, a 5 GB sweep target and a 50 GB free-space target on the engine's filesystem. These are garbage-collection thresholds, not disk quotas or a guarantee of host free space: active references can exceed the budget, age cleanup needs a GC pass, and stopped engines cannot collect their caches.

Dagger mounts this file when it creates a default engine and reads it at engine startup. An existing engine without the mount must be recreated after confirming it is idle; a mounted engine needs a restart after a policy change. For a manually created engine, mount the same file read-only at `/etc/dagger/engine.json`, keep its name explicit in the runner override, and remove that exact disposable container and its own cache volume when the experiment ends.

## Versioning & release provenance

`release-version` generates a UTC calendar-timestamp version (`YYYY.M.D-THHMMSS`) from wall-clock time, not from any git tag; two releases colliding on the same version isn't a real concern — the per-second timestamp already makes it vanishingly unlikely, and GHCR rejects a tag overwrite regardless. `publish` validates that format inline before building or pushing anything. Only after a successful publish does the released commit get an annotated `v<version>` git tag pushed to it — provenance recorded after the fact, never a pre-release gate.

## Publishing

Publish is a manually dispatched workflow, restricted to `main`, that runs full verification (`verify --all`) first; `publish` itself does not run checks. It pushes to the public `ghcr.io/cosminci/plant-journal` package using the GitHub Actions job's own token (`packages: write`), never a personal PAT.

## Deployment

See [`unraid/README.md`](../../unraid/README.md) for NAS deployment, WUD auto-update, and recovery.
