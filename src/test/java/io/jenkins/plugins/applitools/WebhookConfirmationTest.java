package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Result;
import hudson.model.Run;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import io.jenkins.plugins.applitools.review.LabelEventSubscriberTestAccess;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.applitools.scm.GitHubRestClient;
import io.jenkins.plugins.applitools.scm.PullRequestStates;
import io.jenkins.plugins.checks.util.CapturingChecksPublisher;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A pull_request webhook only says which PR changed. What changed is read from GitHub, so a forged or replayed webhook
 * (e.g. on a Jenkins without a webhook secret) can't merge baselines or change the design-review label.
 */
@WithJenkins
public class WebhookConfirmationTest {

    static {
        System.setProperty(BaselineMerge.class.getName() + ".pollMillis", "20");
    }

    @TestExtension
    public static final CapturingChecksPublisher.Factory PUBLISHER = new CapturingChecksPublisher.Factory();

    /** What GitHub says about acme/web#7; {@code null}: GitHub can't be asked. */
    @TestExtension
    public static final class FakePrStates extends PullRequestStates {
        static volatile GitHubRestClient.PullState state;
        static volatile int reads;

        @Override
        public GitHubRestClient.PullState state(Run<?, ?> run, String repo, String number) {
            reads++;
            return "acme/web".equals(repo) && "7".equals(number) ? state : null;
        }
    }

    private FakeEyesServer eyes;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        PUBLISHER.getPublishedChecks().clear();
        FakePrStates.state = null;
        FakePrStates.reads = 0;
        eyes = new FakeEyesServer();
        ApplitoolsGlobalConfiguration.get().setServerUrl(eyes.url());
        ApplitoolsGlobalConfiguration.get().setFollowBranchBuilds(true);
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL,
                        ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID, "", Secret.fromString("run-key-0123")));
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition("applitools(batchId: 'b1') { echo 'tests' }", true));
        j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        // Plain test pipelines aren't PRs; pretend this build is PR #7 of acme/web.
        PendingReview review = ReviewRegistry.get().all().get(0);
        ReviewRegistry.get().register(LabelEventSubscriberTestAccess.asPullRequest(review, "acme/web", "7"));
    }

    @AfterEach
    void tearDown() {
        eyes.close();
    }

    private static String closed(String head) {
        return """
                {"action": "closed", "number": 7,
                 "pull_request": {"merged": true, "head": {"ref": "%s"}, "base": {"ref": "main"}},
                 "repository": {"full_name": "acme/web"}}
                """.formatted(head);
    }

    private static String label(String action) {
        return """
                {"action": "%s", "number": 7, "label": {"name": "design-review"},
                 "repository": {"full_name": "acme/web"}}
                """.formatted(action);
    }

    private static PendingReview review() {
        return ReviewRegistry.get().all().get(0);
    }

    @Test
    void mergeUsesGitHubsBranchesNotThePayloads() throws Exception {
        FakePrStates.state = new GitHubRestClient.PullState(false, true, "feature", "main", Set.of(), null);

        LabelEventSubscriberTestAccess.webhookAndWait(closed("someone-elses-branch"));

        assertThat(eyes.merges, contains("acme/web/feature -> acme/web/main (false)"));
        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void forgedMergeOfAnOpenPrIsIgnored() throws Exception {
        FakePrStates.state = new GitHubRestClient.PullState(true, false, "feature", "main", Set.of(), null);

        LabelEventSubscriberTestAccess.webhookAndWait(closed("feature"));

        assertThat(eyes.merges, empty());
        assertThat(ReviewRegistry.get().all(), hasSize(1));
        assertThat(review().isClosed(), is(false));
    }

    @Test
    void webhookIsIgnoredWhenGitHubCantBeAsked() throws Exception {
        LabelEventSubscriberTestAccess.webhookAndWait(closed("feature"));

        assertThat(FakePrStates.reads, is(1));
        assertThat(eyes.merges, empty());
        assertThat(ReviewRegistry.get().all(), hasSize(1));
    }

    @Test
    void replayedMergeRunsOnce() throws Exception {
        FakePrStates.state = new GitHubRestClient.PullState(false, true, "feature", "main", Set.of(), null);

        LabelEventSubscriberTestAccess.webhookAndWait(closed("feature"));
        LabelEventSubscriberTestAccess.webhookAndWait(closed("feature"));

        assertThat(eyes.merges, hasSize(1));
    }

    @Test
    void forgedLabelChangesAreIgnored() throws Exception {
        FakePrStates.state = new GitHubRestClient.PullState(true, false, "feature", "main", Set.of(), null);
        assertThat(review().isLabelDriven(), is(true));

        LabelEventSubscriberTestAccess.webhookAndWait(label("labeled"));
        assertThat(review().isLabelPresent(), is(false));

        // GitHub agrees: now it applies.
        FakePrStates.state =
                new GitHubRestClient.PullState(true, false, "feature", "main", Set.of("Design-Review"), null);
        LabelEventSubscriberTestAccess.webhookAndWait(label("labeled"));
        assertThat(review().isLabelPresent(), is(true));

        // A forged "unlabeled" can't switch design review back off while GitHub still has the label.
        LabelEventSubscriberTestAccess.webhookAndWait(label("unlabeled"));
        assertThat(review().isLabelPresent(), is(true));
    }

    @Test
    void otherActionsDoNothing() throws Exception {
        FakePrStates.state = new GitHubRestClient.PullState(false, true, "feature", "main", Set.of(), null);

        int tasks = LabelEventSubscriberTestAccess.webhookAndWait(
                "{\"action\": \"edited\", \"number\": 7, \"repository\": {\"full_name\": \"acme/web\"}}");

        assertThat(tasks, is(0));
        assertThat(eyes.merges, empty());
    }
}
