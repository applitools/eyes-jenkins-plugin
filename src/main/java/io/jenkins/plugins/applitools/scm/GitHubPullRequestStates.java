package io.jenkins.plugins.applitools.scm;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import java.util.Set;
import java.util.TreeSet;
import jenkins.scm.api.SCMSource;
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
        SCMSource source = SCMSource.SourceByItem.findSource(run.getParent());
        if (!(source instanceof GitHubSCMSource github)) {
            return null;
        }
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
}
