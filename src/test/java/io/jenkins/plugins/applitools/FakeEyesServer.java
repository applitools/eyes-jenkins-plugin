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
    private static final Pattern SESSION = Pattern.compile("^/api/sessions/batches/([^/]+)/([^/]+)$");

    final List<Request> requests = new CopyOnWriteArrayList<>();
    final List<String> unknownBatches = new CopyOnWriteArrayList<>();
    final Map<String, String> results = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> runningPolls = new ConcurrentHashMap<>();
    /** Session ID → users who accepted that session's changes (as {@code lastAccept} from that session). */
    final Map<String, List<String>> approvals = new ConcurrentHashMap<>();
    /** Baseline merges requested, as "source -> target (onlyCheck)". */
    final List<String> merges = new CopyOnWriteArrayList<>();
    /** Polls of a merge task that answer "still running" before it completes. */
    volatile int mergeRunningPolls = 1;
    /** What the finished merge task reports. */
    volatile String mergeResult = "{\"merged\":true,\"conflicts\":0,\"changes\":[{},{}]}";
    /** When set, merge requests with any other key get 403. */
    volatile String requiredMergeKey;
    private final java.util.concurrent.atomic.AtomicInteger mergePolls = new java.util.concurrent.atomic.AtomicInteger();
    /** When set, GETs with any other key get 403, like Eyes does for run keys. */
    volatile String requiredReadKey;
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
        Matcher session = SESSION.matcher(path);
        String key = exchange.getRequestHeaders().getFirst("X-Eyes-Api-Key");
        if (path.equals("/api/baselines/branches/merge") && method.equals("POST")) {
            if (requiredMergeKey != null && !requiredMergeKey.equals(key)) {
                respond(exchange, 403, null);
                return;
            }
            Map<String, String> q = new java.util.HashMap<>();
            for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                q.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            merges.add(q.get("SourceBranch") + " -> " + q.get("TargetBranch") + " (" + q.get("OnlyCheck") + ")");
            mergePolls.set(mergeRunningPolls);
            exchange.getResponseHeaders().add("Location", url() + "/api/tasks/job-1/status");
            respond(exchange, 202, null);
            return;
        }
        if (path.equals("/api/tasks/job-1/status")) {
            respond(exchange, mergePolls.getAndDecrement() > 0 ? 200 : 201, null);
            return;
        }
        if (path.equals("/api/tasks/job-1/result")) {
            respond(exchange, 200, mergeResult);
            return;
        }
        if (method.equals("GET") && requiredReadKey != null && !requiredReadKey.equals(key)) {
            respond(exchange, 403, null);
        } else if (method.equals("DELETE")) {
            boolean unknown = unknownBatches.stream().anyMatch(id -> path.contains("/batches/" + id + "/"));
            respond(exchange, unknown ? 404 : 200, null);
        } else if (v1.matches() && results.containsKey(v1.group(1))) {
            respond(exchange, 200, results.get(v1.group(1)));
        } else if (byPointer.matches() && results.containsKey(byPointer.group(1))) {
            AtomicInteger polls = runningPolls.get(byPointer.group(1));
            int running = polls != null ? Math.max(0, polls.getAndDecrement()) : 0;
            respond(exchange, 200, "{\"runningCount\":" + running + "}");
        } else if (method.equals("GET") && session.matches() && !path.endsWith("/bypointerid")) {
            String sessionId = session.group(2);
            // Like real Eyes: reviews show up in appOutputResolution once resolved; expectedAppOutput is the baseline
            // snapshot from when the test ran, accepted in an earlier session, and must not count as approval.
            StringBuilder resolutions = new StringBuilder();
            for (String user : approvals.getOrDefault(sessionId, List.of())) {
                resolutions.append(resolutions.length() > 0 ? "," : "")
                        .append("{\"isManuallyRejected\":false,\"lastAccept\":{\"user\":\"%s\",\"sessionId\":\"%s\"}}"
                                .formatted(user, sessionId));
            }
            respond(exchange, 200, "{\"id\":\"" + sessionId + "\","
                    + "\"expectedAppOutput\":[{\"lastAccept\":{\"user\":\"old@example.com\",\"sessionId\":\"earlier\"}}],"
                    + "\"appOutputResolution\":[" + resolutions + "]}");
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

    record FakeTest(String id, String status) {}

    /** Registers results for explicit tests (status: passed, unresolved, failed, new). */
    void results(String batchId, List<FakeTest> tests) {
        long passed = tests.stream().filter(t -> t.status().equals("passed") || t.status().equals("new")).count();
        long failed = tests.stream().filter(t -> t.status().equals("failed")).count();
        long unresolved = tests.stream().filter(t -> t.status().equals("unresolved")).count();
        StringBuilder array = new StringBuilder();
        for (FakeTest t : tests) {
            array.append(array.length() > 0 ? "," : "").append("""
                    {"testId": "%s", "testName": "test %s", "status": "%s",
                     "env": {"app": "Chrome", "os": "Linux"},
                     "sessionUrl": "https://eyes.example/app/test-results/eb-%s/%s"}""".formatted(
                    t.id(), t.id(), t.status(), batchId, t.id()));
        }
        results.put(batchId, """
                {"statistics": {"id": "eb-%s", "pointerId": "%s", "name": "Fake batch", "passed": %d, "failed": %d,
                                "unresolved": %d, "new": 0, "aborted": 0, "total": %d},
                 "batchUrl": "https://eyes.example/app/test-results/eb-%s",
                 "tests": [%s]}
                """.formatted(batchId, batchId, passed, failed, unresolved, tests.size(), batchId, array));
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
