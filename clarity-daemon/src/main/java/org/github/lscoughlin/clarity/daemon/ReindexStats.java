package org.github.lscoughlin.clarity.daemon;

/** Per-index outcome of one reindex pass: file deltas plus the live doc count. */
public record ReindexStats(int added, int changed, int removed, int docs) {}
