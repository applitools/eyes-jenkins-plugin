package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.applitools.scm.ScmInfo;
import java.util.UUID;

/**
 * The Eyes batch identity for one {@code applitools} block, derived from the build's SCM context. Explicit overrides
 * from the Jenkinsfile always win.
 *
 * <p>Branch names use the {@code owner/repo/branch} form so PR baselines branch off (and merge back into) the target
 * branch's baseline. TODO(spike): confirm this is the exact format the Eyes SCM integration expects.
 */
public record BatchSettings(
        @NonNull String batchId,
        @NonNull String batchName,
        @CheckForNull String branchName,
        @CheckForNull String parentBranchName,
        @CheckForNull String sequenceName) {

    /** User-supplied values; any may be null. */
    public record Overrides(
            @CheckForNull String batchId,
            @CheckForNull String batchName,
            @CheckForNull String branchName,
            @CheckForNull String parentBranchName) {

        public static final Overrides NONE = new Overrides(null, null, null, null);
    }

    @NonNull
    public static BatchSettings compute(
            @NonNull ScmInfo scm, @NonNull String jobFullName, int buildNumber, @NonNull Overrides overrides) {
        return compute(scm, jobFullName, buildNumber, overrides, false);
    }

    /**
     * @param fork the build is a pull request from a fork: its branch name is the fork's, which can match one of the
     *     repository's own branches (e.g. a fork's {@code main}), so it gets {@code owner/repo/pr-<number>} instead
     */
    @NonNull
    public static BatchSettings compute(
            @NonNull ScmInfo scm,
            @NonNull String jobFullName,
            int buildNumber,
            @NonNull Overrides overrides,
            boolean fork) {
        String slug = scm.repoSlug();
        String label = slug != null ? slug : jobFullName;
        String prefix = slug != null ? slug + "/" : "";

        String batchId = firstNonNull(
                overrides.batchId(),
                scm.commitSha(),
                // No commit to key on (e.g. a Freestyle job without Git): unique per block.
                UUID.randomUUID().toString());

        String defaultName;
        if (scm.isPullRequest()) {
            defaultName = label + " PR #" + scm.changeId() + (scm.changeTitle() != null ? ": " + scm.changeTitle() : "");
        } else if (scm.branch() != null) {
            defaultName = label + " " + scm.branch();
        } else {
            defaultName = jobFullName + " #" + buildNumber;
        }

        String ownBranch = fork && scm.isPullRequest()
                ? prefix + "pr-" + scm.changeId()
                : scm.branch() != null ? prefix + scm.branch() : null;
        String branchName = firstNonNull(overrides.branchName(), ownBranch);
        String parentBranchName = firstNonNull(
                overrides.parentBranchName(), scm.targetBranch() != null ? prefix + scm.targetBranch() : null);

        return new BatchSettings(
                batchId, firstNonNull(overrides.batchName(), defaultName), branchName, parentBranchName, label);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
