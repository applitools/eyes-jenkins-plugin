package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Result;
import hudson.model.Run;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import io.jenkins.plugins.applitools.scm.CommitStatusPublisher;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksStatus;
import io.jenkins.plugins.checks.util.CapturingChecksPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Jobs that can't post checks (token-based GitHub jobs) get commit statuses with the same names and links. */
@WithJenkins
public class CommitStatusFallbackTest {

    record Posted(String context, CommitStatusPublisher.State state, String description, String url) {
        @Override
        public String toString() {
            return context + " = " + state + " | " + description;
        }
    }

    /** Behaves like a token-based GitHub job: wants statuses unless checks are forced. */
    @TestExtension
    public static final class TokenJobStatuses extends CommitStatusPublisher {
        static final List<Posted> POSTED = new CopyOnWriteArrayList<>();

        @Override
        public boolean handles(Run<?, ?> run, Mode mode) {
            return mode != Mode.CHECKS;
        }

        @Override
        public void publish(Run<?, ?> run, String context, State state, String description, String targetUrl) {
            POSTED.add(new Posted(context, state, description, targetUrl));
        }
    }

    @TestExtension
    public static final CapturingChecksPublisher.Factory CHECKS = new CapturingChecksPublisher.Factory();

    private JenkinsRule j;
    private FakeEyesServer eyes;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        TokenJobStatuses.POSTED.clear();
        CHECKS.getPublishedChecks().clear();
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

    private List<String> posted() {
        return TokenJobStatuses.POSTED.stream().map(Posted::toString).toList();
    }

    @Test
    void passingBuildPostsStatusesInsteadOfChecks() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "passed")));
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        assertThat(posted(), contains(
                "Applitools / Visual tests = PENDING | Running visual tests",
                "Applitools / Visual tests = SUCCESS | 1 passed",
                "Applitools / Design review = SUCCESS | Not requested"));
        assertThat(TokenJobStatuses.POSTED.get(1).url(), is("https://eyes.example/app/test-results/eb-b1"));
        assertThat(CHECKS.getPublishedChecks(), empty());
    }

    @Test
    void changesAndWaitingForADesignerArePending() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        run("applitools(batchId: 'b1', designReview: true) { echo 'tests' }", Result.UNSTABLE);

        assertThat(posted(), contains(
                "Applitools / Visual tests = PENDING | Running visual tests",
                "Applitools / Visual tests = PENDING | 1 visual change to review",
                "Applitools / Design review = PENDING | Waiting for design review"));
    }

    @Test
    void failuresFail() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "failed")));
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.FAILURE);

        assertThat(posted().get(1), is("Applitools / Visual tests = FAILURE | 1 of 1 failed"));
    }

    @Test
    void checksModeForcesChecks() throws Exception {
        ApplitoolsGlobalConfiguration.get().setGithubReporting(CommitStatusPublisher.Mode.CHECKS);
        eyes.results("b1", List.of(new FakeTest("s1", "passed")));
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        assertThat(TokenJobStatuses.POSTED, empty());
        assertThat(CHECKS.getPublishedChecks(), not(empty()));
    }

    @Test
    void mapping() {
        assertThat(state(ChecksStatus.IN_PROGRESS, null), is(CommitStatusPublisher.State.PENDING));
        assertThat(state(ChecksStatus.COMPLETED, ChecksConclusion.SUCCESS), is(CommitStatusPublisher.State.SUCCESS));
        assertThat(state(ChecksStatus.COMPLETED, ChecksConclusion.SKIPPED), is(CommitStatusPublisher.State.SUCCESS));
        assertThat(state(ChecksStatus.COMPLETED, ChecksConclusion.NEUTRAL), is(CommitStatusPublisher.State.SUCCESS));
        assertThat(state(ChecksStatus.COMPLETED, ChecksConclusion.ACTION_REQUIRED),
                is(CommitStatusPublisher.State.PENDING));
        assertThat(state(ChecksStatus.COMPLETED, ChecksConclusion.FAILURE), is(CommitStatusPublisher.State.FAILURE));
    }

    @Test
    void longTitlesAreTrimmedToGitHubsLimit() {
        String description = VisualTestsCheck.toStatus(details(ChecksStatus.COMPLETED, ChecksConclusion.FAILURE,
                        "x".repeat(200)))
                .description();

        assertThat(description.length(), is(140));
        assertThat(description.endsWith("…"), is(true));
    }

    private static CommitStatusPublisher.State state(ChecksStatus status, ChecksConclusion conclusion) {
        return VisualTestsCheck.toStatus(details(status, conclusion, "t")).state();
    }

    private static ChecksDetails details(ChecksStatus status, ChecksConclusion conclusion, String title) {
        ChecksDetails.ChecksDetailsBuilder b = new ChecksDetails.ChecksDetailsBuilder()
                .withName("n")
                .withStatus(status)
                .withOutput(new ChecksOutput.ChecksOutputBuilder().withTitle(title).withSummary("s").build());
        if (conclusion != null) {
            b.withConclusion(conclusion);
        }
        return b.build();
    }
}
