package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.EnvVars;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.checks.util.CapturingChecksPublisher;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
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

/** Fork pull requests, the key in Pipeline state, the design review lock, http URLs and reviewer names. */
@WithJenkins
public class SecurityHardeningTest {

    private static final String RUN_KEY = "run-key-5ecret-0123456789";
    private static final String PR_ENV = "'CHANGE_ID=5', 'CHANGE_BRANCH=main', 'CHANGE_TARGET=main',"
            + " 'GIT_URL=https://github.com/acme/web.git', 'GIT_COMMIT=c0ffee'";

    @TestExtension
    public static final CapturingChecksPublisher.Factory PUBLISHER = new CapturingChecksPublisher.Factory();

    private JenkinsRule j;
    private FakeEyesServer eyes;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        eyes = new FakeEyesServer();
        ApplitoolsGlobalConfiguration.get().setServerUrl(eyes.url());
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL,
                        ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID, "", Secret.fromString(RUN_KEY)));
        eyes.results("c0ffee", List.of(new FakeTest("s1", "unresolved")));
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
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

    // F5: fork pull requests don't get the key unless an admin allows it.

    @Test
    void forkPullRequestsAreRefusedByDefault() throws Exception {
        WorkflowRun run = run("withEnv([" + PR_ENV + ", 'CHANGE_FORK=contributor']) {"
                + " applitools { echo 'tests ran' } }", Result.FAILURE);

        j.assertLogContains("comes from a fork (contributor)", run);
        j.assertLogNotContains("tests ran", run);
    }

    // F7: allowed forks get their own Eyes branch, and that's the branch merged later.

    @Test
    void allowedForksGetTheirOwnEyesBranch() throws Exception {
        ApplitoolsGlobalConfiguration.get().setAllowForkPullRequests(true);

        WorkflowRun run = run("withEnv([" + PR_ENV + ", 'CHANGE_FORK=contributor']) {"
                + " applitools { echo 'tests ran' } }", Result.SUCCESS);

        j.assertLogContains("tests ran", run);
        j.assertLogContains("Branch acme/web/pr-5 ← baseline from acme/web/main", run);
        PendingReview review = ReviewRegistry.get().all().get(0);
        assertThat(review.getEyesBranch(), is("acme/web/pr-5"));
    }

    @Test
    void sameRepositoryPullRequestsKeepTheirBranchName() throws Exception {
        WorkflowRun run = run("withEnv([" + PR_ENV + "]) { applitools { echo 'tests ran' } }", Result.SUCCESS);

        j.assertLogContains("Branch acme/web/main ← baseline from acme/web/main", run);
    }

    @Test
    void ghprbForksAreDetected() {
        EnvVars same = new EnvVars(Map.of(
                "ghprbGhRepository", "acme/web", "ghprbAuthorRepoGitUrl", "https://github.com/acme/web.git"));
        EnvVars fork = new EnvVars(Map.of(
                "ghprbGhRepository", "acme/web", "ghprbAuthorRepoGitUrl", "https://github.com/contributor/web.git"));

        assertThat(ApplitoolsBuildWrapper.forkOf(same), nullValue());
        assertThat(ApplitoolsBuildWrapper.forkOf(fork), is("https://github.com/contributor/web.git"));
        assertThat(ApplitoolsBuildWrapper.forkOf(new EnvVars()), nullValue());
    }

    // F6: the key is declared sensitive and isn't saved in plain text in the Pipeline's state.

    @Test
    void pipelineStepHasTheWrappersOptions() throws Exception {
        var step = new org.jenkinsci.plugins.structs.describable.DescribableModel<>(ApplitoolsStep.class);
        var wrapper = new org.jenkinsci.plugins.structs.describable.DescribableModel<>(ApplitoolsBuildWrapper.class);
        assertThat(step.getParameters().stream().map(p -> p.getName()).sorted().toList(),
                is(wrapper.getParameters().stream().map(p -> p.getName()).sorted().toList()));

        ApplitoolsStep configured = new ApplitoolsStep();
        configured.setBatchId("b1");
        configured.setExpectApps(List.of("Web"));
        configured.setDesignReview(true);
        assertThat(step.uninstantiate2(configured).toMap(),
                is(Map.of("batchId", "b1", "expectApps", List.of("Web"), "designReview", true)));
    }

    @Test
    void keyIsASensitiveVariable() throws Exception {
        WorkflowRun run = run("applitools(batchId: 'b1') { echo \"key: ${env.APPLITOOLS_API_KEY}\" }",
                Result.SUCCESS);

        j.assertLogNotContains(RUN_KEY, run);
        j.assertLogContains("A secret was passed to \"echo\" using Groovy String interpolation", run);
        j.assertLogContains("used the following variable(s): [APPLITOOLS_API_KEY]", run);
    }

    @Test
    void keyIsEncryptedInPipelineState() throws Exception {
        // Through Pipeline's generic wrapper support, the key is saved in plain text: proves this test can see it.
        assertThat(savedStateContainsKey("wrap([$class: 'ApplitoolsBuildWrapper', batchId: 'b1'])"), is(true));
        // The applitools step keeps it encrypted.
        assertThat(savedStateContainsKey("applitools(batchId: 'b1')"), is(false));
    }

    /** Runs a block that pauses, and looks for the key in the build's saved Pipeline state while it's paused. */
    private boolean savedStateContainsKey(String block) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(block + " { echo 'paused'; sleep 60 }", true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("paused", run);
        File program = new File(run.getRootDir(), "program.dat");
        String state = "";
        for (int i = 0; i < 100 && !state.contains("APPLITOOLS_BATCH_ID"); i++) {
            Thread.sleep(100);
            state = program.exists() ? new String(Files.readAllBytes(program.toPath()), StandardCharsets.ISO_8859_1) : "";
        }
        assertThat("program.dat was saved with the block's environment", state, containsString("APPLITOOLS_BATCH_ID"));
        run.doStop();
        j.waitForCompletion(run);
        return state.contains(RUN_KEY);
    }

    // F8: with the lock on, a Jenkinsfile can't turn design review off.

    @Test
    void lockedDesignReviewIgnoresTheJenkinsfile() throws Exception {
        ApplitoolsGlobalConfiguration.get().setDesignReviewByDefault(true);
        ApplitoolsGlobalConfiguration.get().setFollowBranchBuilds(true);
        ApplitoolsGlobalConfiguration.get().setLockDesignReview(true);

        WorkflowRun run = run("applitools(batchId: 'b1', designReview: false, designers: 'me@example.com',"
                + " checks: false) { echo 'tests' }", Result.SUCCESS);

        j.assertLogContains("Design review is locked", run);
        j.assertLogContains("checks: false ignored", run);
        PendingReview review = ReviewRegistry.get().all().get(0);
        assertThat(review.isDesignReviewRequired(), is(true));
        assertThat(review.getDesigners().contains("me@example.com"), is(false));
    }

    @Test
    void unlockedDesignReviewFollowsTheJenkinsfile() throws Exception {
        ApplitoolsGlobalConfiguration.get().setDesignReviewByDefault(true);
        ApplitoolsGlobalConfiguration.get().setFollowBranchBuilds(true);

        run("applitools(batchId: 'b1', designReview: false) { echo 'tests' }", Result.SUCCESS);

        assertThat(ReviewRegistry.get().all().get(0).isDesignReviewRequired(), is(false));
    }

    // F10: keys only go over http to this machine, unless an admin allows it.

    @Test
    void httpIsRefusedForOtherMachinesUnlessAllowed() throws Exception {
        WorkflowRun refused = run("applitools(serverUrl: 'http://eyes.example.com') { echo 'tests ran' }",
                Result.FAILURE);
        j.assertLogContains("must use https://", refused);

        ApplitoolsGlobalConfiguration.get().setAllowHttp(true);
        ServerUrls.check("http://203.0.113.10/eyes", "URL"); // no exception
    }

    // F12: reviewer emails can be left out of PR results.

    @Test
    void reviewersCanBeHidden() {
        BatchResults results = new BatchResults("b", "batch", "https://eyes/b", 0, 0, 0, 0, 0, 1,
                List.of(new BatchResults.Test("t0", "test 0", "Chrome", "passed", null)));
        Designers designers = Designers.of(List.of("lead@example.com"));
        Map<String, Set<String>> approvers = Map.of("t0", Set.of("lead@example.com"));

        assertThat(DesignReview.evaluate(true, "design-review", results, Set.of("t0"), approvers, designers).title(),
                is("Approved by lead@example.com"));

        ApplitoolsGlobalConfiguration.get().setHideReviewers(true);
        DesignReview.Verdict hidden =
                DesignReview.evaluate(true, "design-review", results, Set.of("t0"), approvers, designers);
        assertThat(hidden.title(), is("Approved by a designer"));
        assertThat(hidden.detail(), not(containsString("lead@example.com")));
    }

    @Test
    void testNamesAreShownAsPlainText() {
        assertThat(VisualTestsCheck.escape("Login [click](https://evil.example) ![x](y) *bold*"),
                is("Login \\[click\\](https://evil.example) \\!\\[x\\](y) \\*bold\\*"));
        assertThat(VisualTestsCheck.escape("a|b\nc"), is("a\\|b c"));
    }
}
