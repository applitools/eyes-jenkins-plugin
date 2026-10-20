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

/** Minimal Eyes REST client. Uses Jenkins' proxy settings. */
public class EyesClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
        // TODO(spike): confirm Eyes accepts the key as a header so it stays out of URLs and proxy logs.
        URI uri = URI.create(serverUrl + "/api/sessions/batches/" + encode(batchId) + "/close/bypointerid?apiKey="
                + encode(apiKey));
        HttpRequest request =
                HttpRequest.newBuilder(uri).timeout(TIMEOUT).DELETE().build();
        HttpResponse<String> response =
                ProxyConfiguration.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 404) {
            return CloseResult.NOT_FOUND;
        }
        if (status / 100 != 2) {
            throw new IOException("Eyes returned HTTP " + status + " closing batch " + batchId);
        }
        return CloseResult.CLOSED;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
