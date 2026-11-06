package io.jenkins.plugins.applitools.review;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.BulkChange;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
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
 * Merged PRs whose Eyes baselines aren't merged into their target branch yet (conflicts, a refused key, a timeout,
 * the PR's build gone…), saved in {@code $JENKINS_HOME}. Each build of the target branch retries them before its tests
 * run, so they stay visible (a failing check, an unstable build, a comment and label on the PR) until the merge
 * happens. Unlike {@link MergeHistory}, nothing is dropped for age: an entry leaves only when its baselines merge.
 */
@Extension
public class UnmergedBaselines implements Saveable {

    private static final Logger LOGGER = Logger.getLogger(UnmergedBaselines.class.getName());

    /** One PR whose baselines aren't merged. Eyes branch names are {@code owner/repo/branch}. */
    public static final class Item {
        private final String repoSlug;
        private final String pullRequest;
        private final String source;
        private final String target;
        private final long since;
        private MergeHistory.Outcome outcome;
        private String title;
        private String summary;
        private long lastTried;
        /** The title last posted to the PR's comment; {@code null} until a comment was posted. */
        private String commented;
        /** Why the comment or label couldn't be posted, for the setup page; {@code null} if it was. */
        private String commentError;

        Item(String repoSlug, String pullRequest, String source, String target, long since) {
            this.repoSlug = repoSlug;
            this.pullRequest = pullRequest;
            this.source = source;
            this.target = target;
            this.since = since;
        }

        @NonNull
        public String getRepoSlug() {
            return repoSlug;
        }

        @NonNull
        public String getPullRequest() {
            return pullRequest;
        }

        @NonNull
        public String getSource() {
            return source;
        }

        @NonNull
        public String getTarget() {
            return target;
        }

        /** The branch name after {@code owner/repo/}, e.g. {@code main}. */
        @NonNull
        public String getTargetBranch() {
            return target.length() > repoSlug.length() + 1 ? target.substring(repoSlug.length() + 1) : target;
        }

        @NonNull
        public MergeHistory.Outcome getOutcome() {
            return outcome;
        }

        @NonNull
        public String getTitle() {
            return title;
        }

        /** Markdown, as posted to GitHub. */
        @NonNull
        public String getSummary() {
            return summary;
        }

        @NonNull
        public String getPlainSummary() {
            return summary.replace("**", "").replace("`", "");
        }

        public long getSince() {
            return since;
        }

        @NonNull
        public Date getSinceDate() {
            return new Date(since);
        }

        public long getLastTried() {
            return lastTried;
        }

        @CheckForNull
        public String getCommented() {
            return commented;
        }

        @CheckForNull
        public String getCommentError() {
            return commentError;
        }

        /** {@code acme/web#13}. */
        @NonNull
        public String getPullRequestName() {
            return repoSlug + "#" + pullRequest;
        }

        boolean is(String repo, String number) {
            return repoSlug.equalsIgnoreCase(repo) && pullRequest.equals(number);
        }

        public boolean isInto(@NonNull String eyesBranch) {
            return target.toLowerCase(Locale.ROOT).equals(eyesBranch.toLowerCase(Locale.ROOT));
        }
    }

    private final List<Item> items = new ArrayList<>();
    private transient boolean loaded;

    @NonNull
    public static UnmergedBaselines get() {
        return ExtensionList.lookupSingleton(UnmergedBaselines.class);
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

    /** Records (or updates) a PR whose baselines didn't merge; returns its entry. */
    @NonNull
    public synchronized Item failed(
            @NonNull String repoSlug,
            @NonNull String pullRequest,
            @NonNull String source,
            @NonNull String target,
            @NonNull MergeHistory.Outcome outcome,
            @NonNull String title,
            @NonNull String summary) {
        ensureLoaded();
        long now = System.currentTimeMillis();
        Item item = find(repoSlug, pullRequest);
        if (item == null || !item.target.equalsIgnoreCase(target)) {
            if (item != null) {
                items.remove(item);
            }
            item = new Item(repoSlug, pullRequest, source, target, now);
            items.add(item);
        }
        item.outcome = outcome;
        item.title = title;
        item.summary = summary;
        item.lastTried = now;
        saveQuietly();
        return item;
    }

    /** Notes whether the PR's comment and label are posted for the item's current title. */
    public synchronized void commented(@NonNull Item item, @CheckForNull String error) {
        if (error == null) {
            item.commented = item.title;
        }
        item.commentError = error;
        saveQuietly();
    }

    /** Removes a PR once its baselines merged; returns its entry, or {@code null} if it wasn't listed. */
    @CheckForNull
    public synchronized Item merged(@NonNull String repoSlug, @NonNull String pullRequest) {
        ensureLoaded();
        Item item = find(repoSlug, pullRequest);
        if (item != null) {
            items.remove(item);
            saveQuietly();
        }
        return item;
    }

    @CheckForNull
    public synchronized Item find(@CheckForNull String repoSlug, @CheckForNull String pullRequest) {
        ensureLoaded();
        if (repoSlug == null || pullRequest == null) {
            return null;
        }
        return items.stream().filter(i -> i.is(repoSlug, pullRequest)).findFirst().orElse(null);
    }

    /** The PRs whose baselines aren't merged into an Eyes branch, oldest first. */
    @NonNull
    public synchronized List<Item> into(@NonNull String eyesBranch) {
        ensureLoaded();
        return items.stream().filter(i -> i.isInto(eyesBranch)).toList();
    }

    /** Every listed PR, oldest first. */
    @NonNull
    public synchronized List<Item> all() {
        ensureLoaded();
        return List.copyOf(items);
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save the Applitools list of unmerged baselines", e);
        }
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
