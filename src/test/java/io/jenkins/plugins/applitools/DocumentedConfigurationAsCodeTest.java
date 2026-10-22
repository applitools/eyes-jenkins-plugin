package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.Node;
import hudson.plugins.git.GitSCM;
import hudson.security.ACL;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.github.GitHubPlugin;
import org.jenkinsci.plugins.github_branch_source.BranchDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubAppCredentials;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The jenkins.yaml files in docs/CONFIGURATION-AS-CODE.md load as written and set up what the page says. */
@WithJenkins
class DocumentedConfigurationAsCodeTest {

    private static final Path DOC = Path.of("docs/CONFIGURATION-AS-CODE.md");

    @TempDir
    Path dir;

    @Test
    void recommendedFileSetsUpMultibranchWithGitHubApp(JenkinsRule j) throws Exception {
        apply("recommended");

        assertCommonSetup();
        assertThat(CredentialsProvider.lookupCredentialsInItemGroup(
                        GitHubAppCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of())
                .get(0).getAppID(), is("123456"));
        assertThat(GitHubPlugin.configuration().getHookSecretConfigs().get(0).getCredentialsId(),
                is("github-webhook-secret"));

        WorkflowMultiBranchProject job = j.jenkins.getItemByFullName("my-app", WorkflowMultiBranchProject.class);
        assertThat(job, notNullValue());
        GitHubSCMSource source = (GitHubSCMSource) job.getSCMSources().get(0);
        assertThat(source.getRepoOwner(), is("my-org"));
        assertThat(source.getRepository(), is("my-app"));
        assertThat(source.getCredentialsId(), is("github-app"));
        assertThat(trait(source, BranchDiscoveryTrait.class).getStrategyId(), is(1));
        assertThat(trait(source, OriginPullRequestDiscoveryTrait.class).getStrategyId(), is(2));
        assertThat(source.getTraits().stream().noneMatch(ForkPullRequestDiscoveryTrait.class::isInstance),
                is(true));
        PeriodicFolderTrigger trigger = (PeriodicFolderTrigger) job.getTriggers().values().iterator().next();
        assertThat(trigger.getIntervalMillis(), is(60_000L));
    }

    @Test
    void tokenFileSetsUpPipelineJobWithToken(JenkinsRule j) throws Exception {
        apply("token");

        assertCommonSetup();
        StandardUsernamePasswordCredentials token = CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernamePasswordCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of())
                .get(0);
        assertThat(token.getId(), is("github-token"));
        assertThat(token.getPassword().getPlainText(), is("test-GITHUB_TOKEN"));

        WorkflowJob job = j.jenkins.getItemByFullName("my-app-visual-review", WorkflowJob.class);
        assertThat(job, notNullValue());
        CpsScmFlowDefinition definition = (CpsScmFlowDefinition) job.getDefinition();
        assertThat(definition.getScriptPath(), is("Jenkinsfile"));
        GitSCM git = (GitSCM) definition.getScm();
        assertThat(git.getUserRemoteConfigs().get(0).getUrl(), is("https://github.com/my-org/my-app.git"));
        assertThat(git.getUserRemoteConfigs().get(0).getCredentialsId(), is("github-token"));
    }

    /** What both files configure the same way. */
    private static void assertCommonSetup() {
        Jenkins jenkins = Jenkins.get();
        HudsonPrivateSecurityRealm realm = (HudsonPrivateSecurityRealm) jenkins.getSecurityRealm();
        assertThat(realm.getUser("admin"), notNullValue());
        assertThat(jenkins.getNumExecutors(), is(0));

        Node agent = jenkins.getNode("playwright");
        assertThat(agent, notNullValue());
        assertThat(agent.getLabelString(), is("playwright"));
        assertThat(agent.getNumExecutors(), is(2));
        var launcher = ((SlaveComputer) agent.toComputer()).getLauncher();
        assertThat(launcher, instanceOf(JNLPLauncher.class));
        assertThat(((JNLPLauncher) launcher).isWebSocket(), is(true));

        assertThat(CredentialsProvider.lookupCredentialsInItemGroup(
                                StringCredentials.class, jenkins, ACL.SYSTEM2, List.of())
                        .stream()
                        .filter(c -> c.getId().startsWith("applitools-"))
                        .map(StringCredentials::getId)
                        .sorted()
                        .toList(),
                is(List.of("applitools-api-key", "applitools-merge-api-key", "applitools-read-api-key")));
        assertThat(JenkinsLocationConfiguration.get().getUrl(), is("http://localhost:8080/"));
        assertThat(ApplitoolsGlobalConfiguration.get().getDesigners(), is("team:Design"));
    }

    private static <T> T trait(GitHubSCMSource source, Class<T> type) {
        return source.getTraits().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    /** Applies the YAML block marked {@code <!-- casc:name -->} in the doc, with placeholder secrets. */
    private void apply(String name) throws Exception {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("<!-- casc:" + name + " -->\\n```yaml\\n(.*?)```", Pattern.DOTALL)
                .matcher(doc);
        assertThat("block " + name + " in " + DOC, block.find(), is(true));

        Path pem = dir.resolve("github-app.pem");
        try (var in = getClass().getResourceAsStream("github-app-test.pem")) {
            Files.copy(in, pem);
        }
        String yaml = block.group(1)
                .replace("${readFile:/run/secrets/github-app.pem}", "${readFile:" + pem + "}")
                .replace("${GITHUB_APP_ID}", "123456")
                .replace("${JENKINS_URL}", "http://localhost:8080/");
        yaml = Pattern.compile("\\$\\{([A-Z_]+)}").matcher(yaml).replaceAll("test-$1");

        Path file = dir.resolve("jenkins.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        ConfigurationAsCode.get().configure(file.toString());
    }
}
