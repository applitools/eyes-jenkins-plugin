package io.jenkins.plugins.applitools.review;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration;
import io.jenkins.plugins.applitools.BaselineMerge;
import io.jenkins.plugins.applitools.ReviewEvaluator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;

/**
 * What happens when a pull request's labels change or it closes. Fed by GitHub webhooks (when the GitHub plugin is
 * installed) or by polling the PR with a token (jobs that aren't multibranch). Depends on no GitHub plugin.
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

    /** Handles a raw {@code pull_request} webhook payload: label changes and closed PRs. */
    public static List<Future<?>> onPullRequestWebhook(String rawPayload) {
        JSONObject json;
        try {
            json = JSONObject.fromObject(rawPayload);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Unreadable pull_request payload", e);
            return List.of();
        }
        JSONObject repo = json.optJSONObject("repository");
        lastPullRequestRepo = repo != null ? repo.optString("full_name", null) : null;
        lastPullRequestEvent = System.currentTimeMillis();

        List<Future<?>> closed = handleMerged(json);
        if (closed != null) {
            return closed;
        }
        String action = json.optString("action");
        JSONObject label = json.optJSONObject("label");
        if (repo == null || label == null || !("labeled".equals(action) || "unlabeled".equals(action))) {
            return List.of();
        }
        return labelChanged(
                repo.optString("full_name"),
                String.valueOf(json.optInt("number")),
                label.optString("name"),
                "labeled".equals(action));
    }

    /**
     * A closed PR (merged or not): merge its Eyes baselines if it merged, and stop following it.
     *
     * @return the scheduled work if the event was a closed PR, else {@code null}
     */
    @CheckForNull
    static List<Future<?>> handleMerged(JSONObject json) {
        if (!"closed".equals(json.optString("action"))) {
            return null;
        }
        JSONObject pr = json.optJSONObject("pull_request");
        JSONObject repo = json.optJSONObject("repository");
        if (pr == null || repo == null) {
            return List.of();
        }
        String head = pr.optJSONObject("head") != null ? pr.getJSONObject("head").optString("ref", null) : null;
        String base = pr.optJSONObject("base") != null ? pr.getJSONObject("base").optString("ref", null) : null;
        return closed(
                repo.optString("full_name"), String.valueOf(json.optInt("number")), pr.optBoolean("merged"), head, base);
    }

    /** Raw-payload variant kept for tests. */
    @CheckForNull
    static List<Future<?>> handleMerged(String rawPayload) {
        try {
            return handleMerged(JSONObject.fromObject(rawPayload));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Merges baselines for a merged PR (if enabled) and drops its review, or keeps it until the merge succeeds. */
    public static List<Future<?>> closed(
            String repoSlug, String number, boolean merged, @CheckForNull String headRef, @CheckForNull String baseRef) {
        List<Future<?>> work = new ArrayList<>();
        ReviewRegistry registry = ReviewRegistry.get();
        for (PendingReview review : registry.forPullRequest(repoSlug, number)) {
            if (!merged || headRef == null || baseRef == null
                    || !ApplitoolsGlobalConfiguration.get().isMergeBaselines()) {
                registry.remove(review);
                continue;
            }
            // Keep following it (without polling) until the merge succeeds, so a retry can find it.
            review.setSettled(true);
            review.setClosed(true);
            registry.saveQuietly();
            work.add(Timer.get().submit(() -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    Run<?, ?> run = findRun(review);
                    if (run == null || BaselineMerge.merge(review, run, headRef, baseRef, TaskListener.NULL)) {
                        registry.remove(review);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Baseline merge failed for " + repoSlug + "#" + number, e);
                }
            }));
        }
        return work;
    }

    /** Applies a label change to the PR's tracked review and re-posts its Design review result. */
    public static List<Future<?>> labelChanged(String repoSlug, String number, String labelName, boolean added) {
        List<Future<?>> work = new ArrayList<>();
        ReviewRegistry registry = ReviewRegistry.get();
        for (PendingReview review : registry.forPullRequest(repoSlug, number)) {
            if (!review.isLabelDriven() || !review.getLabel().equalsIgnoreCase(labelName)
                    || review.isLabelPresent() == added) {
                continue;
            }
            review.setLabelPresent(added);
            review.setSettled(false);
            registry.saveQuietly();
            work.add(Timer.get().submit(() -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    Run<?, ?> run = findRun(review);
                    if (run != null) {
                        ReviewEvaluator.refresh(review, run, TaskListener.NULL);
                        registry.saveQuietly();
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Could not apply label change to " + repoSlug + "#" + number, e);
                }
            }));
        }
        return work;
    }

    @CheckForNull
    static Run<?, ?> findRun(PendingReview review) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(review.getJobFullName(), Job.class);
        return job != null ? job.getBuildByNumber(review.getBuildNumber()) : null;
    }
}
