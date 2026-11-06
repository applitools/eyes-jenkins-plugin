package io.jenkins.plugins.applitools.scm;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import java.io.FileNotFoundException;
import org.jenkinsci.plugins.github_branch_source.Connector;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GitHub;

/**
 * The plugin's PR comment and label with the job's own GitHub credentials (e.g. its GitHub App). Needs "Pull
 * requests: read and write".
 */
@Extension(optional = true)
public class GitHubPullRequestComments extends PullRequestComments {

    @Override
    public boolean update(
            @NonNull Run<?, ?> run,
            @NonNull String repo,
            @NonNull String number,
            @NonNull String marker,
            @NonNull String body,
            @NonNull String label,
            boolean labeled)
            throws Exception {
        GitHubSCMSource github = GitHubPullRequestStates.source(run, repo);
        if (github == null) {
            return false;
        }
        StandardCredentials credentials = Connector.lookupScanCredentials(
                github.getOwner(), github.getApiUri(), github.getCredentialsId(), github.getRepoOwner());
        GitHub gh = Connector.connect(github.getApiUri(), credentials);
        try {
            GHIssue issue = gh.getRepository(repo).getIssue(Integer.parseInt(number));
            GHIssueComment existing = null;
            for (GHIssueComment comment : issue.listComments().withPageSize(100)) {
                if (comment.getBody() != null && comment.getBody().contains(marker)) {
                    existing = comment;
                    break;
                }
            }
            if (existing == null) {
                issue.comment(body);
            } else if (!body.equals(existing.getBody())) {
                existing.update(body);
            }
            if (labeled) {
                issue.addLabels(label);
            } else {
                try {
                    issue.removeLabel(label);
                } catch (FileNotFoundException e) {
                    // the label wasn't there: nothing to remove
                }
            }
            return true;
        } finally {
            Connector.release(gh);
        }
    }
}
