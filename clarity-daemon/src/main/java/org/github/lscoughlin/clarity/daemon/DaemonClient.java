package org.github.lscoughlin.clarity.daemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.ControlRequest;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.ReindexRequest;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.SearchRequest;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.SearchResponse;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.StatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin client for a daemon socket: connect-per-request calls plus
 * auto-start. {@link #ensureRunning} connects if anyone listens;
 * otherwise, under an exclusive file lock, it unlinks a stale socket
 * and spawns the daemon, then polls until ready.
 */
public final class DaemonClient {
    private static final Logger LOG = LoggerFactory.getLogger(DaemonClient.class);

    static final Duration READY_TIMEOUT = Duration.ofSeconds(30);
    static final long READY_POLL_MILLIS = 100;

    /** Daemon launch, injectable so tests use a stub acceptor. */
    public interface Spawner {
        void spawn(Target target) throws IOException;
    }

    /** Resolved project base plus its derived socket and lock paths. */
    public record Target(Path baseDir, Path socketPath, Path lockPath) {}

    private DaemonClient() {}

    public static Target target(Path baseDir, Path socketOverride) {
        return target(baseDir, socketOverride, null);
    }

    public static Target target(Path baseDir, Path socketOverride, Path lockOverride) {
        var base = baseDir.toAbsolutePath().normalize();
        var socket =
                socketOverride != null
                        ? socketOverride.toAbsolutePath().normalize()
                        : SocketProtocol.Paths.socketFor(base);
        return new Target(base, socket, lockFor(base, lockOverride));
    }

    static Path lockFor(Path base, Path lockOverride) {
        if (lockOverride != null) {
            return lockOverride.toAbsolutePath().normalize();
        }
        var fromConfig = lockFromConfig(base);
        if (fromConfig != null) {
            return fromConfig;
        }
        return SocketProtocol.Paths.lockFor(base);
    }

    /**
     * {@code daemon.lock_file} from {@code .clarity/config.yaml},
     * resolved against the base dir. Absent or unreadable config
     * means "no key", never a failure.
     */
    private static Path lockFromConfig(Path base) {
        if (!java.nio.file.Files.isRegularFile(base.resolve(ConfigLoader.CONFIG_RELATIVE))) {
            return null;
        }
        try {
            var lockFile = ConfigLoader.load(base).daemon().lockFile();
            if (lockFile == null || lockFile.isBlank()) {
                return null;
            }
            var path = Path.of(lockFile);
            return (path.isAbsolute() ? path : base.resolve(path)).normalize();
        } catch (RuntimeException e) {
            LOG.atDebug().setMessage("ignoring lock_file from unreadable config").setCause(e).log();
            return null;
        }
    }

    public static Path socketFor(Path baseDir) {
        return SocketProtocol.Paths.socketFor(baseDir.toAbsolutePath().normalize());
    }

    public static Spawner defaultSpawner() {
        return DaemonClient::spawnDefault;
    }

    public static void ensureRunning(Target target) throws IOException {
        ensureRunning(target, defaultSpawner());
    }

    public static void ensureRunning(Target target, Spawner spawner) throws IOException {
        if (tryConnect(target)) {
            return;
        }
        var lockPath = target.lockPath();
        Files.createDirectories(lockPath.getParent());
        try (FileChannel channel =
                        FileChannel.open(
                                lockPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            if (tryConnect(target)) {
                return;
            }
            Files.deleteIfExists(target.socketPath());
            spawner.spawn(target);
            waitReady(target);
        } catch (OverlappingFileLockException e) {
            // Same-JVM rival holds the lock; it is starting the daemon.
            waitReady(target);
        }
    }

    public static Map<String, Integer> reindex(Target target) throws IOException {
        return reindex(target, defaultSpawner());
    }

    public static Map<String, Integer> reindex(Target target, Spawner spawner) throws IOException {
        ensureRunning(target, spawner);
        StatusResponse response =
                roundTrip(target, new ReindexRequest(null), StatusResponse.class);
        if (!response.ok()) {
            throw new IOException("daemon error: " + response.error());
        }
        return response.counts();
    }

    /** Reindexes one named index, or every index when {@code indexOrNull} is null. */
    public static Map<String, ReindexStats> reindexWithStats(Target target, String indexOrNull)
            throws IOException {
        return reindexWithStats(target, defaultSpawner(), indexOrNull);
    }

    public static Map<String, ReindexStats> reindexWithStats(
            Target target, Spawner spawner, String indexOrNull) throws IOException {
        ensureRunning(target, spawner);
        StatusResponse response =
                roundTrip(target, new ReindexRequest(indexOrNull), StatusResponse.class);
        if (!response.ok()) {
            throw new IOException("daemon error: " + response.error());
        }
        return response.stats();
    }

    public static List<Hit> search(
            Target target,
            String index,
            String query,
            int topN,
            SearchBackend.Syntax syntax)
            throws IOException {
        return search(target, defaultSpawner(), index, query, topN, syntax, null);
    }

    public static List<Hit> search(
            Target target,
            Spawner spawner,
            String index,
            String query,
            int topN,
            SearchBackend.Syntax syntax)
            throws IOException {
        return search(target, spawner, index, query, topN, syntax, null);
    }

    public static List<Hit> search(
            Target target,
            String index,
            String query,
            int topN,
            SearchBackend.Syntax syntax,
            boolean fullText)
            throws IOException {
        return search(target, defaultSpawner(), index, query, topN, syntax, null, fullText);
    }

    public static List<Hit> search(
            Target target,
            String index,
            String query,
            int topN,
            SearchBackend.Syntax syntax,
            String pathPrefix)
            throws IOException {
        return search(target, defaultSpawner(), index, query, topN, syntax, pathPrefix);
    }

    public static List<Hit> search(
            Target target,
            Spawner spawner,
            String index,
            String query,
            int topN,
            SearchBackend.Syntax syntax,
            String pathPrefix)
            throws IOException {
        return search(target, spawner, index, query, topN, syntax, pathPrefix, false);
    }

    public static List<Hit> search(
            Target target,
            String index,
            String query,
            int topN,
            SearchBackend.Syntax syntax,
            String pathPrefix,
            boolean fullText)
            throws IOException {
        return search(target, defaultSpawner(), index, query, topN, syntax, pathPrefix, fullText);
    }

    public static List<Hit> search(
            Target target,
            Spawner spawner,
            String index,
            String query,
            int topN,
            SearchBackend.Syntax syntax,
            String pathPrefix,
            boolean fullText)
            throws IOException {
        ensureRunning(target, spawner);
        SearchResponse response =
                roundTrip(
                        target,
                        new SearchRequest(
                                index, query, topN, syntax.name().toLowerCase(), pathPrefix, fullText),
                        SearchResponse.class);
        if (!response.ok()) {
            throw new IOException("daemon error: " + response.error());
        }
        return response.hits();
    }

    public static Map<String, Integer> health(Target target) throws IOException {
        return health(target, defaultSpawner());
    }

    public static Map<String, Integer> health(Target target, Spawner spawner) throws IOException {
        ensureRunning(target, spawner);
        StatusResponse response =
                roundTrip(target, new ControlRequest("health"), StatusResponse.class);
        if (!response.ok()) {
            throw new IOException("daemon error: " + response.error());
        }
        return response.counts();
    }

    /** Configured index names and their current document counts. */
    public static Map<String, Integer> listIndexes(Target target) throws IOException {
        return listIndexes(target, defaultSpawner());
    }

    public static Map<String, Integer> listIndexes(Target target, Spawner spawner)
            throws IOException {
        return health(target, spawner);
    }

    private static <T> T roundTrip(Target target, Object request, Class<T> responseType)
            throws IOException {
        var payload = SocketProtocol.JSON.writeValueAsBytes(request);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(target.socketPath()));
            try (InputStream in = Channels.newInputStream(channel);
                    OutputStream out = Channels.newOutputStream(channel)) {
                SocketFrames.write(out, payload);
                channel.shutdownOutput();
                return SocketProtocol.JSON.readValue(SocketFrames.read(in), responseType);
            }
        }
    }

    private static boolean tryConnect(Target target) {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(target.socketPath()));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void waitReady(Target target) throws IOException {
        var deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (tryConnect(target)) {
                return;
            }
            try {
                Thread.sleep(READY_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for daemon", e);
            }
        }
        throw new IOException("daemon did not become ready at " + target.socketPath());
    }

    static void spawnDefault(Target target) throws IOException {
        var command = daemonCommand(target);
        var log = SocketProtocol.Paths.logFor(target.baseDir());
        Files.createDirectories(log.getParent());
        var builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        LOG.atInfo()
                .setMessage("spawning daemon: {}")
                .addArgument(() -> String.join(" ", command))
                .log();
        builder.start();
    }

    /**
     * Daemon launch, in resolution order: {@code CLARITY_DAEMON_JAR}, a
     * sibling daemon jar, or the current classpath for classes-mode runs.
     */
    static List<String> daemonCommand(Target target) throws IOException {
        var self = nativeImagePath();
        if (self != null) {
            // Native binary: no java launcher and no jar to spawn,
            // so re-invoke this executable as `clarity daemon`.
            return List.of(
                    self,
                    "daemon",
                    "--dir",
                    target.baseDir().toString(),
                    "--socket",
                    target.socketPath().toString());
        }
        var head = new ArrayList<>(List.of(javaBin()));
        if (vectorModulePresent()) {
            head.add("--add-modules");
            head.add(VECTOR_MODULE);
        }
        var tail =
                List.of(
                        "--dir",
                        target.baseDir().toString(),
                        "--socket",
                        target.socketPath().toString());
        var jar = System.getenv("CLARITY_DAEMON_JAR");
        if (jar != null && !jar.isBlank()) {
            head.add("-jar");
            head.add(jar);
            return concat(head, tail);
        }
        var location = codeLocation();
        if (location != null && location.toString().endsWith(".jar")) {
            try (var stream =
                    Files.newDirectoryStream(location.getParent(), "clarity-daemon-*.jar")) {
                for (var sibling : stream) {
                    var jarHead = new ArrayList<>(head);
                    jarHead.add("-jar");
                    jarHead.add(sibling.toString());
                    return concat(jarHead, tail);
                }
            } catch (IOException e) {
                LOG.atDebug().setMessage("sibling daemon jar lookup failed").setCause(e).log();
            }
            throw new IOException(
                    "cannot locate clarity-daemon: set CLARITY_DAEMON_JAR or place a"
                            + " clarity-daemon jar next to this jar");
        }
        head.add("-cp");
        head.add(System.getProperty("java.class.path"));
        head.add(DaemonMain.class.getName());
        return concat(head, tail);
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Path of the running executable when compiled to a GraalVM native
     * image (which has no {@code java} launcher or jar to spawn),
     * otherwise null.
     */
    static String nativeImagePath() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
            return null;
        }
        return ProcessHandle.current().info().command().orElse(null);
    }

    /** Incubator module enabling Lucene's SIMD vector path. */
    static final String VECTOR_MODULE = "jdk.incubator.vector";

    /** Probe seam (tests replace this); answers whether the runtime provides the module. */
    static Supplier<Boolean> vectorModuleProbe = DaemonClient::hasVectorModule;

    private static volatile Boolean vectorModuleCached;

    /** Test seam: clears the cached probe result. */
    static void resetVectorModuleCache() {
        synchronized (DaemonClient.class) {
            vectorModuleCached = null;
        }
    }

    /**
     * Whether to pass the incubator flag, probed once per client
     * process. Any probe failure launches without the flag.
     */
    static boolean vectorModulePresent() {
        var cached = vectorModuleCached;
        if (cached != null) {
            return cached;
        }
        synchronized (DaemonClient.class) {
            if (vectorModuleCached == null) {
                try {
                    vectorModuleCached = vectorModuleProbe.get();
                } catch (RuntimeException e) {
                    LOG.atDebug().setMessage("vector module probe failed").setCause(e).log();
                    vectorModuleCached = false;
                }
            }
            return vectorModuleCached;
        }
    }

    private static boolean hasVectorModule() {
        try {
            var process = new ProcessBuilder(javaBin(), "--list-modules").start();
            if (!process.waitFor(Duration.ofSeconds(15))) {
                process.destroyForcibly();
                return false;
            }
            try (var out =
                    new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return out.lines().anyMatch(line -> line.startsWith(VECTOR_MODULE));
            }
        } catch (Exception e) {
            LOG.atDebug().setMessage("vector module probe failed").setCause(e).log();
            return false;
        }
    }

    private static Path codeLocation() {
        try {
            return Path.of(
                            DaemonClient.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())
                    .toAbsolutePath();
        } catch (Exception e) {
            LOG.atDebug().setMessage("cannot locate own code").setCause(e).log();
            return null;
        }
    }

    private static List<String> concat(List<String> head, List<String> tail) {
        return java.util.stream.Stream.concat(head.stream(), tail.stream()).toList();
    }
}
