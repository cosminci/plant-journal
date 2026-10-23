# Code quality checklist — backend

Load only for a PR that changes `backend/**`. `CONTRIBUTING.md` §Naming, §Scala composition, §Logging, §Metrics, and `DESIGN-PRINCIPLES.md` §1/§4/§5 own the general rules, and the general items live in [`code-quality.md`](code-quality.md); the items below live only here.

- [ ] Private members omit their return type unless it is `Unit`.
- [ ] Suite order within a file: reusable non-trivial mock data, then use-case tests, then observable `Refs` (only if needed), then `buildX` helpers.
- [ ] At most one `Refs` instance per test; a test wanting more than one is really two use cases — split it.
- [ ] `Refs` holds only observed collaborator effects/calls a test asserts, never a stored mock response; `buildX` constructs every collaborator substitute and takes fixed responses/failures/clocks as parameters with ordinary-case defaults.
- [ ] A stub/mock is declared in the one suite that needs it, unless it is non-trivial (roughly 4+ lines to define) _and_ shared by more than one suite — only then does it move to `TestImplicits` (a capability `given`) or a shared `Mocks` object (domain data).
- [ ] Prefer a codec that encodes the wire format directly over a DTO; a DTO exists only when it cannot leak past its boundary and no codec can express the format cleanly.
- [ ] A test needs no helper beyond its `buildX` call to read as one use case.
- [ ] Application logic sits behind a trait named for its behavior, with a same-named companion exposing `make` and a private `LiveX`; tests exercise only what `make` returns.
- [ ] Integration tests acquire/use/release their own resource explicitly through the ecosystem's standard primitive — never through a callback-style setup helper.
- [ ] Order everything most important first, everywhere: parameters, methods, nested types, APIs, and declarations in a file. In a file with a trait, order is trait → object `make` → private final `Live*` class → result types in the companion of the trait whose methods return them.
- [ ] Result types live in the companion of the trait whose methods return them. Top-level types are reserved for config or shared values.
- [ ] Trait APIs never return `Either`; use per-operation sum types. Failure enums inside a companion are named `Failure`, not repeated with the operation name.
- [ ] Default arguments appear only in tests; runtime code is kept free of them by a linter gate (wartremover `DefaultArguments`, scalafix `DisableSyntax.noDefaultArgs`).
- [ ] A wartremover or scalafix suppression is allowed only when the flagged operation is provably safe at that site (for example `minBy` after a non-empty guard). It is scoped to the smallest declaration, uses the full wart id, and carries a comment stating why it cannot fail. Never suppress a gate to hide a real defect.
- [ ] Inline shallow helpers that add no meaning beyond the code they wrap, however many call sites they have. A helper stays only when it holds non-trivial logic that would be buggy to diverge.
- [ ] Test IDs and values come from stable builders such as `buildUuid(index)`, not ad hoc literals per test.
