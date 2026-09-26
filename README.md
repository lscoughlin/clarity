# Clarity

Clarity is an MCP server for your local documentation corpus in
Markdown and YAML.

Point it at the docs and source comments you already have, and it
indexes them for fast lookup through the
[Model Context Protocol](https://modelcontextprotocol.io/). It is
aimed at engineers who want an AI assistant to answer questions
grounded in their own project's docs — not the public internet.

> **Status:** working system. A long-running daemon watches the
> corpus, reindexes on change (checksums skip unchanged files), and
> serves text and vector queries over a Unix socket at
> `.clarity/clarity.sock` — one client jar (`clarity index` /
> `query` / `mcp` / `daemon`) that auto-starts it
> (`mvn verify` is green, offline-safe). See [AGENTS.md](AGENTS.md)
> for contributor guidance.

## How it works

1. You describe one or more indexes in `.clarity/config.yaml`.
2. Clarity walks the configured include globs and builds a local
   search index per index entry.
3. An MCP client (or the CLI) queries the index; Clarity returns
   the matching chunks with their source paths.

### What gets indexed

| File kind | What is indexed |
|-----------|-----------------|
| Markdown (`markdown:`) | One chunk per heading section (via `commonmark-java`). Text outside any heading is indexed as a single leading chunk. |
| YAML (`yaml:`) | The whole file as a single document. |
| Source (`source:`) | Comments only — code is stripped and just the extracted comments are indexed. |

Only files matching an include glob are indexed. Re-running the
indexer picks up added, changed, and deleted files.

## Configuration

When started with defaults, Clarity looks for
`.clarity/config.yaml` relative to the working directory.

```yaml
index:
  my_docs:                       # index name; one Lucene index per entry
    index_path: /path/to/index/directory
    markdown:
      - doc/**/*.md
    yaml:
      - doc/**/*.yaml
    source:
      kind: [java, pascal, go, rust, swift]
      include:
        - src/main/java/**/*.java
        - src/test/java/**/*.java
```

Reference:

- `index.<name>` — a named index. Each name maps to one
  self-contained Lucene index.
- `index.<name>.index_path` — where that index is stored on disk.
- `index.<name>.markdown` — list of globs for Markdown files,
  chunked by heading.
- `index.<name>.yaml` — list of globs for YAML files, indexed
  whole-file.
- `index.<name>.source.kind` — source languages whose comment
  syntax should be used for comment extraction.
- `index.<name>.source.include` — list of globs for source files;
  only comments are indexed.

Globs are Ant-style (`**` crosses directory boundaries). Keep
include lists tight — broad globs over `node_modules/`, `target/`,
or `build/` slow down indexing and pollute results.

## Modules

A multi-module Maven project:

- **clarity-parser** — config model (`.clarity/config.yaml`),
  Markdown/YAML/comment chunking, glob resolution. Pure library,
  no Lucene.
- **clarity-daemon** — owns indexing and the Lucene indexes: one
  index per named entry, one document per chunk, path-keyed
  reindexing. Watches the corpus (debounced, plus a periodic scan),
  skips unchanged files by SHA-256, and serves `search` /
  `reindex` / `health` over a Unix socket. Exits after ~10 idle
  minutes; clients auto-start it.
- **clarity-cli** — one jar, one entry point (`ClarityMain`,
  built with `picocli`): `index` / `query` for local use and
  scripting, `mcp` serving a `search` tool over stdio (MCP Java
  SDK), `daemon` running the daemon in the foreground. Thin
  client of the daemon; one server instance per project
  (`--dir`).

## Quickstart

Prerequisites: JDK 26, Maven 3.9+.

```bash
# build + test
mvn verify

# package runnable jars
mvn -q -DskipTests package
```

1. Create `.clarity/config.yaml` in your project (see example above).
2. Point the CLI at the daemon jar (one line, e.g. in your shell
   profile):
   ```bash
   export CLARITY_DAEMON_JAR=$PWD/clarity-daemon/target/clarity-daemon-*.jar
   ```
3. Index: `java -jar clarity-cli/target/clarity-cli-*.jar index
   --dir /path/to/project`. The daemon auto-starts on first use —
   no manual step.
4. Query: `java -jar clarity-cli/target/clarity-cli-*.jar query
   --dir /path/to/project <index> <terms>`.
5. MCP client: launch `java -jar
   clarity-cli/target/clarity-cli-*.jar mcp --dir
   /path/to/project` over stdio:
   ```json
   {
     "mcpServers": {
       "clarity": {
         "command": "java",
         "args": ["-jar", "/path/to/clarity-cli-*.jar", "mcp", "--dir", "/path/to/project"],
         "env": {"CLARITY_DAEMON_JAR": "/path/to/clarity-daemon-*.jar"}
       }
     }
   }
   ```
6. To run the daemon in the foreground (logs, watching):
   `java -jar clarity-cli/target/clarity-cli-*.jar daemon
   --dir /path/to/project` (or the daemon jar directly with
   the same flags).

Queries are plain words by default (`query docs "C++ (notes)"`
just works) and match heading words too — a heading match
ranks above an equal body match. `--query-syntax raw` on the
CLI (or `"syntax": "raw"` on the MCP `search` tool) enables
Lucene query syntax for phrases, fields, and booleans
(`heading_text:` addresses headings explicitly).

`--query-syntax vector` (or `"syntax": "vector"`) runs a
semantic search instead: the daemon embeds each chunk with a
local MiniLM model (`Xenova/all-MiniLM-L6-v2`, 384 dims,
Apache-2.0) and matches by cosine similarity, so paraphrases
with no shared words still retrieve. Model bytes download once
to `~/.cache/clarity/models/` on first daemon start; without
them the daemon logs a warning and serves text search only.
Indexing embeds one file's chunks per inference run (64-row
batches). Vector scores are cosine-derived and not comparable
to BM25 text scores. The auto-started daemon passes
`--add-modules jdk.incubator.vector` when the runtime provides
it (Lucene's SIMD path); add the flag yourself for foreground
runs.

Runtime files live next to the config: `.clarity/clarity.sock`,
`.clarity/daemon.lock`, `.clarity/daemon.log`. Commit
`config.yaml`; ignore the rest.

## Tech stack

- [Apache Lucene](https://lucene.apache.org/) (including
  `KnnFloatVectorField` cosine search) for indexing and search
- [ONNX Runtime](https://onnxruntime.ai/) (daemon only) for local
  MiniLM embeddings — no query text leaves the machine
- [Jackson 3](https://github.com/FasterXML/jackson) for YAML/JSON
  parsing
- [commonmark-java](https://github.com/commonmark/commonmark-java)
  for Markdown parsing
- [Logback](https://logback.qos.ch/) / SLF4J for logging
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
  for the MCP adapter
- [picocli](https://picocli.info/) for CLI options

## Roadmap

- [x] Multi-module Maven split (parser / daemon / cli / mcp)
- [x] Merged client jar (`clarity` with `index` / `query` / `mcp` / `daemon`)
- [x] Lucene text indexing (one index per entry, path-keyed reindex)
- [x] CLI index/query commands for scripting
- [x] File watcher with checksum-skipped reindexing and periodic scan
- [x] Daemon-served queries over a Unix socket (CLI/MCP auto-start it)
- [x] Runnable packaging (fat jars, MCP launch config)
- [x] Vector search over chunk embeddings (opt-in `--query-syntax vector`)
- [ ] Published MCP tool definitions

Contributions welcome — start with [AGENTS.md](AGENTS.md).
