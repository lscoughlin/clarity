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

- `pom.xml` — parent pom (Java 25, UTF-8): `dependencyManagement`
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
  from pre-vector indexes. Explicit `Syntax.TEXT`/`Syntax.RAW`
  queries score exactly as before; `Syntax.VECTOR` is opt-in and
  errors without an embedder. `Syntax.HYBRID` (the default when
  a caller doesn't specify a syntax — see `Syntax.parse`) fuses
  BM25 and cosine ranking via reciprocal rank fusion, silently
  degrading to text-only whenever the vector leg is unavailable.
  Indexing
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

- JDK 25 minimum; default build runs on JDK 26 (Homebrew
  `openjdk` 26.x works), Maven 3.9+. `~/.m2/toolchains.xml`
  declares the default JDK plus the GraalVM 25 toolchain
  (sdkman `25.0.2-graal`).
- Build + test: `mvn verify` (offline-safe: `mvn -o verify`).
- Native binary (GraalVM `native` profile, needs network once
  for plugins): `JAVA_HOME=~/.sdkman/candidates/java/25.0.2-graal
  mvn -Pnative package` → `clarity-cli/target/clarity`.
  Compiles/tests on the GraalVM toolchain, then links with
  `native-maven-plugin`. Text and vector search both work —
  the ONNX dylibs ship as image resources with a traced
  `jni-config.json` (`clarity-daemon/.../META-INF/native-image/`).
  `OnnxEmbedder.tryLoad` still degrades to text-only if the
  natives ever fail to load.
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
Logback/SLF4J · MCP Java SDK · picocli (+ `picocli-codegen`
as a compile-time-only annotation processor: it emits the
`META-INF/native-image` reflection config the native profile
needs; not a runtime dependency) · ONNX Runtime 1.30.0
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
- Modern Java 25 style throughout: `var` for locals with
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
