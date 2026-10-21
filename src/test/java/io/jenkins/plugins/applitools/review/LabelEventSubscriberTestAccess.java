package io.jenkins.plugins.applitools.review;

import java.util.concurrent.TimeUnit;

/** Test hooks for label handling, which normally runs on a timer thread after a webhook. */
public final class LabelEventSubscriberTestAccess {

    private LabelEventSubscriberTestAccess() {}

    /** Delivers a label change and waits for its re-evaluation to finish. */
    public static void handleAndWait(String repoSlug, String number, String label, boolean added) throws Exception {
        for (var future : ReviewEvents.labelChanged(repoSlug, number, label, added)) {
            future.get(30, TimeUnit.SECONDS);
        }
    }

    /** Delivers a "closed" PR event and waits for any baseline merge to finish. */
    public static void mergedAndWait(String repoSlug, String number, boolean merged, String head, String base)
            throws Exception {
        for (var future : ReviewEvents.closed(repoSlug, number, merged, head, base)) {
            future.get(30, TimeUnit.SECONDS);
        }
    }

    /** Runs the raw-payload path and waits for the merge it schedules; false if it wasn't a closed-PR event. */
    public static boolean handleMergedPayloadAndWait(String payload) throws Exception {
        var work = ReviewEvents.handleMerged(payload);
        if (work == null) {
            return false;
        }
        for (var future : work) {
            future.get(30, TimeUnit.SECONDS);
        }
        return true;
    }

    /** A copy of a review as if its build were the given pull request (plain test pipelines aren't PRs). */
    public static PendingReview asPullRequest(PendingReview r, String repoSlug, String number) {
        return new PendingReview(
                r.getJobFullName(),
                r.getBuildNumber(),
                r.getBatchId(),
                r.getServerUrl(),
                r.getRunCredentialsId(),
                r.getReadCredentialsId(),
                repoSlug,
                number,
                r.isLabelDriven() ? null : r.isDesignReviewRequired(),
                false,
                r.getLabel(),
                r.getDesigners(),
                r.isDesignCheck(),
                r.getChangedTestIds(),
                r.isLabelPresent());
    }
}
