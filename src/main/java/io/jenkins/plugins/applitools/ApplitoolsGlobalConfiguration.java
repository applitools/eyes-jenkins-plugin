package io.jenkins.plugins.applitools;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.Util;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.applitools.scm.CommitStatusPublisher;
import java.util.List;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** System-wide defaults shown under Manage Jenkins → System, and configurable via JCasC ({@code applitools:}). */
@Extension
@Symbol("applitools")
public class ApplitoolsGlobalConfiguration extends GlobalConfiguration {

    public static final String DEFAULT_SERVER_URL = "https://eyes.applitools.com";
    public static final String DEFAULT_CREDENTIALS_ID = "applitools-api-key";
    /** Used automatically when a credential with this ID exists and nothing else is configured. */
    public static final String DEFAULT_READ_CREDENTIALS_ID = "applitools-read-api-key";

    private String serverUrl;
    private String credentialsId;
    private String readCredentialsId;
    private String designReviewLabel;
    private String designers;
    private boolean designReviewByDefault;
    private Boolean designReviewCheck;

    public static final String DEFAULT_DESIGN_REVIEW_LABEL = "design-review";
    /** Used automatically for baseline merges when a credential with this ID exists. */
    public static final String DEFAULT_MERGE_CREDENTIALS_ID = "applitools-merge-api-key";

    private CommitStatusPublisher.Mode githubReporting;
    private Boolean mergeBaselines;
    private Boolean commentOnUnmergedBaselines;
    private Boolean unstableWhileUnmerged;
    private boolean followBranchBuilds;
    private String mergeCredentialsId;
    private boolean allowForkPullRequests;
    private boolean allowHttp;
    private boolean apiKeyInQuery;
    private boolean hideReviewers;
    private boolean lockDesignReview;

    /** Review polling schedule ({@link io.jenkins.plugins.applitools.review.PollSchedule}); {@code null}: the default. */
    private Integer reviewPollSeconds;
    private Integer reviewSlowAfterMinutes;
    private Integer reviewSlowPollMinutes;
    private Integer reviewSlowestAfterHours;
    private Integer reviewSlowestPollMinutes;
    private Integer settledRecheckMinutes;
    private Integer followReviewsDays;

    public static final int DEFAULT_REVIEW_POLL_SECONDS = 60;
    public static final int MIN_REVIEW_POLL_SECONDS = 15;
    public static final int DEFAULT_REVIEW_SLOW_AFTER_MINUTES = 60;
    public static final int DEFAULT_REVIEW_SLOW_POLL_MINUTES = 5;
    public static final int DEFAULT_REVIEW_SLOWEST_AFTER_HOURS = 24;
    public static final int DEFAULT_REVIEW_SLOWEST_POLL_MINUTES = 30;
    public static final int DEFAULT_SETTLED_RECHECK_MINUTES = 30;
    public static final int DEFAULT_FOLLOW_REVIEWS_DAYS = 14;
    public static final int MAX_FOLLOW_REVIEWS_DAYS = 365;

    public static ApplitoolsGlobalConfiguration get() {
        return GlobalConfiguration.all().getInstance(ApplitoolsGlobalConfiguration.class);
    }

    @DataBoundConstructor
    public ApplitoolsGlobalConfiguration() {
        // Restore saved values from $JENKINS_HOME/io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration.xml
        load();
    }

    public String getServerUrl() {
        return serverUrl != null ? serverUrl : DEFAULT_SERVER_URL;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = Util.fixEmptyAndTrim(serverUrl);
        save();
    }

    /** ID of the Secret text credential holding the Eyes API key. */
    public String getCredentialsId() {
        return credentialsId != null ? credentialsId : DEFAULT_CREDENTIALS_ID;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        save();
    }

    /**
     * ID of a Secret text credential with an Eyes key that can read results. Run keys can upload tests but get HTTP 403
     * reading results. {@code null} means: use {@link #DEFAULT_READ_CREDENTIALS_ID} if it exists, else the run key.
     */
    public String getReadCredentialsId() {
        return readCredentialsId;
    }

    @DataBoundSetter
    public void setReadCredentialsId(String readCredentialsId) {
        this.readCredentialsId = Util.fixEmptyAndTrim(readCredentialsId);
        save();
    }

    /** PR label that requires a designer's approval. */
    public String getDesignReviewLabel() {
        return designReviewLabel != null ? designReviewLabel : DEFAULT_DESIGN_REVIEW_LABEL;
    }

