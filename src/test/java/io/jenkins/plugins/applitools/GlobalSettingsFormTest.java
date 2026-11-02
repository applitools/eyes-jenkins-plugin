package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.scm.CommitStatusPublisher;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Manage Jenkins → System: the grouped Applitools form shows its sections and saves every value. */
@WithJenkins
class GlobalSettingsFormTest {

    @Test
    void everyValueSurvivesSavingTheForm(JenkinsRule j) throws Exception {
        for (String id : new String[] {"run-key", "read-key", "merge-key"}) {
            SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, id, "", Secret.fromString(id + "-123")));
        }
        SystemCredentialsProvider.getInstance().save();
        ApplitoolsGlobalConfiguration config = ApplitoolsGlobalConfiguration.get();
        config.setServerUrl("https://eyes.example.com");
        config.setCredentialsId("run-key");
        config.setReadCredentialsId("read-key");
        config.setMergeCredentialsId("merge-key");
        config.setGithubReporting(CommitStatusPublisher.Mode.STATUSES);
        config.setDesignReviewCheck(false);
        config.setDesigners("team:Tim's Eyes Org\nlead@example.com");
        config.setDesignReviewLabel("needs-design");
        config.setDesignReviewByDefault(true);
        config.setMergeBaselines(false);
        config.setFollowBranchBuilds(true);
        config.setReviewPollSeconds(20);
        config.setReviewSlowAfterMinutes(45);
        config.setReviewSlowPollMinutes(3);
        config.setReviewSlowestAfterHours(12);
        config.setReviewSlowestPollMinutes(20);
        config.setSettledRecheckMinutes(15);
        config.setFollowReviewsDays(30);

        j.configRoundtrip();

        assertThat(config.getServerUrl(), is("https://eyes.example.com"));
        assertThat(config.getCredentialsId(), is("run-key"));
        assertThat(config.getReadCredentialsId(), is("read-key"));
        assertThat(config.getMergeCredentialsId(), is("merge-key"));
        assertThat(config.getGithubReporting(), is(CommitStatusPublisher.Mode.STATUSES));
        assertThat(config.isDesignReviewCheck(), is(false));
        assertThat(ApplitoolsGlobalConfiguration.parseDesigners(config.getDesigners()),
                is(java.util.List.of("team:Tim's Eyes Org", "lead@example.com")));
        assertThat(config.getDesignReviewLabel(), is("needs-design"));
        assertThat(config.isDesignReviewByDefault(), is(true));
        assertThat(config.isMergeBaselines(), is(false));
        assertThat(config.isFollowBranchBuilds(), is(true));
        assertThat(config.getReviewPollSeconds(), is(20));
        assertThat(config.getReviewSlowAfterMinutes(), is(45));
        assertThat(config.getReviewSlowPollMinutes(), is(3));
        assertThat(config.getReviewSlowestAfterHours(), is(12));
        assertThat(config.getReviewSlowestPollMinutes(), is(20));
        assertThat(config.getSettledRecheckMinutes(), is(15));
        assertThat(config.getFollowReviewsDays(), is(30));
    }

    @Test
    void sectionsAreGrouped(JenkinsRule j) throws Exception {
        HtmlPage page = j.createWebClient().goTo("manage/configure");
        String text = page.asNormalizedText();
        int eyes = text.indexOf("Applitools Eyes");
        int keys = text.indexOf("API keys", eyes);
        int results = text.indexOf("Pull request results", keys);
        int design = text.indexOf("Design review", results);
        int baselines = text.indexOf("Baselines", design);

        assertThat(eyes >= 0 && keys > eyes && results > keys && design > results && baselines > design, is(true));
        assertThat(text, containsString("Automatic: checks with a GitHub App, commit statuses with a token"));
        assertThat(text, containsString("Run key"));
        assertThat(text, containsString("Merge key"));
    }
}
