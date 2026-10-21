package io.jenkins.plugins.applitools;

import hudson.model.Result;

/**
 * What unreviewed visual changes do to the Jenkins build result. The gate is the Applitools PR results, not the
 * build: changes awaiting review aren't a failed run, so by default the build stays green (anything else would make
 * Jenkins' own PR results read as failing). Real failures (rejected or aborted tests, missing results) still fail it.
 */
public enum ChangesPolicy {
    /** Mark the build unstable (yellow): Jenkins' own PR results then show as failing. */
    UNSTABLE(Result.UNSTABLE),
    /** Fail the build at the end of the {@code applitools} block. */
    FAILURE(Result.FAILURE),
    /** Leave the build result alone (default). */
    SUCCESS(Result.SUCCESS);

    private final Result result;

    ChangesPolicy(Result result) {
        this.result = result;
    }

    public Result getResult() {
        return result;
    }
}
