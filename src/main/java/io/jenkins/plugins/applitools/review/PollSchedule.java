package io.jenkins.plugins.applitools.review;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration;
import java.util.concurrent.TimeUnit;

/**
 * How often the watcher polls a review, by its age: every {@code first} at first, every {@code slow} once it's {@code
 * slowAfter} old, every {@code slowest} once it's {@code slowestAfter} old. Settled open PRs are re-read every {@code
 * settledRecheck}, and reviews are dropped after {@code follow}. All in milliseconds; set under Manage Jenkins → System.
 *
 * <p>Later intervals are never shorter than earlier ones, so a misconfigured schedule can only poll less, not more.
 */
public record PollSchedule(
        long first, long slowAfter, long slow, long slowestAfter, long slowest, long settledRecheck, long follow) {

    /** The fastest allowed interval. */
    public static final long MIN_FIRST_MILLIS = TimeUnit.SECONDS.toMillis(15);

    /** Errors back off to at least this, or the slowest interval if that's longer. */
    private static final long BACKOFF_CAP_MILLIS = TimeUnit.HOURS.toMillis(1);

    public PollSchedule {
        slow = Math.max(slow, first);
        slowest = Math.max(slowest, slow);
        slowestAfter = Math.max(slowestAfter, slowAfter);
        settledRecheck = Math.max(settledRecheck, first);
    }

    /**
     * The schedule from the global settings. The {@code PendingReview.minPollSeconds} and {@code
     * ReviewRegistry.maxAgeDays} system properties, if set, still override them (tests set the first to 0).
     */
    @NonNull
    public static PollSchedule current() {
        ApplitoolsGlobalConfiguration config = ApplitoolsGlobalConfiguration.get();
        Long days = Long.getLong(ReviewRegistry.class.getName() + ".maxAgeDays");
        long follow = TimeUnit.DAYS.toMillis(days != null ? days : config.getFollowReviewsDays());
        Long unitSeconds = Long.getLong(PendingReview.class.getName() + ".minPollSeconds");
        if (unitSeconds != null) {
            long unit = unitSeconds * 1000;
            return new PollSchedule(unit, TimeUnit.HOURS.toMillis(1), 5 * unit, TimeUnit.DAYS.toMillis(1), 30 * unit,
                    30 * unit, follow);
        }
        return new PollSchedule(
                TimeUnit.SECONDS.toMillis(config.getReviewPollSeconds()),
                TimeUnit.MINUTES.toMillis(config.getReviewSlowAfterMinutes()),
                TimeUnit.MINUTES.toMillis(config.getReviewSlowPollMinutes()),
                TimeUnit.HOURS.toMillis(config.getReviewSlowestAfterHours()),
                TimeUnit.MINUTES.toMillis(config.getReviewSlowestPollMinutes()),
                TimeUnit.MINUTES.toMillis(config.getSettledRecheckMinutes()),
                follow);
    }

    /** The interval for a review this old after this many consecutive errors (each doubles it, up to a cap). */
    public long interval(long age, int failures) {
        long interval = age < slowAfter ? first : age < slowestAfter ? slow : slowest;
        if (failures > 0) {
            interval = Math.min(Math.max(interval, first) << Math.min(failures, 10),
                    Math.max(BACKOFF_CAP_MILLIS, slowest));
        }
        return interval;
    }
}
