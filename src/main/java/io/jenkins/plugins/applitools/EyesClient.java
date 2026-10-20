package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/** Minimal Eyes REST client. Uses Jenkins' proxy settings and sends the API key in a header. */
public class EyesClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String API_KEY_HEADER = "X-Eyes-Api-Key";

    private final String serverUrl;
    private final String apiKey;

    public EyesClient(@NonNull String serverUrl, @NonNull String apiKey) {
        this.serverUrl = serverUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
    }

    public enum CloseResult {
        CLOSED,
        /** Eyes has no batch with this ID, usually because no tests ran inside the block. */
        NOT_FOUND
    }

    /**
     * Closes a batch by the pointer ID the SDKs were given. Closing tells Eyes the batch is complete, which triggers
     * its notifications and SCM integration.
     */
    @NonNull
    public CloseResult closeBatch(@NonNull String batchId) throws IOException, InterruptedException {
        String path = "/api/sessions/batches/" + encode(batchId) + "/close/bypointerid";
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).DELETE());
        if (response.statusCode() == 401) {
            // Older or on-prem servers may only accept the key as a query parameter on this endpoint.
            response = sendWithoutHeader(HttpRequest.newBuilder(uri(path + "?apiKey=" + encode(apiKey))).DELETE());
        }
        if (response.statusCode() == 404) {
            return CloseResult.NOT_FOUND;
        }
        requireSuccess(response, "closing batch " + batchId);
        return CloseResult.CLOSED;
    }

    /** How many tests in the batch are still rendering or comparing; 0 when results are final. */
    public int runningCount(@NonNull String batchId) throws IOException, InterruptedException {
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(uri("/api/sessions/batches/" + encode(batchId) + "/bypointerid")).GET());
        if (response.statusCode() == 404) {
            return 0;
        }
        requireSuccess(response, "reading batch " + batchId);
        return json(response).optInt("runningCount");
    }

    /** Results for a batch by pointer ID; {@code null} if Eyes has no such batch. */
    public BatchResults getResults(@NonNull String batchId) throws IOException, InterruptedException {
        HttpResponse<String> response =
                send(HttpRequest.newBuilder(uri("/api/v1/batches/" + encode(batchId))).GET());
        if (response.statusCode() == 404) {
            return null;
        }
        requireSuccess(response, "reading results for batch " + batchId);
        return BatchResults.parse(batchId, json(response));
    }

    /**
     * Who accepted this session's changes: users whose accept of a step's baseline came from this very session
     * ({@code expectedAppOutput[].lastAccept.sessionId}). Empty if nothing in it was accepted, or not saved yet.
     */
    @NonNull
    public java.util.Set<String> sessionApprovers(@NonNull String eyesBatchId, @NonNull String sessionId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri("/api/sessions/batches/" + encode(eyesBatchId) + "/" + encode(sessionId)))
                .GET());
        requireSuccess(response, "reading session " + sessionId);
        java.util.Set<String> users = new java.util.TreeSet<>();
        net.sf.json.JSONArray expected = json(response).optJSONArray("expectedAppOutput");
        if (expected != null) {
            for (int i = 0; i < expected.size(); i++) {
                JSONObject accept = expected.optJSONObject(i) != null
                        ? expected.getJSONObject(i).optJSONObject("lastAccept")
                        : null;
                if (accept != null && sessionId.equals(accept.optString("sessionId")) && accept.has("user")) {
                    users.add(accept.getString("user"));
                }
            }
        }
        return users;
    }

    private static JSONObject json(HttpResponse<String> response) throws IOException {
        try {
            return JSONObject.fromObject(response.body());
        } catch (JSONException e) {
            throw new IOException("Eyes returned an unexpected response: " + e.getMessage(), e);
        }
    }

    private URI uri(String pathAndQuery) {
        return URI.create(serverUrl + pathAndQuery);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return sendWithoutHeader(request.header(API_KEY_HEADER, apiKey));
    }

    private static HttpResponse<String> sendWithoutHeader(HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return ProxyConfiguration.newHttpClient()
                .send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Eyes rejected the key for this operation (e.g. a run key reading results). */
    public static class UnauthorizedException extends IOException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private final int status;

        UnauthorizedException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    private static void requireSuccess(HttpResponse<String> response, String what) throws IOException {
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new UnauthorizedException(
                    response.statusCode(), "Eyes returned HTTP " + response.statusCode() + " " + what);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Eyes returned HTTP " + response.statusCode() + " " + what);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
