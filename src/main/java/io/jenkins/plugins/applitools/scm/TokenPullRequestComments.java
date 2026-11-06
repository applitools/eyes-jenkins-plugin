package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import java.io.IOException;

/** The plugin's PR comment and label for builds using {@code githubCredentialsId}. */
@Extension(ordinal = 100)
public class TokenPullRequestComments extends PullRequestComments {

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
        GitHubConnectionAction github = run.getAction(GitHubConnectionAction.class);
        if (github == null || !repo.equalsIgnoreCase(github.getRepo())) {
            return false;
        }
        String token = GitHubRestClient.token(run, github.getCredentialsId());
        if (token == null) {
            throw new IOException("no token credential '" + github.getCredentialsId() + "'");
        }
        GitHubRestClient client = new GitHubRestClient(github.getApiUrl(), token);
        client.upsertComment(repo, number, marker, body);
        client.setLabel(repo, number, label, labeled);
        return true;
    }
}
