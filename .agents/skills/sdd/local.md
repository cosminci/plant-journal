# Plant-journal safety

- README owns toolchain/setup, local startup, contract regeneration and runtime image commands.
- SECURITY.md owns network/authentication posture; ci/specs owns release credentials and pipeline operation.
- contract/ is generated and read-only; regenerate and commit it rather than editing it.
- Changed tapir endpoints or contract/ require `dagger call contract-drift`.
- Every change passes affected `dagger call verify`; use all when impact is uncertain.
- Component backend/frontend/pipeline gates and 100% coverage remain enforced by configuration.
- NAS refresh preserves the previous local journal on failure; replacement requires the launcher's confirmation.
- Household garden notes under `.local/garden-plan/` and all local credentials remain untracked.
