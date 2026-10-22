package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;

/** PR state for builds that report with a token ({@code githubCredentialsId}). */
@Extension(ordinal = 100)
public class TokenPullRequestStates extends PullRequestStates {

    @Override
    public GitHubRestClient.PullState state(@NonNull Run<?, ?> run, @NonNull String repo, @NonNull String number)
            throws Exception {
        GitHubConnectionAction github = run.getAction(GitHubConnectionAction.class);
        if (github == null) {
            return null;
        }
        String token = GitHubRestClient.token(run, github.getCredentialsId());
        return token != null ? new GitHubRestClient(github.getApiUrl(), token).pull(repo, number) : null;
    }
}
