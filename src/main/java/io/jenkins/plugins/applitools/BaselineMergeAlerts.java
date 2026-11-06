package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.applitools.review.MergeHistory;
import io.jenkins.plugins.applitools.review.MergeHistory.Outcome;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.applitools.review.UnmergedBaselines;
import io.jenkins.plugins.applitools.scm.PullRequestComments;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksStatus;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Makes a merged PR whose Eyes baselines weren't merged hard to miss, until they are: a comment and label on the PR
 * (which notifies its author and reviewers), and in every build of the target branch a retry of the merge, a failing
 * "Applitools / Baseline merge" check and an unstable result while it still fails. Once a retry succeeds, the comment
 * says so, the label goes, and the check turns green.
 */
public final class BaselineMergeAlerts {

    private static final Logger LOGGER = Logger.getLogger(BaselineMergeAlerts.class.getName());
    private static final String PREFIX = "[Applitools] ";

    /** Label on a merged PR whose baselines aren't merged; searchable, e.g. {@code label:eyes-baselines-not-merged}. */
    public static final String LABEL = "eyes-baselines-not-merged";

    /** Hidden in the comment's body, so the plugin finds and edits its own comment instead of adding another. */
    static final String MARKER = "<!-- applitools-baseline-merge -->";

    private BaselineMergeAlerts() {}

    /** Longest a build of the target branch waits for each retried merge. */
    static long retryTimeoutMillis() {
        return TimeUnit.SECONDS.toMillis(Long.getLong(BaselineMergeAlerts.class.getName() + ".retryTimeoutSeconds", 120));
    }

