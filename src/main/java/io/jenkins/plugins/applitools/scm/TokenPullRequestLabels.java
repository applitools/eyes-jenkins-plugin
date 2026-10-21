package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import java.io.IOException;
import java.util.Set;

/** PR labels for builds using {@code githubCredentialsId}. */
@Extension(ordinal = 100)
public class TokenPullRequestLabels extends PullRequestLabels {

    @Override
    public Set<String> labels(@NonNull Run<?, ?> run, @NonNull ScmInfo scm) throws Exception {
        GitHubConnectionAction github = run.getAction(GitHubConnectionAction.class);
        String number = scm.changeId();
        if (github == null || number == null) {
            return null;
        }
        String token = GitHubRestClient.token(run, github.getCredentialsId());
        if (token == null) {
            throw new IOException("no token credential '" + github.getCredentialsId() + "'");
        }
        return new GitHubRestClient(github.getApiUrl(), token).pull(github.getRepo(), number).labels();
    }
}
