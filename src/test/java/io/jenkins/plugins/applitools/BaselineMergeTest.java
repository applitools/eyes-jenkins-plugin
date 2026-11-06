package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import hudson.model.queue.QueueTaskFuture;
import io.jenkins.plugins.applitools.review.LabelEventSubscriberTestAccess;
import io.jenkins.plugins.applitools.review.MergeHistory;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
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
        // Plain test pipelines aren't PRs; follow them like PR builds so the review flow can be exercised.
        ApplitoolsGlobalConfiguration.get().setFollowBranchBuilds(true);
        addSecret(ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID, "run-key-0123456789");
        // Unreviewed changes, so the (non-PR) test build is followed and can be turned into a PR below.
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
    void stepCanTurnMergingOff() throws Exception {
        eyes.results("b2", List.of(new FakeTest("s1", "unresolved")));
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "applitools(batchId: 'b2', mergeBaselines: false) { echo 'tests' }", true));
        j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        PendingReview review = ReviewRegistry.get().all().stream()
                .filter(r -> r.getBatchId().equals("b2"))
                .findFirst()
                .orElseThrow();
        PendingReview asPr = LabelEventSubscriberTestAccess.asPullRequest(review, "acme/web", "8");
        asPr.setMergeBaselines(false);
        ReviewRegistry.get().register(asPr);

        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "8", true, "feature", "main");

        assertThat(eyes.merges, empty());
    }

    @Test
    void stepCanTurnMergingOnWhenGloballyOff() throws Exception {
        ApplitoolsGlobalConfiguration.get().setMergeBaselines(false);
        PendingReview review = ReviewRegistry.get().all().get(0);
        review.setMergeBaselines(true);
        ReviewRegistry.get().saveQuietly();

        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        assertThat(eyes.merges, contains("acme/web/feature -> acme/web/main (false)"));
    }

    /** A build of the target branch, as the push of a PR's merge starts it. */
    private WorkflowRun mainBuild(WorkflowJob job) throws Exception {
        return j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    private WorkflowJob mainJob() throws Exception {
        eyes.results("main", List.of(new FakeTest("s1", "passed")));
        WorkflowJob job = j.createProject(WorkflowJob.class, "web-main");
        job.setDefinition(new CpsFlowDefinition(
                "applitools(batchId: 'main', repo: 'acme/web', branch: 'main') { echo 'tests' }", true));
        return job;
    }

    private static MergeHistory.Entry lastMerge() {
        return MergeHistory.get().recent().get(0);
    }

    @Test
    void outcomesAreRecorded() throws Exception {
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature/login", "main");

        MergeHistory.Entry merged = lastMerge();
        assertThat(merged.getOutcome(), is(MergeHistory.Outcome.MERGED));
        assertThat(merged.getPullRequestName(), is("acme/web#7"));
        assertThat(merged.getSource(), is("acme/web/feature/login"));
        assertThat(merged.getTarget(), is("acme/web/main"));
        assertThat(merged.getTitle(), is("Baselines merged into main"));
        assertThat(merged.getBuildUrl(), is("job/test0/1/"));
    }

    @Test
    void refusedKeyIsRecorded() throws Exception {
        eyes.requiredMergeKey = "some-other-key";

        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        assertThat(lastMerge().getOutcome(), is(MergeHistory.Outcome.REFUSED));
        assertThat(lastMerge().getPlainSummary(), containsString("applitools-merge-api-key"));
    }

    @Test
    void goneBuildIsRecordedAsSkipped() throws Exception {
        j.jenkins.getItemByFullName("test0", WorkflowJob.class).getBuildByNumber(1).delete();

        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        assertThat(eyes.merges, empty());
        assertThat(lastMerge().getOutcome(), is(MergeHistory.Outcome.SKIPPED));
        assertThat(lastMerge().getTitle(), is("Baseline merge skipped: build gone"));
        assertThat(lastMerge().getTarget(), is("acme/web/main"));
        assertThat(lastMerge().getBuildUrl(), is(nullValue()));
    }

    @Test
    void unknownBranchesAreRecordedAsSkipped() throws Exception {
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, null, null);

        assertThat(lastMerge().getTitle(), is("Baseline merge skipped: branches unknown"));
    }

    @Test
    void turnedOffIsRecorded() throws Exception {
        ApplitoolsGlobalConfiguration.get().setMergeBaselines(false);

        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        assertThat(lastMerge().getOutcome(), is(MergeHistory.Outcome.TURNED_OFF));
    }

    @Test
    void nextBuildOfTheTargetBranchPrintsTheMergeOnce() throws Exception {
        WorkflowJob main = mainJob();
        mainBuild(main);
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        WorkflowRun next = mainBuild(main);
        j.assertLogContains("[Applitools] Baseline merge from PR #7: Baselines merged into main. 2 baseline changes"
                + " from acme/web/feature now apply to acme/web/main.", next);
        j.assertLogNotContains("WARNING", next);

        j.assertLogNotContains("Baseline merge from PR #7", mainBuild(main)); // only the first build after it
    }

    @Test
    void firstBuildOfTheTargetBranchPrintsEarlierMerges() throws Exception {
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        j.assertLogContains("Baseline merge from PR #7: Baselines merged into main", mainBuild(mainJob()));
    }

    @Test
    void failedMergeWarnsTheTargetBranchBuild() throws Exception {
        WorkflowJob main = mainJob();
        mainBuild(main);
        eyes.mergeResult = "{\"merged\":false,\"conflicts\":2,\"changes\":[]}";
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "main");

        WorkflowRun next = mainBuild(main);
        j.assertLogContains("[Applitools] WARNING: Baseline merge from PR #7: 2 baseline conflicts. The same"
                + " checkpoints changed on main too. Resolve them in Eyes under Compare & merge branches", next);
        j.assertLogContains("PR build: test0 #1", next);
    }

    @Test
    void mergesIntoOtherBranchesArentPrinted() throws Exception {
        WorkflowJob main = mainJob();
        mainBuild(main);
        LabelEventSubscriberTestAccess.mergedAndWait("acme/web", "7", true, "feature", "release");

        j.assertLogNotContains("Baseline merge from", mainBuild(main));
    }

    @Test
    void targetBranchBuildWaitsForARunningMerge() throws Exception {
        WorkflowJob main = mainJob();
        LabelEventSubscriberTestAccess.mergeRunning("acme/web", "acme/web/main");
        QueueTaskFuture<WorkflowRun> build = main.scheduleBuild2(0);
        WorkflowRun run = build.waitForStart();
        j.waitForMessage("is running or about to start; waiting up to 60s for it before the tests start", run);
        Thread.sleep(700);
        assertThat(run.isBuilding(), is(true));
        assertThat(run.getLog().contains("[Pipeline] echo"), is(false)); // the tests haven't started

        LabelEventSubscriberTestAccess.mergeDone("acme/web/main");

        j.assertBuildStatus(Result.SUCCESS, build);
        j.assertLogContains("[Applitools] Waited ", run);
    }

    @Test
    void targetBranchBuildStopsWaitingAfterAWhile() throws Exception {
        System.setProperty(BaselineMergeNotes.class.getName() + ".waitSeconds", "1");
        LabelEventSubscriberTestAccess.mergeRunning("acme/web", "acme/web/main");
        try {
            WorkflowRun run = mainBuild(mainJob());
            j.assertLogContains("WARNING: The baseline merge into acme/web/main was still running after 1s", run);
        } finally {
            LabelEventSubscriberTestAccess.mergeDone("acme/web/main");
            System.clearProperty(BaselineMergeNotes.class.getName() + ".waitSeconds");
        }
    }

    @Test
    void pullRequestBuildsDontWait() throws Exception {
        LabelEventSubscriberTestAccess.mergeRunning("acme/web", "acme/web/main");
        try {
            eyes.results("pr", List.of(new FakeTest("s1", "passed")));
            WorkflowJob job = j.createProject(WorkflowJob.class);
            job.setDefinition(new CpsFlowDefinition("applitools(batchId: 'pr', repo: 'acme/web', pullRequest: '9',"
                    + " branch: 'feature', targetBranch: 'main') { echo 'tests' }", true));
            j.assertLogNotContains("waiting up to", j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0)));
        } finally {
            LabelEventSubscriberTestAccess.mergeDone("acme/web/main");
        }
    }
}
