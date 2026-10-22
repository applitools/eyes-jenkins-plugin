package io.jenkins.plugins.applitools;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.Util;
import hudson.security.ACL;
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
    private boolean followBranchBuilds;
    private String mergeCredentialsId;

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
