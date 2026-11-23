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

/** Just the GitHub REST endpoints the token path uses: commit statuses, a PR's state, labels and comments. */
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
    private static final Pattern COMMENTS = Pattern.compile("^/repos/([^/]+/[^/]+)/issues/(\\d+)/comments$");
    private static final Pattern COMMENT = Pattern.compile("^/repos/([^/]+/[^/]+)/issues/comments/(\\d+)$");
    private static final Pattern LABELS = Pattern.compile("^/repos/([^/]+/[^/]+)/issues/(\\d+)/labels(?:/(.+))?$");

    /** A comment on the PR (any number), as posted or last edited. */
    record Comment(long id, String body) {}

    /** Comments on PRs, oldest first; edits replace the entry in place. */
    final List<Comment> comments = new CopyOnWriteArrayList<>();
    /** Comment, label and status writes answer 403, as GitHub does without write permission. */
    volatile boolean readOnly;
    private final java.util.concurrent.atomic.AtomicLong nextCommentId = new java.util.concurrent.atomic.AtomicLong(100);

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
        Matcher comments = COMMENTS.matcher(path);
        Matcher comment = COMMENT.matcher(path);
        Matcher labelsPath = LABELS.matcher(path);
        String method = exchange.getRequestMethod();
        if (readOnly && !method.equals("GET") && (comments.matches() || comment.matches() || labelsPath.matches())) {
            respond(exchange, 403, "{\"message\":\"Resource not accessible by integration\"}");
            return;
        }
        if (comments.matches() && method.equals("GET")) {
            net.sf.json.JSONArray list = new net.sf.json.JSONArray();
            for (Comment c : this.comments) {
                JSONObject o = new JSONObject();
                o.put("id", c.id());
                o.put("body", c.body());
                list.add(o);
            }
            respond(exchange, 200, list.toString());
            return;
        } else if (comments.matches() && method.equals("POST")) {
            this.comments.add(new Comment(nextCommentId.getAndIncrement(), body(exchange).optString("body")));
            respond(exchange, 201, "{}");
            return;
        } else if (comment.matches() && method.equals("PATCH")) {
            long id = Long.parseLong(comment.group(2));
            String edited = body(exchange).optString("body");
            for (int i = 0; i < this.comments.size(); i++) {
                if (this.comments.get(i).id() == id) {
                    this.comments.set(i, new Comment(id, edited));
                }
            }
            respond(exchange, 200, "{}");
            return;
        } else if (labelsPath.matches() && method.equals("POST") && labelsPath.group(3) == null) {
            net.sf.json.JSONArray added = body(exchange).getJSONArray("labels");
            for (int i = 0; i < added.size(); i++) {
                if (!labels.contains(added.getString(i))) {
                    labels.add(added.getString(i));
                }
            }
            respond(exchange, 200, "[]");
            return;
        } else if (labelsPath.matches() && method.equals("DELETE") && labelsPath.group(3) != null) {
            String name = java.net.URLDecoder.decode(labelsPath.group(3), StandardCharsets.UTF_8);
            respond(exchange, labels.remove(name) ? 200 : 404, "[]");
            return;
        }
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

    private static JSONObject body(HttpExchange exchange) throws IOException {
        return JSONObject.fromObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
