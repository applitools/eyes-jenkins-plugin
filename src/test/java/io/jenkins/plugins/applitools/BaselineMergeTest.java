package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import io.jenkins.plugins.applitools.review.LabelEventSubscriberTestAccess;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
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

/** Merged PR webhook → Eyes baseline branch merge → "Baseline merge" check. */
@WithJenkins
public class BaselineMergeTest {

    static {
        System.setProperty(BaselineMerge.class.getName() + ".pollMillis", "20");
    }

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
        addSecret(ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID, "run-key-0123456789");
        // Unreviewed changes, so the (non-PR) test build is followed and can be turned into a PR below.
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition("applitools(batchId: 'b1') { echo 'tests' }", true));
        j.assertBuildStatus(Result.UNSTABLE, job.scheduleBuild2(0));
        // Plain test pipelines aren't PRs; pretend this build is PR #7 of acme/web.
        PendingReview review = ReviewRegistry.get().all().get(0);
        ReviewRegistry.get().register(LabelEventSubscriberTestAccess.asPullRequest(review, "acme/web", "7"));
    }

    @AfterEach
    void tearDown() {
        eyes.close();
    }

    private void addSecret(String id, String value) throws java.io.IOException {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, id, "", Secret.fromString(value)));
        SystemCredentialsProvider.getInstance().save();
    }

    private ChecksDetails mergeCheck() {
        List<ChecksDetails> checks = PUBLISHER.getPublishedChecks().stream()
                .filter(c -> c.getName().orElse("").equals(BaselineMerge.CHECK_NAME))
                .toList();
        return checks.get(checks.size() - 1);
    }

    private static String title(ChecksDetails check) {
        return check.getOutput().orElseThrow().getTitle().orElseThrow();
    }

    @Test
    void mergedPullRequestMergesItsBaselines() throws Exception {
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature/login", "main");

        assertThat(eyes.merges, contains("acme/web/feature/login -> acme/web/main (false)"));
        assertThat(mergeCheck().getConclusion(), is(ChecksConclusion.SUCCESS));
        assertThat(title(mergeCheck()), is("Baselines merged into main"));
        assertThat(ReviewRegistry.get().all(), empty()); // stops following the review
    }

    @Test
    void mergeCredentialIsPreferred() throws Exception {
        addSecret(ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID, "merge-key-0123456789");
        eyes.requiredMergeKey = "merge-key-0123456789";

        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        assertThat(mergeCheck().getConclusion(), is(ChecksConclusion.SUCCESS));
    }

    @Test
    void refusedKeyExplainsWhatToAdd() throws Exception {
        eyes.requiredMergeKey = "some-other-key";

        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        assertThat(mergeCheck().getConclusion(), is(ChecksConclusion.FAILURE));
        assertThat(mergeCheck().getOutput().orElseThrow().getSummary().orElseThrow(),
                containsString("applitools-merge-api-key"));
    }

    @Test
    void refusedMergeCanBeRetriedOnceAKeyIsAdded() throws Exception {
        eyes.requiredMergeKey = "merge-key-0123456789";
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");
        assertThat(mergeCheck().getConclusion(), is(ChecksConclusion.FAILURE));
        assertThat(ReviewRegistry.get().all().size(), is(1)); // still tracked for a retry

        addSecret(ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID, "merge-key-0123456789");
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main"); // redelivered

        assertThat(mergeCheck().getConclusion(), is(ChecksConclusion.SUCCESS));
        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void conflictsFailTheMergeCheck() throws Exception {
        eyes.mergeResult = "{\"merged\":false,\"conflicts\":2,\"changes\":[]}";

        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        assertThat(mergeCheck().getConclusion(), is(ChecksConclusion.FAILURE));
        assertThat(title(mergeCheck()), is("2 baseline conflicts"));
    }

    @Test
    void closedWithoutMergingOnlyStopsFollowing() throws Exception {
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", false, "feature", "main");

        assertThat(eyes.merges, empty());
        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void mergingCanBeTurnedOff() throws Exception {
        ApplitoolsGlobalConfiguration.get().setMergeBaselines(false);

        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        assertThat(eyes.merges, empty());
    }

    @Test
    void otherPullRequestsAreIgnored() throws Exception {
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "8", true, "feature", "main");

        assertThat(eyes.merges, empty());
        assertThat(ReviewRegistry.get().all().size(), is(1));
    }

    @Test
    void parsesGitHubClosedPayload() throws Exception {
        String payload = """
                {"action": "closed", "number": 7,
                 "pull_request": {"merged": true, "head": {"ref": "feature"}, "base": {"ref": "main"}},
                 "repository": {"full_name": "acme/web"}}
                """;

        assertThat(LabelEventSubscriberTestAccess.handleMergedPayloadAndWait(payload), is(true));
        assertThat(eyes.merges, contains("acme/web/feature -> acme/web/main (false)"));
    }
}
