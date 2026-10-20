package io.jenkins.plugins.applitools.review;

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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/** Reviews the plugin is following, saved in {@code $JENKINS_HOME} so tracking survives restarts. */
@Extension
public class ReviewRegistry implements Saveable {

    private static final Logger LOGGER = Logger.getLogger(ReviewRegistry.class.getName());

    /** How long to follow a review before giving up on it. */
    static final long MAX_AGE_MILLIS =
            Long.getLong(ReviewRegistry.class.getName() + ".maxAgeDays", 14) * 24 * 60 * 60 * 1000;

    private final List<PendingReview> reviews = new ArrayList<>();
    private transient boolean loaded;

    @NonNull
    public static ReviewRegistry get() {
        return ExtensionList.lookupSingleton(ReviewRegistry.class);
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

    /** Tracks a build, replacing any older build of the same job. */
    public synchronized void register(@NonNull PendingReview review) {
        ensureLoaded();
        reviews.removeIf(r -> r.getJobFullName().equals(review.getJobFullName()));
        reviews.add(review);
        saveQuietly();
    }

    public synchronized void remove(@NonNull PendingReview review) {
        ensureLoaded();
        if (reviews.remove(review)) {
            saveQuietly();
        }
    }

    @NonNull
    public synchronized List<PendingReview> all() {
        ensureLoaded();
        long now = System.currentTimeMillis();
        if (reviews.removeIf(r -> now - r.getRegisteredAt() > MAX_AGE_MILLIS)) {
            saveQuietly();
        }
        return new ArrayList<>(reviews);
    }

    @NonNull
    public List<PendingReview> forPullRequest(@NonNull String repoSlug, @NonNull String number) {
        return all().stream().filter(r -> r.matchesPullRequest(repoSlug, number)).toList();
    }

    @Override
    public synchronized void save() throws IOException {
        if (BulkChange.contains(this)) {
            return;
        }
        file().write(this);
        SaveableListener.fireOnChange(this, file());
    }

    public void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save Applitools review registry", e);
        }
    }
}
