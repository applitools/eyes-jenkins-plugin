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

    @Override
    protected void execute(TaskListener listener) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            ReviewRegistry registry = ReviewRegistry.get();
            for (PendingReview review : registry.all()) {
                if (review.isSettled()) {
                    continue;
                }
                Run<?, ?> run = findRun(review);
                if (run == null) {
                    registry.remove(review);
                    continue;
                }
                try {
                    ReviewEvaluator.refresh(review, run, listener);
                } catch (Exception e) {
                    listener.getLogger().println("Could not refresh " + run.getFullDisplayName() + ": " + e);
                }
            }
            registry.saveQuietly();
        }
    }

    static Run<?, ?> findRun(PendingReview review) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(review.getJobFullName(), Job.class);
        return job != null ? job.getBuildByNumber(review.getBuildNumber()) : null;
    }
}
