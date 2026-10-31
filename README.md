# plant-journal

A journal of the household's house plants and their dated care, built for the home NAS. This README is the index; each document below owns its area.

- [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md) — how the code is designed and built; read first.
- [CONTRIBUTING.md](CONTRIBUTING.md) — shared conventions and the SDD workflow; runnable product commands are below.
- [AGENTS.md](AGENTS.md) — operational guardrails for coding agents, on top of the two above.
- [.agents/skills/](.agents/skills/) — shared coding-agent skills, including the spec-driven development skill every change runs through.
- [GLOSSARY.md](GLOSSARY.md) — domain terms.
- [specs/](specs/) — the service's living design / contracts / testing / operational docs (templates in [specs/templates/](specs/templates/)); [ci/specs/](ci/specs/) — the pipeline's.
- [unraid/](unraid/) — the Unraid Community Applications template and its README for NAS deployment.
- [grafana/](grafana/) — the committed `/metrics` dashboard.
- [LICENSE](LICENSE) — MIT.
- [SECURITY.md](SECURITY.md) — intended deployment and vulnerability reporting.

## Local development

Install the toolchain from [.mise.toml](.mise.toml) with `mise install`; Python 3 and Docker are prerequisites.

```sh
npm ci --prefix frontend
mise exec -- python3 scripts/local.py
```

The launcher serves the local UI at `http://127.0.0.1:5173` and API at `http://127.0.0.1:8080`, using ignored `.local/` state. Ctrl-C stops both; `GARDENING_PORT` and `GARDENING_DEV_PORT` override occupied ports.

Optional NAS refresh needs locally configured SSH and NAS `sqlite3`:

```sh
GARDENING_NAS_SSH=tower.lan \
GARDENING_NAS_DB_PATH=/mnt/user/appdata/plant-journal/gardening.db \
mise exec -- python3 scripts/local.py --refresh
```

Replacing an existing local journal requires `replace` confirmation (`--yes` is noninteractive). A failed refresh preserves the previous journal; [operations](specs/operational.md#runtime-dependencies) owns snapshot behavior.

## Product commands

| Task | Command |
| --- | --- |
| Component checks | `dagger call backend-check`, `dagger call frontend-check`, `dagger call pipeline-check` |
| Affected / all checks | `dagger call verify` / `dagger call verify --all` |
| Contract regeneration | `cd backend && sbt "runMain gardening.app.GenerateOpenApi ../contract/openapi.yaml" && cd ../contract && npm run generate` |
| Runtime image | `dagger call build-image` |

Generated contracts stay under `contract/`. [Unraid operations](unraid/README.md) owns deployment; [SECURITY.md](SECURITY.md) owns exposure and authentication.
