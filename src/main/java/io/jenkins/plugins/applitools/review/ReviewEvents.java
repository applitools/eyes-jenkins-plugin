package io.jenkins.plugins.applitools.review;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.applitools.BaselineMerge;
import io.jenkins.plugins.applitools.ReviewEvaluator;
import io.jenkins.plugins.applitools.scm.GitHubRestClient;
import io.jenkins.plugins.applitools.scm.PullRequestStates;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/**
 * What happens when a pull request's labels change or it closes. Prompted by GitHub webhooks (when the GitHub plugin
 * is installed) or by polling the PR with a token (jobs that aren't multibranch); either way, the PR's state is read
 * from GitHub, never taken from a webhook payload. Depends on no GitHub plugin.
 */
public final class ReviewEvents {

    private static final Logger LOGGER = Logger.getLogger(ReviewEvents.class.getName());

    /** When the last pull_request webhook arrived (since Jenkins started), for the setup page; 0 if none. */
    private static volatile long lastPullRequestEvent;
    private static volatile String lastPullRequestRepo;

    private ReviewEvents() {}

    public static long getLastPullRequestEvent() {
        return lastPullRequestEvent;
    }

    public static String getLastPullRequestRepo() {
        return lastPullRequestRepo;
    }

    /** Webhook actions that can change what the plugin reports: a merge, or the design-review label. */
    private static final Set<String> RELEVANT_ACTIONS = Set.of("closed", "labeled", "unlabeled");

    private static final java.util.regex.Pattern REPO = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    /**
     * Runs webhook follow-ups and baseline merges: two threads of the plugin's own, so a burst of events or a slow
     * merge (which polls Eyes for up to 10 minutes) can't occupy Jenkins' shared timer threads.
     */
    private static final java.util.concurrent.ExecutorService EXECUTOR = executor();

