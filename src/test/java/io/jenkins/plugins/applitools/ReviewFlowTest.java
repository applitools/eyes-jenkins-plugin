package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.ExtensionList;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import io.jenkins.plugins.applitools.review.LabelEventSubscriberTestAccess;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksStatus;
import io.jenkins.plugins.checks.util.CapturingChecksPublisher;
import java.util.List;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** End to end: build → reviewer acts in Eyes → watcher updates both PR checks. */
@WithJenkins
public class ReviewFlowTest {

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

    private WorkflowRun run(String script, Result expected) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return j.assertBuildStatus(expected, job.scheduleBuild2(0));
    }

    private void watcherTick() {
        ExtensionList.lookupSingleton(ReviewWatcher.class).execute(TaskListener.NULL);
    }

    private ChecksDetails latest(String name) {
        List<ChecksDetails> checks = PUBLISHER.getPublishedChecks().stream()
                .filter(c -> c.getName().orElse("").equals(name))
                .toList();
        return checks.get(checks.size() - 1);
    }

    private long count(String name) {
        return PUBLISHER.getPublishedChecks().stream()
                .filter(c -> c.getName().orElse("").equals(name))
                .count();
    }

    private PendingReview review() {
        List<PendingReview> all = ReviewRegistry.get().all();
        assertThat(all, hasSize(1));
        return all.get(0);
    }

    @Test
    void notRequestedPostsSkippedDesignCheck() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "passed")));
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        ChecksDetails design = latest(DesignReview.CHECK_NAME);
        assertThat(design.getConclusion(), is(ChecksConclusion.SKIPPED));
        assertThat(ReviewRegistry.get().all(), empty()); // a settled branch build isn't followed
    }

    @Test
    void acceptingInEyesTurnsBothChecksGreen() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved"), new FakeTest("s2", "passed")));
        WorkflowRun build = run("applitools(batchId: 'b1', designReview: true) { echo 'tests' }", Result.UNSTABLE);

        assertThat(latest(VisualTestsCheck.NAME).getConclusion(), is(ChecksConclusion.ACTION_REQUIRED));
        assertThat(latest(DesignReview.CHECK_NAME).getConclusion(), is(ChecksConclusion.ACTION_REQUIRED));
        assertThat(review().isSettled(), is(false));

        watcherTick(); // nothing changed in Eyes yet: no re-posting
        assertThat(count(VisualTestsCheck.NAME), is(2L)); // in progress + action required

        eyes.results("b1", List.of(new FakeTest("s1", "passed"), new FakeTest("s2", "passed")));
        watcherTick();

        assertThat(latest(VisualTestsCheck.NAME).getConclusion(), is(ChecksConclusion.SUCCESS));
        assertThat(latest(DesignReview.CHECK_NAME).getConclusion(), is(ChecksConclusion.SUCCESS));
        assertThat(ReviewRegistry.get().all(), empty()); // done: no longer followed
        assertThat(build.getAction(ApplitoolsResultsAction.class).getLatest().getOutcome(),
                is(BatchResults.Outcome.PASSED)); // build page updated too
    }

    @Test
    void onlyDesignersCanApprove() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        run("applitools(batchId: 'b1', designReview: true, designers: 'dana@example.com') { echo 'tests' }",
                Result.UNSTABLE);

        // A developer accepts their own change.
        eyes.results("b1", List.of(new FakeTest("s1", "passed")));
        eyes.approvals.put("s1", List.of("dev@example.com"));
        watcherTick();

        assertThat(latest(VisualTestsCheck.NAME).getConclusion(), is(ChecksConclusion.SUCCESS));
        ChecksDetails design = latest(DesignReview.CHECK_NAME);
        assertThat(design.getConclusion(), is(ChecksConclusion.ACTION_REQUIRED));
        assertThat(design.getOutput().orElseThrow().getTitle().orElseThrow(), is("Waiting for a designer"));
        assertThat(review().isSettled(), is(false));

        eyes.approvals.put("s1", List.of("dana@example.com"));
        watcherTick();

        design = latest(DesignReview.CHECK_NAME);
        assertThat(design.getConclusion(), is(ChecksConclusion.SUCCESS));
        assertThat(design.getOutput().orElseThrow().getTitle().orElseThrow(), is("Approved by dana@example.com"));
    }

    @Test
    void rejectingRequestsChanges() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        run("applitools(batchId: 'b1', designReview: true) { echo 'tests' }", Result.UNSTABLE);

        eyes.results("b1", List.of(new FakeTest("s1", "failed")));
        watcherTick();

        assertThat(latest(VisualTestsCheck.NAME).getConclusion(), is(ChecksConclusion.FAILURE));
        assertThat(latest(DesignReview.CHECK_NAME).getConclusion(), is(ChecksConclusion.FAILURE));
        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void environmentVariableRequiresDesignReview() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        run("withEnv(['APPLITOOLS_DESIGN_REVIEW=true']) { applitools(batchId: 'b1') { echo 'tests' } }",
                Result.UNSTABLE);

        assertThat(latest(DesignReview.CHECK_NAME).getConclusion(), is(ChecksConclusion.ACTION_REQUIRED));
    }

    @Test
    void addingTheLabelLaterRequiresReviewWithoutRebuilding() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        WorkflowRun build = run("applitools(batchId: 'b1') { echo 'tests' }", Result.UNSTABLE);
        assertThat(latest(DesignReview.CHECK_NAME).getConclusion(), is(ChecksConclusion.SKIPPED));

        // Pretend this build is PR #7 of acme/web, then deliver a "labeled" webhook.
        PendingReview old = review();
        ReviewRegistry.get().register(LabelEventSubscriberTestAccess.asPullRequest(old, "acme/web", "7"));
        LabelEventSubscriberTestAccess.handleAndWait("acme/web", "7", "design-review", true);

        assertThat(latest(DesignReview.CHECK_NAME).getConclusion(), is(ChecksConclusion.ACTION_REQUIRED));
        assertThat(review().isLabelPresent(), is(true));
        assertThat(build.getNumber(), is(1)); // no new build

        LabelEventSubscriberTestAccess.handleAndWait("acme/web", "7", "design-review", false);
        assertThat(latest(DesignReview.CHECK_NAME).getConclusion(), is(ChecksConclusion.SKIPPED));
    }

    @Test
    void designReviewFalseIgnoresLabels() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        run("applitools(batchId: 'b1', designReview: false) { echo 'tests' }", Result.UNSTABLE);

        PendingReview old = review();
        ReviewRegistry.get().register(LabelEventSubscriberTestAccess.asPullRequest(old, "acme/web", "7"));
        long before = count(DesignReview.CHECK_NAME);
        LabelEventSubscriberTestAccess.handleAndWait("acme/web", "7", "design-review", true);

        assertThat(count(DesignReview.CHECK_NAME), is(before));
    }

    @Test
    void designCheckCanBeTurnedOffGlobally() throws Exception {
        ApplitoolsGlobalConfiguration.get().setDesignReviewCheck(false);
        eyes.results("b1", List.of(new FakeTest("s1", "passed")));
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        assertThat(count(DesignReview.CHECK_NAME), is(0L));
    }

    @Test
    void settledPullRequestIsKeptForLabelChanges() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        run("applitools(batchId: 'b1') { echo 'tests' }", Result.UNSTABLE);
        ReviewRegistry.get().register(LabelEventSubscriberTestAccess.asPullRequest(review(), "acme/web", "7"));

        eyes.results("b1", List.of(new FakeTest("s1", "passed")));
        watcherTick();
        watcherTick();

        assertThat(review().isSettled(), is(true)); // still tracked: the PR is open
    }

    @Test
    void newerBuildReplacesOlderReview() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition("applitools(batchId: 'b1') { echo 'tests' }", true));
        j.assertBuildStatus(Result.UNSTABLE, job.scheduleBuild2(0));
        j.assertBuildStatus(Result.UNSTABLE, job.scheduleBuild2(0));

        assertThat(review().getBuildNumber(), is(2));
    }
}
