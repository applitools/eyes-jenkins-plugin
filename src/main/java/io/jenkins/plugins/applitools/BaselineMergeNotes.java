package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.console.HyperlinkNote;
import hudson.model.InvisibleAction;
import hudson.model.Run;
import io.jenkins.plugins.applitools.review.MergeHistory;
import io.jenkins.plugins.applitools.review.ReviewEvents;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

/**
 * For a branch build (not a PR): before its tests run, waits a little for baseline merges into its Eyes branch that are
 * still running, so the tests don't compare against baselines without them, then prints every merge into the branch
 * since the branch was last built, including the ones that failed or were skipped and what to do about them.
 */
final class BaselineMergeNotes {

    private static final String PREFIX = "[Applitools] ";

    /** Longest a build waits for a running merge into its branch. */
    static long waitMillis() {
        return TimeUnit.SECONDS.toMillis(Long.getLong(BaselineMergeNotes.class.getName() + ".waitSeconds", 60));
    }

    private static long pollMillis() {
        return Long.getLong(BaselineMergeNotes.class.getName() + ".pollMillis", 500);
    }

    /** How many earlier builds to look through for the last one that printed merges into the same branch. */
    private static final int LOOKBACK = 20;

    private BaselineMergeNotes() {}

    static void beforeTests(@NonNull Run<?, ?> build, @NonNull String eyesBranch, @NonNull PrintStream log)
            throws InterruptedException {
        waitForRunningMerges(eyesBranch, log);
        long since = shownUpTo(build, eyesBranch);
        long now = System.currentTimeMillis();
        for (MergeHistory.Entry e : MergeHistory.get().into(eyesBranch, since)) {
            if (e.getTime() > now || e.getOutcome() == MergeHistory.Outcome.TURNED_OFF) {
                continue; // turned off on purpose: not worth a line in every build
            }
            String what = "Baseline merge from PR #" + e.getPullRequest() + ": " + e.getTitle() + ". "
                    + e.getPlainSummary();
            if (e.getOutcome().isOk()) {
                log.println(PREFIX + what);
            } else {
                log.println(PREFIX + "WARNING: " + what
                        + " Until the baselines are merged, this build's tests may flag the changes the PR accepted."
                        + (e.getBuildUrl() != null
                                ? " PR build: " + HyperlinkNote.encodeTo("/" + e.getBuildUrl(), e.getBuildName())
                                : ""));
            }
        }
        build.addOrReplaceAction(new Shown(eyesBranch, now));
    }

    private static void waitForRunningMerges(String eyesBranch, PrintStream log) throws InterruptedException {
        if (!ReviewEvents.isMergingInto(eyesBranch)) {
            return;
        }
        long start = System.currentTimeMillis();
        long wait = waitMillis();
        log.println(PREFIX + "A baseline merge into " + eyesBranch + " is running or about to start; waiting up to "
                + TimeUnit.MILLISECONDS.toSeconds(wait) + "s for it before the tests start");
        while (ReviewEvents.isMergingInto(eyesBranch) && System.currentTimeMillis() - start < wait) {
            Thread.sleep(pollMillis());
        }
        long waited = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - start);
        if (ReviewEvents.isMergingInto(eyesBranch)) {
            log.println(PREFIX + "WARNING: The baseline merge into " + eyesBranch + " was still running after "
                    + waited + "s; not waiting longer. This build's tests may flag changes the merged PR accepted;"
                    + " if they do, run the build again once the merge is done (see Manage Jenkins → Applitools"
                    + " setup → Recent baseline merges).");
        } else {
            log.println(PREFIX + "Waited " + waited + "s for the baseline merge into " + eyesBranch);
        }
    }

    /** When merges into this branch were last printed: by an earlier build of it, or when the previous build started. */
    private static long shownUpTo(Run<?, ?> build, String eyesBranch) {
        Run<?, ?> previous = build.getPreviousBuild();
        Run<?, ?> r = previous;
        for (int i = 0; r != null && i < LOOKBACK; i++, r = r.getPreviousBuild()) {
            Shown shown = r.getAction(Shown.class);
            if (shown != null && shown.eyesBranch.equalsIgnoreCase(eyesBranch)) {
                return shown.upTo;
            }
        }
        return previous != null ? previous.getStartTimeInMillis() : 0;
    }

    /** Marks a build that printed the merges into its branch up to a time, so the next build starts from there. */
    static final class Shown extends InvisibleAction {
        private final String eyesBranch;
        private final long upTo;

        Shown(String eyesBranch, long upTo) {
            this.eyesBranch = eyesBranch;
            this.upTo = upTo;
        }
    }
}
