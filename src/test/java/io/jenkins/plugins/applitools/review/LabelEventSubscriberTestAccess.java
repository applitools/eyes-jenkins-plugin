package io.jenkins.plugins.applitools.review;

import java.util.concurrent.TimeUnit;

/** Test hooks for label handling, which normally runs on a timer thread after a webhook. */
public final class LabelEventSubscriberTestAccess {

    private LabelEventSubscriberTestAccess() {}

    /** Delivers a label change and waits for its re-evaluation to finish. */
    public static void handleAndWait(String repoSlug, String number, String label, boolean added) throws Exception {
        for (var future : LabelEventSubscriber.handle(repoSlug, number, label, added)) {
            future.get(30, TimeUnit.SECONDS);
        }
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
