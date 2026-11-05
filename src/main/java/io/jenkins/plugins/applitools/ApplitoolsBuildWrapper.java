package io.jenkins.plugins.applitools;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import io.jenkins.plugins.applitools.scm.GitHubConnectionAction;
import io.jenkins.plugins.applitools.scm.PullRequestLabels;
import io.jenkins.plugins.applitools.scm.ScmInfo;
import io.jenkins.plugins.applitools.scm.ScmInfoResolver;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildWrapper;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.credentialsbinding.masking.SecretPatterns;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.verb.POST;

/**
 * {@code applitools { ... }}: runs the body with everything the Eyes SDKs need in the environment, then closes the
 * batch. Works in Declarative and Scripted Pipeline, and as a Freestyle "Build Environment" option.
 *
 * <p>Doesn't need a workspace, so it can wrap {@code parallel} branches or several {@code node} blocks; they all share
 * one batch, closed once when the outermost block exits. Nested blocks reuse the outer batch.
 */
public class ApplitoolsBuildWrapper extends SimpleBuildWrapper {

    public static final String ENV_API_KEY = "APPLITOOLS_API_KEY";
    public static final String ENV_SERVER_URL = "APPLITOOLS_SERVER_URL";
    public static final String ENV_BATCH_ID = "APPLITOOLS_BATCH_ID";
    public static final String ENV_BATCH_NAME = "APPLITOOLS_BATCH_NAME";
    public static final String ENV_BATCH_SEQUENCE = "APPLITOOLS_BATCH_SEQUENCE";
    public static final String ENV_BRANCH_NAME = "APPLITOOLS_BRANCH_NAME";
    public static final String ENV_PARENT_BRANCH_NAME = "APPLITOOLS_PARENT_BRANCH_NAME";
    public static final String ENV_DONT_CLOSE_BATCHES = "APPLITOOLS_DONT_CLOSE_BATCHES";
    /** Set to true/false to require or skip design review for the build. */
    public static final String ENV_DESIGN_REVIEW = "APPLITOOLS_DESIGN_REVIEW";
    /** Marks env as set by this plugin, so nested blocks reuse the outer batch. */
    static final String ENV_MANAGED = "APPLITOOLS_JENKINS_MANAGED";

    private static final String LOG_PREFIX = "[Applitools] ";

    private String credentialsId;
    private String readCredentialsId;
    private String serverUrl;
    private String batchId;
    private String batchName;
    private String branchName;
    private String parentBranchName;
    private boolean closeBatch = true;
    private boolean waitForResults = true;
    private int resultsTimeout = DEFAULT_RESULTS_TIMEOUT;
    private ChangesPolicy onChanges = ChangesPolicy.SUCCESS;
    /** {@code null} (jobs saved before this option existed) means true. */
    private Boolean checks;
    // For jobs that aren't multibranch: where the PR is, and a token to report to GitHub with.
    private String repo;
    private String pullRequest;
    private String commit;
    private String branch;
    private String targetBranch;
    private String githubCredentialsId;
    private String githubApiUrl;
    /** {@code null}: the global setting decides. */
    private Boolean mergeBaselines;
    /** {@code null} (jobs saved before this option existed) means true. */
    private Boolean judgeDiffs;
    private Integer expectTests;
    private List<String> expectApps;
    /** {@code null}: decided by the environment, then the PR label, then the global default. */
    private Boolean designReview;
    private String designReviewLabel;
    private String designers;

    static final int DEFAULT_RESULTS_TIMEOUT = 300;

    @DataBoundConstructor
    public ApplitoolsBuildWrapper() {}

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    @CheckForNull
    public String getReadCredentialsId() {
        return readCredentialsId;
    }

    /** Secret text credential with a key that can read results; run keys get HTTP 403 reading them. */
    @DataBoundSetter
    public void setReadCredentialsId(String readCredentialsId) {
        this.readCredentialsId = Util.fixEmptyAndTrim(readCredentialsId);
    }

