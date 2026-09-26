package org.github.lscoughlin.clarity.daemon;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local MiniLM embedding via ONNX Runtime. Model bytes
 * ({@code Xenova/all-MiniLM-L6-v2}, quantized, 384 dims, Apache-2.0)
 * download once to {@code ~/.cache/clarity/models/} on first use —
 * runtime data only, never test resources, so offline CI is unaffected.
 * Tokenization is pure-JDK WordPiece over the model's
 * {@code vocab.txt}; mean pooling with the attention mask, L2
 * normalized. Inference runs on CPU.
 */
public final class OnnxEmbedder implements Embedder, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OnnxEmbedder.class);

    static final String MODEL_ID = "Xenova/all-MiniLM-L6-v2:quantized";
    static final int DIMENSIONS = 384;
    private static final int MAX_TOKENS = 512;
    private static final String MODEL_FILE = "model_quantized.onnx";
    private static final String VOCAB_FILE = "vocab.txt";
    private static final String MODEL_BASE =
            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final Map<String, Integer> vocab;
    private final int unkId;

    private OnnxEmbedder(OrtEnvironment env, OrtSession session, Map<String, Integer> vocab)
            throws IOException {
        this.env = env;
        this.session = session;
        this.vocab = vocab;
        var unk = vocab.get("[UNK]");
        if (unk == null) {
            throw new IOException("vocab.txt has no [UNK] token");
        }
        this.unkId = unk;
    }

    /**
     * Loads the embedder, downloading model bytes on first use. Any
     * failure (offline, bad cache, native load) yields empty so the
     * caller can fall back to text-only search.
     */
    public static Optional<OnnxEmbedder> tryLoad() {
        try {
            return Optional.of(load());
        } catch (Exception e) {
            LOG.atWarn()
                    .setMessage("vector search disabled: embedding model unavailable ({})")
                    .addArgument(e.getMessage())
                    .log();
            return Optional.empty();
        }
    }

    static OnnxEmbedder load() throws IOException {
        var dir =
                Path.of(
                        System.getProperty("user.home"),
                        ".cache",
                        "clarity",
                        "models",
                        "all-MiniLM-L6-v2");
        Files.createDirectories(dir);
        var model = dir.resolve(MODEL_FILE);
        var vocabPath = dir.resolve(VOCAB_FILE);
        if (!Files.isRegularFile(model)) {
            fetch(MODEL_BASE + "/onnx/" + MODEL_FILE, model);
        }
        if (!Files.isRegularFile(vocabPath)) {
            fetch(MODEL_BASE + "/" + VOCAB_FILE, vocabPath);
        }
        var vocab = readVocab(vocabPath);
        try {
            var env = OrtEnvironment.getEnvironment();
            var session =
                    env.createSession(model.toString(), new OrtSession.SessionOptions());
            return new OnnxEmbedder(env, session, vocab);
        } catch (OrtException e) {
            throw new IOException("cannot start ONNX session: " + e.getMessage(), e);
        }
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        var batch = new ArrayList<List<Integer>>(texts.size());
        var width = 0;
        for (var text : texts) {
            var tokens = tokenize(text);
            batch.add(tokens);
            width = Math.max(width, tokens.size());
        }
        var size = batch.size();
        var ids = new long[size][width];
        var mask = new long[size][width];
        var types = new long[size][width];
        for (var r = 0; r < size; r++) {
            var tokens = batch.get(r);
            for (var i = 0; i < tokens.size(); i++) {
                ids[r][i] = tokens.get(i);
                mask[r][i] = 1;
            }
        }
        try (var idTensor = OnnxTensor.createTensor(env, ids);
                var maskTensor = OnnxTensor.createTensor(env, mask);
                var typeTensor = OnnxTensor.createTensor(env, types);
                var result =
                        session.run(
                                Map.of(
                                        "input_ids", idTensor,
                                        "attention_mask", maskTensor,
                                        "token_type_ids", typeTensor))) {
            var hidden = (float[][][]) result.get("last_hidden_state").get().getValue();
            var out = new ArrayList<float[]>(size);
            for (var r = 0; r < size; r++) {
                out.add(meanPool(hidden[r], mask[r]));
            }
            return out;
        } catch (OrtException e) {
            throw new IllegalStateException("embedding inference failed", e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            LOG.atDebug().setMessage("session close failed").setCause(e).log();
        }
    }

    /** Mean-pools masked positions of one row, then L2-normalizes. */
    private static float[] meanPool(float[][] hidden, long[] maskRow) {
        var mean = new float[DIMENSIONS];
        var count = 0;
        for (var i = 0; i < hidden.length; i++) {
            if (maskRow[i] == 0) {
                continue;
            }
            count++;
            var row = hidden[i];
            for (int d = 0; d < DIMENSIONS; d++) {
                mean[d] += row[d];
            }
        }
        if (count == 0) {
            return mean;
        }
        for (int d = 0; d < DIMENSIONS; d++) {
            mean[d] /= count;
        }
        var norm = 0f;
        for (float v : mean) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < DIMENSIONS; d++) {
                mean[d] /= norm;
            }
        }
        return mean;
    }

    /** BERT-style tokenize: lowercase, split punctuation, WordPiece, CLS/SEP. */
    List<Integer> tokenize(String text) {
        var ids = new ArrayList<Integer>();
        ids.add(vocab.getOrDefault("[CLS]", unkId));
        for (var word : basicTokenize(text.toLowerCase())) {
            wordPiece(word, ids);
        }
        ids.add(vocab.getOrDefault("[SEP]", unkId));
        while (ids.size() > MAX_TOKENS) {
            ids.remove(ids.size() - 2);
        }
        return ids;
    }

    private static List<String> basicTokenize(String text) {
        var words = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            var cp = text.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                flush(current, words);
            } else if (isPunctuation(cp)) {
                flush(current, words);
                words.add(new String(Character.toChars(cp)));
            } else {
                current.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        flush(current, words);
        return words;
    }

    private static void flush(StringBuilder current, List<String> words) {
        if (!current.isEmpty()) {
            words.add(current.toString());
            current.setLength(0);
        }
    }

    private static boolean isPunctuation(int cp) {
        var type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }

    private void wordPiece(String word, List<Integer> ids) {
        var start = 0;
        var pieces = new ArrayList<Integer>();
        while (start < word.length()) {
            var end = word.length();
            Integer found = null; // Null marks "no match below".
            while (start < end) {
                var piece = start == 0 ? word.substring(start, end) : "##" + word.substring(start, end);
                var id = vocab.get(piece);
                if (id != null) {
                    found = id;
                    break;
                }
                end -= Character.charCount(word.codePointBefore(end));
            }
            if (found == null) {
                ids.add(unkId);
                return;
            }
            pieces.add(found);
            start = end;
        }
        ids.addAll(pieces);
    }

    private static Map<String, Integer> readVocab(Path vocabPath) throws IOException {
        var vocab = new HashMap<String, Integer>();
        var lines = Files.readAllLines(vocabPath, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            vocab.putIfAbsent(lines.get(i), i);
        }
        if (vocab.isEmpty()) {
            throw new IOException("empty vocab: " + vocabPath);
        }
        return vocab;
    }

    private static void fetch(String url, Path dest) throws IOException {
        LOG.atInfo().setMessage("downloading embedding model file to {}").addArgument(dest).log();
        var tmp = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            var client =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(30))
                            .followRedirects(HttpClient.Redirect.ALWAYS)
                            .build();
            var response =
                    client.send(
                            HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofMinutes(10))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) {
                throw new IOException("model download failed: HTTP " + response.statusCode());
            }
            Files.move(tmp, dest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("model download interrupted", e);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
}
