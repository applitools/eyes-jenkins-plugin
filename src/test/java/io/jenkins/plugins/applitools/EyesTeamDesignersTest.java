package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.ExtensionList;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.FakeEyesServer.FakeTest;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.setup.SetupCheck;
import io.jenkins.plugins.applitools.setup.SetupItem;
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

/** Designers given as an Eyes team ("team:Design") are expanded to the team's members. */
@WithJenkins
public class EyesTeamDesignersTest {

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
        Designers.clearCache();
        CHECKS.getPublishedChecks().clear();
        eyes = new FakeEyesServer();
        eyes.teams.put("Design", List.of("dana@example.com", "lee@example.com"));
        eyes.teams.put("Engineering", List.of("dev@example.com"));
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

    private void buildWithChangesThenAcceptedBy(String designers, String approver) throws Exception {
        eyes.results("b1", List.of(new FakeTest("s1", "unresolved")));
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "applitools(batchId: 'b1', designReview: true, designers: \"" + designers + "\") { echo 'tests' }",
                true));
        j.assertBuildStatus(Result.UNSTABLE, job.scheduleBuild2(0));
        eyes.results("b1", List.of(new FakeTest("s1", "passed")));
        eyes.approvals.put("s1", List.of(approver));
        ExtensionList.lookupSingleton(ReviewWatcher.class).execute(TaskListener.NULL);
    }

    private ChecksDetails design() {
        List<ChecksDetails> checks = CHECKS.getPublishedChecks().stream()
                .filter(c -> c.getName().orElse("").equals(DesignReview.CHECK_NAME))
                .toList();
        return checks.get(checks.size() - 1);
    }

    private static String title(ChecksDetails c) {
        return c.getOutput().orElseThrow().getTitle().orElseThrow();
    }

    @Test
    void teamMemberCanApprove() throws Exception {
        buildWithChangesThenAcceptedBy("team:Design", "Lee@Example.com");

        assertThat(design().getConclusion(), is(ChecksConclusion.SUCCESS));
        assertThat(title(design()), is("Approved by Lee@Example.com"));
    }

    @Test
    void nonMemberCannot() throws Exception {
        buildWithChangesThenAcceptedBy("team:design", "dev@example.com"); // team names are case-insensitive

        assertThat(title(design()), is("Waiting for a designer"));
        assertThat(design().getOutput().orElseThrow().getSummary().orElseThrow(),
                containsString("a designer (team:design) must approve"));
    }

    @Test
    void teamsAndEmailsMix() throws Exception {
        buildWithChangesThenAcceptedBy("team:Engineering, dana@example.com", "dana@example.com");

        assertThat(design().getConclusion(), is(ChecksConclusion.SUCCESS));
    }

    @Test
    void unknownTeamFailsClosed() throws Exception {
        buildWithChangesThenAcceptedBy("team:Desgin", "dana@example.com"); // typo

        assertThat(title(design()), is("Designers couldn't be determined"));
        assertThat(design().getOutput().orElseThrow().getSummary().orElseThrow(),
                containsString("Eyes team 'Desgin' not found"));
    }

    @Test
    void teamNamesMayContainSpaces() throws Exception {
        eyes.teams.put("Tim's Eyes Org", List.of("tim@example.com"));

        assertThat(ApplitoolsGlobalConfiguration.parseDesigners("team:Tim's Eyes Org, lead@example.com\nx@example.com"),
                is(List.of("team:Tim's Eyes Org", "lead@example.com", "x@example.com")));
        buildWithChangesThenAcceptedBy("team:Tim's Eyes Org", "tim@example.com");
        assertThat(design().getConclusion(), is(ChecksConclusion.SUCCESS));
    }

    @Test
    void teamLookupsAreCached() throws Exception {
        EyesClient client = new EyesClient(eyes.url(), "fake-key-0123456789");
        Designers.resolve(List.of("team:Design"), client);
        long before = eyes.requests.stream().filter(r -> r.path().startsWith("/api/admin/")).count();

        Designers.resolve(List.of("team:Design"), client);

        assertThat(eyes.requests.stream().filter(r -> r.path().startsWith("/api/admin/")).count(), is(before));
    }

    @Test
    void setupPageShowsResolvedTeam() throws Exception {
        ApplitoolsGlobalConfiguration.get().setDesigners("team:Design");

        SetupItem row = SetupCheck.runAll().stream()
                .filter(i -> i.getTitle().equals("Designers"))
                .findFirst()
                .orElseThrow();

        assertThat(row.getStatus(), is(SetupItem.Status.OK));
        assertThat(row.getDetail(), is("team:Design → dana@example.com, lee@example.com can approve design reviews."));
    }

    @Test
    void setupPageWarnsAboutUnknownTeam() throws Exception {
        ApplitoolsGlobalConfiguration.get().setDesigners("team:Nope");

        SetupItem row = SetupCheck.runAll().stream()
                .filter(i -> i.getTitle().equals("Designers"))
                .findFirst()
                .orElseThrow();

        assertThat(row.getStatus(), is(SetupItem.Status.WARNING));
        assertThat(row.getDetail(), containsString("Eyes team 'Nope' not found"));
    }
}
