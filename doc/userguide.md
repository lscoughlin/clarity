# User guide

## Setup

Prerequisites: JDK 26, Maven 3.9+.

```bash
# build + test (works offline once dependencies are cached)
mvn verify

# package runnable jars
mvn -q -DskipTests package
```

Point the CLI at the daemon jar (one line, e.g. in your shell
profile):

```bash
export CLARITY_DAEMON_JAR=$PWD/clarity-daemon/target/clarity-daemon-*.jar
```

## Configure an index

Create `.clarity/config.yaml` in your project:

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

Markdown is chunked by heading (a leading YAML frontmatter
block becomes its own chunk); YAML files index whole-file;
source files index comments only. Globs are Ant-style (`**`
crosses directories). Commit `config.yaml`; the runtime files
beside it (`clarity.sock`, `daemon.lock`, `daemon.log`) are
local state — ignore them.

## Index and query

```bash
# index (auto-starts the daemon on first use — no manual step)
java -jar clarity-cli/target/clarity-cli-*.jar index --dir /path/to/project

# query
java -jar clarity-cli/target/clarity-cli-*.jar query --dir /path/to/project my_docs "reindex the corpus"
```

Queries run as `hybrid` by default: plain words, fused with a
semantic vector pass via reciprocal rank fusion, degrading
silently to plain BM25 ranking when no embedder is configured.
`--query-syntax text` forces plain BM25-only ranking;
`--query-syntax raw` enables Lucene syntax (phrases, fields,
booleans); `--query-syntax vector` runs semantic search only
(and errors without an embedder, unlike `hybrid`). `-n` limits
hits (default 5); `--socket` overrides the socket path.
`--path-prefix doc/deploy` restricts results to that path or
its subtree, applied before ranking so a strong match outside
the subtree can't crowd out a weaker one inside it. Hits print
as `path:line [breadcrumb] (score)` plus text.

To run the daemon in the foreground (logs, watching):

```bash
java -jar clarity-daemon/target/clarity-daemon-*.jar --dir /path/to/project
```

Add `--add-modules jdk.incubator.vector` after `java` when
your runtime provides the module (same flag the auto-started
daemon probes for) for Lucene's faster vector path.

Both entry points accept `--help`. The daemon reindexes
changed files automatically, rescans periodically, and exits
after ~10 idle minutes; the next query restarts it.

## Vector search

The daemon embeds each chunk with a local MiniLM model on
first index and matches paraphrases by cosine similarity, so
queries with no shared words still retrieve. Model bytes
(~22 MB) download once to `~/.cache/clarity/models/`; if the
download fails the daemon logs a warning and serves text
search only. Vector scores are cosine-derived and not
comparable to text scores.

## MCP client setup

Launch the client jar's `mcp` subcommand over stdio:

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

The `search` tool takes `index` and `query` (plus optional
`top_n` and `syntax`: `hybrid` (default), `text`, `raw`, or
`vector`).

## Troubleshooting

- **Stale socket** (`clarity.sock` present, nothing
  listening): the client unlinks it and restarts the daemon
  under `.clarity/daemon.lock`. If restarts loop, stop all
  `clarity-daemon` processes, delete the socket, and query
  again; details are in `.clarity/daemon.log`.
- **Embedding model drift** (model id/dims mismatch): delete
  the index directory and reindex.
- **Empty results**: check the index name matches
  `config.yaml`, globs actually match files (broad globs over
  `node_modules/`/`target/` slow indexing and pollute
  results), and — for `vector` — that the model downloaded
  (see the daemon log).
