package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.applitools.review.MergeHistory;
import io.jenkins.plugins.applitools.review.MergeHistory.Outcome;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksStatus;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * When a PR merges, merges its Eyes baseline branch into the target branch's, so the changes reviewers accepted on the
 * PR don't show up as new diffs on the target branch. Reports the outcome as an "Applitools / Baseline merge" check on
 * the PR's last tested commit. A merge that fails is retried by each build of the target branch until it succeeds; see
 * {@link BaselineMergeAlerts}.
 */
public final class BaselineMerge {

    static final String CHECK_NAME = "Applitools / Baseline merge";
    private static final Logger LOGGER = Logger.getLogger(BaselineMerge.class.getName());

    static final long POLL_MILLIS = Long.getLong(BaselineMerge.class.getName() + ".pollMillis", 5000);
    public static final long TIMEOUT_MILLIS =
            TimeUnit.SECONDS.toMillis(Long.getLong(BaselineMerge.class.getName() + ".timeoutSeconds", 600));

    private BaselineMerge() {}

    /**
     * Merges {@code owner/repo/headRef} into {@code owner/repo/baseRef} using the PR's last build for credentials.
     *
     * @return true if Eyes merged (or had nothing to merge); false if it should be retried after a fix
     */
    public static boolean merge(
            @NonNull PendingReview review,
            @NonNull Run<?, ?> run,
            @NonNull String headRef,
            @NonNull String baseRef,
            @NonNull TaskListener listener)
            throws InterruptedException {
        return merge(review, run, headRef, baseRef, listener, TIMEOUT_MILLIS);
    }

    /** {@link #merge} waiting at most {@code timeoutMillis} for Eyes. */
    public static boolean merge(
            @NonNull PendingReview review,
            @NonNull Run<?, ?> run,
            @NonNull String headRef,
            @NonNull String baseRef,
            @NonNull TaskListener listener,
            long timeoutMillis)
            throws InterruptedException {
        String slug = review.getRepoSlug();
        if (slug == null) {
            return true; // not a PR we can map to Eyes branches; nothing to retry
        }
        // The branch recorded at build time: right for forks (owner/repo/pr-N) and for a step's own branchName.
        String source = review.getEyesBranch() != null ? review.getEyesBranch() : slug + "/" + headRef;
        String target = slug + "/" + baseRef;
        String key = mergeKey(run, review.getRunCredentialsId());
        Attempt attempt = key == null
                ? noKey(source, target)
                : attempt(new EyesClient(review.getServerUrl(), key), source, target, baseRef, timeoutMillis);
        report(review, run, listener, source, target, attempt.outcome(), attempt.title(), attempt.summary());
        return attempt.outcome().isOk();
    }

    /** What one merge attempt came to. {@code summary} is Markdown. */
    record Attempt(Outcome outcome, String title, String summary) {}

    static Attempt noKey(String source, String target) {
        return new Attempt(Outcome.NO_KEY, "Baseline merge skipped",
                "No Eyes API key credential was available to merge `" + source + "` into `" + target + "`. Add an"
                        + " Eyes merge key as a Secret text credential `"
                        + ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID + "`.");
    }

    /** Asks Eyes to merge {@code source} into {@code target} and waits at most {@code timeoutMillis} for it. */
    static Attempt attempt(EyesClient eyes, String source, String target, String baseRef, long timeoutMillis)
            throws InterruptedException {
        try {
            String jobId = eyes.startBranchMerge(source, target, false);
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!eyes.isTaskDone(jobId)) {
                if (System.currentTimeMillis() > deadline) {
                    return new Attempt(Outcome.TIMED_OUT, "Baseline merge timed out",
                            "Eyes was still merging `" + source + "` into `" + target + "` after "
                                    + TimeUnit.MILLISECONDS.toSeconds(timeoutMillis) + "s. Check the branch in Eyes.");
                }
                Thread.sleep(POLL_MILLIS);
            }
            EyesClient.MergeResult result = eyes.taskResult(jobId);
            if (result.conflicts() > 0) {
                return new Attempt(Outcome.CONFLICTS,
                        result.conflicts() + " baseline conflict" + (result.conflicts() == 1 ? "" : "s"),
                        "The same checkpoints changed on `" + baseRef + "` too. Resolve them in Eyes under **Compare &"
                                + " merge branches** (`" + source + "` → `" + target + "`).");
            } else if (result.merged()) {
                return new Attempt(Outcome.MERGED, "Baselines merged into " + baseRef,
                        result.changes() + " baseline change" + (result.changes() == 1 ? "" : "s")
                                + " from `" + source + "` now apply to `" + target + "`.");
            }
            return new Attempt(Outcome.NOTHING_TO_MERGE, "Nothing to merge",
                    "`" + source + "` had no baseline changes for `" + target + "`.");
        } catch (EyesClient.UnauthorizedException e) {
            return new Attempt(Outcome.REFUSED, "Eyes refused the baseline merge",
                    "HTTP " + e.getStatus() + ": only Eyes **merge** keys (created for a team and a user) can merge"
                            + " baselines. Add one as a Secret text credential `"
                            + ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID + "`, or set one in Manage"
                            + " Jenkins → System → Applitools Eyes. The next build of `" + baseRef + "` retries the"
                            + " merge.");
        } catch (IOException e) {
            return new Attempt(Outcome.FAILED, "Baseline merge failed", String.valueOf(e.getMessage()));
        }
    }

    /** Merge key: the configured merge credential, then the conventional ID, then the run key. */
    static String mergeKey(Run<?, ?> run, String runCredentialsId) {
        String configured = ApplitoolsGlobalConfiguration.get().getMergeCredentialsId();
        for (String id : new String[] {
            configured, ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID, runCredentialsId
        }) {
            if (id != null) {
                String key = ApplitoolsBuildWrapper.lookupApiKey(run, id);
                if (key != null) {
                    return key;
                }
            }
        }
        return null;
    }

    private static void report(
            PendingReview review,
            Run<?, ?> run,
            TaskListener listener,
            String source,
            String target,
            Outcome outcome,
            String title,
            String summary) {
        MergeHistory.record(review, run, source, target, outcome, title, summary);
        ChecksConclusion conclusion = outcome.isOk() ? ChecksConclusion.SUCCESS : ChecksConclusion.FAILURE;
        listener.getLogger().println("[Applitools] " + title + ": " + summary);
        LOGGER.log(conclusion == ChecksConclusion.SUCCESS ? Level.INFO : Level.WARNING,
                "{0}: {1}: {2}", new Object[] {run.getFullDisplayName(), title, summary});
        VisualTestsCheck.publish(run, listener, new ChecksDetails.ChecksDetailsBuilder()
                .withName(CHECK_NAME)
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(conclusion)
                .withCompletedAt(VisualTestsCheck.now())
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle(title)
                        .withSummary(summary)
                        .build())
                .build());
        BaselineMergeAlerts.outcome(review, run, source, target, outcome, title, summary);
    }
}
