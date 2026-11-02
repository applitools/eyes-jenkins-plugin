package io.jenkins.plugins.applitools;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.PollSchedule;
import io.jenkins.plugins.applitools.review.ReviewEvents;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.applitools.scm.GitHubRestClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;

/**
 * Follows reviews after their builds finish: re-reads Eyes for each unsettled review on the configured {@link
 * PollSchedule} and updates the PR checks, so accepting diffs in Eyes turns the checks green without re-running
 * anything. No executors are used.
 */
@Extension
public class ReviewWatcher extends AsyncPeriodicWork {

    /**
     * How often the watcher wakes. A pass only runs as often as the first polling interval (at most every minute), so
     * this is just the fastest interval the schedule allows; waking alone costs no network calls.
     */
    private static final long PERIOD_SECONDS = Long.getLong(ReviewWatcher.class.getName() + ".periodSeconds", 15);

    /** When the last pass ran; a wake-up before the next one is due does nothing. */
    private volatile long lastPassAt;

    public ReviewWatcher() {
        super("Applitools review watcher");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(PERIOD_SECONDS);
    }

    /** Time between passes: the first polling interval, at most a minute (later intervals are multiples of passes). */
    static long passMillis(PollSchedule schedule) {
        return Math.min(TimeUnit.MINUTES.toMillis(1), schedule.first());
    }

    /**
     * Most time one pass may spend; the rest waits for the next pass (least recently polled first). Five sixths of
     * the time between passes (50 s for the default minute), so passes rarely overlap.
     */
    static long budgetMillis(PollSchedule schedule) {
        Long seconds = Long.getLong(ReviewWatcher.class.getName() + ".budgetSeconds");
        if (seconds != null) {
            return seconds * 1000;
        }
        return Math.max(TimeUnit.SECONDS.toMillis(10), passMillis(schedule) * 5 / 6);
    }

    @Override
    protected void execute(TaskListener listener) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            PollSchedule schedule = PollSchedule.current();
            long start = System.currentTimeMillis();
            // Half a wake-up of slack, so timer jitter can't push a pass to the next wake-up.
            if (start - lastPassAt < passMillis(schedule) - TimeUnit.SECONDS.toMillis(PERIOD_SECONDS) / 2) {
                return;
            }
            lastPassAt = start;
            long budget = budgetMillis(schedule);
            ReviewRegistry registry = ReviewRegistry.get();
            boolean changed = false;
            boolean polledAny = false; // the budget never stops a pass before its first poll, so every pass progresses
            List<PendingReview> reviews = new ArrayList<>(registry.all());
            reviews.sort(Comparator.comparingLong(PendingReview::getLastPolledAt));
            for (PendingReview review : reviews) {
                if (review.isDone()) {
                    registry.remove(review);
                    continue;
                }
                if (reconcile(review, registry, listener)) {
                    continue; // no longer followed
                }
                long now = System.currentTimeMillis();
                if (review.needsPullPolling() && now >= review.nextPullPollAt(now, schedule)) {
                    if (polledAny && now - start > budget) {
                        break;
                    }
                    polledAny = true;
                    if (pollPullRequest(review, listener)) {
                        continue; // the PR closed: ReviewEvents took over (merge, then stop following)
                    }
                }
                boolean recheckSettledPr = review.isSettled() && review.isPullRequest() && !review.isClosed()
                        && now >= review.getLastPolledAt() + schedule.settledRecheck();
                if ((review.isSettled() && !recheckSettledPr)
                        || (!review.isSettled() && now < review.nextPollAt(now, schedule))) {
                    // Settled open PRs are re-read now and then (30 minutes by default), so a reviewer who changes their mind in Eyes
                    // (e.g. accepts after rejecting) still updates the PR; otherwise only label/merge events matter.
                    continue;
                }
                if (polledAny && now - start > budget) {
                    break; // out of time this pass; the least recently polled go first next time
                }
                polledAny = true;
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
     * Keeps the list honest without any network calls in the common case. Stops following a review when its build was
     * deleted, its job is gone or disabled (Jenkins disables jobs for deleted branches and closed PRs), its job has a
     * newer finished build, or it's a branch build and branch builds aren't followed. Before dropping a PR whose job
     * went away, asks GitHub whether it merged, so a missed "closed" webhook can't skip the baseline merge.
     *
     * @return true if the review is no longer followed
     */
    static boolean reconcile(PendingReview review, ReviewRegistry registry, TaskListener listener) {
        if (!review.isPullRequest()
                && !io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration.get().isFollowBranchBuilds()) {
            registry.remove(review);
            return true;
        }
        Job<?, ?> job = Jenkins.get().getItemByFullName(review.getJobFullName(), Job.class);
        Run<?, ?> run = job != null ? job.getBuildByNumber(review.getBuildNumber()) : null;
        if (run == null) {
            registry.remove(review);
            return true;
        }
        Run<?, ?> lastFinished = job.getLastCompletedBuild();
        boolean superseded = lastFinished != null && lastFinished.getNumber() > review.getBuildNumber();
        boolean gone = !job.isBuildable();
        if (!superseded && !gone) {
            return false;
        }
        if (gone && review.isPullRequest() && !review.isClosed()) {
            String slug = review.getRepoSlug();
            String number = review.getChangeId();
            try {
                GitHubRestClient.PullState pr = slug != null && number != null
                        ? io.jenkins.plugins.applitools.scm.PullRequestStates.of(run, slug, number)
                        : null;
                if (pr != null && pr.merged()) {
                    // The webhook was missed (or Jenkins was down): merge now, as the event would have.
                    ReviewEvents.closed(slug, number, true, pr.headRef(), pr.baseRef());
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                listener.getLogger().println("Could not check whether " + slug + "#" + number + " merged: " + e);
                return false; // try again next pass rather than risk skipping a merge
            }
        }
        if (review.isClosed()) {
            return false; // merged PR waiting for its baseline merge to succeed
        }
        registry.remove(review);
        return true;
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
