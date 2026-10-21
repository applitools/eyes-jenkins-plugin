package io.jenkins.plugins.applitools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.json.JSONObject;

/** Just the GitHub REST endpoints the token path uses: commit statuses and a PR's state and labels. */
final class FakeGitHubServer implements AutoCloseable {

    record Status(String repo, String sha, String context, String state, String description, String url,
            String authorization) {
        @Override
        public String toString() {
            return context + " = " + state + " | " + description;
        }
    }

    private static final Pattern STATUS = Pattern.compile("^/repos/([^/]+/[^/]+)/statuses/([^/]+)$");
    private static final Pattern PULL = Pattern.compile("^/repos/([^/]+/[^/]+)/pulls/(\\d+)$");

    final List<Status> statuses = new CopyOnWriteArrayList<>();
    final List<String> labels = new CopyOnWriteArrayList<>();
    volatile String token = "gh-token-0123456789";
    /** Conditional PR reads answered 304 (nothing changed). */
    final java.util.concurrent.atomic.AtomicInteger notModified = new java.util.concurrent.atomic.AtomicInteger();
    volatile String state = "open";
    volatile boolean merged;
    volatile String headRef = "feature";
    volatile String baseRef = "main";
    private final HttpServer server;

    FakeGitHubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + token).equals(auth)) {
            respond(exchange, 401, "{\"message\":\"Bad credentials\"}");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        Matcher status = STATUS.matcher(path);
        Matcher pull = PULL.matcher(path);
        if (status.matches() && exchange.getRequestMethod().equals("POST")) {
            JSONObject body = JSONObject.fromObject(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            statuses.add(new Status(status.group(1), status.group(2), body.optString("context"),
                    body.optString("state"), body.optString("description"), body.optString("target_url", null),
                    auth));
            respond(exchange, 201, "{}");
        } else if (pull.matches()) {
            String etag = "\"" + (state + merged + headRef + baseRef + labels).hashCode() + "\"";
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                notModified.incrementAndGet();
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("ETag", etag);
            JSONObject pr = new JSONObject();
            pr.put("number", Integer.parseInt(pull.group(2)));
            pr.put("state", state);
            pr.put("merged", merged);
            pr.put("head", JSONObject.fromObject("{\"ref\":\"" + headRef + "\"}"));
            pr.put("base", JSONObject.fromObject("{\"ref\":\"" + baseRef + "\"}"));
            StringBuilder ls = new StringBuilder("[");
            for (String l : labels) {
                ls.append(ls.length() > 1 ? "," : "").append("{\"name\":\"").append(l).append("\"}");
            }
            pr.put("labels", net.sf.json.JSONArray.fromObject(ls.append("]").toString()));
            respond(exchange, 200, pr.toString());
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    String apiUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<String> posted() {
        return statuses.stream().map(Status::toString).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
