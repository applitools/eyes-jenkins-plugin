package io.jenkins.plugins.applitools.review;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One build's review state, kept after the build finishes so the PR checks can follow what reviewers do in Eyes. The
 * registry keeps the latest entry per job (i.e. per PR); older builds' checks are left as they were.
 */
public class PendingReview {

    private final String jobFullName;
    private final int buildNumber;
    private final String batchId;
    private final String serverUrl;
    private final String runCredentialsId;
    private final String readCredentialsId;
    private final String repoSlug;
    private final String changeId;
    /** From the Jenkinsfile or environment; {@code null} means the PR label decides. */
    private final Boolean designReviewForced;
    private final boolean designReviewByDefault;
    private final String label;
    private final List<String> designers;
    private final boolean designCheck;
    private final Set<String> changedTestIds;
    private final long registeredAt;

    private boolean labelPresent;
    private String lastVisualState;
    private String lastDesignState;
    private boolean settled;

    public PendingReview(
            @NonNull String jobFullName,
            int buildNumber,
            @NonNull String batchId,
            @NonNull String serverUrl,
            @NonNull String runCredentialsId,
            @NonNull String readCredentialsId,
            @CheckForNull String repoSlug,
            @CheckForNull String changeId,
            @CheckForNull Boolean designReviewForced,
            boolean designReviewByDefault,
            @NonNull String label,
            @NonNull List<String> designers,
            boolean designCheck,
            @NonNull Set<String> changedTestIds,
            boolean labelPresent) {
        this.jobFullName = jobFullName;
        this.buildNumber = buildNumber;
        this.batchId = batchId;
        this.serverUrl = serverUrl;
        this.runCredentialsId = runCredentialsId;
        this.readCredentialsId = readCredentialsId;
        this.repoSlug = repoSlug;
        this.changeId = changeId;
        this.designReviewForced = designReviewForced;
        this.designReviewByDefault = designReviewByDefault;
        this.label = label;
        this.designers = new ArrayList<>(designers);
        this.designCheck = designCheck;
        this.changedTestIds = new TreeSet<>(changedTestIds);
        this.labelPresent = labelPresent;
        this.registeredAt = System.currentTimeMillis();
    }

    /** Whether this build needs a designer's approval right now (the label can change after the build). */
    public boolean isDesignReviewRequired() {
        if (designReviewForced != null) {
            return designReviewForced;
        }
        return labelPresent || designReviewByDefault;
    }

    public boolean isLabelDriven() {
        return designReviewForced == null;
    }

    public boolean matchesPullRequest(@NonNull String slug, @NonNull String number) {
        return slug.equalsIgnoreCase(repoSlug) && number.equals(changeId);
    }

    public String getJobFullName() {
        return jobFullName;
    }

    public int getBuildNumber() {
        return buildNumber;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getRunCredentialsId() {
        return runCredentialsId;
    }

    public String getReadCredentialsId() {
        return readCredentialsId;
    }

    @CheckForNull
    public String getRepoSlug() {
        return repoSlug;
    }

    @CheckForNull
    public String getChangeId() {
        return changeId;
    }

    public String getLabel() {
        return label;
    }

    public List<String> getDesigners() {
        return designers;
    }

    public boolean isDesignCheck() {
        return designCheck;
    }

    public Set<String> getChangedTestIds() {
        return changedTestIds;
    }

    public long getRegisteredAt() {
        return registeredAt;
    }

    public boolean isLabelPresent() {
        return labelPresent;
    }

    public void setLabelPresent(boolean labelPresent) {
        this.labelPresent = labelPresent;
    }

    @CheckForNull
    public String getLastVisualState() {
        return lastVisualState;
    }

    public void setLastVisualState(String lastVisualState) {
        this.lastVisualState = lastVisualState;
    }

    @CheckForNull
    public String getLastDesignState() {
        return lastDesignState;
    }

    public void setLastDesignState(String lastDesignState) {
        this.lastDesignState = lastDesignState;
    }

    /** True when nothing can change without a reviewer or label event; the watcher stops polling. */
    public boolean isSettled() {
        return settled;
    }

    public void setSettled(boolean settled) {
        this.settled = settled;
    }
}
