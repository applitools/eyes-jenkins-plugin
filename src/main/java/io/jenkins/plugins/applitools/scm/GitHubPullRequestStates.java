package io.jenkins.plugins.applitools.scm;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import java.util.Set;
import java.util.TreeSet;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import org.jenkinsci.plugins.github_branch_source.Connector;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;

/** PR state with a multibranch job's own GitHub credentials. */
@Extension(optional = true)
public class GitHubPullRequestStates extends PullRequestStates {

    @Override
    public GitHubRestClient.PullState state(@NonNull Run<?, ?> run, @NonNull String repo, @NonNull String number)
            throws Exception {
        GitHubSCMSource github = source(run, repo);
        if (github == null) {
            return null;
        }
        SCMSource source = github;
        StandardCredentials credentials = Connector.lookupScanCredentials(
                source.getOwner(), github.getApiUri(), github.getCredentialsId(), github.getRepoOwner());
        GitHub gh = Connector.connect(github.getApiUri(), credentials);
        try {
            GHPullRequest pr = gh.getRepository(repo).getPullRequest(Integer.parseInt(number));
            Set<String> labels = new TreeSet<>();
            for (GHLabel label : pr.getLabels()) {
                labels.add(label.getName());
            }
            return new GitHubRestClient.PullState(
                    pr.getState() == GHIssueState.OPEN,
                    pr.isMerged(),
                    pr.getHead().getRef(),
                    pr.getBase().getRef(),
                    labels,
                    null);
        } finally {
            Connector.release(gh);
        }
    }

    /**
     * The GitHub source the build's job came from. Multibranch unlinks a closed PR's job from its source (it becomes a
     * "dead" branch) as soon as it notices, which is exactly when the PR's state matters, so fall back to the parent
     * project's source for this repository.
     */
    static GitHubSCMSource source(Run<?, ?> run, String repo) {
        if (SCMSource.SourceByItem.findSource(run.getParent()) instanceof GitHubSCMSource github) {
            return github;
        }
        if (run.getParent().getParent() instanceof SCMSourceOwner owner) {
            for (SCMSource candidate : owner.getSCMSources()) {
                if (candidate instanceof GitHubSCMSource github
                        && repo.equalsIgnoreCase(github.getRepoOwner() + "/" + github.getRepository())) {
                    return github;
                }
            }
        }
        return null;
    }
}