    @CheckForNull
    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = Util.fixEmptyAndTrim(serverUrl);
    }

    @CheckForNull
    public String getBatchId() {
        return batchId;
    }

    @DataBoundSetter
    public void setBatchId(String batchId) {
        this.batchId = Util.fixEmptyAndTrim(batchId);
    }

    @CheckForNull
    public String getBatchName() {
        return batchName;
    }

    @DataBoundSetter
    public void setBatchName(String batchName) {
        this.batchName = Util.fixEmptyAndTrim(batchName);
    }

    @CheckForNull
    public String getBranchName() {
        return branchName;
    }

    @DataBoundSetter
    public void setBranchName(String branchName) {
        this.branchName = Util.fixEmptyAndTrim(branchName);
    }

    @CheckForNull
    public String getParentBranchName() {
        return parentBranchName;
    }

    @DataBoundSetter
    public void setParentBranchName(String parentBranchName) {
        this.parentBranchName = Util.fixEmptyAndTrim(parentBranchName);
    }

    public boolean isCloseBatch() {
        return closeBatch;
    }

    @DataBoundSetter
    public void setCloseBatch(boolean closeBatch) {
        this.closeBatch = closeBatch;
    }

    public boolean isWaitForResults() {
        return waitForResults;
    }

    /** Wait for Eyes to finish comparing, then record results and apply {@link #getOnChanges()}. */
    @DataBoundSetter
    public void setWaitForResults(boolean waitForResults) {
        this.waitForResults = waitForResults;
    }

    public int getResultsTimeout() {
        return resultsTimeout;
    }

    /** Seconds to wait for tests still rendering after the block finishes. */
    @DataBoundSetter
    public void setResultsTimeout(int resultsTimeout) {
        this.resultsTimeout = resultsTimeout > 0 ? resultsTimeout : DEFAULT_RESULTS_TIMEOUT;
    }

    @NonNull
    public ChangesPolicy getOnChanges() {
        return onChanges;
    }

    @DataBoundSetter
    public void setOnChanges(ChangesPolicy onChanges) {
        this.onChanges = onChanges != null ? onChanges : ChangesPolicy.SUCCESS;
    }

    public boolean isChecks() {
        return checks == null || checks;
    }

    /** Post the "Applitools / Visual tests" check on the commit. */
    @DataBoundSetter
    public void setChecks(boolean checks) {
        this.checks = checks;
    }

    @CheckForNull
    public String getRepo() {
        return repo;
    }

    /** {@code owner/repo}; overrides detection (for jobs that aren't multibranch). */
    @DataBoundSetter
    public void setRepo(String repo) {
        this.repo = Util.fixEmptyAndTrim(repo);
    }

    @CheckForNull
    public String getPullRequest() {
        return pullRequest;
    }

    /** PR number; overrides detection. */
    @DataBoundSetter
    public void setPullRequest(String pullRequest) {
        this.pullRequest = Util.fixEmptyAndTrim(pullRequest);
    }

    @CheckForNull
    public String getCommit() {
        return commit;
    }

    /** The commit being tested (for a PR, its head commit); overrides detection. */
    @DataBoundSetter
    public void setCommit(String commit) {
        this.commit = Util.fixEmptyAndTrim(commit);
    }

    @CheckForNull
    public String getBranch() {
        return branch;
    }

    /** Branch under test (for a PR, its source branch); overrides detection. */
    @DataBoundSetter
    public void setBranch(String branch) {
        this.branch = Util.fixEmptyAndTrim(branch);
    }

    @CheckForNull
    public String getTargetBranch() {
        return targetBranch;
    }

    /** PR target branch; overrides detection. */
    @DataBoundSetter
    public void setTargetBranch(String targetBranch) {
        this.targetBranch = Util.fixEmptyAndTrim(targetBranch);
    }

    @CheckForNull
    public String getGithubCredentialsId() {
        return githubCredentialsId;
    }

    /**
     * A token (Secret text, or username/password with the token as password) to post commit statuses and read the PR
     * with, for jobs that aren't multibranch. Needs no GitHub plugins.
     */
    @DataBoundSetter
    public void setGithubCredentialsId(String githubCredentialsId) {
        this.githubCredentialsId = Util.fixEmptyAndTrim(githubCredentialsId);
    }

    @CheckForNull
    public String getGithubApiUrl() {
        return githubApiUrl;
    }

    /** GitHub Enterprise API URL, e.g. https://github.example.com/api/v3; defaults to api.github.com. */
    @DataBoundSetter
    public void setGithubApiUrl(String githubApiUrl) {
        this.githubApiUrl = Util.fixEmptyAndTrim(githubApiUrl);
    }

    @CheckForNull
    public Boolean getMergeBaselines() {
        return mergeBaselines;
    }

    /**
     * When this job's PR merges, merge its Eyes baselines into the target branch's ({@code true}), or don't
     * ({@code false}). Unset: the global setting. Merging only ever happens when the PR merges.
     */
    @DataBoundSetter
    public void setMergeBaselines(Boolean mergeBaselines) {
        this.mergeBaselines = mergeBaselines;
    }

    public boolean isJudgeDiffs() {
        return judgeDiffs == null || judgeDiffs;
    }

    /**
     * Let the plugin judge visual differences: tells SDKs that support it (Storybook, Cypress) not to fail the test
     * command on diffs, while still failing on errors. Playwright needs one config line instead (see the setup guide).
     */
    @DataBoundSetter
    public void setJudgeDiffs(boolean judgeDiffs) {
        this.judgeDiffs = judgeDiffs;
    }

    @CheckForNull
    public Integer getExpectTests() {
        return expectTests;
    }

    /** The batch must contain at least this many Eyes tests, or Visual tests fails. */
    @DataBoundSetter
    public void setExpectTests(Integer expectTests) {
        this.expectTests = expectTests != null && expectTests > 0 ? expectTests : null;
    }

    @CheckForNull
    public List<String> getExpectApps() {
        return expectApps;
    }

    /** Each of these Eyes app names must report at least one test, or Visual tests fails. */
    @DataBoundSetter
    public void setExpectApps(List<String> expectApps) {
        this.expectApps = expectApps == null || expectApps.isEmpty() ? null : new java.util.ArrayList<>(expectApps);
    }

    Expectations expectations() {
        return new Expectations(expectTests, expectApps);
    }

    @CheckForNull
    public Boolean getDesignReview() {
        return designReview;
    }

    /** Require (true) or skip (false) a designer's approval, regardless of PR labels. */
    @DataBoundSetter
    public void setDesignReview(Boolean designReview) {
        this.designReview = designReview;
    }

    @CheckForNull
    public String getDesignReviewLabel() {
        return designReviewLabel;
    }

    @DataBoundSetter
    public void setDesignReviewLabel(String designReviewLabel) {
        this.designReviewLabel = Util.fixEmptyAndTrim(designReviewLabel);
    }

    @CheckForNull
    public String getDesigners() {
        return designers;
    }

    /** Eyes users who may approve, comma separated; overrides the global list. */
    @DataBoundSetter
    public void setDesigners(String designers) {
        this.designers = Util.fixEmptyAndTrim(designers);
    }

    /** Restores defaults for fields added after a job was saved. */
    @Serial
    protected Object readResolve() {
        if (resultsTimeout <= 0) {
            resultsTimeout = DEFAULT_RESULTS_TIMEOUT;
        }
        if (onChanges == null) {
            onChanges = ChangesPolicy.SUCCESS;
        }
        return this;
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public void setUp(Context context, Run<?, ?> build, TaskListener listener, EnvVars initialEnvironment)
            throws IOException, InterruptedException {
        PrintStream log = listener.getLogger();
        if ("true".equals(initialEnvironment.get(ENV_MANAGED))) {
            log.println(LOG_PREFIX + "Nested block: reusing batch " + initialEnvironment.get(ENV_BATCH_ID));
            return;
        }

        String server = effectiveServerUrl();
        try {
            ServerUrls.check(server, "The Eyes server URL");
            ServerUrls.checkOptional(expand(initialEnvironment, githubApiUrl), "The GitHub API URL");
        } catch (ServerUrls.RefusedUrlException e) {
            throw new AbortException(LOG_PREFIX + e.getMessage());
        }
        String credId = effectiveCredentialsId();
        String apiKey = lookupApiKey(build, credId);
        if (apiKey == null) {
            throw new AbortException(LOG_PREFIX + "No Secret text credential with ID '" + credId
                    + "' is visible to this job. Add your Eyes API key under Manage Jenkins → Credentials, or set"
                    + " credentialsId on the applitools step.");
        }

        ScmInfo scm = ScmInfoResolver.resolveFor(build, initialEnvironment)
                .withOverrides(
                        expand(initialEnvironment, repo),
                        expand(initialEnvironment, pullRequest),
                        expand(initialEnvironment, commit),
                        expand(initialEnvironment, branch),
                        expand(initialEnvironment, targetBranch));
        String fork = forkOf(initialEnvironment);
        if (fork != null) {
            if (!ApplitoolsGlobalConfiguration.get().isAllowForkPullRequests()) {
                throw new AbortException(LOG_PREFIX + "This pull request comes from a fork (" + fork + "). Its code"
                        + " isn't trusted, so it doesn't get your Eyes API key and its visual tests don't run. To run"
                        + " them anyway, an admin can tick \"Run visual tests on pull requests from forks\" under"
                        + " Manage Jenkins → System → Applitools Eyes → Advanced.");
            }
            log.println(LOG_PREFIX + "Pull request from a fork (" + fork + "); allowed by Manage Jenkins → System."
                    + " Its baselines get their own Eyes branch.");
        }
        if (githubCredentialsId != null) {
            if (io.jenkins.plugins.applitools.scm.GitHubRestClient.token(build, githubCredentialsId) == null) {
                log.println(LOG_PREFIX + "WARNING: githubCredentialsId '" + githubCredentialsId + "' doesn't match a"
                        + " Secret text or Username with password credential visible to this job. Results won't be"
                        + " posted to GitHub.");
            } else if (scm.repoSlug() == null || scm.commitSha() == null) {
                log.println(LOG_PREFIX + "WARNING: githubCredentialsId is set, but the repository or commit is unknown;"
                        + " set repo: and commit: on the step. Results won't be posted to GitHub.");
            } else {
                build.addOrReplaceAction(new GitHubConnectionAction(
                        expand(initialEnvironment, githubApiUrl),
                        githubCredentialsId,
                        scm.repoSlug(),
                        scm.commitSha(),
                        scm.changeId()));
                log.println(LOG_PREFIX + "Reporting to GitHub " + scm.repoSlug()
                        + (scm.isPullRequest() ? " PR #" + scm.changeId() : "") + " with credential '"
                        + githubCredentialsId + "'");
            }
        }
        BatchSettings batch = BatchSettings.compute(
                scm,
                build.getParent().getFullName(),
                build.getNumber(),
                new BatchSettings.Overrides(
                        expand(initialEnvironment, batchId),
                        expand(initialEnvironment, batchName),
                        expand(initialEnvironment, branchName),
                        expand(initialEnvironment, parentBranchName)),
                fork != null);

        context.env(ENV_API_KEY, apiKey);
        context.env(ENV_SERVER_URL, server);
        context.env(ENV_BATCH_ID, batch.batchId());
        context.env(ENV_BATCH_NAME, batch.batchName());
        // The plugin closes the batch once; stop each SDK runner from closing (and splitting) it.
        context.env(ENV_DONT_CLOSE_BATCHES, "true");
        context.env(ENV_MANAGED, "true");
        if (isJudgeDiffs()) {
            // The plugin judges diffs (yellow build + PR check), so SDKs shouldn't fail the command on them.
            // Storybook's "nodiffs" still fails on errors (e.g. a browser that won't launch).
            putUnlessSet(context, initialEnvironment, "APPLITOOLS_EXITCODE", "nodiffs");
            putUnlessSet(context, initialEnvironment, "APPLITOOLS_FAIL_CYPRESS_ON_DIFF", "false");
        }
        putIfNotNull(context, ENV_BATCH_SEQUENCE, batch.sequenceName());
        putIfNotNull(context, ENV_BRANCH_NAME, batch.branchName());
        putIfNotNull(context, ENV_PARENT_BRANCH_NAME, batch.parentBranchName());

        log.println(LOG_PREFIX + "Batch \"" + batch.batchName() + "\" (id " + batch.batchId() + ")");
        if (batch.branchName() != null) {
            log.println(LOG_PREFIX + "Branch " + batch.branchName()
                    + (batch.parentBranchName() != null ? " ← baseline from " + batch.parentBranchName() : ""));
        }
        log.println(LOG_PREFIX + "Server " + server + ", API key from credential '" + credId + "'");
        boolean locked = ApplitoolsGlobalConfiguration.get().isLockDesignReview();
        boolean postChecks = isChecks() || locked;
        if (!isChecks() && locked) {
            log.println(LOG_PREFIX + "checks: false ignored: design review is locked in Manage Jenkins → System");
        }
        if (postChecks) {
            VisualTestsCheck.started(build, listener, batch.batchName());
        }

        DesignSettings design = postChecks ? designSettings(build, scm, initialEnvironment, log) : null;
        if (design != null) {
            DesignReview.started(build, listener); // clear the previous build's verdict right away
        }
        String explicitReadId = readCredentialsId != null
                ? readCredentialsId
                : ApplitoolsGlobalConfiguration.get().getReadCredentialsId();
        context.setDisposer(new Finish(
                server,
                credId,
                explicitReadId != null ? explicitReadId : ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID,
                explicitReadId != null,
                batch.batchId(),
                closeBatch,
                waitForResults,
                resultsTimeout,
                onChanges,
                postChecks,
                design,
                expectations(),
                mergeBaselines,
                batch.branchName(),
                scm.repoSlug(),
                scm.changeId()));
    }

    /**
     * The fork a pull request comes from, or {@code null} if it isn't from a fork (or that can't be told). Multibranch
     * jobs set {@code CHANGE_FORK}; GitHub Pull Request Builder gives the author's repository.
     */
    @CheckForNull
    static String forkOf(@NonNull EnvVars env) {
        String fork = Util.fixEmptyAndTrim(env.get("CHANGE_FORK"));
        if (fork != null) {
            return fork;
        }
        String authorRepo = Util.fixEmptyAndTrim(env.get("ghprbAuthorRepoGitUrl"));
        String baseRepo = Util.fixEmptyAndTrim(env.get("ghprbGhRepository"));
        if (authorRepo != null && baseRepo != null) {
            String author = authorRepo.toLowerCase(java.util.Locale.ROOT).replaceFirst("\\.git$", "");
            if (!author.endsWith("/" + baseRepo.toLowerCase(java.util.Locale.ROOT))
                    && !author.endsWith(":" + baseRepo.toLowerCase(java.util.Locale.ROOT))) {
                return authorRepo;
            }
        }
        return null;
    }

    @Override
    public ConsoleLogFilter createLoggerDecorator(@NonNull Run<?, ?> build) {
        String apiKey = lookupApiKey(build, effectiveCredentialsId());
        return apiKey == null ? null : new MaskApiKey(apiKey);
    }

    @CheckForNull
    private DesignSettings designSettings(Run<?, ?> build, ScmInfo scm, EnvVars env, PrintStream log) {
        ApplitoolsGlobalConfiguration global = ApplitoolsGlobalConfiguration.get();
        Boolean forced = designReview;
        String fromEnv = Util.fixEmptyAndTrim(env.get(ENV_DESIGN_REVIEW));
        if (forced == null && fromEnv != null) {
            forced = Boolean.parseBoolean(fromEnv);
        }
        // Locked: the Jenkinsfile can require design review, but not turn it off or change who approves.
        boolean locked = global.isLockDesignReview();
        if (locked && (Boolean.FALSE.equals(forced) || designers != null || designReviewLabel != null)) {
            log.println(LOG_PREFIX + "Design review is locked in Manage Jenkins → System; ignoring the Jenkinsfile's"
                    + " designReview: false, designers and designReviewLabel");
            if (Boolean.FALSE.equals(forced)) {
                forced = null;
            }
        }
        if (!global.isDesignReviewCheck() && !Boolean.TRUE.equals(forced)) {
            return null;
        }
        String label = designReviewLabel != null && !locked ? designReviewLabel : global.getDesignReviewLabel();
        String who = designers != null && !locked ? designers : global.getDesigners();
        return new DesignSettings(
                forced,
                global.isDesignReviewByDefault(),
                label,
                ApplitoolsGlobalConfiguration.parseDesigners(who),
                build.getParent().getFullName(),
                build.getNumber(),
                scm.owner(),
                scm.repository(),
                scm.changeId());
    }

    /** Design-review inputs captured when the block starts; labels are read when it ends. */
    record DesignSettings(
            Boolean forced,
            boolean byDefault,
            String label,
            List<String> designers,
            String jobFullName,
            int buildNumber,
            String owner,
            String repository,
            String changeId)
            implements Serializable {
        ScmInfo scm() {
            return new ScmInfo(owner, repository, null, null, null, changeId, null);
        }
    }

    private String effectiveServerUrl() {
        return serverUrl != null ? serverUrl : ApplitoolsGlobalConfiguration.get().getServerUrl();
    }

    private String effectiveCredentialsId() {
        return credentialsId != null ? credentialsId : ApplitoolsGlobalConfiguration.get().getCredentialsId();
    }

    @CheckForNull
    static String lookupApiKey(@NonNull Run<?, ?> build, @NonNull String credentialsId) {
        StringCredentials credentials =
                CredentialsProvider.findCredentialById(credentialsId, StringCredentials.class, build);
        if (credentials == null) {
            return null;
        }
        CredentialsProvider.track(build, credentials);
        return Util.fixEmptyAndTrim(credentials.getSecret().getPlainText());
    }

    @CheckForNull
    private static String expand(EnvVars env, @CheckForNull String value) {
        return value != null ? env.expand(value) : null;
    }

    private static void putUnlessSet(Context context, EnvVars env, String key, String value) {
        if (Util.fixEmpty(env.get(key)) == null) {
            context.env(key, value);
        }
    }

    private static void putIfNotNull(Context context, String key, @CheckForNull String value) {
        if (value != null) {
            context.env(key, value);
        }
    }

    /**
     * Runs when the block exits, whether the body passed or failed: closes the batch, waits for Eyes to finish, records
     * the results on the build, and applies the changes policy. Eyes being unreachable only logs a warning; the build
     * result reflects the tests, not our API calls.
     */
    private static final class Finish extends Disposer {
        @Serial
        private static final long serialVersionUID = 1L;

        private static final long POLL_MILLIS = Long.getLong(ApplitoolsBuildWrapper.class.getName() + ".pollMillis", 5000);

        private final String serverUrl;
        private final String credentialsId;
        private final String readCredentialsId;
        /** True if the user named the read credential, so its absence is worth a warning. */
        private final boolean readCredentialsExplicit;
        private final String batchId;
        private final boolean closeBatch;
        private final boolean waitForResults;
        private final int resultsTimeout;
        private final ChangesPolicy onChanges;
        private final boolean checks;
        @CheckForNull
        private final DesignSettings design;
        private final Expectations expectations;
        @CheckForNull
        private final Boolean mergeBaselinesSetting;
        /** The Eyes branch the tests ran on; merged into the target branch when the PR merges. */
        @CheckForNull
        private final String eyesBranch;
        /**
         * The PR under test ({@code owner/repo} and number), kept apart from the design review settings: a PR is
         * followed (and its baselines merged) whether or not design review is on. {@code null} in builds that started
         * before this was recorded, which fall back to the design review settings.
         */
        @CheckForNull
        private final String repoSlug;
        @CheckForNull
        private final String changeId;

        /** Results, or why there are none (for the check's summary). */
        private record Collected(@CheckForNull BatchResults results, @CheckForNull String problem) {}

        Finish(
                String serverUrl,
                String credentialsId,
                String readCredentialsId,
                boolean readCredentialsExplicit,
                String batchId,
                boolean closeBatch,
                boolean waitForResults,
                int resultsTimeout,
                ChangesPolicy onChanges,
                boolean checks,
                @CheckForNull DesignSettings design,
                Expectations expectations,
                @CheckForNull Boolean mergeBaselinesSetting,
                @CheckForNull String eyesBranch,
                @CheckForNull String repoSlug,
                @CheckForNull String changeId) {
            this.eyesBranch = eyesBranch;
            this.repoSlug = repoSlug;
            this.changeId = changeId;
            this.serverUrl = serverUrl;
            this.credentialsId = credentialsId;
            this.readCredentialsId = readCredentialsId;
            this.readCredentialsExplicit = readCredentialsExplicit;
            this.batchId = batchId;
            this.closeBatch = closeBatch;
            this.waitForResults = waitForResults;
            this.resultsTimeout = resultsTimeout;
            this.onChanges = onChanges;
            this.checks = checks;
            this.design = design;
            this.expectations = expectations;
            this.mergeBaselinesSetting = mergeBaselinesSetting;
        }

        @Override
        public void tearDown(Run<?, ?> build, TaskListener listener) throws IOException, InterruptedException {
            PrintStream log = listener.getLogger();
            String apiKey = lookupApiKey(build, credentialsId);
            if (apiKey == null) {
                log.println(LOG_PREFIX + "WARNING: credential '" + credentialsId
                        + "' disappeared; batch not closed and results not collected");
                if (checks) {
                    VisualTestsCheck.unavailable(build, listener, "The Eyes API key credential '" + credentialsId
                            + "' disappeared during the build.", true);
                }
                return;
            }
            EyesClient eyes = new EyesClient(serverUrl, apiKey);

            if (!closeBatch) {
                log.println(LOG_PREFIX + "closeBatch: false, leaving batch " + batchId + " open");
            } else if (!close(eyes, log)) {
                // Nothing ran, so there are no results to collect; the gate must not pass on an empty run.
                BatchResults none = expectations.apply(BatchResults.empty(batchId));
                if (checks) {
                    VisualTestsCheck.completed(build, listener, none);
                }
                if (none.getExpectationProblem() != null) {
                    apply(none, build, log); // fails the build: tests were expected
                }
                return;
            }
            if (!waitForResults) {
                if (checks) {
                    VisualTestsCheck.unavailable(
                            build, listener, "`waitForResults: false`, so this build didn't wait for Eyes.", false);
                }
                warnNotFollowed(log, "waitForResults: false");
                return;
            }
            Collected collected = collect(reader(build, eyes, log), log);
            BatchResults fetched = collected.results();
            BatchResults results = fetched != null ? expectations.apply(fetched) : null;
            if (results != null) {
                ApplitoolsResultsAction.addTo(build, results);
                if (checks) {
                    VisualTestsCheck.completed(build, listener, results);
                    track(build, listener, results);
                } else {
                    warnNotFollowed(log, "checks: false");
                }
                apply(results, build, log);
            } else if (checks) {
                String problem = collected.problem();
                VisualTestsCheck.unavailable(
                        build, listener, problem != null ? problem : "Eyes returned no results.", true);
            }
        }

        /** Says so when a PR build isn't followed, since its baselines then won't be merged when the PR merges. */
        private void warnNotFollowed(PrintStream log, String why) {
            boolean merge = mergeBaselinesSetting != null
                    ? mergeBaselinesSetting
                    : ApplitoolsGlobalConfiguration.get().isMergeBaselines();
            if (changeId != null && merge) {
                log.println(LOG_PREFIX + "WARNING: " + why + ", so this pull request isn't followed after the build:"
                        + " its Eyes baselines won't be merged when it merges. Merge them in Eyes (Compare & merge"
                        + " branches) instead.");
            }
        }

        /** Posts the Design review check and hands the build to the watcher, so the checks follow reviews in Eyes. */
        private void track(Run<?, ?> build, TaskListener listener, BatchResults results) throws InterruptedException {
            PrintStream log = listener.getLogger();
            boolean labelPresent = false;
            if (design != null && design.forced() == null && design.changeId() != null) {
                String wanted = design.label();
                try {
                    labelPresent = PullRequestLabels.of(build, design.scm()).stream()
                            .anyMatch(l -> l.equalsIgnoreCase(wanted));
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    log.println(LOG_PREFIX + "WARNING: could not read pull request labels: " + e);
                }
            }
            Set<String> changed = new java.util.TreeSet<>();
            for (BatchResults.Test test : results.getTests()) {
                if ("unresolved".equalsIgnoreCase(test.getStatus()) && test.getId() != null) {
                    changed.add(test.getId());
                }
            }
            PendingReview review = new PendingReview(
                    build.getParent().getFullName(),
                    build.getNumber(),
                    batchId,
                    serverUrl,
                    credentialsId,
                    readCredentialsId,
                    repoSlug != null ? repoSlug : design != null ? design.scm().repoSlug() : null,
                    changeId != null ? changeId : design != null ? design.changeId() : null,
                    design != null ? design.forced() : Boolean.FALSE,
                    design != null && design.byDefault(),
                    design != null ? design.label() : ApplitoolsGlobalConfiguration.DEFAULT_DESIGN_REVIEW_LABEL,
                    design != null ? design.designers() : List.of(),
                    design != null,
                    changed,
                    labelPresent);
            review.setExpectations(expectations);
            review.setMergeBaselines(mergeBaselinesSetting);
            review.setEyesBranch(eyesBranch);
            GitHubConnectionAction github = build.getAction(GitHubConnectionAction.class);
            if (github != null) {
                review.setGitHubConnection(github.getApiUrl(), github.getCredentialsId());
            }
            review.setLastVisualState(ReviewEvaluator.visualState(results));
            try {
                ReviewEvaluator.refreshDesign(review, build, listener, results, reader(build, null, log));
            } catch (IOException e) {
                log.println(LOG_PREFIX + "WARNING: could not evaluate design review: " + e.getMessage());
            }
            if (review.isPullRequest() || ApplitoolsGlobalConfiguration.get().isFollowBranchBuilds()) {
                ReviewRegistry.get().register(review);
            } else {
                ReviewRegistry.get().forget(review.getJobFullName()); // an older build of this branch, if any
            }
        }

        /** Client for reading results: the read key if there is one, else the run key. */
        private EyesClient reader(Run<?, ?> build, EyesClient runKeyClient, PrintStream log) {
            String readKey = lookupApiKey(build, readCredentialsId);
            if (readKey != null) {
                return new EyesClient(serverUrl, readKey);
            }
            if (readCredentialsExplicit && runKeyClient != null) {
                log.println(LOG_PREFIX + "WARNING: no Secret text credential with ID '" + readCredentialsId
                        + "'; reading results with the run key instead");
            }
            return runKeyClient != null
                    ? runKeyClient
                    : new EyesClient(serverUrl, java.util.Objects.requireNonNull(lookupApiKey(build, credentialsId)));
        }

        /** @return false if Eyes has never heard of the batch */
        private boolean close(EyesClient eyes, PrintStream log) throws InterruptedException {
            try {
                if (eyes.closeBatch(batchId) == EyesClient.CloseResult.NOT_FOUND) {
                    log.println(LOG_PREFIX + "No Eyes results for batch " + batchId + " (did any tests run?)");
                    return false;
                }
                log.println(LOG_PREFIX + "Closed batch " + batchId);
            } catch (IOException e) {
                log.println(LOG_PREFIX + "WARNING: could not close batch " + batchId + ": " + e.getMessage());
            }
            return true;
        }

        private Collected collect(EyesClient eyes, PrintStream log) throws InterruptedException {
            try {
                long deadline = System.currentTimeMillis() + resultsTimeout * 1000L;
                int running = eyes.runningCount(batchId);
                if (running > 0) {
                    log.println(LOG_PREFIX + "Waiting for " + running + " test(s) to finish in Eyes…");
                }
                while (running > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(POLL_MILLIS);
                    running = eyes.runningCount(batchId);
                }
                if (running > 0) {
                    log.println(LOG_PREFIX + "WARNING: " + running + " test(s) still running after " + resultsTimeout
                            + "s; recording partial results");
                }
                BatchResults results = eyes.getResults(batchId);
                if (results == null) {
                    log.println(LOG_PREFIX + "No Eyes results for batch " + batchId);
                    return new Collected(null, "Eyes has no results for batch `" + batchId + "`.");
                }
                return new Collected(results, null);
            } catch (EyesClient.UnauthorizedException e) {
                log.println(LOG_PREFIX + "WARNING: Eyes refused to share results for batch " + batchId + " (HTTP "
                        + e.getStatus() + "). Run keys can't read results: add a Secret text credential with a read"
                        + " key as '" + ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID
                        + "', or set readCredentialsId.");
                return new Collected(null, "Eyes refused to share results (HTTP " + e.getStatus() + "). Run keys"
                        + " can't read results; add a read key as `"
                        + ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID + "`.");
            } catch (IOException e) {
                log.println(LOG_PREFIX + "WARNING: could not read results for batch " + batchId + ": "
                        + e.getMessage());
                return new Collected(null, "Couldn't reach Eyes to read results: " + e.getMessage());
            }
        }

        private void apply(BatchResults results, Run<?, ?> build, PrintStream log) throws AbortException {
            String line = LOG_PREFIX + results.getOutcome().getLabel() + ": " + results.getSummary()
                    + (results.getUrl() != null ? " → " + results.getUrl() : "");
            // Always log the verdict: if the body already failed, Pipeline only reports the body's error and our
            // exception would be attached to it silently.
            log.println(line);
            boolean fail = results.getOutcome() == BatchResults.Outcome.FAILED
                    || (results.getOutcome() == BatchResults.Outcome.CHANGES && onChanges == ChangesPolicy.FAILURE);
            if (fail) {
                throw new AbortException(LOG_PREFIX + "Failing the build: " + results.getOutcome().getLabel());
            }
            if (results.getOutcome() == BatchResults.Outcome.CHANGES && onChanges == ChangesPolicy.UNSTABLE) {
                build.setResult(Result.UNSTABLE);
            }
        }
    }

    /** Replaces the API key with {@code ****} in the build log, like withCredentials does. */
    private static final class MaskApiKey extends ConsoleLogFilter implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Secret pattern;

        MaskApiKey(String apiKey) {
            this.pattern = Secret.fromString(
                    SecretPatterns.getAggregateSecretPattern(Set.of(apiKey)).pattern());
        }

        @Override
        public OutputStream decorateLogger(Run build, OutputStream logger) {
            String charset = build != null ? build.getCharset().name() : StandardCharsets.UTF_8.name();
            return new SecretPatterns.MaskingOutputStream(
                    logger, () -> Pattern.compile(pattern.getPlainText()), charset);
        }
    }

    @Symbol("applitools")
    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        public ListBoxModel doFillMergeBaselinesItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Global setting", "");
            items.add("Yes", "true");
            items.add("No", "false");
            return items;
        }

        public ListBoxModel doFillDesignReviewItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("From PR label / global default", "");
            items.add("Always require", "true");
            items.add("Never require", "false");
            return items;
        }

        /** The form's "from PR label" choice is an empty value, which data binding would turn into false. */
        @Override
        public ApplitoolsBuildWrapper newInstance(StaplerRequest2 req, @NonNull JSONObject formData)
                throws FormException {
            String mode = formData.optString("designReview", "");
            String merge = formData.optString("mergeBaselines", "");
            // Jenkins may call this more than once with the same object, so don't modify it.
            JSONObject rest = JSONObject.fromObject(formData);
            rest.remove("designReview");
            rest.remove("mergeBaselines");
            ApplitoolsBuildWrapper wrapper = (ApplitoolsBuildWrapper) super.newInstance(req, rest);
            wrapper.setDesignReview(mode.isEmpty() ? null : Boolean.valueOf(mode));
            wrapper.setMergeBaselines(merge.isEmpty() ? null : Boolean.valueOf(merge));
            return wrapper;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Applitools Eyes";
        }

        @POST
        public ListBoxModel doFillReadCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String readCredentialsId) {
            return doFillCredentialsIdItems(item, readCredentialsId);
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null
                    ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    : !item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            item instanceof Queue.Task task ? Tasks.getAuthenticationOf2(task) : ACL.SYSTEM2,
                            item,
                            StringCredentials.class,
                            List.of(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }
    }
}
