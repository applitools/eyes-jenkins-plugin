package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * What we know about the code a build is testing. Every field is optional; callers fall back gracefully.
 *
 * @param owner repository owner, e.g. {@code timhosey}
 * @param repository repository name, e.g. {@code my-app}
 * @param branch branch under test (for a PR, its source branch)
 * @param targetBranch PR target branch; {@code null} for branch builds
 * @param commitSha the commit as the SCM host knows it (for a PR, its head commit, never a synthetic merge)
 * @param changeId PR number; {@code null} for branch builds
 * @param changeTitle PR title
 */
public record ScmInfo(
        @CheckForNull String owner,
        @CheckForNull String repository,
        @CheckForNull String branch,
        @CheckForNull String targetBranch,
        @CheckForNull String commitSha,
        @CheckForNull String changeId,
        @CheckForNull String changeTitle) {

    public static final ScmInfo NONE = new ScmInfo(null, null, null, null, null, null, null);

    public boolean isPullRequest() {
        return changeId != null;
    }

    /** {@code owner/repo}, or {@code null} when either part is unknown. */
    @CheckForNull
    public String repoSlug() {
        return owner != null && repository != null ? owner + "/" + repository : null;
    }
}
