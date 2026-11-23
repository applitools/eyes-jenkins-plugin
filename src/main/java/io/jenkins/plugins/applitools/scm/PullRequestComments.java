package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;

/**
 * Keeps the plugin's own comment and label on a pull request, through whatever connection a build of the same
 * repository has: the PR's build, or a later build of its target branch (once the PR's job is gone).
 */
public abstract class PullRequestComments implements ExtensionPoint {

    /**
     * Posts {@code body}, or edits the comment containing {@code marker}, and adds or removes {@code label}.
     *
     * @param run any build of the repository whose connection can be used
     * @return false if this implementation doesn't handle the build's SCM
     */
    public abstract boolean update(
            @NonNull Run<?, ?> run,
            @NonNull String repo,
            @NonNull String number,
            @NonNull String marker,
            @NonNull String body,
            @NonNull String label,
            boolean labeled)
            throws Exception;

    /**
     * Updates the comment and label with the first implementation that knows the build's SCM.
     *
     * @return false if none does
     */
    public static boolean of(
            @NonNull Run<?, ?> run,
            @NonNull String repo,
            @NonNull String number,
            @NonNull String marker,
            @NonNull String body,
            @NonNull String label,
            boolean labeled)
            throws Exception {
        for (PullRequestComments impl : ExtensionList.lookup(PullRequestComments.class)) {
            if (impl.update(run, repo, number, marker, body, label, labeled)) {
                return true;
            }
        }
        return false;
    }
}
