package io.jenkins.plugins.applitools.review;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.BulkChange;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * The most recent Eyes baseline merge outcomes (merged, nothing to merge, failed or skipped), saved in {@code
 * $JENKINS_HOME}. Shown on the setup page and printed by the next build of the target branch, so a merge that didn't
 * happen is visible in Jenkins, not only on the PR's last tested commit and in the system log.
 */
@Extension
public class MergeHistory implements Saveable {

    private static final Logger LOGGER = Logger.getLogger(MergeHistory.class.getName());

    /** Most outcomes kept; older ones are dropped. */
    static int maxEntries() {
        return Integer.getInteger(MergeHistory.class.getName() + ".maxEntries", 50);
    }

    /** What happened to one PR's baselines. */
    public enum Outcome {
        MERGED(true, "Merged"),
        NOTHING_TO_MERGE(true, "Nothing to merge"),
        CONFLICTS(false, "Conflicts"),
        TIMED_OUT(false, "Timed out"),
        REFUSED(false, "Key refused"),
        NO_KEY(false, "No merge key"),
        FAILED(false, "Failed"),
        SKIPPED(false, "Skipped"),
        /** Merging is turned off for the job or globally: deliberate, so builds don't warn about it. */
        TURNED_OFF(true, "Turned off");

        private final boolean ok;
        private final String label;

        Outcome(boolean ok, String label) {
            this.ok = ok;
            this.label = label;
        }

        public boolean isOk() {
            return ok;
        }

        public String getLabel() {
            return label;
        }
    }

    /** One outcome. Source and target are Eyes branch names ({@code owner/repo/branch}); either may be unknown. */
    public static final class Entry {
        private final String repoSlug;
        private final String pullRequest;
        private final String source;
        private final String target;
        private final Outcome outcome;
        private final String title;
        private final String summary;
        private final long time;
        /** The PR build, relative to Jenkins' root URL; {@code null} when the build was gone. */
        private final String buildUrl;
        private final String buildName;

        public Entry(
                @CheckForNull String repoSlug,
                @CheckForNull String pullRequest,
                @CheckForNull String source,
                @CheckForNull String target,
                @NonNull Outcome outcome,
                @NonNull String title,
                @NonNull String summary,
                long time,
                @CheckForNull String buildUrl,
                @CheckForNull String buildName) {
            this.repoSlug = repoSlug;
            this.pullRequest = pullRequest;
            this.source = source;
            this.target = target;
            this.outcome = outcome;
            this.title = title;
            this.summary = summary;
            this.time = time;
            this.buildUrl = buildUrl;
            this.buildName = buildName;
        }

        @CheckForNull
        public String getRepoSlug() {
            return repoSlug;
        }

        @CheckForNull
        public String getPullRequest() {
            return pullRequest;
        }

        @CheckForNull
        public String getSource() {
            return source;
        }

        @CheckForNull
        public String getTarget() {
            return target;
        }

        @NonNull
        public Outcome getOutcome() {
            return outcome;
        }

        @NonNull
        public String getTitle() {
            return title;
        }

        /** The summary as posted to GitHub (Markdown). */
        @NonNull
        public String getSummary() {
            return summary;
        }

        /** The summary without Markdown, for the console and the setup page. */
        @NonNull
        public String getPlainSummary() {
            return summary.replace("**", "").replace("`", "");
        }

        public long getTime() {
            return time;
        }

        @NonNull
        public Date getDate() {
            return new Date(time);
        }

        @CheckForNull
        public String getBuildUrl() {
            return buildUrl;
        }

        @CheckForNull
        public String getBuildName() {
            return buildName;
        }

        /** {@code acme/web#13}, or what's known of it. */
        @NonNull
        public String getPullRequestName() {
            return (repoSlug != null ? repoSlug : "?") + "#" + (pullRequest != null ? pullRequest : "?");
        }

        /** Whether this merge went into the given Eyes branch (Eyes branch names ignore case, as GitHub's do). */
        public boolean isInto(@NonNull String eyesBranch) {
            return target != null && target.toLowerCase(Locale.ROOT).equals(eyesBranch.toLowerCase(Locale.ROOT));
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private transient boolean loaded;

    @NonNull
    public static MergeHistory get() {
        return ExtensionList.lookupSingleton(MergeHistory.class);
    }

    private XmlFile file() {
        return new XmlFile(Jenkins.XSTREAM2, new File(Jenkins.get().getRootDir(), getClass().getName() + ".xml"));
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        XmlFile file = file();
        if (file.exists()) {
            try {
                file.unmarshal(this);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not load " + file, e);
            }
        }
    }

    /**
     * Records an outcome for a review's PR.
     *
     * @param run the PR build, when it still exists
     */
    public static void record(
            @NonNull PendingReview review,
            @CheckForNull Run<?, ?> run,
            @CheckForNull String source,
            @CheckForNull String target,
            @NonNull Outcome outcome,
            @NonNull String title,
            @NonNull String summary) {
        get().add(new Entry(
                review.getRepoSlug(),
                review.getChangeId(),
                source,
                target,
                outcome,
                title,
                summary,
                System.currentTimeMillis(),
                run != null ? run.getUrl() : null,
                run != null ? run.getFullDisplayName() : review.getJobFullName() + " #" + review.getBuildNumber()));
    }

    public synchronized void add(@NonNull Entry entry) {
        ensureLoaded();
        entries.add(entry);
        int excess = entries.size() - maxEntries();
        if (excess > 0) {
            entries.subList(0, excess).clear();
        }
        try {
            save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save Applitools baseline merge history", e);
        }
    }

    /** Every kept outcome, newest first. */
    @NonNull
    public synchronized List<Entry> recent() {
        ensureLoaded();
        List<Entry> newestFirst = new ArrayList<>(entries);
        java.util.Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** Outcomes of merges into an Eyes branch recorded after {@code since}, oldest first. */
    @NonNull
    public synchronized List<Entry> into(@NonNull String eyesBranch, long since) {
        ensureLoaded();
        return entries.stream().filter(e -> e.time > since && e.isInto(eyesBranch)).toList();
    }

    /** The latest outcome for a PR, e.g. to tell which branches a later skip was about. */
    @CheckForNull
    public synchronized Entry latestFor(@CheckForNull String repoSlug, @CheckForNull String pullRequest) {
        ensureLoaded();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.repoSlug != null && e.repoSlug.equalsIgnoreCase(repoSlug) && e.pullRequest != null
                    && e.pullRequest.equals(pullRequest)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public synchronized void save() throws IOException {
        if (BulkChange.contains(this)) {
            return;
        }
        file().write(this);
        SaveableListener.fireOnChange(this, file());
    }
}
