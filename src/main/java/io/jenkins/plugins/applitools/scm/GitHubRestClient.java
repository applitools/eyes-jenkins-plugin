package io.jenkins.plugins.applitools.scm;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.EyesClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * Just enough of GitHub's REST API, with a token, for jobs that aren't multibranch: post commit statuses, read a PR's
 * labels and state. Needs no GitHub plugin. Works with GitHub Enterprise through {@code apiUrl}.
 */
public class GitHubRestClient {

    public static final String DEFAULT_API_URL = "https://api.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Last outcome of any call, for the setup page. */
    private static volatile long lastCallAt;
    private static volatile String lastError;

    private final String apiUrl;
    private final String token;

    public GitHubRestClient(@CheckForNull String apiUrl, @NonNull String token) {
        this.apiUrl = (apiUrl != null ? apiUrl : DEFAULT_API_URL).replaceAll("/+$", "");
        this.token = token;
    }

    /** The token in a Secret text credential (or the password of a username/password one). */
    @CheckForNull
    public static String token(@NonNull Run<?, ?> run, @NonNull String credentialsId) {
        StandardCredentials credentials =
                CredentialsProvider.findCredentialById(credentialsId, StandardCredentials.class, run);
        if (credentials instanceof StringCredentials string) {
            return Secret.toString(string.getSecret());
        }
        if (credentials instanceof StandardUsernamePasswordCredentials userPass) {
            return Secret.toString(userPass.getPassword());
        }
        return null;
    }

    /** {@code state} is pending, success, failure or error; the description is cut to GitHub's 140 characters. */
    public void createStatus(
            @NonNull String repo,
            @NonNull String sha,
            @NonNull String state,
            @CheckForNull String targetUrl,
            @NonNull String description,
            @NonNull String context)
            throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("state", state);
        body.put("context", context);
        body.put("description", description.length() > 140 ? description.substring(0, 139) + "…" : description);
        if (targetUrl != null) {
            body.put("target_url", targetUrl);
        }
        send(request("/repos/" + repo + "/statuses/" + encode(sha))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Content-Type", "application/json"), "posting status '" + context + "'");
    }

    /** A pull request's state, as far as the review gate cares; {@code etag} identifies this version. */
    public record PullState(
            boolean open, boolean merged, String headRef, String baseRef, Set<String> labels, String etag) {}

    @NonNull
    public PullState pull(@NonNull String repo, @NonNull String number) throws IOException, InterruptedException {
        PullState state = pullIfChanged(repo, number, null);
        if (state == null) {
            throw new IOException("GitHub returned no PR #" + number);
        }
        return state;
    }

    /**
     * Reads the PR only if it changed since {@code etag}: GitHub answers 304 with no body otherwise, and 304s don't
     * count against the API rate limit. Returns {@code null} when unchanged.
     */
    @CheckForNull
    public PullState pullIfChanged(@NonNull String repo, @NonNull String number, @CheckForNull String etag)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = request("/repos/" + repo + "/pulls/" + encode(number)).GET();
        if (etag != null) {
            request.header("If-None-Match", etag);
        }
        HttpResponse<String> response = send(request, "reading PR #" + number);
        if (response.statusCode() == 304) {
            return null;
        }
        JSONObject pr = json(response);
        Set<String> labels = new TreeSet<>();
        JSONArray array = pr.optJSONArray("labels");
        for (int i = 0; array != null && i < array.size(); i++) {
            labels.add(array.getJSONObject(i).optString("name"));
        }
        JSONObject head = pr.optJSONObject("head");
        JSONObject base = pr.optJSONObject("base");
        return new PullState(
                "open".equals(pr.optString("state")),
                pr.optBoolean("merged"),
                head != null ? head.optString("ref", null) : null,
                base != null ? base.optString("ref", null) : null,
                labels,
                response.headers().firstValue("ETag").orElse(null));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Accept-Encoding", "gzip"); // a PR is ~18 KB raw
    }

    private static HttpResponse<String> send(HttpRequest.Builder request, String what)
            throws IOException, InterruptedException {
        lastCallAt = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = EyesClient.sharedHttpClient().send(request.build(), EyesClient.GZIP_AWARE);
        } catch (IOException e) {
            lastError = what + ": " + e.getMessage();
            throw e;
        }
        if (response.statusCode() / 100 != 2 && response.statusCode() != 304) {
            lastError = what + ": GitHub returned HTTP " + response.statusCode()
                    + (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404
                            ? " (check the token and its access to the repository)"
                            : "");
            throw new IOException("GitHub returned HTTP " + response.statusCode() + " " + what);
        }
        lastError = null;
        return response;
    }

    private static JSONObject json(HttpResponse<String> response) throws IOException {
        try {
            return JSONObject.fromObject(response.body());
        } catch (JSONException e) {
            throw new IOException("GitHub returned an unexpected response: " + e.getMessage(), e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static long getLastCallAt() {
        return lastCallAt;
    }

    @CheckForNull
    public static String getLastError() {
        return lastError;
    }
}
