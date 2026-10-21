package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksStatus;
import io.jenkins.plugins.checks.util.CapturingChecksPublisher;
import java.util.List;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class VisualTestsCheckTest {

    @TestExtension
    public static final CapturingChecksPublisher.Factory PUBLISHER = new CapturingChecksPublisher.Factory();

    private JenkinsRule j;
    private FakeEyesServer eyes;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        PUBLISHER.getPublishedChecks().clear();
        eyes = new FakeEyesServer();
        ApplitoolsGlobalConfiguration.get().setServerUrl(eyes.url());
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID,
                        "",
                        Secret.fromString("fake-key-0123456789")));
        SystemCredentialsProvider.getInstance().save();
    }

    @AfterEach
    void tearDown() {
        eyes.close();
    }

    private void run(String script, Result expected) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, true));
        j.assertBuildStatus(expected, job.scheduleBuild2(0));
    }

    private List<ChecksDetails> ours() {
        return PUBLISHER.getPublishedChecks().stream()
                .filter(c -> c.getName().orElse("").equals(VisualTestsCheck.NAME))
                .toList();
    }

    private ChecksDetails completed() {
        List<ChecksDetails> checks = ours();
        assertThat(checks, hasSize(2));
        assertThat(checks.get(0).getStatus(), is(ChecksStatus.IN_PROGRESS));
        assertThat(checks.get(1).getStatus(), is(ChecksStatus.COMPLETED));
        return checks.get(1);
    }

    private static ChecksOutput output(ChecksDetails details) {
        return details.getOutput().orElseThrow();
    }

    @Test
    void passedIsSuccessLinkingToEyes() throws Exception {
        eyes.results("b1", 3, 0, 0, 1, 0);
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        ChecksDetails check = completed();
        assertThat(check.getConclusion(), is(ChecksConclusion.SUCCESS));
        assertThat(check.getDetailsURL().orElseThrow(), is("https://eyes.example/app/test-results/123"));
        assertThat(output(check).getTitle().orElseThrow(), is("3 passed (1 new baselines)"));
        String summary = output(check).getSummary().orElseThrow();
        assertThat(summary, containsString("**[Review in Eyes](https://eyes.example/app/test-results/123)**"));
        assertThat(summary, containsString(
                "| [home page](https://eyes.example/app/test-results/123/456) | Chrome 155.0 on Linux 1280×800 | ✅ passed |"));
    }

    @Test
    void changesRequireAction() throws Exception {
        eyes.results("b1", 1, 0, 2, 0, 0);
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        ChecksDetails check = completed();
        assertThat(check.getConclusion(), is(ChecksConclusion.ACTION_REQUIRED));
        assertThat(output(check).getTitle().orElseThrow(), is("2 visual changes to review"));
    }

    @Test
    void abortedTestsFail() throws Exception {
        eyes.results("b1", 0, 4, 0, 4, 4);
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.FAILURE);

        ChecksDetails check = completed();
        assertThat(check.getConclusion(), is(ChecksConclusion.FAILURE));
        assertThat(output(check).getTitle().orElseThrow(), containsString("4 aborted"));
    }

    @Test
    void noTestsRanFailsTheGate() throws Exception {
        eyes.unknownBatches.add("b1");
        run("applitools(batchId: 'b1') { echo 'forgot to run tests' }", Result.SUCCESS);

        ChecksDetails check = completed();
        assertThat(check.getConclusion(), is(ChecksConclusion.FAILURE));
        assertThat(output(check).getTitle().orElseThrow(), is("No Eyes results"));
    }

    @Test
    void unreadableResultsFailWithReadKeyHint() throws Exception {
        eyes.requiredReadKey = "a-read-key-the-job-does-not-have";
        eyes.results("b1", 3, 0, 0, 0, 0);
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        ChecksDetails check = completed();
        assertThat(check.getConclusion(), is(ChecksConclusion.FAILURE));
        assertThat(output(check).getTitle().orElseThrow(), is("Couldn't read Eyes results"));
        assertThat(output(check).getSummary().orElseThrow(), containsString("applitools-read-api-key"));
    }

    @Test
    void notWaitingForResultsIsNeutral() throws Exception {
        run("applitools(batchId: 'b1', waitForResults: false) { echo 'tests' }", Result.SUCCESS);

        assertThat(completed().getConclusion(), is(ChecksConclusion.NEUTRAL));
    }

    @Test
    void checksCanBeTurnedOff() throws Exception {
        eyes.results("b1", 3, 0, 0, 0, 0);
        run("applitools(batchId: 'b1', checks: false) { echo 'tests' }", Result.SUCCESS);

        assertThat(ours(), empty());
    }

    @Test
    void nestedBlocksPostOneCheck() throws Exception {
        eyes.results("b1", 2, 0, 0, 0, 0);
        run("applitools(batchId: 'b1') { applitools { echo 'inner' } }", Result.SUCCESS);

        assertThat(completed().getConclusion(), is(ChecksConclusion.SUCCESS));
    }

    @Test
    void markdownInNamesIsEscaped() {
        BatchResults results = new BatchResults(
                "b1", "a | b", "https://eyes.example/x", 1, 0, 0, 0, 0, 1,
                List.of(new BatchResults.Test("x | y", "Chrome", "passed", null)));

        String summary = VisualTestsCheck.summary(results);

        assertThat(summary, containsString("· a \\| b"));
        assertThat(summary, containsString("| x \\| y | Chrome | ✅ passed |"));
    }
}
