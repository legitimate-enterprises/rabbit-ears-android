@~/.agents/instructions/agents.md
@./README.md

- Release tags: `v<M.m.N>` of the origin/main commit they tag, by
  `make deploy`.
- Red-green: write the failing test first, then the minimum code. Unit and
  end-to-end tests are both first class. Judge coverage by the defects
  caught at seams: the API boundary, device capability parsing, downloads
  against a real server.
- Comments explain intent, invariants and side effects of non-obvious code.
  Never what the code does, never history or rationale.
- No Markdown beyond `README.md`, `AGENTS.md` and `memory.md`. No ADRs, no
  decision logs.
- `openapi.yaml` is the server's file, replaced whole by `make spec` and
  never edited here. Its `info.version` is the server release the app is
  built against and tested with, and the app may assume that API.
  `api/src/main/kotlin` is generated from it by `make generate`.
- The app's version is `M.m.N`, its `versionName`. `M.m` is stated once,
  in `versionName` in `app/build.gradle.kts`, and only the owner raises
  major or minor; nothing automated does. `N` is the commit count of the
  build's `HEAD`, `git rev-list --count HEAD`, read by Gradle at build time
  and never committed. `versionCode` is `N`. A shipped bundle or a tag needs
  full history; a shallow clone builds with a wrong `N`.
- Store temporal values as UTC instants.
