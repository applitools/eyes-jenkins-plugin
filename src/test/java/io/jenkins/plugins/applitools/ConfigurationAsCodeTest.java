package io.jenkins.plugins.applitools;

import static io.jenkins.plugins.casc.misc.Util.getUnclassifiedRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.security.ACL;
import io.jenkins.plugins.applitools.scm.CommitStatusPublisher;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.github.GitHubPlugin;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.jupiter.api.Test;

/** The whole setup (credentials, GitHub webhook secret, Applitools settings) can be configured as code. */
@WithJenkinsConfiguredWithCode
class ConfigurationAsCodeTest {

    @Test
    @ConfiguredWithCode("jcasc-full.yml")
    void importsEverySetting(JenkinsConfiguredWithCodeRule j) {
        ApplitoolsGlobalConfiguration config = ApplitoolsGlobalConfiguration.get();
        assertThat(config.getServerUrl(), is("https://eyes.example.com"));
        assertThat(config.getCredentialsId(), is("applitools-api-key"));
        assertThat(config.getReadCredentialsId(), is("applitools-read-api-key"));
        assertThat(config.getMergeCredentialsId(), is("applitools-merge-api-key"));
        assertThat(config.getGithubReporting(), is(CommitStatusPublisher.Mode.STATUSES));
        assertThat(config.isDesignReviewCheck(), is(true));
        assertThat(config.getDesignReviewLabel(), is("needs-design"));
        assertThat(ApplitoolsGlobalConfiguration.parseDesigners(config.getDesigners()),
                is(List.of("team:Tim's Eyes Org", "lead@example.com")));
        assertThat(config.isDesignReviewByDefault(), is(true));
        assertThat(config.isMergeBaselines(), is(false));
        assertThat(config.isFollowBranchBuilds(), is(true));

        // ...and what the snippet sets up around it.
        List<StringCredentials> secrets = CredentialsProvider.lookupCredentialsInItemGroup(
                StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of());
        assertThat(secrets.stream().map(StringCredentials::getId).sorted().toList(), is(List.of(
                "applitools-api-key", "applitools-merge-api-key", "applitools-read-api-key",
                "github-webhook-secret")));
        var app = CredentialsProvider.lookupCredentialsInItemGroup(
                org.jenkinsci.plugins.github_branch_source.GitHubAppCredentials.class, Jenkins.get(), ACL.SYSTEM2,
                List.of());
        assertThat(app.get(0).getAppID(), is("123456"));
        assertThat(GitHubPlugin.configuration().getHookSecretConfigs().get(0).getCredentialsId(),
                is("github-webhook-secret"));
        assertThat(jenkins.model.JenkinsLocationConfiguration.get().getUrl(), is("https://jenkins.example.com/"));
    }

    @Test
    @ConfiguredWithCode("jcasc-full.yml")
    void exportsWhatItImports(JenkinsConfiguredWithCodeRule j) throws Exception {
        String exported = export();

        assertThat(exported, containsString("serverUrl: \"https://eyes.example.com\""));
        assertThat(exported, containsString("githubReporting: STATUSES"));
        assertThat(exported, containsString("mergeBaselines: false"));
        assertThat(exported, containsString("designReviewByDefault: true"));
        assertThat(exported, containsString("designReviewLabel: \"needs-design\""));
        assertThat(exported, containsString("designers: \"team:Tim's Eyes Org, lead@example.com\""));
        assertThat(exported, containsString("mergeCredentialsId: \"applitools-merge-api-key\""));
    }

    @Test
    @ConfiguredWithCode("jcasc-minimal.yml")
    void unsetValuesKeepTheirDefaults(JenkinsConfiguredWithCodeRule j) throws Exception {
        ApplitoolsGlobalConfiguration config = ApplitoolsGlobalConfiguration.get();
        assertThat(config.getServerUrl(), is(ApplitoolsGlobalConfiguration.DEFAULT_SERVER_URL));
        assertThat(config.getCredentialsId(), is(ApplitoolsGlobalConfiguration.DEFAULT_CREDENTIALS_ID));
        assertThat(config.getReadCredentialsId(), nullValue());
        assertThat(config.getGithubReporting(), is(CommitStatusPublisher.Mode.AUTO));
        assertThat(config.isDesignReviewCheck(), is(true));
        assertThat(config.isMergeBaselines(), is(true));
        assertThat(config.getDesigners(), is("team:Design"));

        assertThat(export(), not(containsString("readCredentialsId")));
    }

    private static String export() throws Exception {
        ConfigurationContext context = new ConfigurationContext(ConfiguratorRegistry.get());
        CNode applitools = getUnclassifiedRoot(context).get("applitools");
        return toYamlString(applitools);
    }
}
