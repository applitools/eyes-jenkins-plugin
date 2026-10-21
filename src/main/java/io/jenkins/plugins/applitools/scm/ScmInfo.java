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

    /**
     * Applies values given explicitly on the step (e.g. from Generic Webhook Trigger variables), which win over
     * anything detected. {@code repo} is {@code owner/repo}; nulls keep the detected value.
     */
    @edu.umd.cs.findbugs.annotations.NonNull
    public ScmInfo withOverrides(
            @CheckForNull String repo,
            @CheckForNull String pullRequest,
            @CheckForNull String commit,
            @CheckForNull String branch,
            @CheckForNull String targetBranch) {
        String o = owner;
        String r = repository;
        if (repo != null && repo.contains("/")) {
            o = repo.substring(0, repo.indexOf('/'));
            r = repo.substring(repo.indexOf('/') + 1);
        }
        return new ScmInfo(
                o,
                r,
                branch != null ? branch : this.branch,
                targetBranch != null ? targetBranch : this.targetBranch,
                commit != null ? commit : commitSha,
                pullRequest != null ? pullRequest : changeId,
                changeTitle);
    }

    /** {@code owner/repo}, or {@code null} when either part is unknown. */
    @CheckForNull
    public String repoSlug() {
        return owner != null && repository != null ? owner + "/" + repository : null;
    }
}