    @DataBoundSetter
    public void setDesignReviewLabel(String designReviewLabel) {
        this.designReviewLabel = Util.fixEmptyAndTrim(designReviewLabel);
        save();
    }

    /** Eyes users (emails) who may approve design reviews, comma or newline separated; empty means anyone. */
    public String getDesigners() {
        return designers;
    }

    @DataBoundSetter
    public void setDesigners(String designers) {
        this.designers = Util.fixEmptyAndTrim(designers);
        save();
    }

    /** Require design review on every PR, not just labeled ones. */
    public boolean isDesignReviewByDefault() {
        return designReviewByDefault;
    }

    @DataBoundSetter
    public void setDesignReviewByDefault(boolean designReviewByDefault) {
        this.designReviewByDefault = designReviewByDefault;
        save();
    }

    /** Post the "Applitools / Design review" check (skipped when not requested) so it can be a required check. */
    public boolean isDesignReviewCheck() {
        return designReviewCheck == null || designReviewCheck;
    }

    @DataBoundSetter
    public void setDesignReviewCheck(boolean designReviewCheck) {
        this.designReviewCheck = designReviewCheck;
        save();
    }

    /** Checks (GitHub App jobs) or commit statuses (token jobs); AUTO picks per job. */
    public CommitStatusPublisher.Mode getGithubReporting() {
        return githubReporting != null ? githubReporting : CommitStatusPublisher.Mode.AUTO;
    }

    @DataBoundSetter
    public void setGithubReporting(CommitStatusPublisher.Mode githubReporting) {
        this.githubReporting = githubReporting;
        save();
    }

    /** Merge a PR's Eyes baseline branch into its target branch when the PR merges. */
    public boolean isMergeBaselines() {
        return mergeBaselines == null || mergeBaselines;
    }

    @DataBoundSetter
    public void setMergeBaselines(boolean mergeBaselines) {
        this.mergeBaselines = mergeBaselines;
        save();
    }

    /**
     * When a merged PR's baselines couldn't be merged (conflicts, a refused key…), comment on the PR and label it
     * {@code eyes-baselines-not-merged} until a build of the target branch merges them. Needs GitHub "Pull requests:
     * read and write".
     */
    public boolean isCommentOnUnmergedBaselines() {
        return commentOnUnmergedBaselines == null || commentOnUnmergedBaselines;
    }

    @DataBoundSetter
    public void setCommentOnUnmergedBaselines(boolean commentOnUnmergedBaselines) {
        this.commentOnUnmergedBaselines = commentOnUnmergedBaselines;
        save();
    }

    /** Mark builds of a target branch unstable while a merged PR's baselines aren't merged into it. */
    public boolean isUnstableWhileUnmerged() {
        return unstableWhileUnmerged == null || unstableWhileUnmerged;
    }

    @DataBoundSetter
    public void setUnstableWhileUnmerged(boolean unstableWhileUnmerged) {
        this.unstableWhileUnmerged = unstableWhileUnmerged;
        save();
    }

    /**
     * Keep updating results for branch builds (e.g. main) after they finish. Off by default: nobody reviews branch
     * builds, so following them only holds onto results that never settle. PR builds are always followed.
     */
    public boolean isFollowBranchBuilds() {
        return followBranchBuilds;
    }

    @DataBoundSetter
    public void setFollowBranchBuilds(boolean followBranchBuilds) {
        this.followBranchBuilds = followBranchBuilds;
        save();
    }

    /**
     * Run the {@code applitools} step for pull requests from forks. Off by default: a fork's test code is untrusted
     * and would receive the Eyes API key.
     */
    public boolean isAllowForkPullRequests() {
        return allowForkPullRequests;
    }

    @DataBoundSetter
    public void setAllowForkPullRequests(boolean allowForkPullRequests) {
        this.allowForkPullRequests = allowForkPullRequests;
        save();
    }

    /** Allow plain {@code http://} Eyes and GitHub URLs on other machines; keys would cross the network unencrypted. */
    public boolean isAllowHttp() {
        return allowHttp;
    }

    @DataBoundSetter
    public void setAllowHttp(boolean allowHttp) {
        this.allowHttp = allowHttp;
        save();
    }

    /**
     * Retry closing a batch with the key as a URL parameter when the server rejects the header (some older on-premises
     * Eyes servers). Off by default: URL parameters end up in proxy and server logs.
     */
    public boolean isApiKeyInQuery() {
        return apiKeyInQuery;
    }

