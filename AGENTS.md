# AGENTS.md

Guidance for agentic (and human) contributors working in this repo.
The user-facing description lives in [README.md](README.md); this
file is the build/test/contract cheat sheet. Follow it over any
prior assumptions.

## Project in 30 seconds

Clarity is an MCP server over a local docs corpus (Markdown + YAML
+ source comments). Users declare named indexes in
`.clarity/config.yaml`; Clarity builds one Lucene index per name
and serves search results to MCP clients and a CLI. Working
system: three modules, Lucene text + vector backends, Unix-socket
daemon with watcher and auto-start, shaded fat jars,
`mvn verify` green (offline-safe).

## Repo layout

- `pom.xml` — parent pom (Java 26, UTF-8): `dependencyManagement`
  pins (Lucene 10.5.1, Jackson 3, commonmark-java, SLF4J/Logback,
  picocli, MCP SDK 2.0.1, JUnit 5) plus surefire config.
- `clarity-parser/` — config model, Markdown/YAML/comment
  chunking, glob resolution. Pure library: no Lucene here.
- `clarity-daemon/` — orchestration (`IndexService`), the Lucene
  backend behind the `SearchBackend` port, the single-writer
  `Daemon` (watch loop, checksums, idle shutdown), and the Unix
  socket layer (`DaemonServer`/`DaemonClient`: `search`, `reindex`,
  `health` at `<baseDir>/.clarity/clarity.sock`). Only the daemon
  opens indexes for writing; CLI/MCP are thin clients that
  auto-start it. Vector search: `Embedder` port (`OnnxEmbedder`
  loads `Xenova/all-MiniLM-L6-v2`, 384 dims, Apache-2.0, to
  `~/.cache/clarity/models/` at runtime; deterministic stub in
  tests) with COSINE `KnnFloatVectorField`s, model id + dims in
  commit metadata, fail-fast drift check, full re-embed upgrade
  from pre-vector indexes. Text-only queries score exactly as
  before; vector is opt-in via `Syntax.VECTOR`. Indexing
  embeds via `embedBatch` (64-row runs per file; values
  identical to single `embed`). The auto-spawned daemon adds
  `--add-modules jdk.incubator.vector` when the spawning
  runtime provides it (probed once, fail-open).
- `clarity-cli/` — one jar, one entry point (`ClarityMain`,
  picocli): `index` / `query`, `mcp` (MCP `search` tool over
  stdio), `daemon` (foreground daemon). Thin delegation to
  the daemon; the `mcp` server class keeps its
  `org.github.lscoughlin.clarity.mcp` package.
- `Taskfile.yaml` — empty placeholder, no tasks defined.

Module boundaries stay decoupled: indexing/search lives in the
daemon, argument parsing in the CLI, protocol handling in the MCP
adapter. Each module owns `src/main/java`, `src/main/resources`,
`src/test/java` with tests encoding the README contracts.

## Toolchain and commands

- JDK 26 (Homebrew `openjdk` 26.x works), Maven 3.9+.
- Build + test: `mvn verify`
- Fast test loop: `mvn -q test -Dtest=<TestName>`
- Format convention: default Maven/Java style, 4-space indents,
  UTF-8. No formatter plugin configured yet — match surrounding
  code.

## Contracts you must preserve

### Config file (`README.md#configuration`)

- Default location: `.clarity/config.yaml`, resolved relative to
  the working directory at startup.
- Shape: `index.<name>.index_path`, `index.<name>.markdown[]`,
  `index.<name>.yaml[]`, `index.<name>.source.kind[]`,
  `index.<name>.source.include[]`.
- Globs are Ant-style (`**` crosses directories). Never widen a
  default glob to cover `target/`, `build/`, `node_modules/`, or
  `.git/`.

### Indexing semantics (`README.md#what-gets-indexed`)

- Markdown → chunk by heading via `commonmark-java`; pre-heading
  text is one leading chunk.
- YAML → whole file, one document (parsed with Jackson 3).
- Source → comments only; code tokens must never enter the index.
  `source.kind` selects the comment syntax per language
  (`java, pascal, go, rust, swift`).
- Reindexing must handle add/change/delete; only files matching an
  include glob are indexed.

### Tech stack (do not swap without discussion)

Lucene (+ `KnnFloatVectorField`) · Jackson 3 · commonmark-java ·
Logback/SLF4J · MCP Java SDK · picocli · ONNX Runtime 1.30.0
(daemon only, justified: local-only MiniLM inference with no
network at test time — tests use a stub embedder and
`mvn -o verify` must stay green). Prefer these over adding
new dependencies; a new dependency needs a clear justification in
the PR/commit message.

## Working agreements

- Smallest correct fix at the root cause; keep server/CLI/MCP
  boundaries clean.
- Add or update tests under `src/test/java` for behavior changes
  (unit tests for chunking/extraction, round-trip tests for
  config parsing). Run `mvn verify` before handing off.
- Log via SLF4J (Logback backend); no `System.out` in library or
  server code. All runtime logging goes to stderr; stdout is
  reserved (MCP transport, CLI result printing).
- Modern Java 26 style throughout: `var` for locals with
  evident initializer types (fields, params, and null
  initializers keep explicit types — Java requires it),
  enhanced `switch` (arrows, pattern matching), and the SLF4J
  fluent logging API:
  `LOG.atInfo().setMessage("reindexed {}").addArgument(name).log()`
  with `.setCause(e)` for throwables. Message text stays
  stable; only the call style changes.
- Do not commit `target/`, IDE output, or local index
  directories. `.clarity/` configs in test fixtures are fine;
  real index data is not.
- If the request conflicts with this file, ask before deviating.
