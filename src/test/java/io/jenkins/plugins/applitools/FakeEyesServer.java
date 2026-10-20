package io.jenkins.plugins.applitools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records requests and answers like Eyes would.
 *
 * <ul>
 *   <li>{@code DELETE …/close/bypointerid}: 200, or 404 for IDs in {@link #unknownBatches}
 *   <li>{@code GET /api/v1/batches/{id}}: the JSON registered with {@link #results}, else 404
 *   <li>{@code GET …/{id}/bypointerid}: {@code runningCount} counts down from {@link #runningPolls} per batch
 * </ul>
 */
final class FakeEyesServer implements AutoCloseable {

    record Request(String method, String path, String query, String apiKeyHeader) {}

    private static final Pattern V1_BATCH = Pattern.compile("^/api/v1/batches/([^/]+)$");
    private static final Pattern BY_POINTER = Pattern.compile("^/api/sessions/batches/([^/]+)/bypointerid$");

    final List<Request> requests = new CopyOnWriteArrayList<>();
    final List<String> unknownBatches = new CopyOnWriteArrayList<>();
    final Map<String, String> results = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> runningPolls = new ConcurrentHashMap<>();
    private final HttpServer server;

    FakeEyesServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(
                method,
                path,
                exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("X-Eyes-Api-Key")));

        Matcher v1 = V1_BATCH.matcher(path);
        Matcher byPointer = BY_POINTER.matcher(path);
        if (method.equals("DELETE")) {
            boolean unknown = unknownBatches.stream().anyMatch(id -> path.contains("/batches/" + id + "/"));
            respond(exchange, unknown ? 404 : 200, null);
        } else if (v1.matches() && results.containsKey(v1.group(1))) {
            respond(exchange, 200, results.get(v1.group(1)));
        } else if (byPointer.matches() && results.containsKey(byPointer.group(1))) {
            AtomicInteger polls = runningPolls.get(byPointer.group(1));
            int running = polls != null ? Math.max(0, polls.getAndDecrement()) : 0;
            respond(exchange, 200, "{\"runningCount\":" + running + "}");
        } else {
            respond(exchange, 404, null);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    /** Registers results shaped like {@code GET /api/v1/batches/{id}}. */
    void results(String batchId, int passed, int failed, int unresolved, int newTests, int aborted) {
        int total = passed + failed + unresolved;
        results.put(batchId, """
                {"statistics": {"pointerId": "%s", "name": "Fake batch", "passed": %d, "failed": %d,
                                "unresolved": %d, "new": %d, "aborted": %d, "total": %d},
                 "batchUrl": "https://eyes.example/app/test-results/123",
                 "tests": [{"testName": "home page", "status": "%s",
                            "env": {"app": "Chrome 155.0", "os": "Linux", "device": "Desktop",
                                    "viewport": {"width": 1280, "height": 800}},
                            "sessionUrl": "https://eyes.example/app/test-results/123/456"}]}
                """.formatted(batchId, passed, failed, unresolved, newTests, aborted, total,
                failed > 0 ? "failed" : unresolved > 0 ? "unresolved" : "passed"));
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> closeRequests() {
        return requests.stream()
                .filter(r -> r.method().equals("DELETE") && r.path().endsWith("/close/bypointerid"))
                .toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