    @DataBoundSetter
    public void setApiKeyInQuery(boolean apiKeyInQuery) {
        this.apiKeyInQuery = apiKeyInQuery;
        save();
    }

    /** Don't name reviewers (their Eyes login emails) in pull request results; useful for public repositories. */
    public boolean isHideReviewers() {
        return hideReviewers;
    }

    @DataBoundSetter
    public void setHideReviewers(boolean hideReviewers) {
        this.hideReviewers = hideReviewers;
        save();
    }

    /**
     * Ignore Jenkinsfile settings that would loosen design review: turning it off, choosing designers, changing the
     * label, or not posting PR results. Lets the gate hold against people who can edit the Jenkinsfile.
     */
    public boolean isLockDesignReview() {
        return lockDesignReview;
    }

    @DataBoundSetter
    public void setLockDesignReview(boolean lockDesignReview) {
        this.lockDesignReview = lockDesignReview;
        save();
    }

    /** How often to poll a new review, in seconds (at least 15). */
    public int getReviewPollSeconds() {
        return reviewPollSeconds != null ? reviewPollSeconds : DEFAULT_REVIEW_POLL_SECONDS;
    }

    @DataBoundSetter
    public void setReviewPollSeconds(int reviewPollSeconds) {
        this.reviewPollSeconds = Math.max(reviewPollSeconds, MIN_REVIEW_POLL_SECONDS);
        save();
    }

    /** Age, in minutes, after which a review is polled every {@link #getReviewSlowPollMinutes()}. */
    public int getReviewSlowAfterMinutes() {
        return reviewSlowAfterMinutes != null ? reviewSlowAfterMinutes : DEFAULT_REVIEW_SLOW_AFTER_MINUTES;
    }

    @DataBoundSetter
    public void setReviewSlowAfterMinutes(int reviewSlowAfterMinutes) {
        this.reviewSlowAfterMinutes = Math.max(reviewSlowAfterMinutes, 1);
        save();
    }

    public int getReviewSlowPollMinutes() {
        return reviewSlowPollMinutes != null ? reviewSlowPollMinutes : DEFAULT_REVIEW_SLOW_POLL_MINUTES;
    }

    @DataBoundSetter
    public void setReviewSlowPollMinutes(int reviewSlowPollMinutes) {
        this.reviewSlowPollMinutes = Math.max(reviewSlowPollMinutes, 1);
        save();
    }

    /** Age, in hours, after which a review is polled every {@link #getReviewSlowestPollMinutes()}. */
    public int getReviewSlowestAfterHours() {
        return reviewSlowestAfterHours != null ? reviewSlowestAfterHours : DEFAULT_REVIEW_SLOWEST_AFTER_HOURS;
    }

    @DataBoundSetter
    public void setReviewSlowestAfterHours(int reviewSlowestAfterHours) {
        this.reviewSlowestAfterHours = Math.max(reviewSlowestAfterHours, 1);
        save();
    }

    public int getReviewSlowestPollMinutes() {
        return reviewSlowestPollMinutes != null ? reviewSlowestPollMinutes : DEFAULT_REVIEW_SLOWEST_POLL_MINUTES;
    }

    @DataBoundSetter
    public void setReviewSlowestPollMinutes(int reviewSlowestPollMinutes) {
        this.reviewSlowestPollMinutes = Math.max(reviewSlowestPollMinutes, 1);
        save();
    }

    /** How often, in minutes, a fully reviewed open PR is re-read, in case a reviewer changes their mind in Eyes. */
    public int getSettledRecheckMinutes() {
        return settledRecheckMinutes != null ? settledRecheckMinutes : DEFAULT_SETTLED_RECHECK_MINUTES;
    }

    @DataBoundSetter
    public void setSettledRecheckMinutes(int settledRecheckMinutes) {
        this.settledRecheckMinutes = Math.max(settledRecheckMinutes, 1);
        save();
    }

    /** How many days to follow a review before dropping it (1 to 365). */
    public int getFollowReviewsDays() {
        return followReviewsDays != null ? followReviewsDays : DEFAULT_FOLLOW_REVIEWS_DAYS;
    }

    @DataBoundSetter
    public void setFollowReviewsDays(int followReviewsDays) {
        this.followReviewsDays = Math.min(Math.max(followReviewsDays, 1), MAX_FOLLOW_REVIEWS_DAYS);
        save();
    }

