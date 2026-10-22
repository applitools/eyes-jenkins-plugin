package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.ExtensionList;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import io.jenkins.plugins.applitools.review.PendingReview;
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

/** #4: SDKs told not to fail on diffs. #8: missing suites can't pass the gate. */
@WithJenkins
public class DiffJudgingAndExpectationsTest {

    static {
        System.setProperty(PendingReview.class.getName() + ".minPollSeconds", "0");
    }

    @TestExtension
    public static final CapturingChecksPublisher.Factory CHECKS = new CapturingChecksPublisher.Factory();

    private JenkinsRule j;
    private FakeEyesServer eyes;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        CHECKS.getPublishedChecks().clear();
        eyes = new FakeEyesServer();
        ApplitoolsGlobalConfiguration.get().setServerUrl(eyes.url());
        // Plain test pipelines aren't PRs; follow them like PR builds so the review flow can be exercised.
        ApplitoolsGlobalConfiguration.get().setFollowBranchBuilds(true);
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

    private ChecksDetails visualCheck() {
        List<ChecksDetails> checks = CHECKS.getPublishedChecks().stream()
                .filter(c -> c.getName().orElse("").equals(VisualTestsCheck.NAME))
                .toList();
        return checks.get(checks.size() - 1);
    }

    private static final String PRINT_ENV = """
            echo "exitcode=${env.APPLITOOLS_EXITCODE} cypress=${env.APPLITOOLS_FAIL_CYPRESS_ON_DIFF}"
            """;

    @Test
    void sdksAreToldNotToFailOnDiffs() throws Exception {
        WorkflowRun r = run("applitools(batchId: 'b1') { " + PRINT_ENV + " }", Result.SUCCESS);

        j.assertLogContains("exitcode=nodiffs cypress=false", r);
    }

    @Test
    void userSettingsWin() throws Exception {
        WorkflowRun r = run("withEnv(['APPLITOOLS_EXITCODE=true']) { applitools(batchId: 'b1') { " + PRINT_ENV
                + " } }", Result.SUCCESS);

        j.assertLogContains("exitcode=true cypress=false", r);
    }

    @Test
    void judgeDiffsFalseLeavesSdksAlone() throws Exception {
        WorkflowRun r = run("applitools(batchId: 'b1', judgeDiffs: false) { " + PRINT_ENV + " }", Result.SUCCESS);

        j.assertLogContains("exitcode=null cypress=null", r);
    }

    @Test
    void tooFewTestsFailTheGate() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "passed"), new FakeTest("s2", "passed")));
        WorkflowRun r = run("applitools(batchId: 'b1', expectTests: 5) { echo 'tests' }", Result.FAILURE);

        j.assertLogContains("Missing results: expected at least 5 tests, Eyes received 2", r);
        assertThat(visualCheck().getConclusion(), is(ChecksConclusion.FAILURE));
    }

    @Test
    void enoughTestsPass() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "passed"), new FakeTest("s2", "passed")));
        run("applitools(batchId: 'b1', expectTests: 2) { echo 'tests' }", Result.SUCCESS);
    }

    @Test
    void missingAppFailsTheGate() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "passed", "Website")));
        WorkflowRun r = run(
                "applitools(batchId: 'b1', expectApps: ['website', 'Storybook Sandbox']) { echo 'tests' }",
                Result.FAILURE);

        j.assertLogContains("Missing results: no tests from Storybook Sandbox", r);
    }

    @Test
    void allAppsPresentPass() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "passed", "Website"),
                new FakeTest("s2", "passed", "Storybook Sandbox")));
        run("applitools(batchId: 'b1', expectApps: ['Website', 'Storybook Sandbox']) { echo 'tests' }",
                Result.SUCCESS);
    }

    @Test
    void nothingRanFailsWhenTestsWereExpected() throws Exception {
        eyes.unknownBatches.add("b1");
        WorkflowRun r = run("applitools(batchId: 'b1', expectTests: 1) { echo 'suite never started' }",
                Result.FAILURE);

        j.assertLogContains("expected at least 1 test, Eyes received 0", r);
        assertThat(visualCheck().getConclusion(), is(ChecksConclusion.FAILURE));
    }

    @Test
    void acceptingOtherDiffsCantPassAMissingSuite() throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved", "Website")));
        run("applitools(batchId: 'b1', expectApps: ['Website', 'Storybook Sandbox']) { echo 'tests' }",
                Result.FAILURE);

        // A reviewer accepts the Website diff; Storybook results never arrived.
        eyes.results("b1", List.of(new FakeTest("s1", "passed", "Website")));
        ExtensionList.lookupSingleton(ReviewWatcher.class).execute(TaskListener.NULL);

        assertThat(visualCheck().getConclusion(), is(ChecksConclusion.FAILURE));
    }
}
