package io.jenkins.plugins.applitools.review;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
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
import java.util.Locale;
import java.util.Map;
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
     * Baseline merges running or that may be about to, so a build of the target branch can wait for them before its
     * tests compare against the baselines. Keyed by what's pending (a queued PR read, a queued merge, a running merge).
     */
    private static final Map<String, InFlight> IN_FLIGHT = new ConcurrentHashMap<>();

    /**
     * @param repoSlug the PR's repository
     * @param target the Eyes branch merged into, or {@code null} while that's not known yet (the PR is still being read
     *     from GitHub), which counts as any branch of the repository
     */
    private record InFlight(String repoSlug, @CheckForNull String target) {

        boolean into(String eyesBranch) {
            String branch = eyesBranch.toLowerCase(Locale.ROOT);
            return target != null
                    ? target.toLowerCase(Locale.ROOT).equals(branch)
                    : branch.startsWith(repoSlug.toLowerCase(Locale.ROOT) + "/");
        }
    }

    /**
     * Whether a baseline merge into this Eyes branch ({@code owner/repo/branch}) is running, or may be about to: a PR
     * event of that repository is still being read from GitHub, or a merge into it is queued.
     */
    public static boolean isMergingInto(@NonNull String eyesBranch) {
        return IN_FLIGHT.values().stream().anyMatch(f -> f.into(eyesBranch));
    }

    /** For tests: pretend a merge into {@code target} is running until {@link #testMergeDone} is called. */
    static void testMergeStarted(String repoSlug, String target) {
        IN_FLIGHT.put("test:" + target, new InFlight(repoSlug, target));
    }

    static void testMergeDone(String target) {
        IN_FLIGHT.remove("test:" + target);
    }

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
            String reading = "read:" + key;
            IN_FLIGHT.put(reading, new InFlight(repoSlug, null)); // it may turn out to be a merge
            work.add(EXECUTOR.submit(() -> {
                QUEUED.remove(key);
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    Run<?, ?> run = findRun(review);
                    if (run == null) {
                        // Its job was deleted: a merged PR's baselines were merged then (jobDeleting).
                        LOGGER.log(Level.INFO, "{0}#{1}: build {2} #{3} is gone; ignoring the webhook",
                                new Object[] {repoSlug, number, review.getJobFullName(), review.getBuildNumber()});
                        return;
                    }
                    GitHubRestClient.PullState pr = PullRequestStates.of(run, repoSlug, number);
                    if (pr == null) {
                        LOGGER.log(Level.FINE, "Can't read {0}#{1} from GitHub for {2}; ignoring the webhook",
                                new Object[] {repoSlug, number, run.getFullDisplayName()});
                        return;
                    }
                    if (!pr.open()) {
                        closedNow(review, registry, pr.merged(), pr.headRef(), pr.baseRef(), run);
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
                } finally {
                    IN_FLIGHT.remove(reading);
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
            String queued = "queued:" + mergeKey(review);
            if (merged && baseRef != null) {
                IN_FLIGHT.put(queued, new InFlight(repoSlug, repoSlug + "/" + baseRef));
            }
            work.add(EXECUTOR.submit(() -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    closedNow(review, registry, merged, headRef, baseRef, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Baseline merge failed for " + repoSlug + "#" + number, e);
                } finally {
                    IN_FLIGHT.remove(queued);
                }
            }));
        }
        return work;
    }

    /**
     * Longest a job deletion waits for an Eyes baseline merge. The merge runs while the job still exists (see {@link
     * #jobDeleting}), which holds up the deletion, so this is shorter than a normal merge's wait.
     */
    static final long DELETION_MERGE_TIMEOUT_MILLIS = java.util.concurrent.TimeUnit.SECONDS.toMillis(
            Long.getLong(ReviewEvents.class.getName() + ".deletionMergeTimeoutSeconds", 120));

    /**
     * Called before Jenkins deletes a job. Multibranch deletes a closed PR's job, sometimes before the plugin has
     * handled the PR's "closed" webhook, or when the webhook never arrived (found by a later scan). Once the job is
     * gone, so is what the merge needs (the build's credentials, GitHub connection and where to report), so this
     * runs before the deletion, on its thread: if a followed PR of this job merged, its baselines are merged now.
     */
    public static void jobDeleting(Job<?, ?> job) {
        ReviewRegistry registry = ReviewRegistry.get();
        for (PendingReview review : registry.all()) {
            if (!review.getJobFullName().equals(job.getFullName()) || !review.isPullRequest() || review.isClosed()) {
                continue;
            }
            Run<?, ?> run = job.getBuildByNumber(review.getBuildNumber());
            String slug = review.getRepoSlug();
            String number = review.getChangeId();
            String key = mergeKey(review);
            if (run == null || slug == null || number == null || !MERGING.add(key)) {
                continue; // a merge is already running, or there's nothing to merge with
            }
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                GitHubRestClient.PullState pr = PullRequestStates.of(run, slug, number);
                if (pr == null) {
                    LOGGER.log(Level.WARNING, "{0}#{1}: job {2} is being deleted and the PR couldn''t be read from"
                            + " GitHub, so its Eyes baselines may not be merged",
                            new Object[] {slug, number, job.getFullName()});
                    MergeHistory.record(review, run, review.getEyesBranch(), null, MergeHistory.Outcome.SKIPPED,
                            "Couldn't tell whether the PR merged",
                            "Job " + job.getFullName() + " was deleted and the PR couldn't be read from GitHub, so"
                                    + " its Eyes baselines may not be merged. If it merged, merge them in Eyes under"
                                    + " Compare & merge branches.");
                } else if (!pr.open()) {
                    LOGGER.log(Level.INFO, "{0}#{1} closed (merged: {2}) and job {3} is being deleted; handling it"
                            + " first", new Object[] {slug, number, pr.merged(), job.getFullName()});
                    mergeNow(review, registry, pr.merged(), pr.headRef(), pr.baseRef(), run,
                            DELETION_MERGE_TIMEOUT_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not check whether " + slug + "#" + number + " merged before its"
                        + " job was deleted; its Eyes baselines may not be merged", e);
                MergeHistory.record(review, run, review.getEyesBranch(), null, MergeHistory.Outcome.SKIPPED,
                        "Couldn't tell whether the PR merged",
                        "Job " + job.getFullName() + " was deleted and checking the PR failed (" + e
                                + "), so its Eyes baselines may not be merged. If it merged, merge them in Eyes"
                                + " under Compare & merge branches.");
            } finally {
                MERGING.remove(key);
            }
        }
    }

    /** True while a baseline merge for this review is running or about to. */
    public static boolean isMerging(PendingReview review) {
        return MERGING.contains(mergeKey(review));
    }

    private static String mergeKey(PendingReview review) {
        return review.getJobFullName() + "#" + review.getBuildNumber();
    }

    /**
     * Merges baselines for a merged PR (if enabled) and drops its review, or keeps it until the merge succeeds. Uses
     * {@code run} when the caller already has the build, since its job may be deleted meanwhile.
     */
    private static void closedNow(
            PendingReview review,
            ReviewRegistry registry,
            boolean merged,
            @CheckForNull String headRef,
            @CheckForNull String baseRef,
            @CheckForNull Run<?, ?> run)
            throws InterruptedException {
        String key = mergeKey(review);
        if (!MERGING.add(key)) {
            return; // already merging
        }
        try {
            mergeNow(review, registry, merged, headRef, baseRef, run, BaselineMerge.TIMEOUT_MILLIS);
        } finally {
            MERGING.remove(key);
        }
    }

    /** {@link #closedNow} for a caller that holds the review's merge key. */
    private static void mergeNow(
            PendingReview review,
            ReviewRegistry registry,
            boolean merged,
            @CheckForNull String headRef,
            @CheckForNull String baseRef,
            @CheckForNull Run<?, ?> run,
            long timeoutMillis)
            throws InterruptedException {
        String pr = review.getRepoSlug() + "#" + review.getChangeId();
        if (!merged) {
            registry.remove(review);
            return;
        }
        String slug = review.getRepoSlug();
        String target = slug != null && baseRef != null ? slug + "/" + baseRef : null;
        String source = review.getEyesBranch() != null
                ? review.getEyesBranch()
                : slug != null && headRef != null ? slug + "/" + headRef : null;
        if (!review.isMergeBaselinesOnPrMerge()) {
            LOGGER.log(Level.INFO, "{0} merged; not merging its Eyes baselines (turned off)", pr);
            MergeHistory.record(review, run, source, target, MergeHistory.Outcome.TURNED_OFF,
                    "Baseline merge turned off",
                    "mergeBaselines is off for this job or in Manage Jenkins → System → Applitools Eyes.");
            registry.remove(review);
            return;
        }
        if (headRef == null || baseRef == null) {
            LOGGER.log(Level.WARNING, "{0} merged, but GitHub didn''t say its branches; Eyes baselines not merged", pr);
            MergeHistory.record(review, run, source, target, MergeHistory.Outcome.SKIPPED,
                    "Baseline merge skipped: branches unknown",
                    "GitHub didn't say the PR's source and target branches. Merge its baselines in Eyes under"
                            + " Compare & merge branches.");
            registry.remove(review);
            return;
        }
        if (!registry.all().contains(review)) {
            return; // merged already, by an earlier event
        }
        // Keep following it (without polling) until the merge succeeds, so a retry can find it.
        review.setSettled(true);
        review.setClosed(true);
        registry.saveQuietly();
        Run<?, ?> build = run != null ? run : findRun(review);
        if (build == null) {
            LOGGER.log(Level.WARNING, "{0} merged, but build {1} #{2} is gone, so its Eyes baselines weren''t merged"
                    + " into {3}. Merge them in Eyes (Compare & merge branches).",
                    new Object[] {pr, review.getJobFullName(), review.getBuildNumber(), baseRef});
            MergeHistory.record(review, null, source, target, MergeHistory.Outcome.SKIPPED,
                    "Baseline merge skipped: build gone",
                    "Build " + review.getJobFullName() + " #" + review.getBuildNumber() + " was deleted before the"
                            + " merge could run, so its credentials are gone. Merge the baselines in Eyes under"
                            + " Compare & merge branches (" + source + " → " + target + ").");
            registry.remove(review);
            return;
        }
        String running = mergeKey(review);
        if (slug != null && target != null) {
            IN_FLIGHT.put(running, new InFlight(slug, target));
        }
        try {
            if (BaselineMerge.merge(review, build, headRef, baseRef, TaskListener.NULL, timeoutMillis)) {
                registry.remove(review);
            }
        } finally {
            IN_FLIGHT.remove(running);
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
