package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;

/**
 * Reads whether a pull request is still open, or merged, from the SCM host. Used before the plugin stops following a
 * PR whose job disappeared, so a merge whose webhook was missed still gets its baselines merged.
 */
public abstract class PullRequestStates implements ExtensionPoint {

    /** @return the PR's state, or {@code null} if this implementation doesn't handle the build's SCM */
    @CheckForNull
    public abstract GitHubRestClient.PullState state(@NonNull Run<?, ?> run, @NonNull String repo, @NonNull String number)
            throws Exception;

    @CheckForNull
    public static GitHubRestClient.PullState of(@NonNull Run<?, ?> run, @NonNull String repo, @NonNull String number)
            throws Exception {
        for (PullRequestStates impl : ExtensionList.lookup(PullRequestStates.class)) {
            GitHubRestClient.PullState state = impl.state(run, repo, number);
            if (state != null) {
                return state;
            }
        }
        return null;
    }
}
