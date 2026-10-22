package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.scm.GitHubRestClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A server that isn't Eyes or GitHub, named by a Jenkinsfile, can't use the controller to read internal services (its
 * responses never reach logs or PRs) or exhaust its memory.
 */
@WithJenkins
class HostileServerTest {

    private static final String INTERNAL = "internal-secret-value-42";

    private HttpServer server;
    private volatile byte[] body = new byte[0];
    private volatile boolean gzip;
    private volatile String contentType = "text/html";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            if (gzip) {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        System.clearProperty(EyesClient.class.getName() + ".maxResponseMegabytes");
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void responsesThatArentJsonAreNotEchoed(JenkinsRule j) {
        body = ("<html>" + INTERNAL + "</html>").getBytes(StandardCharsets.UTF_8);

        IOException eyes = assertThrows(IOException.class, () -> new EyesClient(url(), "key").getResults("b1"));
        assertThat(eyes.getMessage(), not(containsString(INTERNAL)));
        assertThat(eyes.getMessage(), containsString("HTTP 200, text/html"));

        IOException github =
                assertThrows(IOException.class, () -> new GitHubRestClient(url(), "token").pull("acme/web", "7"));
        assertThat(github.getMessage(), not(containsString(INTERNAL)));
    }

    @Test
    void decompressionIsCapped(JenkinsRule j) throws IOException {
        System.setProperty(EyesClient.class.getName() + ".maxResponseMegabytes", "1");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(compressed)) {
            out.write(new byte[8 * 1024 * 1024]); // 8 MB of zeros, ~8 KB compressed
        }
        body = compressed.toByteArray();
        gzip = true;
        contentType = "application/json";

        IOException e = assertThrows(IOException.class, () -> new EyesClient(url(), "key").getResults("b1"));
        assertThat(rootMessage(e), containsString("larger than 1 MB"));
    }

    @Test
    void rawResponsesAreCapped(JenkinsRule j) {
        System.setProperty(EyesClient.class.getName() + ".maxResponseMegabytes", "1");
        body = new byte[3 * 1024 * 1024];
        contentType = "application/json";

        IOException e = assertThrows(IOException.class, () -> new EyesClient(url(), "key").getResults("b1"));
        assertThat(rootMessage(e), containsString("larger than 1 MB"));
    }

    @Test
    void reservedAddressesAreRefused(JenkinsRule j) {
        IOException e = assertThrows(
                IOException.class, () -> new EyesClient("http://169.254.169.254", "key").getResults("b1"));
        assertThat(e, instanceOf(ServerUrls.RefusedUrlException.class));

        assertThrows(ServerUrls.RefusedUrlException.class,
                () -> new GitHubRestClient("http://169.254.169.254", "token").pull("acme/web", "7"));
    }

    @Test
    void repositoryNamesAreValidated(JenkinsRule j) {
        IOException e = assertThrows(
                IOException.class, () -> new GitHubRestClient(url(), "token").pull("acme/web/../../admin", "7"));
        assertThat(e.getMessage(), containsString("Not a GitHub repository name"));
    }

    @Test
    void stepRefusesAMetadataServerUrl(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL,
                        ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID, "", Secret.fromString("run-key-0123")));
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "applitools(serverUrl: 'http://169.254.169.254/latest/meta-data?x=') { echo 'tests ran' }", true));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        j.assertLogContains("points at a reserved address (169.254.169.254)", run);
        j.assertLogNotContains("tests ran", run);
    }

    @Test
    void stepRefusesANonWebGitHubApiUrl(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL,
                        ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID, "", Secret.fromString("run-key-0123")));
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "applitools(serverUrl: '" + url() + "', githubApiUrl: 'file:///etc/passwd') { echo 'tests ran' }",
                true));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        j.assertLogContains("The GitHub API URL must start with https://", run);
    }

    private static String rootMessage(Throwable e) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append(" | ");
        }
        return messages.toString();
    }

    @Test
    void serverUrlChecks() {
        assertThat(accepted("https://eyes.applitools.com"), is(true));
        assertThat(accepted("http://127.0.0.1:8080"), is(true)); // on-prem and local testing
        assertThat(accepted("https://203.0.113.10/eyes"), is(true));
        assertThat(accepted("javascript:alert(1)"), is(false));
        assertThat(accepted("file:///etc/passwd"), is(false));
        assertThat(accepted("https://user:pass@eyes.example.com"), is(false));
        assertThat(accepted("http://169.254.169.254/latest"), is(false));
        assertThat(accepted("http://[fe80::1]/"), is(false));
        assertThat(accepted("http://[fd00:ec2::254]/"), is(false));
        assertThat(accepted("http://metadata.google.internal/computeMetadata/v1/"), is(false));
        assertThat(accepted("http://0.0.0.0:8080"), is(false));
        assertThat(accepted("not a url"), is(false));
    }

    private static boolean accepted(String url) {
        try {
            ServerUrls.check(url, "URL");
            return true;
        } catch (ServerUrls.RefusedUrlException e) {
            return false;
        }
    }
}