    /**
     * After a PR's merge was attempted or skipped: lists the PR as unmerged (and comments on it) when its baselines
     * didn't merge, or clears it when they did.
     *
     * @param run the PR's build, when it still exists; without it the PR is commented on by the next build of the
     *     target branch
     */
    public static void outcome(
            @NonNull PendingReview review,
            @CheckForNull Run<?, ?> run,
            @CheckForNull String source,
            @CheckForNull String target,
            @NonNull Outcome outcome,
            @NonNull String title,
            @NonNull String summary) {
        String slug = review.getRepoSlug();
        String number = review.getChangeId();
        if (slug == null || number == null) {
            return;
        }
        try {
            if (outcome.isOk()) {
                UnmergedBaselines.Item item = UnmergedBaselines.get().merged(slug, number);
                if (item != null && run != null) {
                    notifyMerged(run, item, title);
                }
            } else if (outcome != Outcome.TURNED_OFF && source != null && target != null) {
                UnmergedBaselines.Item item =
                        UnmergedBaselines.get().failed(slug, number, source, target, outcome, title, summary);
                if (run != null) {
                    notifyFailing(run, item);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not record the baseline merge outcome of " + slug + "#" + number, e);
        }
    }

    /**
     * For a build of a branch, before its tests: retries the merges into its Eyes branch that haven't happened, then
     * reports them on the build's commit and, while any still fails, marks the build unstable.
     */
    static void retry(
            @NonNull Run<?, ?> build,
            @NonNull String eyesBranch,
            @NonNull String serverUrl,
            @NonNull String runCredentialsId,
            @NonNull TaskListener listener)
            throws InterruptedException {
        List<UnmergedBaselines.Item> items = UnmergedBaselines.get().into(eyesBranch);
        if (items.isEmpty()) {
            return;
        }
        PrintStream log = listener.getLogger();
        String key = BaselineMerge.mergeKey(build, runCredentialsId);
        List<String> failing = new ArrayList<>();
        List<String> merged = new ArrayList<>();
        for (UnmergedBaselines.Item item : items) {
            String branch = item.getTargetBranch();
            log.println(PREFIX + "Retrying the baseline merge of PR #" + item.getPullRequest() + " ("
                    + item.getSource() + " → " + item.getTarget() + "): " + item.getTitle() + " since "
                    + format(item.getSince()));
            BaselineMerge.Attempt attempt = key == null
                    ? BaselineMerge.noKey(item.getSource(), item.getTarget())
                    : BaselineMerge.attempt(new EyesClient(serverUrl, key), item.getSource(), item.getTarget(),
                            branch, retryTimeoutMillis());
            String pr = "**" + item.getPullRequestName() + "**";
            if (attempt.outcome().isOk()) {
                UnmergedBaselines.get().merged(item.getRepoSlug(), item.getPullRequest());
                MergeHistory.get().add(new MergeHistory.Entry(item.getRepoSlug(), item.getPullRequest(),
                        item.getSource(), item.getTarget(), attempt.outcome(), attempt.title(),
                        attempt.summary() + " Retried by " + build.getFullDisplayName() + ".",
                        System.currentTimeMillis(), build.getUrl(), build.getFullDisplayName()));
                for (PendingReview review : ReviewRegistry.get().forPullRequest(item.getRepoSlug(),
                        item.getPullRequest())) {
                    if (review.isClosed()) {
                        ReviewRegistry.get().remove(review); // nothing left for a redelivered webhook to retry
                    }
                }
                notifyMerged(build, item, attempt.title());
                log.println(PREFIX + "Baseline merge of PR #" + item.getPullRequest() + " retried: "
                        + attempt.title() + ". " + plain(attempt.summary()));
                merged.add("- " + pr + ": " + attempt.title() + ". " + attempt.summary());
            } else {
                UnmergedBaselines.Item updated = UnmergedBaselines.get().failed(item.getRepoSlug(),
                        item.getPullRequest(), item.getSource(), item.getTarget(), attempt.outcome(),
                        attempt.title(), attempt.summary());
                notifyFailing(build, updated);
                log.println(PREFIX + "WARNING: The baselines of PR #" + item.getPullRequest() + " still aren't merged"
                        + " into " + branch + ": " + attempt.title() + ". " + plain(attempt.summary())
                        + " Until they are, this build's visual tests may flag the changes that PR accepted.");
                failing.add("- " + pr + ": **" + attempt.title() + ".** " + attempt.summary());
            }
        }
        report(build, listener, eyesBranch, failing, merged);
        if (!failing.isEmpty() && ApplitoolsGlobalConfiguration.get().isUnstableWhileUnmerged()) {
            build.setResult(Result.UNSTABLE);
            log.println(PREFIX + "Marked unstable: " + failing.size() + " merged pull request"
                    + (failing.size() == 1 ? "'s" : "s'") + " baselines aren't merged into " + eyesBranch
                    + ". See Manage Jenkins → Applitools setup → Unmerged baselines.");
        }
    }

    /** The "Applitools / Baseline merge" check on the branch build's commit. */
    private static void report(
            Run<?, ?> build, TaskListener listener, String eyesBranch, List<String> failing, List<String> merged) {
        String title;
        StringBuilder summary = new StringBuilder();
        if (!failing.isEmpty()) {
            title = failing.size() == 1
                    ? "Baselines of a merged PR aren't merged"
                    : failing.size() + " merged PRs' baselines aren't merged";
            summary.append("Not merged into `").append(eyesBranch).append("`:\n\n")
                    .append(String.join("\n", failing))
                    .append("\n\nEach build of this branch retries the merge before its tests. Until it succeeds,"
                            + " the tests may flag changes those PRs accepted.");
            if (!merged.isEmpty()) {
                summary.append("\n\nMerged by this build:\n\n").append(String.join("\n", merged));
            }
        } else {
            title = merged.size() == 1 ? "Retried baseline merge succeeded" : merged.size() + " retried baseline merges succeeded";
            summary.append("Merged into `").append(eyesBranch).append("` by this build:\n\n")
                    .append(String.join("\n", merged));
        }
        VisualTestsCheck.publish(build, listener, new ChecksDetails.ChecksDetailsBuilder()
                .withName(BaselineMerge.CHECK_NAME)
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(failing.isEmpty() ? ChecksConclusion.SUCCESS : ChecksConclusion.FAILURE)
                .withCompletedAt(VisualTestsCheck.now())
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle(title)
                        .withSummary(summary.toString())
                        .build())
                .build());
    }

    /** Comments on and labels the PR, unless that was already done for the same reason. */
    private static void notifyFailing(Run<?, ?> run, UnmergedBaselines.Item item) {
        if (!ApplitoolsGlobalConfiguration.get().isCommentOnUnmergedBaselines()
                || item.getTitle().equals(item.getCommented())) {
            return;
        }
        update(run, item, failingComment(item), true);
    }

    /** Updates the PR's comment to say its baselines are merged, and removes the label, if the plugin had added them. */
    private static void notifyMerged(Run<?, ?> run, UnmergedBaselines.Item item, String title) {
        if (item.getCommented() == null) {
            return; // nothing was posted (the comment comes before the label, so no label either)
        }
        update(run, item, mergedComment(item, run, title), false);
    }

    private static void update(Run<?, ?> run, UnmergedBaselines.Item item, String body, boolean labeled) {
        String error;
        try {
            error = PullRequestComments.of(run, item.getRepoSlug(), item.getPullRequest(), MARKER, body, LABEL, labeled)
                    ? null
                    : run.getFullDisplayName() + " has no GitHub connection the plugin can comment with";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        } catch (Exception e) {
            error = "GitHub refused the comment or label (" + e.getMessage() + "). Give the GitHub App or token"
                    + " \"Pull requests: Read and write\".";
        }
        if (error != null) {
            LOGGER.log(Level.WARNING, "Could not update the comment and label on {0}: {1}",
                    new Object[] {item.getPullRequestName(), error});
        }
        if (labeled) {
            UnmergedBaselines.get().commented(item, error);
        }
    }

    static String failingComment(UnmergedBaselines.Item item) {
        String branch = item.getTargetBranch();
        boolean unstable = ApplitoolsGlobalConfiguration.get().isUnstableWhileUnmerged();
        return MARKER + "\n"
                + "### ⚠️ Eyes baselines from this PR weren't merged into `" + branch + "`\n\n"
                + "**" + item.getTitle() + ".** " + item.getSummary() + "\n\n"
                + "Until they're merged, builds of `" + branch + "` show a failing **" + BaselineMerge.CHECK_NAME
                + "** check" + (unstable ? " and are marked unstable" : "") + ", and their visual tests may flag the"
                + " changes this PR accepted. Each build of `" + branch + "` retries the merge first; when one"
                + " succeeds, this comment is updated and the `" + LABEL + "` label removed.";
    }

    static String mergedComment(UnmergedBaselines.Item item, Run<?, ?> run, String title) {
        String root = Jenkins.get().getRootUrl();
        String by = root != null ? "[" + run.getFullDisplayName() + "](" + root + run.getUrl() + ")" : run.getFullDisplayName();
        return MARKER + "\n"
                + "### ✅ Eyes baselines from this PR are merged into `" + item.getTargetBranch() + "`\n\n"
                + title + ", by " + by + " on " + format(System.currentTimeMillis()) + ". Earlier: "
                + item.getTitle() + " (since " + format(item.getSince()) + ").";
    }

    private static String plain(String markdown) {
        return markdown.replace("**", "").replace("`", "");
    }

    private static String format(long time) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(time));
    }
}
