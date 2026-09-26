# Architecture

Clarity is a local-first search system for a project's own docs:
Markdown, YAML, and source-code comments are indexed per project
and served to a CLI and to MCP clients. All indexing and search
runs on-device; the only network use is a one-time embedding
model download (see below).

## Modules

Four Maven modules, one direction of dependence:

- `clarity-parser` — pure library, no Lucene. Config model
  (`.clarity/config.yaml`), glob resolution, and chunking:
  `MarkdownChunker` (one chunk per heading section, leading
  text as its own chunk, leading YAML frontmatter as a
  `frontmatter` chunk), `YamlLoader` (whole file, one chunk),
  `CommentExtractor` (comment runs and block comments only —
  code tokens never enter the index). Every `Chunk` carries
  `sourcePath`, a heading breadcrumb, text, a 1-based
  `startLine`, and the file's parsed frontmatter (empty unless
  it's a Markdown file with a YAML-mapping frontmatter block) —
  every chunk from that file carries the same frontmatter, not
  just the standalone `frontmatter` chunk.
- `clarity-daemon` — owns indexing and the Lucene indexes: one
  index per named entry, one document per chunk. `FileParser`
  parses files on virtual threads; unchanged files skip by
  SHA-256 checksum. `Daemon` is the single writer: initial full
  index, watch loop (debounced) plus periodic scan, config
  reload, idle shutdown (~10 minutes). `DaemonServer` /
  `DaemonClient` serve `search` / `reindex` / `health` over a
  Unix socket; clients auto-start the daemon under a file lock.
- `clarity-cli` — one client jar (`ClarityMain`, picocli):
  `index` / `query`, `mcp` (MCP `search` tool over stdio),
  `daemon` (foreground daemon). Thin client of the daemon.

Only the daemon opens indexes for writing or loads the
embedding model.

## Index schema

One Lucene document per chunk. Stored fields: `path`,
`heading` (verbatim breadcrumb), `checksum`, analyzed `text`,
analyzed-but-unstored `heading_text` (non-empty headings, so
heading-only terms retrieve),
`line` (chunk start line, `0` for data predating line
tracking), and `frontmatter` (the chunk's frontmatter map,
JSON-encoded; absent when empty — duplicated across every
chunk of a file rather than looked up by path at query time).
With an embedder, each document also carries a
COSINE `KnnFloatVectorField` over heading plus body text.
Writer commit metadata records `schema_version` (currently
`3`: 1 added `line`, 2 added `heading_text`, 3 added
`frontmatter`) and, when embedded, the embedding model id and
dimensions. A missing or older schema marker forces one full
reindex pass so new stored fields backfill; a changed
embedding model id/dims fails fast with a drift error (delete
the index directory and reindex).

## Query modes

- `hybrid` (default): fuses `text` and `vector` ranking via
  reciprocal rank fusion (RRF, `k = 60`) over each mode's
  top candidates within one reader snapshot. Silently
  degrades to text-only ranking whenever vector search isn't
  available (no embedder configured, or the vector leg fails
  for any other reason) — never errors for that. A hybrid
  hit's score is the fused RRF value, not a raw BM25 or
  cosine score, and is not comparable to scores from any
  other mode.
- `text`: plain words, Lucene-escaped, BM25 over the `text`
  field plus boosted `heading_text`.
- `raw`: Lucene query syntax against the `text` field
  (`heading_text:` explicitly addressable)
  (phrases, fields, booleans).
- `vector`: the query is embedded with the local model and
  matched by cosine similarity; scores are not comparable to
  BM25. Errors if no embedder is configured (unlike `hybrid`,
  which degrades instead).

Results carry source path, heading breadcrumb, text, score,
start line, and the source file's frontmatter (empty when
none); CLI/MCP render `path:line`, and print/surface the
frontmatter separately from the chunk text when present.

## Embeddings

`OnnxEmbedder` runs `Xenova/all-MiniLM-L6-v2` (quantized ONNX,
384 dims, Apache-2.0) via ONNX Runtime, CPU-only, with
pure-JDK WordPiece tokenization, mean pooling, and L2
normalization. Indexing embeds one file's chunks per
inference run (64-row batches, values identical to single
embeds). Model bytes download once to
`~/.cache/clarity/models/` on first daemon start; without
them the daemon logs a warning and serves text search only.
The auto-spawned daemon passes `--add-modules
jdk.incubator.vector` when the runtime provides it (Lucene's
SIMD path; probe is fail-open). Tests never touch model bytes
(deterministic stub embedders), so `mvn -o verify` stays
green.

## Runtime files and packaging

Per project, next to the config: `.clarity/clarity.sock`,
`.clarity/daemon.lock`, `.clarity/daemon.log`. Commit
`config.yaml`; ignore the rest. Runnable packaging is shaded
fat jars per module; the daemon jar embeds the ONNX runtime
(~81 MB); the single client jar also embeds it (~70 MB),
since `clarity daemon` runs the daemon in-process —
embeddings live daemon-side only. The `native` Maven profile
additionally links a `clarity` binary from `clarity-cli` via
GraalVM native-image on the GraalVM 25 toolchain (see
`AGENTS.md#toolchain-and-commands`); it serves text and vector
search (ONNX dylibs as image resources, traced `jni-config.json`;
`OnnxEmbedder.tryLoad` still degrades to text-only if native
load ever fails).

## Conventions

See `AGENTS.md`: modern Java 25 style (`var` for
evident-type locals, enhanced `switch`, fluent SLF4J
logging), stderr-only logging, tests beside behavior
changes, offline-safe `mvn verify`.
