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

    /** What a key is allowed to do, judged from harmless requests. */
    public enum KeyCheck {
        /** Eyes doesn't recognize the key. */
        INVALID,
        /** Valid, but not allowed this operation. */
        NOT_ALLOWED,
        ALLOWED
    }

    /** Reads a batch that can't exist: 404 means the key may read results, 403 that it may not, 401 that it's invalid. */
    @NonNull
    public KeyCheck checkRead() throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri("/api/sessions/batches/applitools-jenkins-setup-check/bypointerid"))
                .GET());
        return switch (response.statusCode()) {
            case 401 -> KeyCheck.INVALID;
            case 403 -> KeyCheck.NOT_ALLOWED;
            default -> KeyCheck.ALLOWED;
        };
    }

    /**
     * Requests a real merge of two branches that can't exist, so nothing changes: 202 means the key may merge. (A
     * check-only merge isn't enough: read keys may check.)
     */
    @NonNull
    public KeyCheck checkMerge() throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/baselines/branches/merge?SourceBranch="
                        + encode("applitools-jenkins-setup-check/nonexistent/source") + "&TargetBranch="
                        + encode("applitools-jenkins-setup-check/nonexistent/target") + "&OnlyCheck=false"))
                .POST(HttpRequest.BodyPublishers.noBody()));
        return switch (response.statusCode()) {
            case 401 -> KeyCheck.INVALID;
            case 202, 404 -> KeyCheck.ALLOWED;
            default -> KeyCheck.NOT_ALLOWED;
        };
    }

    /** Outcome of a baseline branch merge job. */
    public record MergeResult(boolean merged, int conflicts, int changes) {}

    /**
     * Starts merging one baseline branch into another (both in {@code owner/repo/branch} form) and returns the job ID.
     * {@code onlyCheck} reports conflicts without changing anything.
     */
    @NonNull
    public String startBranchMerge(@NonNull String sourceBranch, @NonNull String targetBranch, boolean onlyCheck)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/baselines/branches/merge?SourceBranch="
                        + encode(sourceBranch) + "&TargetBranch=" + encode(targetBranch) + "&OnlyCheck=" + onlyCheck))
                .POST(HttpRequest.BodyPublishers.noBody()));
        if (response.statusCode() == 404) {
            throw new IOException("Eyes has no baseline branch '" + sourceBranch + "' or '" + targetBranch + "'");
        }
        if (response.statusCode() != 202) {
            requireSuccess(response, "starting a merge of " + sourceBranch + " into " + targetBranch);
        }
        String location = response.headers().firstValue("Location").orElse("");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/tasks/([^/?]+)").matcher(location);
        if (!m.find()) {
            throw new IOException("Eyes accepted the merge but returned no task ID (Location: " + location + ")");
        }
        // Eyes returns the ID already URL-encoded (e.g. "...%7e1r"); decode it so it isn't encoded twice later.
        return java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    /** True once the merge job has finished (HTTP 201), false while it's running (HTTP 200). */
    public boolean isTaskDone(@NonNull String jobId) throws IOException, InterruptedException {
        HttpResponse<String> response =
                send(HttpRequest.newBuilder(uri("/api/tasks/" + encode(jobId) + "/status")).GET());
        if (response.statusCode() == 201) {
            return true;
        }
        if (response.statusCode() == 200) {
            return false;
        }
        requireSuccess(response, "checking merge job " + jobId);
        return false;
    }

    @NonNull
    public MergeResult taskResult(@NonNull String jobId) throws IOException, InterruptedException {
        HttpResponse<String> response =
                send(HttpRequest.newBuilder(uri("/api/tasks/" + encode(jobId) + "/result")).GET());
        if (response.statusCode() == 404) {
            throw new IOException("Eyes couldn't find one of the branches to merge");
        }
        requireSuccess(response, "reading merge job " + jobId);
        JSONObject result = json(response);
        net.sf.json.JSONArray changes = result.optJSONArray("changes");
        return new MergeResult(
                result.optBoolean("merged"), result.optInt("conflicts"), changes != null ? changes.size() : 0);
    }

    /**
     * Who accepted this session's changes: users whose accept came from this very session. Reviews are recorded in
     * {@code appOutputResolution[].lastAccept} once a session is resolved; {@code expectedAppOutput} is the baseline as
     * it was when the test ran, so it only names earlier sessions. Empty if nothing was accepted (or saved) yet.
     */
    @NonNull
    public java.util.Set<String> sessionApprovers(@NonNull String eyesBatchId, @NonNull String sessionId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri("/api/sessions/batches/" + encode(eyesBatchId) + "/" + encode(sessionId)))
                .GET());
        requireSuccess(response, "reading session " + sessionId);
        java.util.Set<String> users = new java.util.TreeSet<>();
        JSONObject session = json(response);
        for (String field : new String[] {"appOutputResolution", "expectedAppOutput"}) {
            net.sf.json.JSONArray outputs = session.optJSONArray(field);
            for (int i = 0; outputs != null && i < outputs.size(); i++) {
                JSONObject output = outputs.optJSONObject(i);
                JSONObject accept = output != null ? output.optJSONObject("lastAccept") : null;
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
