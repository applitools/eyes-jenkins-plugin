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

    /** Delivers a raw pull_request webhook payload and waits for everything it starts; returns how many tasks. */
    public static int webhookAndWait(String payload) throws Exception {
        var work = ReviewEvents.onPullRequestWebhook(payload);
        for (var future : work) {
            future.get(30, TimeUnit.SECONDS);
        }
        return work.size();
    }

    /** Pretends a baseline merge into an Eyes branch is running, until {@link #mergeDone}. */
    public static void mergeRunning(String repoSlug, String target) {
        ReviewEvents.testMergeStarted(repoSlug, target);
    }

    public static void mergeDone(String target) {
        ReviewEvents.testMergeDone(target);
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
