package io.jenkins.plugins.applitools;

import hudson.model.Result;

/**
 * What unreviewed visual changes do to the Jenkins build result. The merge gate is the GitHub check, not the build
 * result, so the default only flags the build instead of failing it.
 */
public enum ChangesPolicy {
    /** Mark the build unstable (yellow) and carry on. */
    UNSTABLE(Result.UNSTABLE),
    /** Fail the build at the end of the {@code applitools} block. */
    FAILURE(Result.FAILURE),
    /** Leave the build result alone. */
    SUCCESS(Result.SUCCESS);

    private final Result result;

    ChangesPolicy(Result result) {
        this.result = result;
    }

    public Result getResult() {
        return result;
    }
}
