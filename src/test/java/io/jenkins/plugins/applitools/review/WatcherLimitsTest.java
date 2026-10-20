package io.jenkins.plugins.applitools.review;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

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
