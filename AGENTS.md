@~/.agents/instructions/agents.md
@./README.md

- Release tags: `v<version>`, at the version `app/build.gradle.kts` states,
  by `make deploy`.
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
- The app's version is `M.M.P`, `versionName` in `app/build.gradle.kts`,
  raised in the first change after a release tag; `versionCode` derives
  from it.
- Store temporal values as UTC instants.