    @POST
    public FormValidation doCheckReviewPollSeconds(@QueryParameter String value) {
        Integer n = parse(value);
        if (n == null || n < MIN_REVIEW_POLL_SECONDS) {
            return FormValidation.error("At least " + MIN_REVIEW_POLL_SECONDS + " seconds.");
        }
        return n > 60
                ? FormValidation.warning("Reviews are first checked in Eyes " + n + " seconds after the build.")
                : FormValidation.ok();
    }

    @POST
    public FormValidation doCheckReviewSlowPollMinutes(
            @QueryParameter String value, @QueryParameter String reviewPollSeconds) {
        Integer n = parse(value);
        Integer first = parse(reviewPollSeconds);
        if (n == null || n < 1) {
            return FormValidation.error("At least 1 minute.");
        }
        return first != null && n * 60 < first
                ? FormValidation.warning("Shorter than the first interval, so the first interval is used.")
                : FormValidation.ok();
    }

    @POST
    public FormValidation doCheckReviewSlowestPollMinutes(
            @QueryParameter String value, @QueryParameter String reviewSlowPollMinutes) {
        Integer n = parse(value);
        Integer slow = parse(reviewSlowPollMinutes);
        if (n == null || n < 1) {
            return FormValidation.error("At least 1 minute.");
        }
        return slow != null && n < slow
                ? FormValidation.warning("Shorter than the previous interval, so that one is used.")
                : FormValidation.ok();
    }

    @POST
    public FormValidation doCheckReviewSlowestAfterHours(
            @QueryParameter String value, @QueryParameter String reviewSlowAfterMinutes) {
        Integer n = parse(value);
        Integer slowAfter = parse(reviewSlowAfterMinutes);
        if (n == null || n < 1) {
            return FormValidation.error("At least 1 hour.");
        }
        return slowAfter != null && n * 60 <= slowAfter
                ? FormValidation.warning("Not after the previous step, so reviews skip straight to this interval.")
                : FormValidation.ok();
    }

    @POST
    public FormValidation doCheckReviewSlowAfterMinutes(@QueryParameter String value) {
        return positive(value, "minute");
    }

    @POST
    public FormValidation doCheckSettledRecheckMinutes(@QueryParameter String value) {
        return positive(value, "minute");
    }

    @POST
    public FormValidation doCheckFollowReviewsDays(@QueryParameter String value) {
        Integer n = parse(value);
        return n == null || n < 1 || n > MAX_FOLLOW_REVIEWS_DAYS
                ? FormValidation.error("From 1 to " + MAX_FOLLOW_REVIEWS_DAYS + " days.")
                : FormValidation.ok();
    }

    private static FormValidation positive(String value, String unit) {
        Integer n = parse(value);
        return n == null || n < 1 ? FormValidation.error("At least 1 " + unit + ".") : FormValidation.ok();
    }

    private static Integer parse(String value) {
        try {
            return Integer.valueOf(Util.fixNull(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Secret text credential with an Eyes key allowed to merge baselines; falls back to the run key. */
    public String getMergeCredentialsId() {
        return mergeCredentialsId;
    }

    @DataBoundSetter
    public void setMergeCredentialsId(String mergeCredentialsId) {
        this.mergeCredentialsId = Util.fixEmptyAndTrim(mergeCredentialsId);
        save();
    }

    @POST
    public ListBoxModel doFillMergeCredentialsIdItems(@QueryParameter String mergeCredentialsId) {
        return doFillCredentialsIdItems(mergeCredentialsId);
    }

    /** Splits a designers setting into a list. */
    public static java.util.List<String> parseDesigners(String value) {
        if (value == null) {
            return java.util.List.of();
        }
        // Commas and new lines only: Eyes team names can contain spaces ("team:Tim's Eyes Org").
        return java.util.Arrays.stream(value.split("[,\\n]+"))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .toList();
    }

    @POST
    public ListBoxModel doFillReadCredentialsIdItems(@QueryParameter String readCredentialsId) {
        return doFillCredentialsIdItems(readCredentialsId);
    }

    @POST
    public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
        StandardListBoxModel result = new StandardListBoxModel();
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return result.includeCurrentValue(credentialsId);
        }
        return result.includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2, Jenkins.get(), StringCredentials.class, List.of(), CredentialsMatchers.always())
                .includeCurrentValue(credentialsId);
    }
}
