package io.jenkins.plugins.applitools.review;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.applitools.ReviewEvaluator;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

/**
 * Adding or removing the design-review label on a PR updates its Design review check right away, without re-running
 * the tests. Arrives through the GitHub plugin's webhook endpoint (the same one multibranch jobs already use).
 */
@Extension(optional = true)
public class LabelEventSubscriber extends GHEventsSubscriber {

    private static final Logger LOGGER = Logger.getLogger(LabelEventSubscriber.class.getName());

    @Override
    protected boolean isApplicable(Item item) {
        return true;
    }

    @Override
    protected Set<GHEvent> events() {
        return Set.of(GHEvent.PULL_REQUEST);
    }

    @Override
    protected void onEvent(GHSubscriberEvent event) {
        if (handleMerged(event.getPayload()) != null) {
            return;
        }
        GHEventPayload.PullRequest payload;
        try {
            payload = GitHub.offline()
                    .parseEventPayload(new StringReader(event.getPayload()), GHEventPayload.PullRequest.class);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Unreadable pull_request payload", e);
            return;
        }
        String action = payload.getAction();
        if (!"labeled".equals(action) && !"unlabeled".equals(action) || payload.getLabel() == null) {
            return;
        }
        handle(
                payload.getRepository().getFullName(),
                String.valueOf(payload.getNumber()),
                payload.getLabel().getName(),
                "labeled".equals(action));
    }

    /**
     * A merged PR: merge its Eyes baseline branch into the target's, then stop following its review. Read from the raw
     * JSON because the GitHub library may try to fetch {@code merged} from the API, which offline parsing can't do.
     *
     * @return the scheduled work if the event was a closed PR (merged or not), else {@code null}
     */
    static List<Future<?>> handleMerged(String rawPayload) {
        net.sf.json.JSONObject json;
        try {
            json = net.sf.json.JSONObject.fromObject(rawPayload);
        } catch (RuntimeException e) {
            return null;
        }
        if (!"closed".equals(json.optString("action"))) {
            return null;
        }
        net.sf.json.JSONObject pr = json.optJSONObject("pull_request");
        net.sf.json.JSONObject repo = json.optJSONObject("repository");
        if (pr == null || repo == null) {
            return List.of();
        }
        String head = pr.optJSONObject("head") != null ? pr.getJSONObject("head").optString("ref", null) : null;
        String base = pr.optJSONObject("base") != null ? pr.getJSONObject("base").optString("ref", null) : null;
        return merged(
                repo.optString("full_name"), String.valueOf(json.optInt("number")), pr.optBoolean("merged"), head, base);
    }

    /** Merges baselines for a merged PR (if enabled) and drops its review from the registry either way. */
    static List<Future<?>> merged(String repoSlug, String number, boolean merged, String headRef, String baseRef) {
        List<Future<?>> work = new ArrayList<>();
        ReviewRegistry registry = ReviewRegistry.get();
        for (PendingReview review : registry.forPullRequest(repoSlug, number)) {
            registry.remove(review);
            if (!merged || headRef == null || baseRef == null
                    || !io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration.get().isMergeBaselines()) {
                continue;
            }
            work.add(Timer.get().submit(() -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    Run<?, ?> run = findRun(review);
                    if (run != null) {
                        io.jenkins.plugins.applitools.BaselineMerge.merge(
                                review, run, headRef, baseRef, TaskListener.NULL);
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

    /** Applies a label change to the PR's tracked review and re-posts its Design review check. */
    static List<Future<?>> handle(String repoSlug, String number, String labelName, boolean added) {
        List<Future<?>> work = new ArrayList<>();
        ReviewRegistry registry = ReviewRegistry.get();
        for (PendingReview review : registry.forPullRequest(repoSlug, number)) {
            if (!review.isLabelDriven() || !review.getLabel().equalsIgnoreCase(labelName)) {
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

    private static Run<?, ?> findRun(PendingReview review) {
        hudson.model.Job<?, ?> job = Jenkins.get().getItemByFullName(review.getJobFullName(), hudson.model.Job.class);
        return job != null ? job.getBuildByNumber(review.getBuildNumber()) : null;
    }
}
