package io.jenkins.plugins.applitools;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records requests and answers like Eyes would. Batch IDs listed in {@link #unknownBatches} return 404. */
final class FakeEyesServer implements AutoCloseable {

    record Request(String method, String path, String query) {}

    final List<Request> requests = new CopyOnWriteArrayList<>();
    final List<String> unknownBatches = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    FakeEyesServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getQuery()));
            boolean unknown = unknownBatches.stream().anyMatch(id -> path.contains("/batches/" + id + "/"));
            exchange.sendResponseHeaders(unknown ? 404 : 200, -1);
            exchange.close();
        });
        server.start();
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
