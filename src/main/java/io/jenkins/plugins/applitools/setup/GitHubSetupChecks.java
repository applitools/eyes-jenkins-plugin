package io.jenkins.plugins.applitools.setup;

import hudson.Extension;
import io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration;
import io.jenkins.plugins.applitools.review.LabelEventSubscriber;
import io.jenkins.plugins.applitools.scm.CommitStatusPublisher;
import io.jenkins.plugins.applitools.scm.GitHubCommitStatusPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import org.jenkinsci.plugins.github.GitHubPlugin;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;

/** GitHub: plugins, webhook signing, whether webhooks arrive, and how each GitHub job will report. */
@Extension(optional = true, ordinal = 50)
public class GitHubSetupChecks extends SetupCheck {

    @Override
    public List<SetupItem> run() {
        List<SetupItem> items = new ArrayList<>();
        boolean checksPlugin = Jenkins.get().getPlugin("github-checks") != null;
        items.add(checksPlugin
                ? SetupItem.ok("GitHub Checks plugin", "Installed.")
                : SetupItem.warning("GitHub Checks plugin", "Not installed, so results are posted as commit statuses"
                                + " even for GitHub App jobs.",
                        "Install GitHub Checks (Manage Jenkins → Plugins) for richer checks on PRs."));

        items.add(GitHubPlugin.configuration().getHookSecretConfigs().isEmpty()
                ? SetupItem.warning("Webhook signatures", "Jenkins accepts unsigned GitHub webhooks.",
                        "Manage Jenkins → System → GitHub → Advanced → Shared secrets → add your webhook secret.")
                : SetupItem.ok("Webhook signatures", "Jenkins only accepts webhooks signed with your secret."));

        long last = LabelEventSubscriber.getLastPullRequestEvent();
        if (last == 0) {
            items.add(SetupItem.warning("Pull request webhooks", "None received since Jenkins started. Label changes"
                            + " and merges only take effect when they arrive.",
                    "Open or label a PR, then reload. If nothing arrives, check the webhook URL ends in"
                            + " /github-webhook/ and Recent Deliveries in GitHub."));
        } else {
            long minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - last);
            String repo = LabelEventSubscriber.getLastPullRequestRepo();
            items.add(SetupItem.ok("Pull request webhooks", "Last one arrived "
                    + (minutes == 0 ? "less than a minute" : minutes + " minute" + (minutes == 1 ? "" : "s"))
                    + " ago" + (repo != null ? " (" + repo + ")" : "") + "."));
        }

        CommitStatusPublisher.Mode mode = ApplitoolsGlobalConfiguration.get().getGithubReporting();
        int jobs = 0;
        for (SCMSourceOwner owner : Jenkins.get().allItems(SCMSourceOwner.class)) {
            for (SCMSource source : owner.getSCMSources()) {
                if (!(source instanceof GitHubSCMSource github)) {
                    continue;
                }
                jobs++;
                String title = github.getRepoOwner() + "/" + github.getRepository();
                String job = " (job " + owner.getFullDisplayName() + ")";
                if (github.getCredentialsId() == null) {
                    items.add(SetupItem.problem(title, "No credentials" + job + ": Jenkins can't read pull requests"
                                    + " or post results.",
                            "Configure the job's GitHub source with a GitHub App (recommended) or a token."));
                    continue;
                }
                boolean app = GitHubCommitStatusPublisher.usesGitHubApp(source);
                boolean checks = mode == CommitStatusPublisher.Mode.CHECKS
                        || (mode == CommitStatusPublisher.Mode.AUTO && app && checksPlugin);
                items.add(SetupItem.ok(title, (app ? "GitHub App" : "Token") + job + ": results post as "
                        + (checks ? "checks." : "commit statuses.")));
            }
        }
        if (jobs == 0) {
            items.add(SetupItem.warning("GitHub jobs", "No multibranch jobs with a GitHub source.",
                    "Create a Multibranch Pipeline with a GitHub branch source (setup guide, step 5)."));
        }
        return items;
    }
}
