package io.jenkins.plugins.applitools.setup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration;
import io.jenkins.plugins.applitools.FakeEyesServerAccess;
import java.util.List;
import io.jenkins.plugins.applitools.review.MergeHistory;
import io.jenkins.plugins.applitools.review.UnmergedBaselines;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTableRow;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SetupPageTest {

    private static final String RUN = "run-key-0123456789";
    private static final String READ = "read-key-0123456789";
    private static final String MERGE = "merge-key-0123456789";

    private JenkinsRule j;
    private FakeEyesServerAccess eyes;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        eyes = new FakeEyesServerAccess();
        eyes.requireReadKey(READ);
        eyes.requireMergeKey(MERGE);
        ApplitoolsGlobalConfiguration.get().setServerUrl(eyes.url());
        add(ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID, RUN);
    }

    @AfterEach
    void tearDown() {
        eyes.close();
    }

    private void add(String id, String value) throws java.io.IOException {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, id, "", Secret.fromString(value)));
        SystemCredentialsProvider.getInstance().save();
    }

    private SetupItem row(String title) throws InterruptedException {
        return SetupCheck.runAll().stream()
                .filter(i -> i.getTitle().equals(title))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + title));
    }

    @Test
    void allKeysValid() throws Exception {
        add(ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID, READ);
        add(ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID, MERGE);

        assertThat(row("Eyes server").getStatus(), is(SetupItem.Status.OK));
        assertThat(row("Eyes run key").getStatus(), is(SetupItem.Status.OK));
        assertThat(row("Eyes read key").getDetail(), containsString("allowed to read results"));
        assertThat(row("Eyes merge key").getDetail(), containsString("allowed to merge baselines"));
    }

    @Test
    void missingKeysAreWarningsWithTheFix() throws Exception {
        SetupItem read = row("Eyes read key");
        assertThat(read.getStatus(), is(SetupItem.Status.WARNING));
        assertThat(read.getFix(), containsString("applitools-read-api-key"));
        assertThat(row("Eyes merge key").getFix(), containsString("merge option"));
    }

    @Test
    void wrongKindOfKeyIsAProblem() throws Exception {
        add(ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID, "a-write-key-not-read");
        add(ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID, READ); // read keys can't merge

        assertThat(row("Eyes read key").getStatus(), is(SetupItem.Status.PROBLEM));
        assertThat(row("Eyes read key").getDetail(), containsString("won't let it read results"));
        assertThat(row("Eyes merge key").getDetail(), containsString("won't let it merge baselines"));
    }

    @Test
    void unrecognizedRunKey() throws Exception {
        eyes.invalid(RUN);

        assertThat(row("Eyes run key").getStatus(), is(SetupItem.Status.PROBLEM));
        assertThat(row("Eyes run key").getDetail(), containsString("doesn't recognize"));
    }

    @Test
    void missingRunKeyStopsEarly() throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().clear();

        assertThat(row("Eyes run key").getStatus(), is(SetupItem.Status.PROBLEM));
    }

    @Test
    void unreachableServer() throws Exception {
        ApplitoolsGlobalConfiguration.get().setServerUrl("http://127.0.0.1:9");

        assertThat(row("Eyes server").getStatus(), is(SetupItem.Status.PROBLEM));
    }

    @Test
    void gitHubRowsWithoutJobsOrWebhooks() throws Exception {
        assertThat(row("GitHub jobs").getStatus(), is(SetupItem.Status.INFO));
        assertThat(row("Pull request webhooks").getStatus(), is(SetupItem.Status.WARNING));
        assertThat(row("Webhook signatures").getStatus(), is(SetupItem.Status.WARNING));
    }

    @Test
    void pageRendersForAdmins() throws Exception {
        add(ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID, READ);
        HtmlPage start = j.createWebClient().goTo("manage/applitools-setup");
        // Opening the page runs nothing; the button does (a POST).
        assertThat(start.getByXPath("//table[@id='applitools-setup']").isEmpty(), is(true));
        HtmlPage page = j.submit(start.getFormByName("run"));

        String text = page.asNormalizedText();
        assertThat(text, containsString("Applitools setup"));
        assertThat(text, containsString("Eyes read key"));
        assertThat(text, containsString("allowed to read results"));
        List<?> rows = page.getByXPath("//table[@id='applitools-setup']/tbody/tr");
        assertThat(rows.size() > 5, is(true));
    }

    @Test
    void recentBaselineMergesAreListed() throws Exception {
        HtmlPage empty = j.createWebClient().goTo("manage/applitools-setup");
        assertThat(empty.asNormalizedText(), containsString("No pull request has merged"));

        MergeHistory.get().add(new MergeHistory.Entry("acme/web", "13", "acme/web/feature", "acme/web/main",
                MergeHistory.Outcome.MERGED, "Baselines merged into main",
                "2 baseline changes from `acme/web/feature` now apply to `acme/web/main`.",
                System.currentTimeMillis() - 60_200, "job/web/job/PR-13/4/", "web » PR-13 #4"));
        MergeHistory.get().add(new MergeHistory.Entry("acme/web", "14", "acme/web/fix", "acme/web/main",
                MergeHistory.Outcome.SKIPPED, "Baseline merge skipped: build gone",
                "Build web/PR-14 #2 was deleted before the merge could run.",
                System.currentTimeMillis(), null, "web/PR-14 #2"));

        HtmlPage page = j.createWebClient().goTo("manage/applitools-setup");
        List<HtmlTableRow> rows = page.getByXPath("//table[@id='applitools-merges']/tbody/tr");
        assertThat(rows.size(), is(2));
        // Newest first.
        assertThat(rows.get(0).getAttribute("data-outcome"), is("SKIPPED"));
        assertThat(rows.get(0).asNormalizedText(), containsString("acme/web#14"));
        assertThat(rows.get(0).asNormalizedText(), containsString("Baseline merge skipped: build gone"));
        assertThat(rows.get(0).getElementsByTagName("a").isEmpty(), is(true)); // no build to link to
        assertThat(rows.get(1).asNormalizedText(), matchesPattern("(?s)1 min \\d+ sec ago.*"));
        assertThat(rows.get(1).asNormalizedText(), containsString("acme/web/feature → acme/web/main"));
        assertThat(rows.get(1).asNormalizedText(), containsString("2 baseline changes from acme/web/feature"));
        HtmlAnchor link = (HtmlAnchor) rows.get(1).getElementsByTagName("a").get(0);
        assertThat(link.getHrefAttribute(), containsString("/job/web/job/PR-13/4/"));
        assertThat(link.asNormalizedText(), is("acme/web#13"));
    }

    @Test
    void unmergedBaselinesAreListed() throws Exception {
        HtmlPage empty = j.createWebClient().goTo("manage/applitools-setup");
        assertThat(empty.asNormalizedText(), containsString("None: every merged pull request's baselines are merged."));

        UnmergedBaselines.Item conflict = UnmergedBaselines.get().failed("acme/web", "13", "acme/web/feature",
                "acme/web/main", MergeHistory.Outcome.CONFLICTS, "2 baseline conflicts",
                "The same checkpoints changed on `main` too. Resolve them in Eyes under **Compare & merge branches**.");
        UnmergedBaselines.get().commented(conflict, null);
        UnmergedBaselines.Item refused = UnmergedBaselines.get().failed("acme/web", "14", "acme/web/fix",
                "acme/web/main", MergeHistory.Outcome.REFUSED, "Eyes refused the baseline merge", "HTTP 403.");
        UnmergedBaselines.get().commented(refused, "GitHub refused the comment or label (HTTP 403).");

        HtmlPage page = j.createWebClient().goTo("manage/applitools-setup");
        List<HtmlTableRow> rows = page.getByXPath("//table[@id='applitools-unmerged']/tbody/tr");
        assertThat(rows.size(), is(2));
        assertThat(rows.get(0).getAttribute("data-outcome"), is("CONFLICTS"));
        assertThat(rows.get(0).asNormalizedText(), containsString("acme/web#13"));
        assertThat(rows.get(0).asNormalizedText(), containsString("acme/web/feature → acme/web/main"));
        assertThat(rows.get(0).asNormalizedText(), containsString("Resolve them in Eyes under Compare & merge"));
        assertThat(rows.get(0).asNormalizedText(), containsString("Posted, labeled"));
        assertThat(rows.get(1).asNormalizedText(), containsString("Not posted: GitHub refused the comment"));

        ApplitoolsGlobalConfiguration.get().setCommentOnUnmergedBaselines(false);
        HtmlPage off = j.createWebClient().goTo("manage/applitools-setup");
        assertThat(off.asNormalizedText(), containsString("Off (Manage Jenkins → System)"));
    }

    @Test
    void checksDontRunOnAGetRequest() throws Exception {
        var client = j.createWebClient();
        client.setThrowExceptionOnFailingStatusCode(false);
        var response = client.goTo("manage/applitools-setup/run").getWebResponse();
        assertThat(response.getStatusCode() == 405 || response.getStatusCode() == 404, is(true));
    }
}
