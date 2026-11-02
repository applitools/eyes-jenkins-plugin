package io.jenkins.plugins.applitools.review;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Bounds on what the review watcher costs: polling slows with age, backs off on errors, and the list is capped. */
@WithJenkins
class WatcherLimitsTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    private static PendingReview review(String job) {
        return new PendingReview(job, 1, "b", "http://eyes", "run", "read", "acme/web", "7", null, false,
                "design-review", List.of(), true, Set.of("s1"), false);
    }

    @Test
    void pollingSlowsAsReviewsAge(JenkinsRule j) {
        PendingReview r = review("job");
        long start = r.getRegisteredAt();

        r.polled(start, true);
        assertThat(r.nextPollAt(start) - start, is(MIN)); // first hour: every minute

        r.setRegisteredAtForTest(start - 2 * HOUR);
        assertThat(r.nextPollAt(start) - start, is(5 * MIN)); // first day: every 5 minutes

        r.setRegisteredAtForTest(start - 3 * DAY);
        assertThat(r.nextPollAt(start) - start, is(30 * MIN)); // after that: every 30 minutes
    }

    @Test
    void errorsBackOffToAnHour(JenkinsRule j) {
        PendingReview r = review("job");
        long now = r.getRegisteredAt();

        r.polled(now, false);
        assertThat(r.nextPollAt(now) - now, is(2 * MIN));
        r.polled(now, false);
        assertThat(r.nextPollAt(now) - now, is(4 * MIN));
        for (int i = 0; i < 20; i++) {
            r.polled(now, false);
        }
        assertThat(r.nextPollAt(now) - now, is(60 * MIN)); // capped

        r.polled(now, true);
        assertThat(r.nextPollAt(now) - now, is(MIN)); // recovered
    }

    @Test
    void scheduleIsConfigurable(JenkinsRule j) {
        ApplitoolsGlobalConfiguration config = ApplitoolsGlobalConfiguration.get();
        config.setReviewPollSeconds(20);
        config.setReviewSlowAfterMinutes(10);
        config.setReviewSlowPollMinutes(2);
        config.setReviewSlowestAfterHours(3);
        config.setReviewSlowestPollMinutes(90);
        PendingReview r = review("job");
        long start = r.getRegisteredAt();
        r.polled(start, true);
        r.pullPolled(start, true);

        assertThat(r.nextPollAt(start) - start, is(20_000L));
        assertThat(r.nextPullPollAt(start) - start, is(20_000L)); // GitHub polling follows the same schedule
        r.setRegisteredAtForTest(start - 11 * MIN);
        assertThat(r.nextPollAt(start) - start, is(2 * MIN));
        r.setRegisteredAtForTest(start - 4 * HOUR);
        assertThat(r.nextPollAt(start) - start, is(90 * MIN));
        for (int i = 0; i < 20; i++) {
            r.polled(start, false);
        }
        assertThat(r.nextPollAt(start) - start, is(90 * MIN)); // backoff never caps below the slowest interval
    }

    @Test
    void scheduleCanOnlyPollLessThanAllowed(JenkinsRule j) {
        ApplitoolsGlobalConfiguration config = ApplitoolsGlobalConfiguration.get();
        config.setReviewPollSeconds(1);
        assertThat(config.getReviewPollSeconds(), is(15));

        config.setReviewPollSeconds(180);
        config.setReviewSlowPollMinutes(1); // shorter than the first interval
        config.setReviewSlowestPollMinutes(1);
        config.setSettledRecheckMinutes(1);
        PollSchedule schedule = PollSchedule.current();
        assertThat(schedule.slow(), is(3 * MIN));
        assertThat(schedule.slowest(), is(3 * MIN));
        assertThat(schedule.settledRecheck(), is(3 * MIN));

        config.setFollowReviewsDays(0);
        assertThat(config.getFollowReviewsDays(), is(1));
        config.setFollowReviewsDays(1000);
        assertThat(config.getFollowReviewsDays(), is(365));
    }

    @Test
    void reviewsAreDroppedAfterTheConfiguredDays(JenkinsRule j) {
        ApplitoolsGlobalConfiguration.get().setFollowReviewsDays(2);
        ReviewRegistry registry = ReviewRegistry.get();
        PendingReview fresh = review("fresh");
        fresh.setRegisteredAtForTest(System.currentTimeMillis() - DAY);
        PendingReview stale = review("stale");
        stale.setRegisteredAtForTest(System.currentTimeMillis() - 3 * DAY);
        registry.register(fresh);
        registry.register(stale);

        assertThat(registry.all().stream().map(PendingReview::getJobFullName).toList(), contains("fresh"));
    }

    @Test
    void neverPolledIsDueImmediately(JenkinsRule j) {
        PendingReview r = review("job");

        assertThat(r.nextPollAt(r.getRegisteredAt()) <= r.getRegisteredAt(), is(true));
    }

    @Test
    void registryIsCapped(JenkinsRule j) {
        System.setProperty(ReviewRegistry.class.getName() + ".maxReviews", "2");
        try {
            ReviewRegistry registry = ReviewRegistry.get();
            long now = System.currentTimeMillis();
            PendingReview a = review("a");
            a.setRegisteredAtForTest(now - 2000);
            PendingReview b = review("b");
            b.setRegisteredAtForTest(now - 1000);
            PendingReview c = review("c");
            c.setRegisteredAtForTest(now);
            registry.register(a);
            registry.register(b);
            registry.register(c);

            assertThat(registry.all().stream().map(PendingReview::getJobFullName).sorted().toList(),
                    contains("b", "c"));
        } finally {
            System.clearProperty(ReviewRegistry.class.getName() + ".maxReviews");
        }
    }
}
