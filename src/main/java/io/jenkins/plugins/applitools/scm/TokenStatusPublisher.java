package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import java.io.IOException;
import java.util.Locale;

/** Commit statuses for builds using {@code githubCredentialsId} (any job type, no GitHub plugins). */
@Extension(ordinal = 100)
public class TokenStatusPublisher extends CommitStatusPublisher {

    @Override
    public boolean handles(@NonNull Run<?, ?> run, @NonNull Mode mode) {
        return run.getAction(GitHubConnectionAction.class) != null;
    }

    @Override
    public void publish(
            @NonNull Run<?, ?> run,
            @NonNull String context,
            @NonNull State state,
            @NonNull String description,
            @CheckForNull String targetUrl)
            throws Exception {
        GitHubConnectionAction github = run.getAction(GitHubConnectionAction.class);
        if (github == null) {
            return;
        }
        String token = GitHubRestClient.token(run, github.getCredentialsId());
        if (token == null) {
            throw new IOException("no token credential '" + github.getCredentialsId() + "'");
        }
        new GitHubRestClient(github.getApiUrl(), token)
                .createStatus(github.getRepo(), github.getCommit(), state.name().toLowerCase(Locale.ROOT), targetUrl,
                        description, context);
    }
}
