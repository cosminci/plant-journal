# Restore display-name metrics after restart

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0. Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-10-23

**Classification:** investigation

**Grounded in:** A live spike found that the scrape target remained healthy after restart while plant and catalog display-name metric families were absent; ordinary successful reads restored them. The same read-capable services already exist before the backend begins serving requests, so startup can reuse that behavior without introducing a new domain contract.

## What & Why

The dashboard currently depends on a browser read to regain display names after restart, even while scraping is healthy; making the names available as soon as persisted state is read keeps monitoring useful without interactive traffic.

## Acceptance Criteria

- On the first scrape after startup, the plant, substrate-component, and pesticide info families contain exactly the current persisted ID/name mappings; entities absent from current state have no info series.
- If one persisted-state read fails during startup, the failure is logged, the backend still serves requests, and successful reads for other entity types still populate their metric families. A failed read does not create fabricated mappings.
- Subsequent successful reads continue to replace the corresponding mappings with current names rather than accumulating stale names.

## Doc Sync

- `specs/operational.md` — Metrics: clarify that event counters are process-lifetime event counts and are not reconstructed from persisted journal history after a restart.
