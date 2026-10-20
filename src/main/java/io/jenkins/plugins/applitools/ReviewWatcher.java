package io.jenkins.plugins.applitools;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;

/**
 * Follows reviews after their builds finish: every minute, re-reads Eyes for each unsettled review and updates the PR
 * checks, so accepting diffs in Eyes turns the checks green without re-running anything. No executors are used.
 */
@Extension
public class ReviewWatcher extends AsyncPeriodicWork {

    private static final long PERIOD_SECONDS = Long.getLong(ReviewWatcher.class.getName() + ".periodSeconds", 60);

    public ReviewWatcher() {
        super("Applitools review watcher");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(PERIOD_SECONDS);
    }

    /** Most time one pass may spend; the rest waits for the next pass (least recently polled first). */
    static long budgetMillis() {
        return Long.getLong(ReviewWatcher.class.getName() + ".budgetSeconds", 50) * 1000;
    }

    @Override
    protected void execute(TaskListener listener) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            ReviewRegistry registry = ReviewRegistry.get();
            long start = System.currentTimeMillis();
            boolean changed = false;
            List<PendingReview> reviews = new ArrayList<>(registry.all());
            reviews.sort(Comparator.comparingLong(PendingReview::getLastPolledAt));
            for (PendingReview review : reviews) {
                if (review.isDone()) {
                    registry.remove(review);
                    continue;
                }
                long now = System.currentTimeMillis();
                if (review.isSettled() || now < review.nextPollAt(now)) {
                    continue; // settled open PR (kept for labels and its merge), or not due yet
                }
                if (now - start > budgetMillis()) {
                    break; // out of time this pass; the least recently polled go first next time
                }
                Run<?, ?> run = findRun(review);
                if (run == null) {
                    registry.remove(review);
                    continue;
                }
                String before = review.stateKey();
                try {
                    ReviewEvaluator.refresh(review, run, listener);
                    review.polled(now, true);
                } catch (Exception e) {
                    review.polled(now, false);
                    listener.getLogger().println("Could not refresh " + run.getFullDisplayName() + ": " + e);
                }
                if (review.isDone()) {
                    registry.remove(review);
                } else if (!before.equals(review.stateKey())) {
                    changed = true;
                }
            }
            if (changed) {
                registry.saveQuietly();
            }
        }
    }

    static Run<?, ?> findRun(PendingReview review) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(review.getJobFullName(), Job.class);
        return job != null ? job.getBuildByNumber(review.getBuildNumber()) : null;
    }
}
