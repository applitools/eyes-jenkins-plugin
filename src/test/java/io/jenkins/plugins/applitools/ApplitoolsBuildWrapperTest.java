package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.util.Secret;
import java.util.concurrent.atomic.AtomicReference;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ApplitoolsBuildWrapperTest {

    private static final String API_KEY = "fake-eyes-key-0123456789";
    private static final String READ_KEY = "fake-eyes-read-key-9876543210";

    static {
        // Poll the fake server quickly while "tests are still running".
        System.setProperty(ApplitoolsBuildWrapper.class.getName() + ".pollMillis", "50");
    }

    private JenkinsRule j;
    private FakeEyesServer eyes;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        eyes = new FakeEyesServer();
        ApplitoolsGlobalConfiguration.get().setServerUrl(eyes.url());
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID,
                        "",
                        Secret.fromString(API_KEY)));
        SystemCredentialsProvider.getInstance().save();
    }

    @AfterEach
    void tearDown() {
        eyes.close();
    }

    private WorkflowRun runPipeline(String script, Result expected) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return j.assertBuildStatus(expected, job.scheduleBuild2(0));
    }

    @Test
    void injectsSdkEnvironmentAndClosesBatch() throws Exception {
        WorkflowRun run = runPipeline(
                """
                applitools {
                  echo "id=${env.APPLITOOLS_BATCH_ID}"
                  echo "name=${env.APPLITOOLS_BATCH_NAME}"
                  echo "server=${env.APPLITOOLS_SERVER_URL}"
                  echo "dontClose=${env.APPLITOOLS_DONT_CLOSE_BATCHES}"
                }
                """,
                Result.SUCCESS);

        String batchId = logValue(run, "id=");
        assertThat(batchId, matchesPattern("[0-9a-f-]{36}")); // no SCM: generated per block
        j.assertLogContains("name=" + run.getParent().getFullName() + " #1", run);
        j.assertLogContains("server=" + eyes.url(), run);
        j.assertLogContains("dontClose=true", run);
        j.assertLogContains("[Applitools] Closed batch " + batchId, run);

        assertThat(eyes.closeRequests(), hasSize(1));
        FakeEyesServer.Request close = eyes.closeRequests().get(0);
        assertThat(close.path(), is("/api/sessions/batches/" + batchId + "/close/bypointerid"));
        assertThat(close.apiKeyHeader(), is(API_KEY));
        assertThat(close.query(), nullValue()); // key stays out of URLs
    }

    @Test
    void masksApiKeyInLog() throws Exception {
        WorkflowRun run = runPipeline("applitools { echo \"key=${env.APPLITOOLS_API_KEY}\" }", Result.SUCCESS);

        j.assertLogContains("key=****", run);
        j.assertLogNotContains(API_KEY, run);
    }

    @Test
    void closesBatchEvenWhenBodyFails() throws Exception {
        WorkflowRun run = runPipeline("applitools { error 'tests blew up' }", Result.FAILURE);

        j.assertLogContains("tests blew up", run);
        assertThat(eyes.closeRequests(), hasSize(1));
    }

    @Test
    void nestedBlocksShareOneBatchAndCloseOnce() throws Exception {
        WorkflowRun run = runPipeline(
                """
                applitools {
                  echo "outer=${env.APPLITOOLS_BATCH_ID}"
                  parallel a: {
                    applitools { echo "a=${env.APPLITOOLS_BATCH_ID}" }
                  }, b: {
                    applitools { echo "b=${env.APPLITOOLS_BATCH_ID}" }
                  }
                }
                """,
                Result.SUCCESS);

        String outer = logValue(run, "outer=");
        assertThat(logValue(run, "a="), is(outer));
        assertThat(logValue(run, "b="), is(outer));
        assertThat(eyes.closeRequests(), hasSize(1));
    }

    @Test
    void overridesWinAndExpandVariables() throws Exception {
        WorkflowRun run = runPipeline(
                """
                applitools(batchId: 'fixed-${BUILD_NUMBER}', batchName: 'Nightly', branchName: 'acme/web/feature',
                           parentBranchName: 'acme/web/main', closeBatch: false) {
                  echo "id=${env.APPLITOOLS_BATCH_ID} name=${env.APPLITOOLS_BATCH_NAME}"
                  echo "branch=${env.APPLITOOLS_BRANCH_NAME} parent=${env.APPLITOOLS_PARENT_BRANCH_NAME}"
                }
                """,
                Result.SUCCESS);

        j.assertLogContains("id=fixed-1 name=Nightly", run);
        j.assertLogContains("branch=acme/web/feature parent=acme/web/main", run);
        j.assertLogContains("closeBatch: false", run);
        assertThat(eyes.closeRequests(), empty());
    }

    @Test
    void unknownBatchIsReportedNotFailed() throws Exception {
        eyes.unknownBatches.add("nothing-ran");
        WorkflowRun run = runPipeline("applitools(batchId: 'nothing-ran') { echo 'no tests' }", Result.SUCCESS);

        j.assertLogContains("No Eyes results for batch nothing-ran", run);
    }

    @Test
    void missingCredentialFailsWithGuidance() throws Exception {
        WorkflowRun run = runPipeline("applitools(credentialsId: 'nope') { echo 'unreachable' }", Result.FAILURE);

        j.assertLogContains("No Secret text credential with ID 'nope'", run);
        j.assertLogNotContains("unreachable", run);
        assertThat(eyes.requests, empty());
    }

    @Test
    void freestyleBuildEnvironment() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildWrappersList().add(new ApplitoolsBuildWrapper());
        AtomicReference<EnvVars> seen = new AtomicReference<>();
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws java.io.IOException, InterruptedException {
                seen.set(build.getEnvironment(listener));
                return true;
            }
        });

        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        assertThat(seen.get().get("APPLITOOLS_API_KEY"), is(API_KEY));
        String batchId = seen.get().get("APPLITOOLS_BATCH_ID");
        assertThat(
                eyes.closeRequests().stream().map(FakeEyesServer.Request::path).toList(),
                contains("/api/sessions/batches/" + batchId + "/close/bypointerid"));
        j.assertLogContains("[Applitools] Closed batch", build);
    }

    @Test
    void freestyleConfigRoundtrip() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        ApplitoolsBuildWrapper wrapper = new ApplitoolsBuildWrapper();
        wrapper.setCredentialsId(ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID);
        wrapper.setBatchName("Nightly");
        wrapper.setCloseBatch(false);
        project.getBuildWrappersList().add(wrapper);

        project = j.configRoundtrip(project);

        j.assertEqualDataBoundBeans(wrapper, project.getBuildWrappersList().get(ApplitoolsBuildWrapper.class));
    }

    private static String logValue(WorkflowRun run, String prefix) throws java.io.IOException {
        return run.getLog(1000).stream()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line starting with " + prefix));
    }

    @Test
    void resultsAreFetchedGzipped() throws Exception {
        eyes.results("b1", 3, 0, 0, 1, 0);
        WorkflowRun run = runPipeline("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        j.assertLogContains("Passed: 3 passed", run); // parsed from a compressed response
        assertThat(eyes.gzipRequests.get() > 0, is(true));
    }

    @Test
    void passedResultsAreRecordedAndShownOnBuildPage() throws Exception {
        eyes.results("b1", 3, 0, 0, 1, 0);
        WorkflowRun run = runPipeline(
                """
                applitools(batchId: 'b1') { echo 'tests' }
                def r = applitoolsResults()
                echo "status=${r.status} total=${r.total} new=${r.new} url=${r.url}"
                """,
                Result.SUCCESS);

        j.assertLogContains("[Applitools] Passed: 3 passed (1 new baselines)", run);
        j.assertLogContains("status=PASSED total=3 new=1 url=https://eyes.example/app/test-results/123", run);
        ApplitoolsResultsAction action = run.getAction(ApplitoolsResultsAction.class);
        assertThat(action.getLatest().getTests().get(0).getEnvironment(), is("Chrome 155.0 on Linux 1280×800"));
        assertThat(action.getUrlName(), is("https://eyes.example/app/test-results/123"));
        String page = j.createWebClient().getPage(run).asNormalizedText();
        assertThat(page, containsString("Applitools Eyes: Passed"));
        assertThat(page, containsString("Chrome 155.0 on Linux"));
    }

    @Test
    void changesLeaveTheBuildGreenByDefault() throws Exception {
        eyes.results("b1", 1, 0, 2, 0, 0);
        WorkflowRun run = runPipeline(
                """
                applitools(batchId: 'b1') { echo 'tests' }
                echo "after block: ${applitoolsResults().status}"
                """,
                Result.SUCCESS);

        j.assertLogContains("Changes to review: 2 of 3 with visual changes to review", run);
        j.assertLogContains("after block: CHANGES", run); // the pipeline keeps going
    }

    @Test
    void changesCanMarkTheBuildUnstable() throws Exception {
        eyes.results("b1", 1, 0, 2, 0, 0);
        runPipeline("applitools(batchId: 'b1', onChanges: 'UNSTABLE') { echo 'tests' }", Result.UNSTABLE);
    }

    @Test
    void changesCanFailTheBuild() throws Exception {
        eyes.results("b1", 1, 0, 2, 0, 0);
        WorkflowRun run = runPipeline(
                """
                applitools(batchId: 'b1', onChanges: 'FAILURE') { echo 'tests' }
                echo 'unreachable'
                """,
                Result.FAILURE);

        j.assertLogContains("Changes to review", run);
        j.assertLogNotContains("unreachable", run);
    }

    @Test
    void changesCanBeIgnored() throws Exception {
        eyes.results("b1", 1, 0, 2, 0, 0);
        runPipeline("applitools(batchId: 'b1', onChanges: 'SUCCESS') { echo 'tests' }", Result.SUCCESS);
    }

    @Test
    void abortedTestsFailTheBuild() throws Exception {
        eyes.results("b1", 0, 4, 0, 4, 4);
        WorkflowRun run = runPipeline("applitools(batchId: 'b1') { echo 'tests' }", Result.FAILURE);

        j.assertLogContains("Failed: 4 of 4 failed (4 aborted before any checkpoint reached Eyes)", run);
    }

    @Test
    void waitsForRunningTests() throws Exception {
        eyes.results("b1", 2, 0, 0, 0, 0);
        eyes.runningPolls.put("b1", new java.util.concurrent.atomic.AtomicInteger(3));
        WorkflowRun run = runPipeline("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        j.assertLogContains("Waiting for 3 test(s) to finish in Eyes", run);
        j.assertLogContains("Passed: 2 passed", run);
    }

    @Test
    void waitForResultsFalseSkipsCollection() throws Exception {
        eyes.results("b1", 1, 0, 2, 0, 0);
        WorkflowRun run = runPipeline(
                """
                applitools(batchId: 'b1', waitForResults: false) { echo 'tests' }
                echo "results=${applitoolsResults()}"
                """,
                Result.SUCCESS);

        j.assertLogContains("results=null", run);
        assertThat(eyes.requests.stream().filter(r -> r.method().equals("GET")).toList(), empty());
    }

    @Test
    void eyesOutageOnlyWarns() throws Exception {
        WorkflowRun run;
        try (FakeEyesServer down = new FakeEyesServer()) {
            ApplitoolsGlobalConfiguration.get().setServerUrl(down.url());
        }
        run = runPipeline("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        j.assertLogContains("WARNING: could not close batch b1", run);
    }

    private void addSecret(String id, String value) throws java.io.IOException {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, id, "", Secret.fromString(value)));
        SystemCredentialsProvider.getInstance().save();
    }

    @Test
    void runKeyOnlyGetsReadKeyHint() throws Exception {
        eyes.requiredReadKey = READ_KEY;
        eyes.results("b1", 1, 0, 0, 0, 0);
        WorkflowRun run = runPipeline("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        j.assertLogContains("[Applitools] Closed batch b1", run); // run key still closes
        j.assertLogContains("Eyes refused to share results for batch b1 (HTTP 403)", run);
        j.assertLogContains("'applitools-read-api-key'", run);
    }

    @Test
    void conventionalReadKeyIsUsedAutomatically() throws Exception {
        addSecret(ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID, READ_KEY);
        eyes.requiredReadKey = READ_KEY;
        eyes.results("b1", 2, 0, 0, 0, 0);
        WorkflowRun run = runPipeline("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        j.assertLogContains("Passed: 2 passed", run);
        assertThat(eyes.closeRequests().get(0).apiKeyHeader(), is(API_KEY));
        assertThat(
                eyes.requests.stream()
                        .filter(r -> r.method().equals("GET"))
                        .map(FakeEyesServer.Request::apiKeyHeader)
                        .distinct()
                        .toList(),
                contains(READ_KEY));
    }

    @Test
    void explicitReadKey() throws Exception {
        addSecret("team-read-key", READ_KEY);
        eyes.requiredReadKey = READ_KEY;
        eyes.results("b1", 2, 0, 0, 0, 0);
        WorkflowRun run = runPipeline(
                "applitools(batchId: 'b1', readCredentialsId: 'team-read-key') { echo 'tests' }", Result.SUCCESS);

        j.assertLogContains("Passed: 2 passed", run);
    }

    @Test
    void missingExplicitReadKeyFallsBackToRunKey() throws Exception {
        eyes.results("b1", 2, 0, 0, 0, 0);
        WorkflowRun run = runPipeline(
                "applitools(batchId: 'b1', readCredentialsId: 'not-added-yet') { echo 'tests' }", Result.SUCCESS);

        j.assertLogContains("no Secret text credential with ID 'not-added-yet'; reading results with the run key", run);
        j.assertLogContains("Passed: 2 passed", run);
    }

    @Test
    void readKeyFromGlobalConfig() throws Exception {
        addSecret("global-read", READ_KEY);
        ApplitoolsGlobalConfiguration.get().setReadCredentialsId("global-read");
        eyes.requiredReadKey = READ_KEY;
        eyes.results("b1", 2, 0, 0, 0, 0);
        WorkflowRun run = runPipeline("applitools(batchId: 'b1') { echo 'tests' }", Result.SUCCESS);

        j.assertLogContains("Passed: 2 passed", run);
    }

    @Test
    void verdictIsLoggedEvenWhenTestsAlreadyFailed() throws Exception {
        eyes.results("b1", 0, 4, 0, 4, 4);
        WorkflowRun run = runPipeline(
                "applitools(batchId: 'b1') { error 'script returned exit code 1' }", Result.FAILURE);

        j.assertLogContains("script returned exit code 1", run);
        j.assertLogContains("Failed: 4 of 4 failed (4 aborted before any checkpoint reached Eyes)", run);
    }

    @Test
    void freestyleDesignReviewChoicesRoundtrip() throws Exception {
        for (Boolean choice : new Boolean[] {null, true, false}) {
            FreeStyleProject project = j.createFreeStyleProject();
            ApplitoolsBuildWrapper wrapper = new ApplitoolsBuildWrapper();
            wrapper.setDesignReview(choice);
            wrapper.setDesigners("dana@example.com");
            project.getBuildWrappersList().add(wrapper);

            project = j.configRoundtrip(project);

            j.assertEqualDataBoundBeans(wrapper, project.getBuildWrappersList().get(ApplitoolsBuildWrapper.class));
        }
    }
}