    private static java.util.concurrent.ExecutorService executor() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                2,
                2,
                60,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                new hudson.util.NamingThreadFactory(
                        new hudson.util.DaemonThreadFactory(), "Applitools review events"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Builds with a GitHub read queued; a burst of webhooks for one PR then costs one read, not one each. */
    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();

    /** Builds whose baseline merge is running, so a repeated event doesn't start a second one. */
    private static final Set<String> MERGING = ConcurrentHashMap.newKeySet();

    /**
     * Handles a raw {@code pull_request} webhook payload. The payload is only a hint that a PR changed: what changed
     * (merged or not, its branches, its labels) is read back from GitHub with the job's own credentials before
     * anything happens. A forged or replayed webhook, for example on a Jenkins without a webhook secret, therefore
     * can't merge baselines or change the design-review label.
     */
    public static List<Future<?>> onPullRequestWebhook(String rawPayload) {
        JSONObject json;
        try {
            json = JSONObject.fromObject(rawPayload);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Unreadable pull_request payload", e);
            return List.of();
        }
        JSONObject repo = json.optJSONObject("repository");
        String slug = repo != null ? repo.optString("full_name", null) : null;
        lastPullRequestRepo = slug;
        lastPullRequestEvent = System.currentTimeMillis();

        int number = json.optInt("number");
        if (slug == null
                || !REPO.matcher(slug).matches() // nothing GitHub would send; keeps odd text out of logs
                || number <= 0
                || !RELEVANT_ACTIONS.contains(json.optString("action"))) {
            return List.of();
        }
        return confirmWithGitHub(slug, String.valueOf(number));
    }

    /** Reads each followed review's PR from GitHub, then applies its real state: closed, merged, labels. */
    static List<Future<?>> confirmWithGitHub(String repoSlug, String number) {
        List<Future<?>> work = new ArrayList<>();
        ReviewRegistry registry = ReviewRegistry.get();
        for (PendingReview review : registry.forPullRequest(repoSlug, number)) {
            String key = review.getJobFullName() + "#" + review.getBuildNumber();
            if (!QUEUED.add(key)) {
                continue; // a read is already queued; it will see this change too
            }
            work.add(EXECUTOR.submit(() -> {
                QUEUED.remove(key);
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    Run<?, ?> run = findRun(review);
                    if (run == null) {
                        return;
                    }
                    GitHubRestClient.PullState pr = PullRequestStates.of(run, repoSlug, number);
                    if (pr == null) {
                        LOGGER.log(Level.FINE, "Can't read {0}#{1} from GitHub for {2}; ignoring the webhook",
                                new Object[] {repoSlug, number, run.getFullDisplayName()});
                        return;
                    }
                    if (!pr.open()) {
                        closedNow(review, registry, pr.merged(), pr.headRef(), pr.baseRef());
                        return;
                    }
                    boolean labeled = pr.labels().stream().anyMatch(l -> l.equalsIgnoreCase(review.getLabel()));
                    if (review.isLabelDriven() && labeled != review.isLabelPresent()) {
                        labelChangedNow(review, registry, labeled);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Could not apply a webhook for " + repoSlug + "#" + number, e);
                }
            }));
        }
        return work;
    }

    /**
     * A closed PR, as read from GitHub: merge its Eyes baselines if it merged, and stop following it. Callers must
     * pass GitHub's own state, never a webhook payload's.
     */
    public static List<Future<?>> closed(
            String repoSlug, String number, boolean merged, @CheckForNull String headRef, @CheckForNull String baseRef) {
        List<Future<?>> work = new ArrayList<>();
        ReviewRegistry registry = ReviewRegistry.get();
        for (PendingReview review : registry.forPullRequest(repoSlug, number)) {
            work.add(EXECUTOR.submit(() -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    closedNow(review, registry, merged, headRef, baseRef);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Baseline merge failed for " + repoSlug + "#" + number, e);
                }
            }));
        }
        return work;
    }

    /** Merges baselines for a merged PR (if enabled) and drops its review, or keeps it until the merge succeeds. */
    private static void closedNow(
            PendingReview review,
            ReviewRegistry registry,
            boolean merged,
            @CheckForNull String headRef,
            @CheckForNull String baseRef)
            throws InterruptedException {
        if (!merged || headRef == null || baseRef == null || !review.isMergeBaselinesOnPrMerge()) {
            registry.remove(review);
            return;
        }
        String key = review.getJobFullName() + "#" + review.getBuildNumber();
        if (!MERGING.add(key)) {
            return; // already merging
        }
        if (!registry.all().contains(review)) {
            MERGING.remove(key);
            return; // merged already, by an earlier event
        }
        try {
            // Keep following it (without polling) until the merge succeeds, so a retry can find it.
            review.setSettled(true);
            review.setClosed(true);
            registry.saveQuietly();
            Run<?, ?> run = findRun(review);
            if (run == null || BaselineMerge.merge(review, run, headRef, baseRef, TaskListener.NULL)) {
                registry.remove(review);
            }
        } finally {
            MERGING.remove(key);
        }
    }

    /**
     * Applies a label change, as read from GitHub, to the PR's tracked review and re-posts its Design review result.
     */
    public static List<Future<?>> labelChanged(String repoSlug, String number, String labelName, boolean added) {
        List<Future<?>> work = new ArrayList<>();
        ReviewRegistry registry = ReviewRegistry.get();
        for (PendingReview review : registry.forPullRequest(repoSlug, number)) {
            if (!review.isLabelDriven() || !review.getLabel().equalsIgnoreCase(labelName)
                    || review.isLabelPresent() == added) {
                continue;
            }
            work.add(EXECUTOR.submit(() -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    labelChangedNow(review, registry, added);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Could not apply label change to " + repoSlug + "#" + number, e);
                }
            }));
        }
        return work;
    }

    private static void labelChangedNow(PendingReview review, ReviewRegistry registry, boolean added)
            throws Exception {
        review.setLabelPresent(added);
        review.setSettled(false);
        registry.saveQuietly();
        Run<?, ?> run = findRun(review);
        if (run != null) {
            ReviewEvaluator.refresh(review, run, TaskListener.NULL);
            registry.saveQuietly();
        }
    }

    @CheckForNull
    static Run<?, ?> findRun(PendingReview review) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(review.getJobFullName(), Job.class);
        return job != null ? job.getBuildByNumber(review.getBuildNumber()) : null;
    }
}
