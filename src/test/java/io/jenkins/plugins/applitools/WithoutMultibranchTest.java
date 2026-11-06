package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.ExtensionList;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.applitools.review.UnmergedBaselines;
import io.jenkins.plugins.applitools.setup.SetupCheck;
import io.jenkins.plugins.applitools.setup.SetupItem;
import io.jenkins.plugins.checks.util.CapturingChecksPublisher;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.util.Timer;
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

/**
 * A plain Pipeline job (not multibranch, no GitHub App, no webhooks) that reports with a token: statuses posted
 * directly, labels and merges learned by polling the PR.
 */
@WithJenkins
public class WithoutMultibranchTest {

    static {
        System.setProperty(PendingReview.class.getName() + ".minPollSeconds", "0");
        System.setProperty(BaselineMerge.class.getName() + ".pollMillis", "20");
    }

    @TestExtension
    public static final CapturingChecksPublisher.Factory CHECKS = new CapturingChecksPublisher.Factory();

    private static final String SHA = "abc1234def";

    private JenkinsRule j;
    private FakeEyesServer eyes;
    private FakeGitHubServer github;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        CHECKS.getPublishedChecks().clear();
        eyes = new FakeEyesServer();
        github = new FakeGitHubServer();
        ApplitoolsGlobalConfiguration.get().setServerUrl(eyes.url());
        addSecret(ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID, "eyes-key-0123456789");
        addSecret("gh-token", github.token);
    }

    @AfterEach
    void tearDown() {
        eyes.close();
        github.close();
    }

    private void addSecret(String id, String value) throws java.io.IOException {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, id, "", Secret.fromString(value)));
        SystemCredentialsProvider.getInstance().save();
    }

    /** Step options as a Generic Webhook Trigger job would pass them. */
    private String step(String extra, String body) {
        return "applitools(repo: 'acme/web', pullRequest: '7', commit: '" + SHA + "', branch: 'feature',"
                + " targetBranch: 'main', githubCredentialsId: 'gh-token', githubApiUrl: '" + github.apiUrl() + "'"
                + extra + ") { " + body + " }";
    }

    private WorkflowRun run(String script, Result expected) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return j.assertBuildStatus(expected, job.scheduleBuild2(0));
    }

    private void watcherTick() throws Exception {
        ExtensionList.lookupSingleton(ReviewWatcher.class).execute(TaskListener.NULL);
        Timer.get().schedule(() -> {}, 0, TimeUnit.MILLISECONDS).get(); // let label/merge tasks start
        for (int i = 0; i < 100 && Timer.get() instanceof java.util.concurrent.ThreadPoolExecutor tpe
                && tpe.getActiveCount() > 0; i++) {
            Thread.sleep(50);
        }
        Thread.sleep(300);
    }

    @Test
    void explicitOptionsSetUpEyesAndPostStatuses() throws Exception {
        eyes.results(SHA, List.of(new FakeTest("s1", "passed")));
        WorkflowRun r = run(step("", "echo \"id=${env.APPLITOOLS_BATCH_ID} branch=${env.APPLITOOLS_BRANCH_NAME}"
                + " parent=${env.APPLITOOLS_PARENT_BRANCH_NAME} name=${env.APPLITOOLS_BATCH_NAME}\""), Result.SUCCESS);

        j.assertLogContains("id=" + SHA + " branch=acme/web/feature parent=acme/web/main name=acme/web PR #7", r);
        j.assertLogContains("Reporting to GitHub acme/web PR #7 with credential 'gh-token'", r);
        assertThat(github.posted(), contains(
                "Applitools / Visual tests = pending | Running visual tests",
                "Applitools / Design review = pending | Waiting for visual tests",
                "Applitools / Visual tests = success | 1 passed",
                "Applitools / Design review = success | Not requested"));
        FakeGitHubServer.Status first = github.statuses.get(2);
        assertThat(first.sha(), is(SHA));
        assertThat(first.repo(), is("acme/web"));
        assertThat(first.url(), is("https://eyes.example/app/test-results/eb-" + SHA));
        assertThat(CHECKS.getPublishedChecks(), empty()); // statuses only
    }

    @Test
    void labelPresentAtBuildTimeRequiresReview() throws Exception {
        github.labels.add("design-review");
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        run(step("", "echo 'tests'"), Result.SUCCESS);

        assertThat(github.posted(), hasItem("Applitools / Design review = pending | Waiting for design review"));
    }

    @Test
    void labelAddedLaterIsFoundByPolling() throws Exception {
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        run(step("", "echo 'tests'"), Result.SUCCESS);
        assertThat(github.posted(), hasItem("Applitools / Design review = success | Not requested"));

        github.labels.add("design-review");
        watcherTick();

        assertThat(github.posted().get(github.posted().size() - 1),
                is("Applitools / Design review = pending | Waiting for design review"));
    }

    @Test
    void unchangedPullRequestsCostA304() throws Exception {
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        run(step("", "echo 'tests'"), Result.SUCCESS);

        watcherTick(); // first read: full PR, ETag remembered
        watcherTick(); // unchanged: 304
        watcherTick();

        assertThat(github.notModified.get() >= 2, is(true));
    }

    @Test
    void mergeIsFoundByPollingAndMergesBaselines() throws Exception {
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        run(step("", "echo 'tests'"), Result.SUCCESS);

        github.state = "closed";
        github.merged = true;
        watcherTick();

        assertThat(eyes.merges, contains("acme/web/feature -> acme/web/main (false)"));
        assertThat(github.posted(), hasItem("Applitools / Baseline merge = success | Baselines merged into main"));
        assertThat(ReviewRegistry.get().all(), empty());
    }

    private static final String CONFLICTS = "{\"merged\":false,\"conflicts\":2,\"changes\":[]}";

    /** PR #7 builds, then merges with baseline conflicts. */
    private void mergedWithConflicts() throws Exception {
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        run(step("", "echo 'tests'"), Result.SUCCESS);
        eyes.mergeResult = CONFLICTS;
        github.state = "closed";
        github.merged = true;
        watcherTick();
    }

    /** A build of main, as the push of a merge starts it. */
    private WorkflowRun mainBuild(WorkflowJob job, String sha, Result expected) throws Exception {
        eyes.results(sha, List.of(new FakeTest("s1", "passed")));
        job.setDefinition(new CpsFlowDefinition("applitools(repo: 'acme/web', commit: '" + sha + "', branch: 'main',"
                + " githubCredentialsId: 'gh-token', githubApiUrl: '" + github.apiUrl() + "') { echo 'tests' }", true));
        return j.assertBuildStatus(expected, job.scheduleBuild2(0));
    }

    private List<FakeGitHubServer.Status> mergeStatuses(String sha) {
        return github.statuses.stream()
                .filter(st -> st.sha().equals(sha) && st.context().equals(BaselineMerge.CHECK_NAME))
                .toList();
    }

    @Test
    void conflictsAreCommentedAndLabeledOnTheMergedPullRequest() throws Exception {
        mergedWithConflicts();

        assertThat(github.posted(), hasItem("Applitools / Baseline merge = failure | 2 baseline conflicts"));
        assertThat(github.labels, hasItem(BaselineMergeAlerts.LABEL));
        assertThat(github.comments.size(), is(1));
        String comment = github.comments.get(0).body();
        assertThat(comment, startsWith(BaselineMergeAlerts.MARKER));
        assertThat(comment, containsString("### ⚠️ Eyes baselines from this PR weren't merged into `main`"));
        assertThat(comment, containsString("**2 baseline conflicts.** The same checkpoints changed on `main` too."
                + " Resolve them in Eyes under **Compare & merge branches** (`acme/web/feature` → `acme/web/main`)."));
        assertThat(comment, containsString("builds of `main` show a failing **Applitools / Baseline merge** check"
                + " and are marked unstable"));
        UnmergedBaselines.Item item = UnmergedBaselines.get().find("acme/web", "7");
        assertThat(item.getCommented(), is("2 baseline conflicts"));
    }

    @Test
    void targetBranchBuildsStayRedUntilTheConflictIsResolved() throws Exception {
        mergedWithConflicts();
        WorkflowJob main = j.createProject(WorkflowJob.class, "web-main");

        WorkflowRun first = mainBuild(main, "main0001", Result.UNSTABLE);
        j.assertLogContains("[Applitools] WARNING: The baselines of PR #7 still aren't merged into main: 2 baseline"
                + " conflicts.", first);
        j.assertLogContains("Marked unstable", first);
        assertThat(mergeStatuses("main0001").get(0).state(), is("failure"));
        assertThat(mergeStatuses("main0001").get(0).description(), is("Baselines of a merged PR aren't merged"));
        assertThat(github.comments.size(), is(1)); // same reason: not posted again

        mainBuild(main, "main0002", Result.UNSTABLE); // still unresolved: still red

        eyes.mergeResult = "{\"merged\":true,\"conflicts\":0,\"changes\":[{}]}"; // resolved in Eyes
        WorkflowRun fixed = mainBuild(main, "main0003", Result.SUCCESS);
        j.assertLogContains("Baseline merge of PR #7 retried: Baselines merged into main.", fixed);
        assertThat(mergeStatuses("main0003").get(0).state(), is("success"));
        assertThat(mergeStatuses("main0003").get(0).description(), is("Retried baseline merge succeeded"));
        assertThat(github.labels, not(hasItem(BaselineMergeAlerts.LABEL)));
        assertThat(github.comments.size(), is(1));
        assertThat(github.comments.get(0).body(),
                containsString("### ✅ Eyes baselines from this PR are merged into `main`"));
        assertThat(UnmergedBaselines.get().all(), empty());
        assertThat(ReviewRegistry.get().all(), empty());

        mainBuild(main, "main0004", Result.SUCCESS);
        assertThat(mergeStatuses("main0004"), empty()); // nothing left to report
    }

    @Test
    void withoutWritePermissionTheBuildIsStillRed() throws Exception {
        github.readOnly = true;
        mergedWithConflicts();

        UnmergedBaselines.Item item = UnmergedBaselines.get().find("acme/web", "7");
        assertThat(item.getCommented(), is((String) null));
        assertThat(item.getCommentError(), containsString("Pull requests: Read and write"));
        mainBuild(j.createProject(WorkflowJob.class), "main0001", Result.UNSTABLE);
        assertThat(mergeStatuses("main0001").get(0).state(), is("failure"));
    }

    @Test
    void commentAndUnstableCanBeTurnedOff() throws Exception {
        ApplitoolsGlobalConfiguration.get().setCommentOnUnmergedBaselines(false);
        ApplitoolsGlobalConfiguration.get().setUnstableWhileUnmerged(false);
        mergedWithConflicts();

        assertThat(github.comments, empty());
        assertThat(github.labels, not(hasItem(BaselineMergeAlerts.LABEL)));
        mainBuild(j.createProject(WorkflowJob.class), "main0001", Result.SUCCESS);
        assertThat(mergeStatuses("main0001").get(0).state(), is("failure")); // the check still says so
    }

    @Test
    void mergesBaselinesWithDesignReviewOff() throws Exception {
        // No Design review result: the PR must still be followed, and its baselines merged.
        ApplitoolsGlobalConfiguration.get().setDesignReviewCheck(false);
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        run(step("", "echo 'tests'"), Result.SUCCESS);
        assertThat(ReviewRegistry.get().all().get(0).isPullRequest(), is(true));

        github.state = "closed";
        github.merged = true;
        watcherTick();

        assertThat(eyes.merges, contains("acme/web/feature -> acme/web/main (false)"));
        assertThat(github.posted(), hasItem("Applitools / Baseline merge = success | Baselines merged into main"));
        assertThat(github.posted(), not(hasItem(startsWith("Applitools / Design review"))));
    }

    @Test
    void mergesBaselinesWhenTheJobIsDeletedFirst() throws Exception {
        // Multibranch deletes a closed PR's job, possibly before the merge was noticed: merge before it's gone.
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        WorkflowRun build = run(step("", "echo 'tests'"), Result.SUCCESS);
        github.state = "closed";
        github.merged = true;

        build.getParent().delete(); // merges before the job is gone

        assertThat(eyes.merges, contains("acme/web/feature -> acme/web/main (false)"));
        assertThat(github.posted(), hasItem("Applitools / Baseline merge = success | Baselines merged into main"));
        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void deletingTheJobOfAnOpenPullRequestMergesNothing() throws Exception {
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        WorkflowRun build = run(step("", "echo 'tests'"), Result.SUCCESS);

        build.getParent().delete();
        Thread.sleep(500);
        watcherTick();

        assertThat(eyes.merges, empty());
        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void checksOffWarnsThatBaselinesWontBeMerged() throws Exception {
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        WorkflowRun build = run(step(", checks: false", "echo 'tests'"), Result.SUCCESS);

        j.assertLogContains("its Eyes baselines won't be merged when it merges", build);
    }

    @Test
    void closedWithoutMergeJustStopsFollowing() throws Exception {
        eyes.results(SHA, List.of(new FakeTest("s1", "unresolved")));
        run(step("", "echo 'tests'"), Result.SUCCESS);

        github.state = "closed";
        watcherTick();

        assertThat(eyes.merges, empty());
        assertThat(ReviewRegistry.get().all(), empty());
    }

    @Test
    void ghprbVariablesAreDetected() throws Exception {
        eyes.results("ghprb-sha", List.of(new FakeTest("s1", "passed")));
        WorkflowRun r = run("withEnv(['ghprbPullId=12', 'ghprbActualCommit=ghprb-sha', 'ghprbGhRepository=acme/app',"
                + " 'ghprbSourceBranch=fix', 'ghprbTargetBranch=develop', 'ghprbPullTitle=Fix header']) {"
                + " applitools(githubCredentialsId: 'gh-token', githubApiUrl: '" + github.apiUrl() + "') {"
                + " echo \"id=${env.APPLITOOLS_BATCH_ID} name=${env.APPLITOOLS_BATCH_NAME}"
                + " parent=${env.APPLITOOLS_PARENT_BRANCH_NAME}\" } }", Result.SUCCESS);

        j.assertLogContains("id=ghprb-sha name=acme/app PR #12: Fix header parent=acme/app/develop", r);
        assertThat(github.statuses.get(0).repo(), is("acme/app"));
        assertThat(github.statuses.get(0).sha(), is("ghprb-sha"));
    }

    @Test
    void unknownCredentialWarnsOnce() throws Exception {
        eyes.results(SHA, List.of(new FakeTest("s1", "passed")));
        WorkflowRun r = run(step("", "echo 'tests'").replace("'gh-token'", "'not-a-credential'"), Result.SUCCESS);

        j.assertLogContains("githubCredentialsId 'not-a-credential' doesn't match a Secret text or Username with"
                + " password credential", r);
        j.assertLogNotContains("could not publish", r); // one clear warning, not one per status
        assertThat(github.statuses, empty());
    }

    @Test
    void usernamePasswordCredentialWorks() throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "gh-userpass", "", "octocat", github.token));
        eyes.results(SHA, List.of(new FakeTest("s1", "passed")));
        run(step("", "echo 'tests'").replace("'gh-token'", "'gh-userpass'"), Result.SUCCESS);

        assertThat(github.posted(), hasItem("Applitools / Visual tests = success | 1 passed"));
    }

    @Test
    void missingRepoOrCommitWarns() throws Exception {
        WorkflowRun r = run("applitools(batchId: 'b1', githubCredentialsId: 'gh-token') { echo 'tests' }",
                Result.SUCCESS);

        j.assertLogContains("githubCredentialsId is set, but the repository or commit is unknown", r);
        assertThat(github.statuses, empty());
    }

    @Test
    void rejectedTokenShowsOnTheSetupPage() throws Exception {
        github.token = "a-different-token";
        eyes.results(SHA, List.of(new FakeTest("s1", "passed")));
        WorkflowRun r = run(step("", "echo 'tests'"), Result.SUCCESS); // reporting problems never fail builds

        j.assertLogContains("could not publish", r);
        SetupItem row = SetupCheck.runAll().stream()
                .filter(i -> i.getTitle().equals("GitHub with a token"))
                .findFirst()
                .orElseThrow();
        assertThat(row.getStatus(), is(SetupItem.Status.PROBLEM));
        assertThat(row.getDetail().contains("HTTP 401"), is(true));
    }
}
