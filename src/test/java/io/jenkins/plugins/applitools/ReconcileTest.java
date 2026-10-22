package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.ExtensionList;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import io.jenkins.plugins.applitools.review.LabelEventSubscriberTestAccess;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.applitools.scm.GitHubRestClient;
import io.jenkins.plugins.applitools.scm.PullRequestStates;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.util.CapturingChecksPublisher;
import java.util.List;
import java.util.Set;
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

/** The watcher only follows what can still change, and never loses a merge to a missed webhook. */
@WithJenkins
public class ReconcileTest {

    static {
        System.setProperty(PendingReview.class.getName() + ".minPollSeconds", "0");
        System.setProperty(BaselineMerge.class.getName() + ".pollMillis", "20");
    }

    @TestExtension
    public static final CapturingChecksPublisher.Factory CHECKS = new CapturingChecksPublisher.Factory();

    /** What GitHub would say about PR acme/web#7. */
    @TestExtension
    public static final class FakePrStates extends PullRequestStates {
        static volatile GitHubRestClient.PullState state;

        @Override
        public GitHubRestClient.PullState state(Run<?, ?> run, String repo, String number) {
            return state;
        }
    }

    private JenkinsRule j;
    private FakeEyesServer eyes;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        CHECKS.getPublishedChecks().clear();
        FakePrStates.state = null;
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

    private WorkflowJob job() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition("applitools(batchId: 'b1') { echo 'tests' }", true));
        return job;
    }

    /** Builds with unreviewed changes, then registers the build as PR acme/web#7. */
    private WorkflowRun prBuild(WorkflowJob job) throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        ApplitoolsGlobalConfiguration.get().setFollowBranchBuilds(true);
        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        ApplitoolsGlobalConfiguration.get().setFollowBranchBuilds(false);
        PendingReview review = ReviewRegistry.get().all().get(0);
        ReviewRegistry.get().register(LabelEventSubscriberTestAccess.asPullRequest(review, "acme/web", "7"));
        return run;
    }

    private void tick() throws Exception {
        ExtensionList.lookupSingleton(ReviewWatcher.class).execute(TaskListener.NULL);
        Thread.sleep(500); // let any merge it scheduled finish
    }

    @Test
    void branchBuildsArentFollowedByDefault() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        j.assertBuildStatus(Result.SUCCESS, job().scheduleBuild2(0));

        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void existingBranchReviewsAreDroppedWhenFollowingIsOff() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        ApplitoolsGlobalConfiguration.get().setFollowBranchBuilds(true);
        j.assertBuildStatus(Result.SUCCESS, job().scheduleBuild2(0));
        assertThat(ReviewRegistry.get().all(), hasSize(1));

        ApplitoolsGlobalConfiguration.get().setFollowBranchBuilds(false);
        tick();

        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void deletedBuildIsDropped() throws Exception {
        WorkflowRun run = prBuild(job());

        run.delete();
        tick();

        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void supersededBuildIsDropped() throws Exception {
        WorkflowJob job = job();
        prBuild(job);
        eyes.results("b1", List.of(new FakeTest("s1", "passed")));
        j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0)); // newer build, PR-less so not registered

        tick();

        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void closedPrJobIsDropped() throws Exception {
        WorkflowJob job = job();
        prBuild(job);
        FakePrStates.state = new GitHubRestClient.PullState(false, false, "feature", "main", Set.of(), null);

        job.setDisabled(true); // what Jenkins does to a closed PR's job
        tick();

        assertThat(ReviewRegistry.get().all(), empty());
        assertThat(eyes.merges, empty());
    }

    @Test
    void missedMergeWebhookStillMergesBaselines() throws Exception {
        WorkflowJob job = job();
        prBuild(job);
        FakePrStates.state = new GitHubRestClient.PullState(false, true, "feature", "main", Set.of(), null);

        job.setDisabled(true);
        tick();

        assertThat(eyes.merges, contains("acme/web/feature -> acme/web/main (false)"));
        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void noWayToAskGitHubDropsIt() throws Exception {
        WorkflowJob job = job();
        prBuild(job);
        FakePrStates.state = null; // GitHub not reachable / no SCM info

        job.setDisabled(true);
        tick();

        assertThat(ReviewRegistry.get().all(), empty()); // no SCM to ask: nothing could merge it anyway
    }

    @Test
    void settledPrIsRecheckedSoAcceptingAfterRejectingTurnsGreen() throws Exception {
        prBuild(job());
        eyes.results("b1", List.of(new FakeTest("s1", "failed"))); // reviewer rejects
        tick();
        assertThat(latest().getConclusion(), is(ChecksConclusion.FAILURE));
        assertThat(ReviewRegistry.get().all().get(0).isSettled(), is(true));

        eyes.results("b1", List.of(new FakeTest("s1", "passed"))); // ...then changes their mind
        tick();

        assertThat(latest().getConclusion(), is(ChecksConclusion.SUCCESS));
    }

    private ChecksDetails latest() {
        List<ChecksDetails> visual = CHECKS.getPublishedChecks().stream()
                .filter(c -> c.getName().orElse("").equals(VisualTestsCheck.NAME))
                .toList();
        return visual.get(visual.size() - 1);
    }
}
