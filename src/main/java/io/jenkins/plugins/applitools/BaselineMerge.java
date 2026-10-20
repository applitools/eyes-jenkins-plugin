package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
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
 * the PR's last tested commit.
 */
public final class BaselineMerge {

    static final String CHECK_NAME = "Applitools / Baseline merge";
    private static final Logger LOGGER = Logger.getLogger(BaselineMerge.class.getName());

    static final long POLL_MILLIS = Long.getLong(BaselineMerge.class.getName() + ".pollMillis", 5000);
    static final long TIMEOUT_MILLIS =
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
        String slug = review.getRepoSlug();
        if (slug == null) {
            return true; // not a PR we can map to Eyes branches; nothing to retry
        }
        String source = slug + "/" + headRef;
        String target = slug + "/" + baseRef;
        String key = mergeKey(run, review);
        if (key == null) {
            report(run, listener, ChecksConclusion.FAILURE, "Baseline merge skipped",
                    "No Eyes API key credential was available to merge `" + source + "` into `" + target + "`.");
            return false;
        }
        EyesClient eyes = new EyesClient(review.getServerUrl(), key);
        try {
            String jobId = eyes.startBranchMerge(source, target, false);
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while (!eyes.isTaskDone(jobId)) {
                if (System.currentTimeMillis() > deadline) {
                    report(run, listener, ChecksConclusion.FAILURE, "Baseline merge timed out",
                            "Eyes was still merging `" + source + "` into `" + target + "` after "
                                    + TimeUnit.MILLISECONDS.toSeconds(TIMEOUT_MILLIS) + "s. Check the branch in Eyes.");
                    return false;
                }
                Thread.sleep(POLL_MILLIS);
            }
            EyesClient.MergeResult result = eyes.taskResult(jobId);
            if (result.conflicts() > 0) {
                report(run, listener, ChecksConclusion.FAILURE,
                        result.conflicts() + " baseline conflict" + (result.conflicts() == 1 ? "" : "s"),
                        "The same checkpoints changed on `" + baseRef + "` too. Resolve them in Eyes under **Compare &"
                                + " merge branches** (`" + source + "` → `" + target + "`).");
                return false;
            } else if (result.merged()) {
                report(run, listener, ChecksConclusion.SUCCESS, "Baselines merged into " + baseRef,
                        result.changes() + " baseline change" + (result.changes() == 1 ? "" : "s")
                                + " from `" + source + "` now apply to `" + target + "`.");
            } else {
                report(run, listener, ChecksConclusion.SUCCESS, "Nothing to merge",
                        "`" + source + "` had no baseline changes for `" + target + "`.");
            }
            return true;
        } catch (EyesClient.UnauthorizedException e) {
            report(run, listener, ChecksConclusion.FAILURE, "Eyes refused the baseline merge",
                    "HTTP " + e.getStatus() + ": this key can't merge baselines. Add a Secret text credential with a"
                            + " key that can, as `" + ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID
                            + "`, or set one in Manage Jenkins → System → Applitools Eyes. Then redeliver the PR's"
                            + " `closed` webhook (GitHub App → Advanced → Recent Deliveries) to retry.");
            return false;
        } catch (IOException e) {
            report(run, listener, ChecksConclusion.FAILURE, "Baseline merge failed", e.getMessage());
            return false;
        }
    }

    /** Merge key: the configured merge credential, then the conventional ID, then the run key. */
    private static String mergeKey(Run<?, ?> run, PendingReview review) {
        String configured = ApplitoolsGlobalConfiguration.get().getMergeCredentialsId();
        for (String id : new String[] {
            configured, ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID, review.getRunCredentialsId()
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
            Run<?, ?> run, TaskListener listener, ChecksConclusion conclusion, String title, String summary) {
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
    }
}
