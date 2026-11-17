# Pipeline contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

The contract with the external CI runner (GitHub Actions) is the `dagger call` invocations the workflows actually make, not the full internal function list — [`.dagger/src/index.ts`](../../.dagger/src/index.ts) is authoritative for that:

| Invocation | Caller | Arguments |
| --- | --- | --- |
| `changed` | `build.yml`, every PR | `--base` (defaults to merge-base with `origin/main`). |
| `verify`, or `verify-cached` with the [Actions snapshot](operational.md#actions-cache) | `build.yml` | `--base` for pull requests; `--all` for main pushes and manual runs. |
| `verify-cached` | `release.yml`, before a release | `--all`, with restore-only trusted main state. |
| `release-version` | `release.yml`, manual dispatch | none — generates a UTC calendar-timestamp tag from wall-clock time, once per dispatched release. |
| `publish` | `release.yml`, manual dispatch | `--tag`, `--token` (a Dagger Secret), `--registry-user`. |

The [runtime image definition](../../.dagger/src/hooks/image.ts) owns image contents, runtime identity, environment, and labels. The application's HTTP surface is defined separately by [OpenAPI](../../contract/openapi.yaml).
