package org.github.lscoughlin.clarity.daemon;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one {@link IndexService} and its {@link CorpusWatcher}.
 *
 * <p>Two independent concerns are locked separately. {@code structureLock},
 * a read-write lock, guards the {@code service}/{@code watcher} field
 * references themselves: {@link #reload} takes the write side to swap and
 * close them, while {@link #search}, {@link #drainReindex}, and {@link
 * #reindexNow} take the read side, so searches never observe a
 * half-reloaded service but otherwise run fully concurrently with each
 * other and with reindex passes — Lucene's near-real-time reader already
 * makes concurrent search and write against the same backend safe, so nothing
 * here needs to serialize them. {@code reindexMutex} separately serializes
 * reindex passes against each other (search never touches it), so two
 * passes never race each other's checksum comparisons or write duplicate
 * documents. Everything else — the queued/scan flags, {@code inFlight}, and
 * the idle-timeout clock — is cheap bookkeeping still guarded by this
 * object's own monitor, held only for the instant it takes to read or flip
 * a field, never across a Lucene call.
 */
public final class Daemon implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(Daemon.class);

    static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);
    static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofMinutes(5);
    static final Duration QUIET_PERIOD = Duration.ofSeconds(1);
    static final long POLL_MILLIS = 500;

    private final Path baseDir;
    private final Duration idleTimeout;
    private final Duration scanInterval;
    private final Embedder embedder;
    private final ReentrantReadWriteLock structureLock = new ReentrantReadWriteLock();
    private final ReentrantLock reindexMutex = new ReentrantLock();
    private IndexService service;
    private CorpusWatcher watcher;
    private long lastActivityNanos = System.nanoTime();
    private long lastEventNanos;
    private long nextScanNanos;
    private int inFlight;
    private boolean reindexQueued;
    private boolean scanQueued;
    private volatile boolean shutdown;

    private Daemon(
            Path baseDir,
            Duration idleTimeout,
            Duration scanInterval,
            Embedder embedder,
            IndexService service,
            CorpusWatcher watcher) {
        this.baseDir = baseDir;
        this.idleTimeout = idleTimeout;
        this.scanInterval = scanInterval;
        this.embedder = embedder;
        this.service = service;
        this.watcher = watcher;
        this.nextScanNanos = System.nanoTime() + scanInterval.toNanos();
    }

    /** Opens the service, runs the initial full index, and registers the watcher. */
    public static Daemon start(Path baseDir, Duration idleTimeout) throws IOException {
        return start(baseDir, idleTimeout, DEFAULT_SCAN_INTERVAL);
    }

    static Daemon start(Path baseDir, Duration idleTimeout, Duration scanInterval)
            throws IOException {
        return start(baseDir, idleTimeout, scanInterval, null);
    }

    /**
     * Starts with an embedder for vector search; a null embedder keeps
     * text-only behavior. The embedder survives config reloads.
     */
    static Daemon start(
            Path baseDir, Duration idleTimeout, Duration scanInterval, Embedder embedder)
            throws IOException {
        IndexService service = IndexService.open(baseDir, ConfigLoader.load(baseDir), embedder);
        service.reindex();
        CorpusWatcher watcher = CorpusWatcher.register(baseDir, service.config());
        Daemon daemon = new Daemon(baseDir, idleTimeout, scanInterval, embedder, service, watcher);
        daemon.touch();
        return daemon;
    }

    public List<Hit> search(
            String indexName, String query, int topN, SearchBackend.Syntax syntax)
            throws IOException {
        return search(indexName, query, topN, syntax, null);
    }

    public List<Hit> search(
            String indexName, String query, int topN, SearchBackend.Syntax syntax, String pathPrefix)
            throws IOException {
        return search(indexName, query, topN, syntax, pathPrefix, false);
    }

    public List<Hit> search(
            String indexName,
            String query,
            int topN,
            SearchBackend.Syntax syntax,
            String pathPrefix,
            boolean fullText)
            throws IOException {
        markInFlight(1);
        structureLock.readLock().lock();
        try {
            var hits = service.search(indexName, query, topN, syntax, pathPrefix, fullText);
            // Fire-and-forget: queues a pass for the background loop to pick up
            // (within POLL_MILLIS), so callers never wait on it.
            requestReindex();
            touch();
            return hits;
        } finally {
            structureLock.readLock().unlock();
            markInFlight(-1);
        }
    }

    public Map<String, Integer> counts() throws IOException {
        structureLock.readLock().lock();
        try {
            return service.counts();
        } finally {
            structureLock.readLock().unlock();
        }
    }

    /** Document updates performed per named index; operational visibility and tests. */
    public Map<String, Long> writeCounts() throws IOException {
        structureLock.readLock().lock();
        try {
            return service.writeCounts();
        } finally {
            structureLock.readLock().unlock();
        }
    }

    /** Queues a pass; the next drain runs it (coalescing bursts). */
    public synchronized void requestReindex() {
        reindexQueued = true;
    }

    /**
     * Runs queued passes until none remain. Watcher-triggered passes always
     * mark activity; a scan-only pass marks activity too when it actually
     * finds changes (note: a search-triggered pass already sets
     * {@code reindexQueued}, so it always counts as activity here regardless
     * of this check).
     */
    public void drainReindex() throws IOException {
        boolean activity;
        boolean scan;
        synchronized (this) {
            activity = reindexQueued;
            reindexQueued = false;
            scan = scanQueued;
            scanQueued = false;
        }
        if (!activity && !scan) {
            return;
        }
        Map<String, ReindexStats> stats;
        structureLock.readLock().lock();
        reindexMutex.lock();
        try {
            stats = service.reindex();
        } finally {
            reindexMutex.unlock();
            structureLock.readLock().unlock();
        }
        if (activity || hasChanges(stats)) {
            touch();
        }
    }

    private static boolean hasChanges(Map<String, ReindexStats> stats) {
        return stats.values().stream()
                .anyMatch(s -> s.added() > 0 || s.changed() > 0 || s.removed() > 0);
    }

    /** Reindexes one named index, or every index when {@code indexOrNull} is null. */
    public Map<String, ReindexStats> reindexNow(String indexOrNull) throws IOException {
        markInFlight(1);
        structureLock.readLock().lock();
        reindexMutex.lock();
        try {
            var stats =
                    indexOrNull == null
                            ? service.reindex()
                            : Map.of(indexOrNull, service.reindexIndex(indexOrNull));
            touch();
            return stats;
        } finally {
            reindexMutex.unlock();
            structureLock.readLock().unlock();
            markInFlight(-1);
        }
    }

    /** Reloads config, service, and watcher registration. Queues a pass. */
    public void reload() throws IOException {
        structureLock.writeLock().lock();
        try {
            service.close();
            watcher.close();
            service = IndexService.open(baseDir, ConfigLoader.load(baseDir), embedder);
            watcher = CorpusWatcher.register(baseDir, service.config());
        } finally {
            structureLock.writeLock().unlock();
        }
        requestReindex();
        touch();
        LOG.atInfo().setMessage("reloaded configuration").log();
    }

    /**
     * Applies one drained batch: config reload, debounce accounting, then
     * any due pass. {@code nowNanos} is a parameter for tests. Only ever
     * called from {@link #runLoop}'s single thread, so the timer fields it
     * owns outright ({@code lastEventNanos}, {@code nextScanNanos}) need no
     * guard; the flags shared with other threads go through {@link
     * #requestReindex} or a short {@code synchronized} block.
     */
    void tick(CorpusWatcher.Drain drain, long nowNanos) throws IOException {
        if (drain.configChanged()) {
            reload();
        }
        if (!drain.changedFiles().isEmpty()) {
            lastEventNanos = nowNanos;
        }
        if (lastEventNanos > 0 && nowNanos - lastEventNanos >= QUIET_PERIOD.toNanos()) {
            lastEventNanos = 0;
            requestReindex();
        }
        if (nowNanos >= nextScanNanos) {
            nextScanNanos = nowNanos + scanInterval.toNanos();
            synchronized (this) {
                scanQueued = true;
            }
        }
        drainReindex();
    }

    synchronized boolean idleExpired(long nowNanos) {
        return inFlight == 0 && nowNanos - lastActivityNanos >= idleTimeout.toNanos();
    }

    /** Blocks serving watch passes until shutdown (idle timeout or hook). */
    public void runLoop() {
        while (!shutdown) {
            CorpusWatcher.Drain drain = watcher.drainMillis(POLL_MILLIS);
            var now = System.nanoTime();
            if (shutdown) {
                return;
            }
            try {
                tick(drain, now);
            } catch (IOException e) {
                LOG.atWarn()
                        .setMessage("reindex pass failed, will retry on next trigger")
                        .setCause(e)
                        .log();
            }
            if (idleExpired(now)) {
                LOG.atInfo().setMessage("idle timeout reached, shutting down").log();
                shutdown();
                return;
            }
        }
    }

    /** Idempotent clean shutdown from any thread. */
    public void shutdown() {
        synchronized (this) {
            if (shutdown) {
                return;
            }
            shutdown = true;
        }
        structureLock.writeLock().lock();
        try {
            try {
                watcher.close();
            } catch (IOException e) {
                LOG.atDebug().setMessage("watcher close failed").setCause(e).log();
            }
            try {
                service.close();
            } catch (IOException e) {
                LOG.atDebug().setMessage("service close failed").setCause(e).log();
            }
        } finally {
            structureLock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private synchronized void touch() {
        lastActivityNanos = System.nanoTime();
    }

    private synchronized void markInFlight(int delta) {
        inFlight += delta;
    }
}
