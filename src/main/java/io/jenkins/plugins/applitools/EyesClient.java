package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
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

    private static boolean apiKeyInQueryAllowed() {
        return jenkins.model.Jenkins.getInstanceOrNull() != null
                && ApplitoolsGlobalConfiguration.get().isApiKeyInQuery();
    }

    /** Identifies server + key for caches, without keeping the key itself. */
    String cacheKey() {
        return serverUrl + "|" + Integer.toHexString(apiKey.hashCode());
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
        if (response.statusCode() == 401 && apiKeyInQueryAllowed()) {
            // Older or on-prem servers may only accept the key as a query parameter on this endpoint. Opt-in: URL
            // parameters end up in proxy and server logs.
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

    /** What Eyes says about a key: its team, organization, owner and permissions. */
    public record KeyInfo(String teamId, String orgId, String user, java.util.List<String> permissions) {}

    /** {@code GET /api/v1/auth/api-key-validation}. */
    @NonNull
    public KeyInfo keyInfo() throws IOException, InterruptedException {
        HttpResponse<String> response =
                send(HttpRequest.newBuilder(uri("/api/v1/auth/api-key-validation")).GET());
        requireSuccess(response, "validating the API key");
        JSONObject json = json(response);
        java.util.List<String> permissions = new java.util.ArrayList<>();
        net.sf.json.JSONArray array = json.optJSONArray("permissions");
        for (int i = 0; array != null && i < array.size(); i++) {
            permissions.add(array.getString(i));
        }
        return new KeyInfo(
                json.optString("teamId", null), json.optString("orgId", null), json.optString("user", null), permissions);
    }

    /** An Eyes team ("account" in the admin API) and its members' login emails. */
    public record Team(String id, String name, java.util.Set<String> members) {}

    /** The organization's teams visible to this key ({@code GET /api/admin/orgs/{orgId}/accounts/}). */
    @NonNull
    public java.util.List<Team> teams(@NonNull String orgId) throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri("/api/admin/orgs/" + encode(orgId) + "/accounts/?format=json"))
                .GET());
        requireSuccess(response, "listing Eyes teams");
        java.util.List<Team> teams = new java.util.ArrayList<>();
        net.sf.json.JSONArray array;
        try {
            array = net.sf.json.JSONArray.fromObject(response.body());
        } catch (net.sf.json.JSONException e) {
            throw unexpected(response, "team list");
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject team = array.optJSONObject(i);
            if (team == null) {
                continue;
            }
            JSONObject members = team.optJSONObject("members");
            java.util.Set<String> emails = new java.util.TreeSet<>();
            if (members != null) {
                for (Object key : members.keySet()) {
                    emails.add(String.valueOf(key));
                }
            }
            teams.add(new Team(team.optString("id", null), team.optString("name", ""), emails));
        }
        return teams;
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
            throw unexpected(response, "response");
        }
    }

    /**
     * Describes a response that isn't the JSON expected, without any of its content: the server may not be Eyes, and
     * error messages end up in build logs and on pull requests.
     */
    private static IOException unexpected(HttpResponse<String> response, String what) {
        return new IOException("Eyes returned an unexpected " + what + " (HTTP " + response.statusCode() + ", "
                + response.headers().firstValue("Content-Type").orElse("no content type") + ")");
    }

    private URI uri(String pathAndQuery) throws IOException {
        return URI.create(ServerUrls.check(serverUrl, "The Eyes server URL") + pathAndQuery);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return sendWithoutHeader(request.header(API_KEY_HEADER, apiKey));
    }

    private static HttpResponse<String> sendWithoutHeader(HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return client().send(request.timeout(TIMEOUT).header("Accept-Encoding", "gzip").build(), GZIP_AWARE);
    }

    /**
     * Eyes compresses JSON about 6x when asked (a 10-test batch: 7.6 KB → 1.2 KB), which matters for the review
     * watcher's polling. Java's HTTP client doesn't decompress on its own, so do it here.
     */
    public static final HttpResponse.BodyHandler<String> GZIP_AWARE = info -> {
        boolean gzip = info.headers()
                .firstValue("Content-Encoding")
                .map(v -> v.equalsIgnoreCase("gzip"))
                .orElse(false);
        return HttpResponse.BodySubscribers.mapping(new BoundedBody(maxResponseBytes()), bytes -> {
            if (!gzip) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            try (java.util.zip.GZIPInputStream in =
                    new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                return new String(readAtMost(in, maxResponseBytes()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    };

    /**
     * The largest response the plugin reads, before and after decompression. Eyes results for a 1,000-test batch are
     * under 1 MB; the limit keeps a misbehaving or hostile server from exhausting the controller's memory.
     */
    static int maxResponseBytes() {
        return Integer.getInteger(EyesClient.class.getName() + ".maxResponseMegabytes", 16) * 1024 * 1024;
    }

    static byte[] readAtMost(java.io.InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > limit) {
                throw new IOException("Response larger than " + limit / (1024 * 1024) + " MB; not read");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Collects a response body, failing (and cancelling the download) once it exceeds {@code limit} bytes. */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result =
                new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;

        BoundedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.util.List<java.nio.ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (java.nio.ByteBuffer item : items) {
                if (out.size() + item.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IOException("Response larger than " + limit / (1024 * 1024) + " MB; not read"));
                    return;
                }
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.writeBytes(bytes);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }

    private static HttpClient sharedClient;
    private static ProxyConfiguration sharedClientProxy;

    /**
     * One HTTP client for every Eyes call. Each client owns a selector thread and a connection pool, so creating one
     * per request (as {@link ProxyConfiguration#newHttpClient()} does) churns threads and sockets under the review
     * watcher's load. Rebuilt only when Jenkins' proxy settings change; the old one is left to finish in-flight
     * requests and be collected.
     */
    /** The shared client, for other REST calls the plugin makes (e.g. to GitHub). */
    public static HttpClient sharedHttpClient() {
        return client();
    }

    static synchronized HttpClient client() {
        jenkins.model.Jenkins instance = jenkins.model.Jenkins.getInstanceOrNull();
        ProxyConfiguration proxy = instance != null ? instance.getProxy() : null;
        if (sharedClient == null || proxy != sharedClientProxy) {
            sharedClient = ProxyConfiguration.newHttpClient();
            sharedClientProxy = proxy;
        }
        return sharedClient;
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
