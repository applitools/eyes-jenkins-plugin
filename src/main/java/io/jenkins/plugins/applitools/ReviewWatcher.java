package io.jenkins.plugins.applitools;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewEvents;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.applitools.scm.GitHubRestClient;
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
                if (review.needsPullPolling() && now >= review.nextPullPollAt(now)) {
                    if (now - start > budgetMillis()) {
                        break;
                    }
                    if (pollPullRequest(review, listener)) {
                        continue; // the PR closed: ReviewEvents took over (merge, then stop following)
                    }
                }
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

    /**
     * For PRs reported with a token (no webhooks): read the PR from GitHub and apply what changed, exactly as the
     * webhooks would. Returns true if the PR has closed.
     */
    private static boolean pollPullRequest(PendingReview review, TaskListener listener) {
        long now = System.currentTimeMillis();
        String slug = review.getRepoSlug();
        String number = review.getChangeId();
        String credentialsId = review.getGithubCredentialsId();
        Run<?, ?> run = findRun(review);
        String token = run != null && credentialsId != null ? GitHubRestClient.token(run, credentialsId) : null;
        if (slug == null || number == null || token == null) {
            review.pullPolled(now, false);
            return false;
        }
        try {
            GitHubRestClient.PullState pr = new GitHubRestClient(review.getGithubApiUrl(), token)
                    .pullIfChanged(slug, number, review.getPullEtag());
            review.pullPolled(now, true);
            if (pr == null) {
                return false; // unchanged since last time (HTTP 304)
            }
            review.setPullEtag(pr.etag());
            if (!pr.open()) {
                ReviewEvents.closed(slug, number, pr.merged(), pr.headRef(), pr.baseRef());
                return true;
            }
            boolean labeled = pr.labels().stream().anyMatch(l -> l.equalsIgnoreCase(review.getLabel()));
            if (labeled != review.isLabelPresent()) {
                ReviewEvents.labelChanged(slug, number, review.getLabel(), labeled);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            review.pullPolled(now, false);
            listener.getLogger().println("Could not read " + slug + "#" + number + ": " + e);
        }
        return false;
    }

    static Run<?, ?> findRun(PendingReview review) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(review.getJobFullName(), Job.class);
        return job != null ? job.getBuildByNumber(review.getBuildNumber()) : null;
    }
}
